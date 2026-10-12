---
title: 'v1s-18-1 — Export module foundation (backend): ports, CSV/XLSX renderers, export jobs, gating, signed download'
type: 'feature'
created: '2026-10-12'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-18-context.md'
warnings: [oversized]
deferred: []
baseline_revision: '006c3b503c82135bddf1b5417f9e1276bdc8e718'
---

<intent-contract>

## Intent

**Problem:** FR55/FR94/FR96 — an owner cannot take data out of Keevo. Nothing exists for server-side export: no dataset port, no format engines, no background jobs, no gated download. Stories 18-2 (datasets), 18-3 (PDF) and 18-4 (app screen) all build on this foundation.

**Approach:** New hexagonal module `com.keevo.reporting.export`. Domains contribute datasets through an `ExportDatasetProvider` port (in the OPEN `shared` module, to avoid Modulith cycles); a renderer registry (`CsvExportRenderer`, `XlsxExportRenderer` on Apache POI SXSSF) streams page by page; `POST /api/v1/exports` streams small exports directly and runs large Premium exports as tenant-scoped `ExportJob`s stored on local disk for 24 h, with an FCM "export ready" push, a signed download link, a purge job, per-tenant rate limiting, plan gating and a `DATA_EXPORTED` audit entry. One demo dataset (`stores`) proves the chain end to end; the real catalogue is 18-2.

## Boundaries & Constraints

**Always:**
- OWNER-only on every export endpoint (FR55/FR94/FR96 say « Un propriétaire »): EMPLOYEE → 403 `FORBIDDEN`. Use class-level `@PreAuthorize("hasRole('OWNER')")` plus `CurrentActor.requireOwner(...)`.
- Tenant isolation: providers read only through the tenant-routed JPA `EntityManager` (search_path from `TenantContext`), never `JdbcTemplate`/raw `DataSource`; the background job sets `TenantContext` to the job's tenant and clears it in `finally`.
- Bounded memory: providers are read by keyset pages (page size 1000, configurable), each page in its own read-only transaction; renderers write rows as they arrive (CSV streaming writer, `SXSSFWorkbook` with a 100-row window, `dispose()` in `finally`). Never collect all rows of a dataset in memory.
- Gating: CSV on every plan; XLSX, PDF and background jobs require Premium (`PlanType` `PREMIUM` or `PREMIUM_TRIAL` with an ACTIVE subscription) else 403 `PLAN_FEATURE_REQUIRED` with `details.feature` (`EXPORT_XLSX`, `EXPORT_PDF`). No subscription row ⇒ treated as FREE.
- Data conventions for renderers: `AMOUNT_XAF` integer, dates/datetimes converted to WAT (`Africa/Douala`); CSV is UTF-8 with BOM, `;` separator, CRLF, header row of column labels; text cells starting with `=`, `+`, `-`, `@`, tab or CR are prefixed with `'` (formula injection).
- Every export (direct or background) publishes `DataExportedEvent` on the request thread, audited by `AuditEventListener` as `DATA_EXPORTED` (entityType `Export`, entityId = export id, storeId = filter store, value_after `{format, datasets, storeId, from, to, rowCount, mode}`).
- Next public Flyway is `V15`; tenant DDL through `TenantSchemaProvisioner` + `TenantSchemaSyncService` (provisioner pattern of 18-6).

**Never:**
- No real business datasets beyond the `stores` demo (products, sales, stock, audit… are 18-2). No PDF renderer (18-3: a `PDF` request from a Premium tenant returns 400 `EXPORT_FORMAT_UNSUPPORTED`). No app/dashboard change (18-4).
- No object storage (S3) — local disk in V1 behind an `ExportFileStorage` port.
- Do not run the whole Maven suite; do not touch Docker containers.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Employee | EMPLOYEE token, any `/api/v1/exports**` | 403 | `FORBIDDEN` |
| Small CSV | OWNER, FREE, `{datasets:["stores"],format:"CSV"}`, rows ≤ threshold | 200 `text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="keevo-stores-<yyyyMMdd-HHmm>.csv"`, BOM + `;` + header + rows; audit `DATA_EXPORTED` mode `DIRECT` | — |
| Several datasets CSV | 2+ dataset keys, CSV | 200 `application/zip`, one `<key>.csv` per dataset | — |
| XLSX on FREE | FREE, `format:"XLSX"` | 403 | `PLAN_FEATURE_REQUIRED`, `details.feature=EXPORT_XLSX` |
| Large CSV on FREE | FREE, rows > threshold | streamed directly (200), no job — CSV stays free, background is Premium | — |
| Large export on Premium | PREMIUM, rows > threshold | 202 + job `{id,status:"PENDING",…}`; job → RUNNING → READY with file, `rowCount`, `expiresAt = completedAt + 24 h`; FCM `EXPORT_READY` to owners | job failure → FAILED + `errorCode`, partial file deleted, FCM `EXPORT_FAILED` |
| Small XLSX on Premium | PREMIUM, rows ≤ threshold | 200 xlsx (one sheet per dataset, frozen header, XAF/date formats) | — |
| PDF on Premium | PREMIUM, `format:"PDF"` | 400 | `EXPORT_FORMAT_UNSUPPORTED` |
| Bad request | unknown dataset key, empty/duplicate datasets, `from > to`, missing format | 400 | `EXPORT_DATASET_UNKNOWN` / `VALIDATION_ERROR` |
| Rate limit | > `max-requests-per-hour` exports for the tenant, or ≥ `max-active-jobs` PENDING/RUNNING jobs | 429 | `RATE_LIMIT_EXCEEDED` |
| Download READY | OWNER `GET /api/v1/exports/{id}/file` | 200 file stream with stored content type and filename | — |
| Download expired | `expiresAt` past or status EXPIRED | 410 | `EXPORT_EXPIRED` |
| Download not ready | PENDING/RUNNING/FAILED | 409 | `EXPORT_NOT_READY` |
| Unknown / other tenant's id | id not in caller's schema | 404 | `EXPORT_NOT_FOUND` |
| Signed link | `GET /api/v1/exports/download?token=…` (public path), valid HMAC, not expired | 200 file stream | bad/tampered token → 403 `EXPORT_LINK_INVALID`; expired → 410 `EXPORT_EXPIRED` |
| Purge | scheduler, READY job past `expiresAt` | file deleted, status EXPIRED; PENDING/RUNNING older than `stale-job-after` (default 2 h) → FAILED `EXPORT_INTERRUPTED` | per-tenant errors logged, loop continues |
| 50k rows | synthetic provider, 50,000 rows, CSV and XLSX | completes; provider never asked for more than the page size; output has 50,000 data rows | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/application/port/AuditPort.java` + `shared/infrastructure/web/AuditEventListener.java` (`write(...)` ~:670, example `on(InventoryReportGeneratedEvent)` ~:597) -- add `on(DataExportedEvent)`; `shared` is OPEN and already imports domain events.
- `shared/domain/exception/ErrorCode.java` + `shared/infrastructure/web/GlobalExceptionHandler.java` (`domainCodeToHttpStatus` :210-281, `FR_MESSAGES` :45) -- add codes and status mapping (410 is new).
- `shared/infrastructure/security/CurrentActor.java` (`requireOwner` :87, `actorId()`), `shared/infrastructure/persistence/TenantContext.java`, `TenantSchema.validate`.
- `shared/infrastructure/security/SecurityConfig.java` `PUBLIC_PATHS` -- add `/api/v1/exports/download`.
- `shared/infrastructure/persistence/TenantSchemaProvisioner.java` (`createTables()` :918-1053; `DDL_REPORTS` :743 as job-table template) and `TenantSchemaSyncService.java` (`REQUIRED_TENANT_TABLES_DDL` :65-86, `ensureRequiredIndexes` :241).
- `keevo/backend/src/main/resources/db/migration/V14__audit_log_enrichment.sql` -- last migration; `spring.jpa.hibernate.ddl-auto=validate` validates entities against `public`, so `public.export_jobs` is mandatory.
- `subscription/plan/domain/port/out/SubscriptionRepository.java` (`findActivePlan()`), `Subscription.isPremium()/isPremiumTrial()/isActive()`; usage example `store/store/application/service/CreateStoreService.java:52-56`. reporting → subscription is an allowed dependency.
- `messaging/notification/domain/port/out/NotificationPort.java` (`notifyOwners(tenantId, payload)`), `NotificationPayload.of(type,title,body,deepLink,metadata)`. Call it directly from reporting (a messaging listener on a reporting event would create a cycle). `FcmNotificationAdapter.notifyOwners` clears `TenantContext` in its `finally` — call it last.
- `identity/auth/domain/port/out/TenantRepository` + pattern `reporting/report/application/service/WeeklyReportScheduler.java:85-104` (iterate ACTIVE tenants, set/clear `TenantContext`, `@Scheduled` + `@SchedulerLock`).
- `identity/auth/application/service/PasswordResetRateLimiter.java` -- Caffeine fixed-window pattern with injected `Clock` to copy (do not reuse across modules).
- `shared/infrastructure/config/AsyncConfig.java` -- only `notificationExecutor`; add a dedicated `exportExecutor`.
- `sync/sync/domain/port/in/DeltaEntityProvider.java` + `sync/.../provider/DeltaPageQuery` -- keyset-page pattern to mirror (EntityManager native query, `(cursor_col, id) > (?, ?)`).
- `store/store/adapter/out/persistence/` + `stores` DDL (`TenantSchemaProvisioner.java:83`: id, name, address, phone, type, is_active, created_at, updated_at) -- demo provider lives in the store module.
- `reporting/report/` -- layout template (`domain/{model,port/in,port/out}`, `application/service`, `adapter/in/rest`, `adapter/out/persistence`).
- Tests: `src/test/java/com/keevo/shared/infrastructure/persistence/PostgresIntegrationTest.java` (+ `DashboardServicePostgresIntegrationTest` for tenant-routed JPA in tests, `AccountDeletionSchedulerPostgresIntegrationTest:72-90` for seeding a tenant), `reporting/report/adapter/in/rest/ReportControllerTest.java` (standalone MockMvc + mocked SecurityContext), `messaging/.../DeviceTokenControllerSecurityTest.java` (`@WebMvcTest` + `SecurityConfig` + `filterAs(...)`).
- `pom.xml` -- `org.apache.poi:poi-ooxml:5.4.1` is already in `~/.m2` (offline build OK); `commons-csv 1.10.0` present.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/pom.xml` -- add `org.apache.poi:poi-ooxml:5.4.1` -- XLSX streaming (SXSSF).
- `shared/application/port/export/` (new: `ExportDatasetProvider`, `ExportColumn(key,label,ExportColumnType type)`, `ExportColumnType {TEXT, INTEGER, AMOUNT_XAF, DECIMAL, BOOLEAN, DATE, DATETIME}`, `ExportFilter(UUID storeId, Instant from, Instant to)`, `ExportPage(List<List<Object>> rows, String nextCursor)`) -- provider contract: `key()`, `label()` (French), `columns()`, `long count(ExportFilter)`, `ExportPage readPage(ExportFilter, String cursor, int limit)` (opaque keyset cursor; `nextCursor == null` when exhausted). Javadoc: tenant scoping comes from the routed EntityManager; row values match `columns()` order and types (`Instant`/`LocalDate`, `Long`, `BigDecimal`, `Boolean`, `String`, null allowed).
- `store/store/adapter/out/export/StoresExportDatasetProvider.java` -- demo dataset `stores` (« Boutiques »): columns id, name, type, address, phone, active, createdAt; store filter on `id`, period filter on `created_at`; keyset on `(created_at, id)` via EntityManager native query, `@Transactional(readOnly = true)` per call.
- `reporting/export/domain/model/` -- `ExportFormat {CSV, XLSX, PDF}`, `ExportJobStatus {PENDING, RUNNING, READY, FAILED, EXPIRED}`, `ExportJob` (id, requestedBy, status, format, datasets, filter, rowCount, fileName, fileSize, contentType, storagePath, errorCode, createdAt, startedAt, completedAt, expiresAt; transitions `start/complete/fail/expire` guarding illegal states), `ExportRequest` (datasets, format, filter).
- `reporting/export/domain/event/DataExportedEvent.java` -- record `(UUID exportId, UUID actorId, String tenantId, String format, List<String> datasets, UUID storeId, Instant from, Instant to, long rowCount, String mode /*DIRECT|BACKGROUND*/, Instant occurredAt)`.
- `reporting/export/domain/port/out/` -- `ExportRenderer` (`format()`, `contentType(int datasetCount)`, `extension(int datasetCount)`, `RenderSession open(OutputStream, int datasetCount)` with `beginDataset(key,label,columns)`, `row(List<Object>)`, `endDataset()`, `close()`), `ExportJobRepository`, `ExportFileStorage` (open write to a temp part, commit, read, delete, exists).
- `reporting/export/application/service/ExportDatasetRegistry.java` / `ExportRendererRegistry.java` -- collect beans by key/format; duplicate key ⇒ startup failure.
- `reporting/export/application/service/ExportStreamingWriter.java` -- loops datasets × pages (configured page size) into a `RenderSession`, returns total rows written; never holds more than one page.
- `reporting/export/application/service/ExportService.java` (use case `RequestExportUseCase`, `ListExportsUseCase`, `DownloadExportUseCase`) -- validate → rate limit → plan gate → count → DIRECT (≤ threshold, or FREE) streams to the given `OutputStream` and publishes `DataExportedEvent` with actual row count; BACKGROUND saves PENDING job, publishes `DataExportedEvent` (mode BACKGROUND, counted rows), submits `ExportJobRunner` to `exportExecutor` (rejected ⇒ job FAILED `EXPORT_QUEUE_FULL` and 429).
- `reporting/export/application/service/ExportJobRunner.java` -- sets `TenantContext`, RUNNING, writes via storage temp file, READY (+size, rows, `expiresAt`), then `notificationPort.notifyOwners(tenant, EXPORT_READY, deepLink "/exports/{id}", metadata jobId)`; on any exception FAILED + delete partial + `EXPORT_FAILED` push; always clears context.
- `reporting/export/application/service/ExportRateLimiter.java` -- Caffeine per-tenant fixed window (`Clock` injected) + active-job count from repository.
- `reporting/export/application/service/ExportLinkSigner.java` -- HMAC-SHA256 token `base64url(tenantSchema|jobId|expEpochSec).base64url(sig)`, constant-time compare; secret `keevo.export.link-secret` (blank ⇒ random per boot + WARN log).
- `reporting/export/application/service/ExportPurgeScheduler.java` -- hourly `@Scheduled` + `@SchedulerLock(name="exportPurge")`, per ACTIVE tenant: expire READY past `expiresAt` (delete file), fail stale PENDING/RUNNING.
- `reporting/export/adapter/out/render/CsvExportRenderer.java` / `XlsxExportRenderer.java` -- per Always rules; CSV with >1 dataset ⇒ zip of `<key>.csv`; XLSX: sheet per dataset (name = label, sanitized, ≤31 chars, unique), bold frozen header, `#,##0 "XAF"`, `dd/mm/yyyy`, `dd/mm/yyyy hh:mm`, numeric cells, overflow past 1,048,575 rows continues in `<label> (2)`.
- `reporting/export/adapter/out/storage/LocalDiskExportFileStorage.java` -- root `keevo.export.storage-dir` (default `${java.io.tmpdir}/keevo-exports`), path `<root>/<validated tenant schema>/<uuid>.<ext>`, write `.part` then atomic move.
- `reporting/export/adapter/out/persistence/` -- `ExportJobJpaEntity`, Spring Data repo, adapter (`findAllByOrderByCreatedAtDesc` limit 50, active count, READY-expired and stale lookups).
- `reporting/export/adapter/out/config/ExportProperties.java` (`@ConfigurationProperties("keevo.export")`: `syncRowThreshold=5000`, `pageSize=1000`, `retention=24h`, `storageDir`, `linkSecret`, `maxRequestsPerHour=30`, `maxActiveJobs=3`, `staleJobAfter=2h`) + `ExportExecutorConfig` (`exportExecutor`: core 1, max 2, queue 20, thread prefix `export-`) -- also `application.yml` entries (`link-secret: ${KEEVO_EXPORT_LINK_SECRET:}`).
- `reporting/export/adapter/in/rest/ExportController.java` + dto -- `GET /api/v1/exports/datasets` (key, label, columns), `POST /api/v1/exports` (DIRECT: writes to `HttpServletResponse` on the request thread — no `StreamingResponseBody`, which would lose `TenantContext`; BACKGROUND: 202 `ApiResponseWrapper` job), `GET /api/v1/exports` (Mes exports, newest first, `downloadUrl` = signed `/api/v1/exports/download?token=…` only when READY), `GET /api/v1/exports/{id}`, `GET /api/v1/exports/{id}/file`; `ExportDownloadController` for the public signed `GET /api/v1/exports/download` (validates `TenantSchema`, sets/clears `TenantContext` itself).
- `ErrorCode.java` / `GlobalExceptionHandler.java` -- `PLAN_FEATURE_REQUIRED` 403, `EXPORT_NOT_FOUND` 404, `EXPORT_EXPIRED` 410, `EXPORT_NOT_READY` 409, `EXPORT_DATASET_UNKNOWN` 400, `EXPORT_FORMAT_UNSUPPORTED` 400, `EXPORT_LINK_INVALID` 403, `EXPORT_QUEUE_FULL` 429 + French messages.
- `AuditEventListener.java` -- `on(DataExportedEvent)` → `DATA_EXPORTED`, label « Export <FORMAT> — <dataset labels or keys> ».
- `db/migration/V15__export_jobs.sql` + `TenantSchemaProvisioner` `DDL_EXPORT_JOBS` (+ indexes `(created_at DESC)`, `(status, expires_at)`; CHECKs on status/format) executed in `createTables()`; `TenantSchemaSyncService` `REQUIRED_TENANT_TABLES_DDL` entry + index constants in `ensureRequiredIndexes`.
- Tests (RED first): renderer unit tests (BOM, `;`, CRLF, injection prefix, WAT dates, zip for multi, XLSX read back: sheets, frozen pane, formats); `ExportStreamingWriterTest` 50,000 synthetic rows CSV + XLSX (page-size bound asserted, row count read back); `ExportServiceTest` (gating matrix, threshold DIRECT/BACKGROUND, rate limit, validation, audit event fields, queue full); `ExportJobRunnerTest` (READY + push, FAILED + cleanup, context cleared); `ExportLinkSignerTest`; `ExportControllerTest` (EMPLOYEE 403 on every route, 202/200, 410/409/404); `ExportPurgeSchedulerTest`; `ExportPostgresIntegrationTest extends PostgresIntegrationTest` (two tenants, `stores` export of tenant A contains only A's rows, job table provisioned + V15 public template, purge marks EXPIRED); `AuditEventListener` test for `DATA_EXPORTED`.

**Acceptance Criteria:**
- Given an EMPLOYEE token, when calling `POST /api/v1/exports` (or any export route), then the response is 403.
- Given two tenants with stores, when tenant A exports `stores` directly or in background, then the file contains only tenant A's rows.
- Given a 50,000-row dataset, when exported as CSV and XLSX, then it completes with all rows while the provider is only ever read one bounded page at a time.
- Given a READY export whose `expiresAt` has passed, when downloading via `/file` or the signed link, then the response is 410 `EXPORT_EXPIRED`.
- Given any completed export request, when the owner opens « Historique des actions », then a `DATA_EXPORTED` entry shows format, datasets, filters and row count with source ONLINE.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 44 findings — high 0, medium 14, low 21, false 4, maybe-false 5
- findings:
  - `[medium]` `patch` (blind) Formula guard prefixes phone numbers and is pointless in XLSX — guard now CSV-only and skips numeric-like values (`^[+-]?[\d\s().]+$`); XLSX writes text as-is.
  - `[medium]` `patch` (blind) Direct export failing mid-stream sends a broken 200 / JSON under attachment headers — controller resets the response when not yet committed, rethrows otherwise (container aborts the chunked stream).
  - `[medium]` `patch` (blind) Failed/interrupted direct exports leave no audit; downloads not audited — DIRECT_INCOMPLETE audit with rows written so far; download auditing rejected (low: the request is already audited, intent asks to audit exports, not each download).
  - `[medium]` `patch` (blind) Purge marks a long-queued RUNNING job stale from createdAt and runner overwrites FAILED with READY — RUNNING staleness now from startedAt; runner re-reads and skips READY/push when no longer RUNNING.
  - `[medium]` `patch` (blind) PENDING jobs lost on restart block max-active-jobs for hours — ApplicationReadyEvent recovery fails PENDING/RUNNING as EXPORT_INTERRUPTED (single-instance V1, documented).
  - `[low]` `patch` (blind) Executor core 1/max 2 with bounded queue only ever runs one job — core = max = 2.
  - `[medium]` `patch` (blind) Purge skips non-ACTIVE tenants (files of suspended/deletion-pending tenants kept); orphan committed file / no row retention — purge now covers every non-DELETED tenant; orphan-after-move and row retention rejected (low, rare, table bounded by 50-row listing use).
  - `[medium]` `patch` (blind) Link secret: blank only WARNs, no min length; tmpdir storage per instance — secret < 32 chars rejected at startup; blank keeps random+WARN (prod fail-fast rejected: deploy is manual and would break boot); multi-instance storage rejected as low: architecture fixes local disk for V1.
  - `[medium]` `patch` (blind) Signed link usable for a suspended/deleted tenant, not revocable — tenant must exist and be ACTIVE; revocation/log exposure rejected (low: 24 h bounded bearer link is the intent's design).
  - `[low]` `patch` (blind) Dataset keys unvalidated, request list unbounded — registry enforces `[a-z][a-z0-9_]{0,39}` at startup; >20 datasets → 400.
  - `[low]` `patch` (blind) Rate-limit slot consumed before plan gate; check-then-act on active jobs; FREE direct unbounded — acquire moved after plan gate; active-jobs race rejected (low: at most 1-2 extra jobs, bounded executor); FREE size cap rejected (CSV free for portability, memory bounded, 30 req/h cap).
  - `[low]` `patch` (blind) Useless COUNT for FREE tenants — count skipped when not Premium.
  - `[false]` `reject` (blind) Timestamp binding shifts TIMESTAMPTZ filters — pgjdbc sends java.sql.Timestamp with the JVM offset; the Postgres integration test covers period filters and keyset with equal createdAt.
  - `[low]` `patch` (blind) Audit label shows raw keys — event carries datasetLabels; label built from them (keys fallback).
  - `[low]` `patch` (blind) CSV/XLSX datetime formats differ — XLSX now `dd/mm/yyyy hh:mm:ss`.
  - `[low]` `patch` (blind) exists()/read() race → 500 — NoSuchFileException mapped to EXPORT_EXPIRED.
  - `[low]` `patch` (blind) Risky paths untested — tests added for each patch above.
  - `[medium]` `patch` (edge) Direct mid-stream failure → truncated 200 — same root cause as blind #2.
  - `[low]` `reject` (edge) No row cap on FREE direct path — same decision as blind #11 (portability; bounded memory; rate limit).
  - `[low]` `reject` (edge) Concurrent POSTs exceed maxActiveJobs — same as blind #11, low impact.
  - `[low]` `patch` (edge) Quota consumed before plan gate — same as blind #11 patch.
  - `[low]` `reject` (edge) save() throwing inside RejectedExecution handler → 500 — only on DB failure, where a 500 is correct; stale purge/startup recovery clean the job.
  - `[medium]` `patch` (edge) Purge/runner lost update — same as blind #4.
  - `[medium]` `patch` (edge) Non-ACTIVE tenants never purged — same as blind #7.
  - `[medium]` `patch` (edge) Signed link for suspended tenant — same as blind #9.
  - `[maybe-false]` `reject` (edge) Multi-instance storage/secret mismatch — depends on prod topology; architecture V1 decision is local disk; would be settled by a multi-instance deployment, then an S3 adapter behind ExportFileStorage (if-true severity low for V1 single EC2).
  - `[low]` `patch` (edge) exists/read race — same as blind #16.
  - `[low]` `reject` (edge) Orphan file when Files.size fails after move — practically unreachable right after a successful move.
  - `[maybe-false]` `reject` (edge) Provider cursor cycle → infinite loop — provider contract is keyset (strictly increasing); the only provider is tested over equal timestamps; if-true low.
  - `[low]` `patch` (edge) XLSX text > 32,767 chars kills export — truncated.
  - `[low]` `patch` (edge) Double amounts truncated — Math.round.
  - `[maybe-false]` `reject` (edge) NaN/Infinity decimals — no provider produces them (amounts are integer XAF); if-true low.
  - `[low]` `patch` (edge) OffsetDateTime/Date not handled in date conversion — branches added.
  - `[low]` `reject` (edge) Errors (OOM) bypass FAILED/push — JVM Errors are not recoverable; startup recovery + stale purge fail the job.
  - `[false]` `reject` (edge claim) Signed link bypasses EMPLOYEE 403 — links are only issued to OWNER through the OWNER-only list; the bearer link is the intent's « lien signé 24 h ».
  - `[false]` `reject` (edge claim) Background audit uses estimated count / written even if job fails — documented Design Note (audit on request thread for ONLINE source/actor); final rowCount is on the job.
  - `[false]` `reject` (edge claim) Errors bypass FAILED — duplicate of the Error finding above, rejected there.
  - `[medium]` `patch` (verification-gap) JwtAuthFilter public path for signed download untested — JwtAuthFilterTest case added.
  - `[low]` `patch` (verification-gap) EXPORT_QUEUE_FULL → 429 not pinned — ExportControllerTest case added.
  - `[medium]` `patch` (verification-gap) Sync index creation for export_jobs not asserted — pg_indexes assertion after syncNow.
  - `[low]` `patch` (verification-gap) Threshold boundary not pinned — 5000 DIRECT / 5001 BACKGROUND tests.
  - `[low]` `patch` (verification-gap) findRecent ordering / countActive never hit a DB — Postgres assertions added.
  - `[maybe-false]` `reject` (verification-gap other) Direct mid-stream error behavior undefined — same root cause as blind #2, patched there.
  - `[maybe-false]` `reject` (intent) ACs proven at component level, not HTTP+real JWT — the project has no full-context JWT harness; real filter (JwtAuthFilterTest), real SecurityConfig slice (EMPLOYEE 403), two real tenants on Postgres, and 50k synthetic stream through real renderers together cover each AC; if-true low.

## Design Notes

- **Port in `shared`, not in `reporting`:** reporting already depends on catalog/commerce/store/inventory; providers implemented in those modules against a port owned by reporting would create Modulith cycles. `shared` (OPEN, like `AuditPort`) is the shared kernel; the refonte lifts it as-is. The module still reads no table directly.
- **Libraries confirmed (epic asks at start of 18.1):** XLSX = Apache POI `poi-ooxml` 5.4.1 (SXSSF; ≥ 5.4.0 for the CVE-2025-31672 fix). PDF (18-3) = Thymeleaf (Boot-managed) + maintained fork `io.github.openhtmltopdf:openhtmltopdf-pdfbox` 1.1.22 (verified on Maven Central). Storage = local disk behind `ExportFileStorage` (S3 later = new adapter).
- **Free + large CSV stays direct:** the change proposal makes CSV free for portability and background jobs Premium, so a FREE tenant's large CSV is streamed on the request (memory bounded) rather than refused.
- **Mes exports lists background jobs only:** direct exports are delivered immediately and traced in the audit log; no file is retained for them.
- **Signed link** complements the authenticated `/file` route so a notification or share target can fetch the file without a bearer token; the token binds tenant + job + expiry, so it cannot open another tenant's job.
- **Audit on the request thread** keeps `source=ONLINE`, actor label and device; for background jobs the audited row count is the count taken at request time (the job's final `rowCount` is on the job).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o -q test-compile` -- expected: success.
- `MAVEN_OPTS=-Xmx1g mvn -o test -Dtest='<new and touched test classes>' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures / 0 errors.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend reporting store shared subscription architecture sync` -- expected: every lot OK (run by the orchestrating agent, one lot at a time; never the whole suite).

## Auto Run Result

Status: done

**Summary:** new hexagonal module `com.keevo.reporting.export` — dataset port in `shared/application/port/export` (Modulith-cycle-free), renderer registry with streaming `CsvExportRenderer` (BOM, `;`, CRLF, zip for several datasets, formula guard) and `XlsxExportRenderer` (POI SXSSF 5.4.1, sheet per dataset, frozen header, XAF/date formats); `POST/GET /api/v1/exports`, `GET /api/v1/exports/{id}`, `/{id}/file`, `/datasets`, public HMAC-signed `GET /api/v1/exports/download?token=`; DIRECT (≤ 5000 rows or non-Premium CSV) vs BACKGROUND Premium `ExportJob` on a 2-thread executor with local-disk storage, 24 h retention, FCM `EXPORT_READY`/`EXPORT_FAILED`; plan gate `PLAN_FEATURE_REQUIRED`; per-tenant rate limit (30/h, 3 active jobs); hourly purge + startup recovery; `DATA_EXPORTED` audit (DIRECT / DIRECT_INCOMPLETE / BACKGROUND, French dataset labels); demo dataset `stores`; Flyway V15 + tenant DDL/sync for `export_jobs`.

**Files changed (main):**
- `keevo/backend/pom.xml` -- poi-ooxml 5.4.1.
- `shared/application/port/export/*` -- ExportDatasetProvider port + column/filter/page types.
- `store/store/adapter/out/export/StoresExportDatasetProvider.java` -- demo `stores` dataset (keyset on created_at,id).
- `reporting/export/**` -- domain (model, event, ports), services (registries, streaming writer, ExportService, job runner, rate limiter, link signer, purge scheduler), adapters (renderers, local-disk storage, JPA, config, controllers).
- `shared/.../ErrorCode.java`, `GlobalExceptionHandler.java` -- 8 export codes (410 EXPORT_EXPIRED new).
- `shared/.../SecurityConfig.java`, `JwtAuthFilter.java` -- public signed download path.
- `shared/.../AuditEventListener.java` -- `on(DataExportedEvent)` → DATA_EXPORTED.
- `shared/.../TenantSchemaProvisioner.java`, `TenantSchemaSyncService.java`, `db/migration/V15__export_jobs.sql` -- export_jobs table + indexes.
- `application.yml` -- `keevo.export.*`.
- Tests: 13 new export test classes + `AuditEventListenerExportTest`, `JwtAuthFilterTest`, `TenantSchemaSyncServiceTest`, `SchedulerLockCoverageTest` updated.

**Review findings:** 44 (0 high, 14 medium, 21 low, 4 false, 5 maybe-false). Patched: 14 medium + 15 low entries (see triage log). Deferred: none. Rejected with reasons in the triage log: download auditing, FREE direct size cap, active-jobs race, link revocation, multi-instance storage (V1 local disk by architecture), orphan file after move, cursor cycles, NaN decimals, JVM Errors, and the 4 false claims.

**Follow-up review recommended: true** -- more than two medium entries were patched in this pass (14 medium patched, 15 low patched); the specific unverified risk is the direct-stream failure path (response reset / chunked abort) and the purge-vs-runner re-read guard, which have unit coverage but no real-servlet / concurrent run.

**Verification:**
- Targeted: 14 covering classes, 106 tests, 0 failures (implementation agent), after the first pass 26 classes / 214 tests green.
- Chunked lots (`run-tests-chunked.sh backend …`), after patches: reporting 301 OK, store 40 OK, subscription 34 OK, architecture 6 OK, shared 445 OK, sync 431 OK — 0 failures / 0 errors. (`SchedulerLockCoverageTest` needed `ExportPurgeScheduler` classified as a locked DB job.)

**Residual risks:**
- `KEEVO_EXPORT_LINK_SECRET` must be set (≥ 32 chars) in prod, otherwise signed links die on each restart (WARN only; deploy is manual — ops action).
- Local-disk storage and the in-memory rate limiter assume a single backend instance (V1 architecture); multi-instance needs an S3 `ExportFileStorage` adapter.
- Startup recovery logs `EXPORT_RECOVERY_FAILED` for tenant schemas lacking `export_jobs` until the sync creates it (test databases).
- Modulith baseline (warning mode) reports `shared → reporting` (DataExportedEvent, existing pattern) and `reporting → messaging` (NotificationPort, allowed dependency).

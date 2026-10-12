---
title: 'v1s-18-6 — Audit enrichi (backend): store, label, device, source, metadata, filtered list + detail'
type: 'feature'
created: '2026-10-12'
status: 'done'
baseline_revision: '34ad1a131859123ee87f0e2aee72b0b1a9d89af3'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-18-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Sync pull sends the enriched tenant audit log to EMPLOYEE devices while REST is OWNER-only (routed to v1s-18-7).
    evidence: |-
      AuditEntryDeltaProvider predates the story and DeltaEntityProvider has no role input; SyncPushService iterates every provider for every role.
    location: >-
      keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/AuditEntryDeltaProvider.java
    severity: medium
  - summary: >-
      Generic entity labels for sale/closure/inventory entries and null labels for employee events (routed to v1s-18-7).
    evidence: |-
      Events carry no receipt number or employee name; richer labels need event changes driven by the detail screen.
    location: >-
      keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** `audit_log` rows only say who (raw user id), what (action code) and a free-form JSON blob — no store, no readable target, no device, no channel (REST vs offline sync vs scheduler), context mixed into `value_after`. `GET /api/v1/audit` has no filters, offset paging only, no actor name, no detail endpoint, and no role check. The table is append-only only at ORM level, and sale services silently write `"[]"` snapshots on serialization failure. (FR84, FR98, NFR13; routed items from CR v1s-13-5 and v1s-15-5 D3.)

**Approach:** Add `store_id`, `entity_label`, `device_id`, `source`, `metadata` (jsonb) + indexes to `audit_log` (Flyway V14 on `public` + idempotent tenant DDL), a DB trigger forbidding UPDATE/DELETE/TRUNCATE, an `AuditContext` resolved at write time (source/device/actor label/fallback store) used by the single producer `AuditEventListener`, and an OWNER-only filtered, cursor-paged list plus `GET /api/v1/audit/{id}` with resolved actor and store names.

## Boundaries & Constraints

**Always:**
- Backward compatible for the shipped app: `GET /api/v1/audit` still accepts `page`/`size`/`entityType`/`entityId` and still returns `entries`, `hasMore`, `page`, `size` and every existing entry field (`id, entityType, entityId, action, valueBefore, valueAfter, userId, actorPhone, occurredAt`). New fields are additive.
- `value_after` keys the app renders today stay where they are: `role, storeName, productName, appliedPrice, movementType, quantityChange, quantityAfter, productName, currentQuantity, threshold, name, newName, type` and `value_before.cataloguePrice` (`keevo/app/lib/features/audit/presentation/widget/stock_history_widget.dart:263-330`).
- `value_before`/`value_after` hold entity fields only (field-by-field maps, `value_before` = previous values of the changed fields when known). Pure context (storeId, notes, justification, occurredAt, saleId, scope, isAutomatic, trigger ids, forced products, cancelledBy, sessionId of a count) goes to `metadata`.
- Old rows (all new columns NULL) list and open without error; `source` NULL is returned as `null`.
- Tenant isolation stays schema-based (TenantContext); cross-schema joins only to `public.users`.
- Audit write failures keep current semantics (REQUIRES_NEW, best-effort where already best-effort).
- TDD: failing test first for each behaviour; new DB tests extend `PostgresIntegrationTest`.

**Never:**
- No Flutter/app change (story 18-7 owns the screen), no dashboard change.
- No UPDATE/DELETE path on audit rows, no backfill of old rows (would violate immutability).
- No new public endpoint other than `GET /api/v1/audit/{id}`; no change to sync pull cursor semantics.
- Do not REVOKE privileges from the table owner role (the app role owns tenant tables; the trigger is the effective guard).
- Never write to `deferred-work.md`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner list, filters | OWNER, `from`,`to`,`storeId`,`userId`,`action`,`entityType`,`entityId`,`size=20` | Only matching rows, `occurred_at DESC, id DESC`, `nextCursor` set when more | — |
| Cursor paging | `cursor=<nextCursor>` | Next slice strictly after (occurredAt,id) of the cursor, no duplicate/skip on equal timestamps | malformed cursor → 400 VALIDATION_ERROR |
| Legacy paging | `page=1&size=20` no cursor | Offset paging as before + `hasMore` | — |
| Employee | EMPLOYEE token on list or detail | 403 FORBIDDEN | — |
| Detail | OWNER, existing id | Full entry incl. storeName, entityLabel, deviceId, source, metadata (JSON object), actorName | unknown id → 404 `AUDIT_ENTRY_NOT_FOUND` |
| Legacy row | row written before V14 | listed/opened, new fields null, actorName still resolved | — |
| REST write | authenticated request, optional `X-Device-Id` header | `source=ONLINE`, `device_id` = sanitized header or null | — |
| Sync write | event raised inside `/api/v1/sync/push` | `source=SYNC`, `device_id` = push `deviceId` | context cleared in finally |
| System write | event outside any HTTP request (scheduler, auto day close) | `source=SYSTEM` | — |
| Mutation attempt | SQL UPDATE/DELETE/TRUNCATE on tenant or public `audit_log` | rejected by trigger | SQL exception |
| Snapshot failure | Jackson fails on sale items | `log.error` with saleId, entry still written | no silent `[]` |
| Bad filter | `storeId`/`userId`/`entityId` not UUID, `from`>`to`, `size` <1 or >100 | 400 VALIDATION_ERROR | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/migration/` -- last is `V13__idempotent_check_constraints.sql`; new `V14__audit_log_enrichment.sql`. `public.audit_log` is the column template mirrored into tenant tables by `TenantSchemaSyncService.syncColumns` (Step 2), but tenant DDL must also be explicit (provisioner pattern).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java:207-226` -- `DDL_AUDIT_LOG` + indexes; `provision()` ~773-870 executes DDL constants. Add columns to CREATE, add `DDL_AUDIT_LOG_MIGRATE_*` ALTER ADD COLUMN IF NOT EXISTS, 3 indexes, trigger DDL.
- `.../persistence/TenantSchemaSyncService.java:224-300` `ensureRequiredIndexes` -- append the same audit ALTER/index/trigger constants (runs at boot via `TenantSchemaMigrationRunner` and on first request).
- `.../persistence/entity/AuditLogJpaEntity.java` -- add 5 columns (`metadata` mapped `@JdbcTypeCode(SqlTypes.JSON)` String or columnDefinition jsonb), keep `@Immutable`.
- `.../persistence/impl/AuditLogRepositoryAdapter.java` -- `record(...)` + native queries (`SELECT_WITH_PHONE` joins `public.users`). Extend select with new columns, `LEFT JOIN employees e ON e.user_id = a.user_id`, `LEFT JOIN stores st ON st.id = a.store_id`, `u.role`.
- `keevo/backend/src/main/java/com/keevo/shared/application/port/AuditPort.java` -- add `AuditEntry` write record overload + `AuditQuery` filter record + `search(...)` + `findById(...)`; keep the old 7-arg `record` as a default delegating method so callers/tests compile.
- `.../shared/infrastructure/web/AuditEventListener.java` -- ONLY audit producer (~45 handlers). Each builds `valueBefore/valueAfter` maps via `toJson`. Events carrying a store: StockAdjusted, StockThresholdBreached, SaleCompleted, SalePendingValidation, StockForcedZero, DayClosed, InventorySessionCreated, Store* (store = entity). Labels: productName, client/supplier/category/store name, `Vente`/closure etc.
- `.../shared/infrastructure/web/AuditController.java` + `dto/AuditEntryResponse.java`, `dto/AuditPageResponse.java` -- extend; GlobalExceptionHandler maps ErrorCode→HTTP (add `AUDIT_ENTRY_NOT_FOUND` → 404 in `ErrorCode` + mapping).
- `.../shared/infrastructure/security/CurrentActor.java` -- reuse `isOwner/requireOwner`, `actorLabel()`, add non-throwing `assignedStoreIdOrNull()` and public `fallbackLabel(String role)` for read-time name fallback.
- `keevo/backend/src/main/java/com/keevo/sync/sync/adapter/in/rest/SyncController.java:127-170` -- push: wrap `syncUseCase.pushBatch(...)` in `AuditContext.runAsSync(deviceId, ...)` (try/finally). Push is synchronous on the request thread; listener is a plain `@EventListener` (same thread).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/AuditEntryDeltaProvider.java` -- pull map: add `storeId, entityLabel, deviceId, source, metadata` keys (additive; app ignores unknown keys, `rest_sync_service.dart:1416`).
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/{RecordSaleService.java:160,CorrectSaleService.java:128,ValidateSaleService.java:143,165,268,SaleValidationCascadeService.java:120}` -- silent `"[]"`.
- Tests: `src/test/java/com/keevo/shared/infrastructure/web/{AuditControllerTest,AuditEventListenerTest,AuditEventListenerCategoryTest,AuditEventListenerContactTest}.java`, `.../persistence/{PostgresIntegrationTest,TenantSchemaSyncServiceTest,FlywayBaselineIntegrationTest}.java`; architecture rules under `src/test/java/com/keevo/architecture`.
- App owner-only route list includes `/audit` (`keevo/app/lib/core/router/app_router.dart:156`) → OWNER-only API does not break employees.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/resources/db/migration/V14__audit_log_enrichment.sql` -- on `public.audit_log` (skip if absent): ADD COLUMN IF NOT EXISTS `store_id uuid`, `entity_label varchar(200)`, `device_id varchar(100)`, `source varchar(10)` (CHECK `source IN ('ONLINE','SYNC','SYSTEM')`, nullable), `metadata jsonb`; create function `public.keevo_audit_log_immutable()` (RAISE EXCEPTION 'audit_log is append-only' USING ERRCODE 'insufficient_privilege' or similar); `CREATE OR REPLACE TRIGGER trg_audit_log_no_mutation BEFORE UPDATE OR DELETE ... FOR EACH ROW` and `trg_audit_log_no_truncate BEFORE TRUNCATE ... FOR EACH STATEMENT` on public.audit_log; `REVOKE UPDATE, DELETE, TRUNCATE ON public.audit_log FROM PUBLIC` -- template + DB-level immutability (item D3).
- `TenantSchemaProvisioner.java` -- add the 5 columns (and CHECK) to `DDL_AUDIT_LOG`; add constants `DDL_AUDIT_LOG_MIGRATE_STORE_ID/ENTITY_LABEL/DEVICE_ID/SOURCE/METADATA`, `DDL_AUDIT_LOG_IDX_STORE_OCCURRED (store_id, occurred_at DESC)`, `DDL_AUDIT_LOG_IDX_USER_OCCURRED (user_id, occurred_at DESC)`, `DDL_AUDIT_LOG_IDX_ACTION_OCCURRED (action, occurred_at DESC)`, `DDL_AUDIT_LOG_TRIGGER_NO_MUTATION`, `DDL_AUDIT_LOG_TRIGGER_NO_TRUNCATE`, `DDL_AUDIT_LOG_REVOKE_PUBLIC`; execute them in `provision()` after `DDL_AUDIT_LOG`.
- `TenantSchemaSyncService.java` -- execute the same constants in `ensureRequiredIndexes` (idempotent).
- `shared/application/port/AuditContext.java` (new, pure Java) -- ThreadLocal holder: `runAsSync(String deviceId, Supplier<T>)`, `current()` → Optional sync context; always cleared in finally (nested-safe: restore previous).
- `shared/infrastructure/web/AuditWriteContextResolver.java` (new, `@Component`) -- resolves `source` (SYNC if AuditContext sync, else ONLINE if `RequestContextHolder` has a request, else SYSTEM), `deviceId` (sync deviceId, else `X-Device-Id` header stripped of control chars and truncated to 100), `actorLabel` (`CurrentActor.actorLabel()` when authenticated, else null), fallback `storeId` (`CurrentActor.assignedStoreIdOrNull()`).
- `AuditPort.java` -- add `record AuditEntry(UUID actorId, String tenantId, String action, String entityType, UUID entityId, String valueBefore, String valueAfter, UUID storeId, String entityLabel, String deviceId, String source, String metadataJson)` + `void record(AuditEntry)`; old `record(...)` becomes `default` delegating with nulls; add `record AuditQuery(Instant from, Instant to, UUID storeId, UUID userId, String action, String entityType, UUID entityId)`, `AuditSlice search(AuditQuery q, String cursor, Integer page, int size)` returning entries + hasMore + nextCursor, `Optional<AuditEntryRecord> findById(UUID)`; extend `AuditEntryRecord` with `storeId, storeName, entityLabel, deviceId, source, metadata, actorName`. Keep the three old finders working (may delegate to `search`).
- `AuditLogJpaEntity.java` / `AuditLogRepositoryAdapter.java` -- persist new columns (`metadata` cast to jsonb); dynamic WHERE built from non-null filters with bound parameters only; keyset `(a.occurred_at, a.id) < (:cAt, :cId)` ordered `occurred_at DESC, id DESC`; cursor = Base64URL of `<epochMicros or ISO instant>|<uuid>`; `actorName` = `trim(e.first_name||' '||e.last_name)` if employee row, else stored `metadata->>'actorLabel'`, else `CurrentActor.fallbackLabel(u.role)`; `metadata` returned as String JSON.
- `AuditEventListener.java` -- inject resolver; every handler calls `auditPort.record(AuditEntry)` with: `storeId` (event store, Store events → the store id, else resolver fallback), `entityLabel` (product/client/supplier/category/store name, employee name if available, `null` otherwise — never a UUID), resolver source/device, `metadata` = context map + `actorLabel`. Keep app-read keys in `value_after`; move context keys to `metadata`; category events (14-15) keep `{name}`/`{active}` before/after and add the category name as label; `SaleCorrected` → `value_before = beforeSnapshot`, `value_after = afterSnapshot`, justification in metadata; `StoreUpdated` → `value_after {newName, name}` (+ `value_before {name}` if event carries old name). `toJson` failure: `log.error` (not warn).
- `CurrentActor.java` -- add `assignedStoreIdOrNull()` (never throws) and `public static String fallbackLabel(String role)`.
- `AuditController.java` -- `CurrentActor.requireOwner(...)` on GET list and GET `/{id}`; list params `from,to` (ISO-8601 instant), `storeId,userId,action,entityType,entityId,cursor,page,size` (default 20, max 100); validation → `VALIDATION_ERROR`; keep DELETE/PUT guards. `GET /{id}` → `AuditEntryResponse` or 404.
- `dto/AuditEntryResponse.java`, `dto/AuditPageResponse.java` -- add `storeId, storeName, entityLabel, deviceId, source, metadata` (JsonNode / raw JSON object, null-safe), `actorName`; page adds `nextCursor`.
- `ErrorCode.java` + `GlobalExceptionHandler.java` -- `AUDIT_ENTRY_NOT_FOUND` → 404.
- `SyncController.java` -- wrap push `pushBatch` in `AuditContext.runAsSync(deviceId, ...)`.
- `AuditEntryDeltaProvider.java` -- select and map the 5 new columns.
- Sale services (4 files) -- serialization failure: `log.error("AUDIT_SNAPSHOT_SERIALIZATION_FAILED saleId=...")` and pass a marker snapshot `{"error":"SNAPSHOT_UNAVAILABLE"}` instead of silent `[]`; hard-coded `"[]"` at ValidateSaleService:143/165 and SaleValidationCascadeService:120 replaced by the real items snapshot when the aggregate exposes items (otherwise keep `[]` with a code comment stating why — not a failure).
- Tests (RED first): `AuditControllerTest` (EMPLOYEE 403 list+detail, filters bound, cursor round-trip, bad params 400, detail 404, legacy params still work); `AuditEventListenerTest` (+Category/Contact) for source/device/store/label/metadata/value split; `AuditWriteContextResolverTest` (ONLINE/SYNC/SYSTEM, header sanitizing); new `AuditLogEnrichmentPostgresIntegrationTest extends PostgresIntegrationTest` (tenant provisioning creates columns+indexes+trigger, legacy row with null columns is listed and found by id, filters + keyset paging with equal timestamps, UPDATE/DELETE/TRUNCATE rejected, V14 applied on public); sale service tests for the error log/marker.

**Acceptance Criteria:**
- Given entries written before the migration, when an owner lists or opens them, then they are returned with their original fields and null enrichment fields.
- Given an EMPLOYEE token, when calling `GET /api/v1/audit` or `GET /api/v1/audit/{id}`, then the response is 403.
- Given any SQL UPDATE, DELETE or TRUNCATE on a tenant `audit_log`, when executed, then the database rejects it (NFR13 at DB level).
- Given a sync push from device D that creates a category, when the owner opens that audit entry, then `source=SYNC`, `deviceId=D`, `entityLabel` = category name, and `actorName` is resolved.
- Given an owner filtering by store S and period P, when paging with `nextCursor` to the end, then every row of S within P appears exactly once, newest first.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 38 findings — high 0, medium 7, low 22, false 7, maybe-false 2
- findings:
  - `[medium]` `[patch]` (blind) Sale lifecycle events (auto/manual validated, cancelled, corrected) write store_id NULL for OWNER / actor's home store for employees — added `storeId` to the events, publishers pass the sale store, listener writes it; listener tests assert the column.
  - `[medium]` `[patch]` (blind) Fallback store applied to tenant-wide entities depends on who acted — kept by design (employee actions are scoped to their store; owner actions tenant-wide), documented in listener Javadoc; root cause for store-scoped events fixed by the row above.
  - `[medium]` `[patch]` (blind) eventActorName never used because CurrentActor.actorLabel never returns null — writeRaw now prefers non-blank event actor name.
  - `[medium]` `[defer]` (blind) Sync pull ships the (now enriched) tenant audit log to EMPLOYEE devices while REST is OWNER-only — pre-existing channel (AuditEntryDeltaProvider predates the story); DeltaEntityProvider has no role input, fix needs a role-aware pull contract → routed to v1s-18-7 (owns the offline audit list / employee hiding).
  - `[false]` `[reject]` (blind) Moved valueAfter keys may break consumers — every key read by stock_history_widget.dart:263-330 stays in value_after (confirmed by verification-gap and intent layers); no other consumer reads audit JSON.
  - `[low]` `[reject]` (blind) metadata is a JSON object on REST but a string in sync delta — every delta value of this provider is a string (valueBefore/After too); 18-7 consumer decides; not met in everyday use.
  - `[low]` `[reject]` (blind) page echoed when cursor used — cosmetic, documented that cursor wins.
  - `[low]` `[reject]` (blind) cursor not bound to filters — standard opaque-cursor contract; client reuses its own filters; adding a filter hash adds complexity.
  - `[low]` `[defer]` (blind) Generic labels "Vente"/"Inventaire"/"Clôture de journée", null for employee events — events carry no receipt number/name; richer labels need event changes → routed to v1s-18-7 (consumer decides what the detail needs).
  - `[low]` `[reject]` (blind) CREATE OR REPLACE TRIGGER needs PG14+ — all environments run postgres:16 (docker-compose, Testcontainers).
  - `[low]` `[reject]` (blind) Enrichment DDL re-run at every boot — same pattern as every other ensureRequiredIndexes DDL; idempotent and brief.
  - `[false]` `[reject]` (blind) REVOKE FROM PUBLIC presented as a guard — V14 comment explicitly says the trigger is the guard and owner privileges are not revoked.
  - `[low]` `[reject]` (blind) keyset predicate lacks (occurred_at,id) index — idx_audit_log_occurred serves the order; id only tie-breaks equal timestamps.
  - `[false]` `[reject]` (blind) No real-push test for source=SYNC — SyncControllerPushGateTest asserts the context inside pushBatch and AuditEventListenerTest asserts SYNC rows under runAsSync; listener is a synchronous @EventListener.
  - `[low]` `[patch]` (blind) `to` boundary untested — boundary rows added to the Postgres integration test.
  - `[low]` `[reject]` (blind) employees LIMIT 1 tie-break untested — one employee row per user per tenant in practice; ORDER BY updated_at is deterministic enough.
  - `[low]` `[reject]` (blind) cursor decoded twice — harmless, gives the 400 at the controller.
  - `[low]` `[patch]` (blind) truncate can split a surrogate pair — cut moved before a trailing high surrogate.
  - `[low]` `[reject]` (blind) AuditContext lives in shared.application.port — pure Java holder next to AuditPort, accepted by architecture tests.
  - `[low]` `[reject]` (blind) SaleAuditSnapshot catches RuntimeException — failure is logged at ERROR (alertable); the sale must not fail because of an audit snapshot.
  - `[low]` `[reject]` (blind) public test-only listener constructor — cosmetic.
  - `[medium]` `[patch]` (edge) eventActorName overridden by role fallback — same as blind row, fixed once.
  - `[medium]` `[patch]` (edge) Sale events without storeId — same root cause as blind row, fixed once.
  - `[medium]` `[patch]` (edge) Inventory cancel/count/report/validate without storeId — events now carry the session store.
  - `[low]` `[patch]` (edge) `to` inclusive vs Javadoc exclusive — Javadoc aligned to inclusive, boundary test added.
  - `[low]` `[patch]` (edge) page*size int overflow → 500 — controller rejects with 400; test added.
  - `[low]` `[reject]` (edge) port default finders with size<=0 — only reachable from code that never passes it (controller validates 1..100).
  - `[low]` `[reject]` (edge) fallback label uses global users.role, not tenant role — only legacy rows of users without an employee row and without stored label; rare, fix needs a membership join.
  - `[low]` `[patch]` (edge) U+2028/U+2029/format chars survive device id sanitizing — regex extended; test added.
  - `[low]` `[patch]` (verification-gap) ValidateSaleService manual-validate / pending-cancel snapshots not asserted — assertions added.
  - `[low]` `[patch]` (verification-gap) store_id column untested for SaleCompleted/InventorySessionCreated etc. — listener enrichment tests added.
  - `[low]` `[patch]` (verification-gap) from/to boundary untested — same as above.
  - `[false]` `[reject]` (verification-gap other) `to` contract contradiction — duplicate of the patched row.
  - `[maybe-false]` `[reject]` (intent a/b) "where"/"on what" partially met — store fixed by patch; labels routed to 18-7 (would only be low).
  - `[false]` `[reject]` (intent c) field-by-field partial — STORE_UPDATED/EMPLOYEE_UPDATED events carry no previous values; spec allows before = known values only.
  - `[maybe-false]` `[reject]` (intent e) async publication would be mis-sourced — no audit-producing event is published asynchronously today (all plain @EventListener on the request thread); would be low.
  - `[false]` `[reject]` (intent g) size cap / entityId 400 not asked — spec I/O matrix requires both.
  - `[false]` `[reject]` (intent h/i) interim AC ticking not in diff / index reading — ticking done at finalize in the epic; per-dimension indexes are the spec's choice.

## Design Notes

- RBAC (FR98): the audit log is OWNER-only; the PRD hides the screen from employees, so "employee sees own actions" is not offered (rejected reading).
- Why a ThreadLocal `AuditContext` and not event fields: ~45 event types; source/device are request-channel facts, not domain facts. The listener runs synchronously on the publishing thread, so the context set by `SyncController` is visible; `finally` restore prevents leaks across pooled threads.
- Immutability: Hibernate `@Immutable` + controller guards stay; the new trigger (row UPDATE/DELETE + statement TRUNCATE) is the DB-level guarantee. `REVOKE ... FROM PUBLIC` is applied; revoking from the owner role is rejected (owner can re-grant, and it would block maintenance) — the trigger blocks the owner too. `DROP SCHEMA ... CASCADE` (tenant purge) is unaffected.
- Cursor example: `nextCursor = base64url("2026-10-12T08:15:30.123456Z|3f2a...")`; query `WHERE (a.occurred_at, a.id) < (?, ?)`.
- The epic context mentions "Flyway V10" for the trigger; V10–V13 already exist, so it ships in V14.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=AuditControllerTest,AuditEventListenerTest,AuditEventListenerCategoryTest,AuditEventListenerContactTest,AuditWriteContextResolverTest,AuditLogEnrichmentPostgresIntegrationTest -DforkCount=1 -DargLine=-Xmx1536m` -- expected: all green
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend shared sync commerce catalog architecture` -- expected: 0 failures / 0 errors per batch

## Auto Run Result

Status: done

**Summary:** `audit_log` gains `store_id`, `entity_label`, `device_id`, `source` (ONLINE/SYNC/SYSTEM) and `metadata` jsonb with store/user/action indexes (Flyway V14 on `public` + idempotent tenant DDL in TenantSchemaProvisioner/TenantSchemaSyncService). Row UPDATE/DELETE and TRUNCATE are rejected by triggers (public + every tenant schema, own trigger function per tenant). All producers (single `AuditEventListener`) write enriched entries: event store (8 sale/inventory events now carry `storeId`), readable label, `AuditWriteContextResolver` source/device (`AuditContext.runAsSync` around sync push), context moved to `metadata` with `actorLabel`; app-read `value_after` keys preserved. `GET /api/v1/audit` is OWNER-only with period/store/user/action/entity filters, keyset cursor (`nextCursor`) plus legacy page paging, resolved `actorName`/`storeName`; new `GET /api/v1/audit/{id}` (404 `AUDIT_ENTRY_NOT_FOUND`). Sync pull delta carries the new columns. Sale snapshots: `SaleAuditSnapshot` logs `AUDIT_SNAPSHOT_SERIALIZATION_FAILED` and stores a marker instead of silent `[]`; hard-coded `[]` replaced by real items.

**Files (main):** `db/migration/V14__audit_log_enrichment.sql` (columns, indexes, triggers, REVOKE PUBLIC); `TenantSchemaProvisioner`/`TenantSchemaSyncService` (tenant DDL); `AuditPort` (AuditEntry/AuditQuery/AuditSlice/AuditCursor, search, findById); `AuditContext` (sync ThreadLocal); `AuditWriteContextResolver` (source/device/label/fallback store); `AuditEventListener` (enriched writes); `AuditLogJpaEntity`/`AuditLogRepositoryAdapter` (persistence, dynamic filters, keyset); `AuditController`, `InvalidRequestParameterException`, DTOs, `ErrorCode`/`GlobalExceptionHandler`; `CurrentActor` (`assignedStoreIdOrNull`, `fallbackLabel`); `SyncController`, `AuditEntryDeltaProvider`; sale services + `SaleAuditSnapshot`; 8 sale/inventory event records and their publishers.

**Review:** 38 findings (medium 7, low 22, false 7, maybe-false 2). Patched: 3 medium entries (sale/inventory store_id, event actor name over role fallback) and 7 low (to-boundary doc+test, page overflow 400, device-id Unicode separators, surrogate-safe truncate, ValidateSaleService snapshot assertions, listener store assertions). Deferred → v1s-18-7: sync pull exposes audit log to employee devices (pre-existing channel); generic labels. Rejected findings and reasons are in the triage log.

**Follow-up review recommended:** true — 3 medium entries were patched, including a constructor change on 8 domain events across commerce and inventory; the unverified risk is a publisher path (e.g. sync handlers creating these events) passing the wrong store, only covered by unit tests.

**Routed items:** CR v1s-13-5 silent `[]` → resolved here; D3 DB immutability → resolved here (V14 triggers; owner-role REVOKE rejected, trigger is the guard).

**Verification:** targeted audit tests green (incl. `AuditLogEnrichmentPostgresIntegrationTest` 9+ on Postgres 16); chunked batches: shared 436, sync 425, commerce 222, catalog 394, inventory 160, architecture 6 — 0 failures / 0 errors. Fixed one order-dependent assertion in `SaleAuditSnapshotTest` (Map.of key order).

**Residual risks:** backend deploy is manual — V14 and the boot tenant sync ship with the next manual deploy; a test row committed by the integration test in the shared Testcontainers DB can no longer be deleted (append-only, harmless); employee devices still pull the audit delta until v1s-18-7.


---
title: '16-6 RGPD account deletion — complete the purge (public personal data, export files), keep a durable non-personal deletion trace; DISABLED modules never purged'
type: 'feature'
created: '2026-11-02'
status: 'done'
baseline_revision: 'cf9402ba20aa80e6cbf096de59e3d01b2d71810c'  # Reprise 10/11/2026 (first pass: 6fe8f91013e9a72f2eeaae4531d0b71ab7abbe02)
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** FR91 account deletion is delivered from V1 (v1s-14-5: owner request `POST /api/v1/account/delete`, 30-day grace, cancel, admin force-delete, daily `AccountDeletionScheduler` → `TenantDeletionService`: `DROP SCHEMA … CASCADE`, memberships, orphan users, refresh tokens, files). Story 16.6 requires "tenant data purged by DROP SCHEMA + `public` identity anonymised/deleted, with an audit trace of the request", and "a DISABLED module's data is never purged". An audit of the current code (2026-11-02) found: AC2 holds and is tested; AC1 has gaps — (1) no audit trace survives (request/cancel audit rows live in the tenant `audit_log`, dropped with the schema; the purge and admin force-delete/cancel are only logged); (2) `public` rows with personal data survive: `tenants.name`, `feedback` (user_id, free-text description), `user_daily_activity`, `user_sync_state`, `password_reset_tokens` (phone), `tenant_modules`, `plan_expiry_reminders`, `sync_silence_notices`; (3) a recent `password_reset_tokens` row (FK to `users`, no cascade) makes the `DELETE FROM users` fail → deletion postponed to the next daily run; (4) export files (`<storage-dir>/<schema>/…`, `LocalDiskExportFileStorage`) are never purged.

**Approach:** Complete `TenantDeletionService.executeDeletionForTenant` (same transaction, after the DROP) with the missing `public` deletes/anonymisation, make the export storage a `TenantFilesPurger`, and add a durable, non-personal `public.account_deletion_log` written on request, cancel (owner and admin), admin force-delete and execution. AC2 stays as is, re-asserted by a test.

## Boundaries & Constraints

**Always:**
- New Flyway `V40__account_deletion_log.sql` (public): `account_deletion_log(id uuid PK default gen_random_uuid(), tenant_id uuid NOT NULL, tenant_code varchar(10) NOT NULL, event varchar(20) NOT NULL CHECK IN ('REQUESTED','CANCELLED','EXECUTED'), source varchar(10) NOT NULL CHECK IN ('OWNER','ADMIN','SYSTEM'), scheduled_for timestamptz NULL, occurred_at timestamptz NOT NULL default now())`, index on (tenant_id, occurred_at). No FK, no name, no phone, no user id — the trace must itself be RGPD-clean and survive the purge.
- Writes: REQUESTED (OWNER) and CANCELLED (OWNER) in `AccountDeletionService` in the same transaction as the status change; admin force-delete → REQUESTED (ADMIN, scheduled_for = now) then EXECUTED (SYSTEM… use ADMIN as source of the execution when forced); admin cancel → CANCELLED (ADMIN); daily execution → EXECUTED (SYSTEM) inside `executeDeletionForTenant`'s transaction (rolled back with it if the public deletes fail). Existing tenant `audit_log` writes stay.
- `executeDeletionForTenant`, after the DROP and in this order (FK-safe): delete `password_reset_tokens` of the affected users that become orphaned (BEFORE deleting users — removes the postponement) and every `password_reset_tokens` row of an orphaned user; delete `feedback` WHERE tenant_id = schema (free text and user id are personal data); delete `user_daily_activity`, `user_sync_state` WHERE tenant = this tenant (check each table's tenant column type: schema name vs uuid); delete `tenant_modules`, `plan_expiry_reminders`, `sync_silence_notices` of the tenant; anonymise the kept `tenants` row (`name` → « Commerce supprimé », and any other free-text/personal column found on the row → null); keep `code`, `schema_name`, `status = DELETED`, plan/billing columns. Keep `tenant_payments`, `tenant_plan_events` (accounting/billing trace, no personal data of the owner; actor ids are platform admins) and `global_notifications` (admin content).
- Refonte-era public tables must be covered too: `device_sessions` (V36, 16.11), sync scope (V37), `admin_jobs`, bundle/à-la-carte rows keyed by tenant. Before writing the list, the implementer greps every `public` table that has a tenant or user column (migrations V1–V39 + `TenantSchemaProvisioner` public DDL) and records in the spec's Design Notes each table with its decision (deleted / anonymised / kept + reason). Any personal-data table not listed above is handled the same way (delete when keyed by the tenant; for user-keyed rows, only orphaned users).
- `LocalDiskExportFileStorage` implements `TenantFilesPurger`: deletes `<storage-dir>/<schema>/` recursively (no-op when absent; never outside the root — validate the schema with `TenantSchema.validate`). Purge runs after commit like the photo/logo purgers (already wired by the service through the `TenantFilesPurger` beans).
- Multi-tenant users keep their user row, other memberships, their own password-reset tokens and other tenants' data.
- Idempotent: a second run (or a retry after a failure after the DROP) does nothing more and writes no second EXECUTED row.
- AC2: no change to module lifecycle/expiry; add one assertion-level test that a DISABLED module past grace keeps its tables and rows (or point to the existing `ModuleLifecyclePostgresIntegrationTest` / `ModuleExpiryMaterializerPostgresIntegrationTest` cases in the Design Notes and keep them green).

**Never:** no change to the 30-day grace, request/cancel endpoints or their responses (V1 2.0.1 apps unaffected); no purge of DISABLED module data; no deletion of `tenant_payments`/`tenant_plan_events`; no personal data in `account_deletion_log`; no Flutter work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner requests deletion | `POST /account/delete` | DELETION_PENDING (unchanged) + log REQUESTED/OWNER with scheduled_for | — |
| Owner cancels | `POST /account/delete/cancel` | ACTIVE (unchanged) + log CANCELLED/OWNER | — |
| Daily run, grace over | tenant with owner + employee, feedback, activity, sync state, modules rows, reset token of the employee created 1 h ago, export files | schema dropped; all listed public rows gone; tenants row anonymised (code kept, DELETED); users/memberships/tokens gone; export dir gone; log EXECUTED/SYSTEM | — |
| User in two tenants | employee also member of tenant B | user row, B membership, B data and the user's reset token kept | — |
| Admin force-delete | super-admin | REQUESTED/ADMIN + EXECUTED/ADMIN, same purge | — |
| Admin cancel | super-admin | CANCELLED/ADMIN | — |
| Second run | already DELETED | nothing, no second EXECUTED | — |
| Failure after DROP | public delete throws | public changes + EXECUTED rolled back, tenant stays DELETION_PENDING, retried next run | ERROR log (existing) |
| Module DISABLED past grace | no deletion request | tables and rows kept | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/TenantDeletionService.java:206-265` -- `executeDeletionForTenant`: lock, owner phones, `dropSchemaForDeletion`, memberships, orphan users (`DELETE … RETURNING`), refresh tokens, public device_tokens, status DELETED; after-commit file purge (`TenantFilesPurger` beans) + final WhatsApp (~lines 119-169).
- `.../identity/auth/application/service/AccountDeletionService.java` -- request/cancel (status change, events, WhatsApp after commit); add log writes.
- `.../admin/**/AdminTenantService.java` (~line 242 `forceDelete`, cancel-deletion) -- admin paths; use `TenantDeletionPort.deleteNow`.
- `.../identity/api/AccountDeletionPolicy.java` (30 days), `TenantDeletionPort.java`.
- `.../shared/domain/port/out/TenantFilesPurger.java` -- port; implementations `LocalDiskProductPhotoStorage`, `LocalDiskCompanyLogoStorage` (pattern).
- `.../reporting/export/adapter/out/storage/LocalDiskExportFileStorage.java` -- layout `<storage-dir>/<schema>/<job>.<ext>`, `tenantDir(schema)` ~line 99.
- `.../reporting/export/**/ExportPurgeScheduler.java:83` -- skips DELETED tenants (fine once the dir is purged).
- `keevo/backend/src/main/resources/db/migration/` -- V6 (password_reset_tokens, FK no cascade), V7 (feedback), V18 (user_daily_activity), V37 (user_sync_state), V1 baseline (tenants: code, name, schema_name, status, deletion_scheduled_at …); add V40.
- `.../shared/infrastructure/web/AuditEventListener.java:577-595` -- existing tenant audit rows for request/cancel (kept).
- Tests: `TenantDeletionServicePostgresIntegrationTest` (extend: seeded public rows incl. employee, reset token < 1 day, feedback, activity, sync state, tenant_modules, export dir; log rows; second run; multi-tenant user), `TenantDeletionServiceTest`, `AccountDeletionServiceTest`, `AccountDeletionConcurrencyPostgresIntegrationTest`, admin force-delete tests, `ModuleLifecyclePostgresIntegrationTest` (~line 428), `ModuleExpiryMaterializerPostgresIntegrationTest` (`reactivationKeepsData` ~line 389).

## Tasks & Acceptance

**Execution:**
- `V40__account_deletion_log.sql` + a small repository/port for the log (identity-internal).
- `AccountDeletionService`, admin service -- log writes.
- `TenantDeletionService` -- complete purge + EXECUTED log, FK-safe order.
- `LocalDiskExportFileStorage` -- `TenantFilesPurger`.
- Tests for every matrix row; Design Notes table of every public table with its decision.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- record 16.6 done with evidence (Epic 16 status / story notes).

**Acceptance Criteria:**
- Given a tenant whose deletion grace has ended, when the daily job runs, then no row in any `public` table holds a name, phone, free text or user id of that tenant's (non-shared) users, the schema and export files are gone, and `account_deletion_log` shows REQUESTED then EXECUTED with no personal data.
- Given a module DISABLED for longer than its grace, when time passes, then its data is still there and comes back on reactivation.

## Verification

Run from `keevo/backend`, ONE package at a time (check `free -m`, one Maven build at a time): `mvn -q test -Dtest='com/keevo/<pkg>/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` for `identity`, `admin`, `reporting`, `shared`, `architecture`, `feedback`, `sync` — all rc=0, 0 failures.

## Spec Change Log

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 33 findings — high 0, medium 4, low 20, false 7, maybe-false 2
- findings:
  - (gap) `[low]` `[patch]` `account_deletion_log` missing from the explicit never-mirrored safety list — added.
  - (intent) `[low]` `[reject]` request/cancel tested at service level, not HTTP — controllers and responses unchanged (existing `AccountControllerTest` green); the trace is written below the controller.
  - (intent) `[low]` `[reject]` daily loop / ERROR log exercised through `runTenantDeletion` on a hand-built instance — the loop is unchanged; `executeDeletions` runs in the integration test's second-run check; the production wiring runs through `adminTenantService.forceDelete` on the real context.
  - (intent) `[low]` `[reject]` "any public table" guaranteed by a prose inventory, not a test — a schema-wide scanner would have to know which columns are personal; the inventory is reviewable and pinned per table by the integration test.
  - (intent) `[false]` `[reject]` refonte-era `device_sessions` / sync scope need new deletes — V36/V37 only add columns to `refresh_tokens` / `user_sync_state`, both deleted.
  - (intent) `[low]` `[reject]` force-delete not atomic (REQUESTED/ADMIN committed before `deleteNow`) — `forceDelete` is non-transactional by design (DROP on its own connection); a failed forced run is retried by the daily job, the trace still shows both events.
  - (intent) `[low]` `[patch]` idempotence failure case only integration-tested; unit test name over-claims — renamed `skippedDeletion_writesNoTrace`.
  - (intent) `[false]` `[reject]` anonymisation split across raw UPDATE + JPA save — same transaction, combined result asserted by the integration test.
  - (intent) `[false]` `[reject]` spec/epics outside the patch — planning files excluded from the review diff on purpose.
  - (edge) `[low]` `[reject]` forced execution misattributed to SYSTEM when `deleteNow` fails and the daily run completes — same as the non-atomic row; the earlier REQUESTED/ADMIN row shows the origin.
  - (edge) `[low]` `[patch]` admin `scheduled_for` from the JVM clock vs DB `now()` marker — taken from the tenants row.
  - (edge) `[low]` `[reject]` two concurrent owner requests → two REQUESTED rows — pre-existing read-then-save race on a double tap; a duplicate trace row is harmless.
  - (edge) `[low]` `[patch]` trace silently skipped when the tenant row is missing — WARN logged, tested.
  - (edge) `[medium]` `[patch]` in-flight export job recreates `<schema>/` after the purge — the daily export purge now removes the directory of every DELETED tenant.
  - (edge) `[medium]` `[patch]` failed export purge never retried — same daily sweep; Javadoc fixed.
  - (edge) `[medium]` `[patch]` admin free text kept in `tenant_payments.note/void_reason`, `tenant_plan_events.reason` — nulled / replaced by « Motif supprimé (RGPD) », billing rows kept.
  - (edge) `[maybe-false]` `[reject]` serialized events in `event_publication` keep names/phones — completed publications are purged by the existing `EventPublicationPurgeJob`; incomplete ones of a dropped tenant fail on replay; if real it would be low (internal outbox, not user-facing, bounded retention).
  - (blind) `[medium]` `[patch]` free text in billing tables — same as the edge row.
  - (blind) `[medium]` `[patch]` TENANT-targeted `global_notifications` keep title/body — rows of the deleted tenant deleted.
  - (blind) `[low]` `[patch]` `occurred_at DEFAULT now()` ties within a transaction — `clock_timestamp()`; test tie-break removed.
  - (blind) `[low]` `[patch]` trace lost without notice — same as the edge row.
  - (blind) `[low]` `[reject]` force-delete trace inconsistency — same as the intent row.
  - (blind) `[low]` `[patch]` admin `scheduled_for` clock — same as the edge row.
  - (blind) `[medium]` `[patch]` failed export purge never retried — same as the edge row.
  - (blind) `[medium]` `[patch]` running export brings files back — same as the edge row.
  - (blind) `[low]` `[reject]` 8-argument constructor without trace — only test callers; the Spring bean uses the full constructor with `requireNonNull`.
  - (blind) `[low]` `[patch]` unit test name over-claims — renamed.
  - (blind) `[low]` `[reject]` integration test leaves data in the shared test database — same pattern as the existing Postgres integration tests (unique tenant codes per run); no cross-test interference.
  - (blind) `[low]` `[patch]` no index on `tenant_code` — added.
  - (blind) `[maybe-false]` `[reject]` trace table has no retention rule / no edit protection — retention is a legal/policy decision outside this story; the table holds no personal data; if real it would be low.
  - (edge) `[false]` `[reject]` deletion trace contains personal data — checked by `traceTableHasNoPersonalColumns`.
  - (gap) `[false]` `[reject]` remaining paths — layer reported them covered.
  - (blind) `[false]` `[reject]` billing tables must be deleted — intent: keep `tenant_payments` / `tenant_plan_events` (accounting trace); only their free text is cleared.

### 2026-11-10 — Review pass (reprise, 5 layers incl. FUS-NFR11)
- verdicts: 24 findings — high 0, medium 0, low 13, false 8, maybe-false 3
- findings:
  - (nfr11) `[low]` `[reject]` `offer_change_log` journal scanned on each deletion (no `entity_key` index) — set-based, admin-written journal of a few hundred rows, a handful of deletions a day; recorded in Design Notes.
  - (nfr11) `[low]` `[reject]` `expo_push_tickets` scanned by `tenant_schema` — table holds ≤ 24 h of tickets; recorded in Design Notes.
  - (gap) `[low]` `[reject]` purge-failure rollback only mock-tested — one `@Transactional` method, single DataSource (`JdbcTemplate` joins the JPA transaction, as in every other mixed service); a real-DB failing-purger test needs a test bean override, more than the risk warrants.
  - (edge) `[false]` `[reject]` `payment_requests.discount_label` missed when the TENANT discount was deleted earlier — discounts are never deleted by the application (archive only, `archived_at`); the only `DELETE FROM public.discounts` is this purge.
  - (edge) `[false]` `[reject]` journal summary missed for an already-deleted discount — same refutation.
  - (edge) `[low]` `[patch]` removed member's MCP tokens left in public — tokens are owner-only and an owner cannot be removed, but a former owner can; added `McpTokenMemberPurger` (mcp module, one indexed delete) and asserted in `AiAssistantFullContextTest.memberRemovalErasesTheirConversations`.
  - (edge) `[low]` `[reject]` `payment_requests.requested_by` keeps the deleted owner's id — the users row is deleted, the id resolves to nothing (pseudonymous, unlinkable); payment trace kept by decision, like `tenant_payments.recorded_by`.
  - (edge) `[low]` `[reject]` `external_subscriptions` / `revenuecat_webhook_event` keep app user / Apple transaction ids — documented decision: Apple billing and webhook routing trace (an active Apple subscription keeps sending events until the owner cancels it in Apple); ids only, no name/phone.
  - (blind) `[low]` `[reject]` App Store tables not purged — same decision as above.
  - (blind) `[false]` `[reject]` journal rows of a deleted discount keep the company name — discounts are archive-only.
  - (blind) `[false]` `[reject]` discount label kept after SET NULL — discounts are archive-only.
  - (blind) `[low]` `[patch]` `payment_requests.failure_reason` (provider text, may echo the payer number) kept — nulled with the phone; seeded and asserted in the RGPD integration test. `ussd_code` / provider refs are not personal.
  - (blind) `[low]` `[reject]` no Meta-side unsubscribe on deletion — excluded by the spec's Never list (no Meta call); deliveries for an unknown number are dropped by the 21.5 webhook routing; residual risk noted.
  - (blind) `[low]` `[patch]` member removal only purges assistant conversations (MCP tokens) — same fix as the edge row; `ai_tool_calls.actor_id` is a journal with ids only, purged with the company.
  - (blind) `[low]` `[reject]` rollback promise untested on a real DB — same as the gap row.
  - (blind) `[maybe-false]` `[reject]` DISABLED-module purge untested — the purger has no module-state check at all (only `to_regclass`), so the state cannot change its behaviour; if real it would be low.
  - (blind) `[maybe-false]` `[reject]` `memberPurgeWithoutModuleTable` asserts little — its purpose is "no SQL error without the table"; a missing guard throws on the DELETE; if real, low.
  - (blind) `[false]` `[reject]` null `employee.getUserId()` — employees.user_id is NOT NULL (every employee is a member).
  - (blind) `[false]` `[reject]` spec missing from the diff — planning files excluded from the review diff on purpose.
  - (intent) `[low]` `[reject]` owner deactivation purges nothing — deactivation is reversible; documented default decision.
  - (intent) `[false]` `[reject]` "deleted member" half of (a) has no new test — the DROP SCHEMA removes `ai_conversations`; `schemaExists` is asserted by the existing integration test; no per-user account deletion surface exists.
  - (intent) `[maybe-false]` `[reject]` tests seed raw SQL instead of the 21.x flows — the purge is keyed by tenant id / schema, not by the content written by those flows; if real, low.
  - (intent) `[low]` `[reject]` journal assertion uses a hand-written summary — the redaction keys on `entity_key`, independent of the text.
  - (intent) `[false]` `[reject]` sprint-status / spec outside the diff — finalisation step, committed with the story.

## Design Notes

Deleting `feedback` rather than anonymising it: the description and screenshot are free user content (personal data under RGPD) and the product team keeps aggregate feedback counts elsewhere only through the admin inbox, which concerns live tenants. Billing tables are kept as the legal accounting trace; they carry no owner identity once the tenants row is anonymised.

**Inventory of `public` tables with a tenant or user column (V1–V40 + `TenantSchemaSyncService` global list, 2026-11-02) and their fate on account deletion** (all in `TenantDeletionService.executeDeletionForTenant`, same transaction, after the DROP):

| Table | Tenant / user key | Decision | Reason |
|---|---|---|---|
| `tenants` | id | **anonymised** — `name` → « Commerce supprimé », `designation`, `suspended_reason` → null; `code`, `schema_name`, `status = DELETED`, plan/limits, `suspended_at/by` kept | billing and code references; free text may name the owner; `suspended_by` is a platform admin id |
| `user_tenant_memberships` | tenant uuid, user | deleted (existing) | identity link |
| `users` | user | deleted when orphaned (existing); kept for a member of another tenant | multi-tenant users keep their account |
| `password_reset_tokens` | user (FK, no cascade), phone | **deleted for orphaned users, before `users`** | phone = personal data; a recent token made the user delete fail and postponed the whole deletion |
| `refresh_tokens` (incl. 16.11 device sessions: `session_id`, `device_id`, `device_label`) | tenant schema, user | deleted for the tenant (existing) | device label is personal data |
| `device_tokens` (public) | user | deleted for orphaned users (existing) | FCM token |
| `feedback` | tenant schema, user, free text | **deleted** | free text + user id are personal data |
| `user_sync_state` (incl. V37 sync scope) | tenant schema, user | **deleted** for the tenant | user id, device id |
| `user_daily_activity` | tenant uuid, user | **deleted** for the tenant | user id + activity |
| `tenant_modules` | tenant uuid | **deleted** | licences of a deleted account (no module history table exists in public) |
| `plan_expiry_reminders` | tenant uuid | **deleted** | operational, tied to the tenant |
| `sync_silence_notices` | tenant uuid | **deleted** | operational, tied to the tenant |
| `tenant_payments` | tenant uuid (FK RESTRICT), `recorded_by`/`voided_by` admin ids, free text `note`, `void_reason` | **kept, free text cleared** — `note` → null, `void_reason` → « Motif supprimé (RGPD) » where set (no CHECK on these columns, V17/V22); amounts, dates, method, ids kept | legal accounting trace (Never list), but admin text may name the owner |
| `tenant_plan_events` | tenant uuid, `actor_id` admin, free text `reason` | **kept, `reason` → null** | billing/churn trace (Never list), but admin text may name the owner |
| `global_notifications` | `target_tenant_id` (TENANT target), title/body | **deleted** where `target_tenant_id` = the tenant; broadcast rows (ALL/PREMIUM/TRIAL/FREE) kept | per-tenant title/body may hold personal data; no table references `global_notifications` (no read/ack table), so the delete is FK-safe |
| `admin_jobs` | report names tenants by code only, `actor` admin | kept | no personal data |
| `admin_alert_acks` | opaque alert key (may embed tenant/feedback uuid), admin id | kept | ids only, no personal data |
| `account_deletion_log` (V40) | tenant uuid + code | kept (written) | the durable, non-personal trace; `occurred_at` defaults to `clock_timestamp()` (events of one transaction still order), indexed on (tenant_id, occurred_at) and tenant_code; added to `TenantSchemaSyncService` global-only tables |
| `event_publication` / `event_listener_receipt` | serialized events (may carry actor ids) | kept | transient outbox, completed rows purged by `EventPublicationMaintenance`/purge job |
| `plan_profiles`, `profile_modules`, `bundle_definitions`, `bundle_modules`, `module_definitions`, `platform_health_samples`, `shedlock` | none | n/a | global catalogue / infra |
| V1 template tables in public (`audit_log`, `stores`, `clients`, `sales`, `export_jobs`, …) | — | n/a | structural templates, rows live in tenant schemas (dropped with the schema) |

**Files:** product photos, company logo (existing purgers) and now export files (`LocalDiskExportFileStorage` → `TenantFilesPurger`, recursive delete of `<storage-dir>/<schema>/`, schema validated by `TenantSchema.validate`, never outside the root) are purged after commit. The hourly `ExportPurgeScheduler.purge()` also calls `ExportFileStorage.deleteTenantFiles` for every DELETED tenant: it retries a failed post-commit purge and removes files an export still running at deletion time wrote after it.

**Trace sources:** owner request/cancel → OWNER (`AccountDeletionService`, same transaction); admin cancel → CANCELLED/ADMIN (same transaction as the conditional UPDATE); admin force-delete → REQUESTED/ADMIN (autocommit, right after the committed marker; `scheduled_for` copied from the row's `deletion_scheduled_at`, so it equals the marker) then EXECUTED/ADMIN (inside `deleteNow`); a trace write for a tenant row that does not exist inserts nothing and logs `ACCOUNT_DELETION_LOG_SKIPPED`; daily run → EXECUTED/SYSTEM. Admin writes go through `identity.api.TenantDeletionPort` (`recordAdminDeletionRequest` / `recordAdminDeletionCancel`) so the admin module never touches identity internals.

**AC2 evidence:** `ModuleExpiryMaterializerPostgresIntegrationTest.reactivationKeepsData` now also materialises 400 days past the grace and asserts the DISABLED module's 5 invoices are still there, then reactivates them; `ModuleLifecyclePostgresIntegrationTest.deactivateKeepsData` unchanged. No code change on the module lifecycle.


## Auto Run Result

**Summary:** FR91 account deletion (V1 core: request, 30-day grace, cancel, admin force-delete, daily DROP SCHEMA) completed for the refonte. The purge now also removes or anonymises every remaining `public` trace of the tenant's personal data: password-reset tokens of orphaned users (deleted before the users — the deletion is no longer postponed by a recent token), `feedback`, `user_sync_state`, `user_daily_activity`, `tenant_modules`, `plan_expiry_reminders`, `sync_silence_notices`, tenant-targeted `global_notifications`; `tenants` row kept but anonymised (« Commerce supprimé », designation / suspension reason cleared); billing rows kept with their free text cleared (`note`, `reason` null, `void_reason` « Motif supprimé (RGPD) »). Export files: `<storage-dir>/<schema>/` purged after commit and swept again by the hourly export purge for every DELETED tenant. New durable, non-personal `public.account_deletion_log` (V40): REQUESTED / CANCELLED / EXECUTED with source OWNER / ADMIN / SYSTEM, written in the same transactions. DISABLED module data is never purged (unchanged, re-asserted 400 days past grace).

**Files changed:** V40 migration; `AccountDeletionLogRepository` + `JdbcAccountDeletionLogRepository`; `AccountDeletionService`, `TenantDeletionService`, `TenantDeletionPort` / `TenantDeletionAdapter`, `AdminTenantService`, `Tenant.anonymisedAsDeleted`; `ExportFileStorage.deleteTenantFiles`, `LocalDiskExportFileStorage` (`TenantFilesPurger`), `ExportPurgeScheduler`; `TenantSchemaSyncService` (global-only table). Tests: new `AccountDeletionRgpdPostgresIntegrationTest` (10), `LocalDiskExportFileStoragePurgeTest`; extended `TenantDeletionServiceTest`, `AccountDeletionServiceTest`, `AdminTenantServiceTest`, `IdentityAuthApiAdaptersTest`, `ExportPurgeSchedulerTest`, `GlobalNotificationPostgresIntegrationTest`, `ModuleExpiryMaterializerPostgresIntegrationTest`.

**Review:** 33 findings — 0 high, 4 medium entries (free text in billing tables, tenant-targeted notifications, export purge never retried, running export recreating files — all patched), 20 low (8 patched, 12 rejected with reasons), 7 false, 2 maybe-false (rejected). 0 deferred.

**Follow-up review recommended:** true — four medium entries patched on a first pass; named risk: the export sweep of DELETED tenants in `ExportPurgeScheduler` and the billing free-text redaction were only re-tested, not re-reviewed.

**Verification performed (2026-11-02):** after the patches, one package at a time: identity 493, admin 561, reporting 420, feedback 52, sync 635, architecture 32, shared 1154 — all rc=0, 0 failures/errors. Live (local backend, noop messaging; V40 applied on keevo_dev at boot): KV-EW02A2 owner `POST /account/delete` 200 then `/cancel` 200 → trace REQUESTED/OWNER (with scheduled date) then CANCELLED/OWNER, tenant back to ACTIVE, `account_deletion_log` present only in `public`; 0 ERROR. Redmi (Flutter test client, after the user unlocked it): « En ligne », fresh `last_pull_at`, no « suppression programmée » banner after the cancel, 0 ERROR.

**Mobile / dashboard:** no section — endpoints, request bodies and responses are unchanged (V1 2.0.1 and the RN app unaffected); no new admin API.

**Residual risks:** retention period of `account_deletion_log` not defined (policy decision; the table holds no personal data); serialized payloads of incomplete event publications of a deleted tenant are left to the existing outbox purge.

### Deferred items routed 2026-11-10
- [ ] [Deferred-from: 21-4 review, 2026-11-10] Assistant conversations of a removed or deleted member never purged [keevo/backend/src/main/java/com/keevo/assistant/conversation/adapter/out/persistence/JdbcAiConversationRepository.java] — `kv_x.ai_conversations` rows (free-text business questions, client names and phones copied from guided answers) stay after a member is removed from the company or deletes their account (only the tenant purge drops them); settle by deleting the user's rows in the member-removal / account-deletion path (module table present only when ai-assistant was provisioned) and a test, or by a documented retention rule.
- [ ] [Deferred-from: 21-5 review, 2026-11-10] Encrypted WhatsApp Business token of a deleted company kept at rest [keevo/backend/src/main/resources/db/migration/V68__whatsapp_cloud.sql, public.whatsapp_accounts] — the company deletion is a soft delete (`tenants.status = 'DELETED'`), so the `ON DELETE CASCADE` never runs: the row (AES-GCM token, WABA and number ids) stays until another company connects the same number (21.5 frees it then). Settle by deleting `public.whatsapp_accounts` of the tenant in the account-deletion purge (one statement by tenant_id) and a test; the tenant-schema inbox goes with the schema drop.
- [ ] [Deferred-from: 19-3b review, 2026-11-10] Accounts without any company cannot be deleted, so their Sign in with Apple link is never revoked [keevo/backend/src/main/java/com/keevo/identity/auth/application/service/TenantDeletionService.java] — a Google/Apple account created without company (19.3) has no tenant, and the only user deletion is the tenant purge (orphans); the App Store requires in-app deletion for every account. Settle with a self-service deletion of an account without membership that deletes the user (cascade on `user_authenticators`) after queueing its Apple tokens with the same `INSERT … SELECT` into `public.apple_token_revocations` (see `TenantDeletionService.QUEUE_APPLE_REVOCATIONS_SQL`), and a test.

## Reprise 10/11/2026

### Intent (reprise)

**Problem:** two items routed to 16-6 by the 21-4 and 21-5 reviews, plus an audit of the public tables added since V40 (stories 19-x, 20-x, 21-1..21-5): (1) a member removed from a company (super-admin member removal, `RemoveEmployeeService`, story 20.13) keeps their assistant conversations (`"kv_x".ai_conversations`: free-text questions, client names/phones) in the company schema forever; (2) a deleted company keeps its encrypted WhatsApp Business token row (`public.whatsapp_accounts`, V68) because the deletion is logical (tenant row kept, `ON DELETE CASCADE` never fires); (3) the same soft-delete gap affects the V41–V68 tables declared `ON DELETE CASCADE` on `tenants`, several with personal data: `mcp_access_tokens` (token name, owner id; a multi-tenant owner keeps them), `ai_tool_calls` (actor id + tool usage), `tenant_module_activation` (actor id), `discounts` of scope TENANT (admin label written for the company), `payment_requests.phone` (Mobile Money payer number), `payment_requests.discount_label` and `offer_change_log.summary` (copy the tenant discount label, and `OfferCatalogService.discountSummary` writes « pour "<company name>" »), `expo_push_tickets` (device push token keyed by schema), and the user-keyed `web_push_subscriptions` (no FK to `users`: endpoint + user agent of a deleted user survive).

**Approach:** extend `TenantDeletionService.executeDeletionForTenant` (same transaction, set-based, one statement per table) with the missing deletes/redactions; for member removal add a shared out-port `MemberDataPurger` (same shape as `TenantFilesPurger`) called by `RemoveEmployeeService` in its transaction, implemented by the assistant module (delete the member's `ai_conversations` rows when the module table exists). No migration needed (V72 left unused): every new statement hits an existing index or a small table (see Design Notes).

### Boundaries & Constraints (reprise)

**Always:**
- Account deletion, after the existing step 9 and before the trace, FK-safe order: redact `payment_requests.discount_label` (→ « Remise ») of rows using a TENANT discount of the tenant and `offer_change_log.summary` (→ « Remise réservée à une entreprise supprimée ») of `entity = 'DISCOUNT'` rows whose `entity_key` is one of those discounts — both BEFORE deleting the discounts; then delete `discounts`, `whatsapp_accounts`, `mcp_access_tokens`, `ai_tool_calls`, `tenant_module_activation` by tenant id; `expo_push_tickets` by schema; `UPDATE payment_requests SET phone = NULL` by tenant (rows kept: payment trace); `web_push_subscriptions` of orphaned users (next to the existing orphan-only `device_tokens` delete).
- Member removal: `RemoveEmployeeService.execute` calls every `MemberDataPurger` bean after the membership delete, in the same transaction (a purger failure rolls the removal back — the super-admin retries). The assistant purger deletes `"<schema>".ai_conversations WHERE user_id = ?` when `to_regclass` finds the table (module never provisioned → no-op), schema validated by `TenantSchema.validate`, even if the module is DISABLED (the removed person's personal data, not module data).
- Multi-tenant users: only the deleted tenant's rows go (`mcp_access_tokens`, `ai_tool_calls` by tenant; web push only for orphaned users).

**Never:** no change to endpoints or responses (V1 2.0.1 / RN apps unaffected); no purge on member *deactivation* (reversible); no deletion of `payment_requests`, `tenant_payments`, `external_subscriptions`, `revenuecat_webhook_event` (billing / store routing trace); no change to campaign code (21-6, parallel story); no Meta-side call on deletion.

### I/O & Edge-Case Matrix (reprise)

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Daily deletion, new tables | tenant with WhatsApp account, MCP token, AI tool call, module activation, TENANT discount + journal + payment request using it (phone), Expo ticket, orphan's web push subscription | rows deleted; payment request kept with phone NULL and discount label « Remise »; journal summary redacted | rolled back with the rest on failure |
| Shared user | employee also member of tenant B with B rows | B's WhatsApp account, MCP token, tool calls, module activation, web push subscription kept | — |
| Member removed, assistant provisioned | member with 2 conversations, another member with 1 | the member's 2 rows deleted, the other's kept | purger exception → removal rolled back |
| Member removed, module never provisioned | no `ai_conversations` table | removal succeeds, no SQL error | — |
| Owner removal refused | target is OWNER | no purger called | 403 (unchanged) |

### Code Map (reprise)

- `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/TenantDeletionService.java:236-340` -- step 6 (`orphanedUserIds`), step 8 (device_tokens of orphans), step 9 (public deletes): add the reprise statements after step 9.
- `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/RemoveEmployeeService.java:47-63` -- add `List<MemberDataPurger>` and the call after `membershipRepository.remove`.
- `keevo/backend/src/main/java/com/keevo/shared/domain/port/out/TenantFilesPurger.java` -- pattern for the new `MemberDataPurger` port (same package).
- `keevo/backend/src/main/java/com/keevo/assistant/conversation/adapter/out/persistence/JdbcAiConversationRepository.java` -- table / schema validation pattern; new `AiConversationMemberPurger` next to it.
- `keevo/backend/src/main/resources/db/modules/ai-assistant/V1__ai_conversations.sql` -- `idx_ai_conversations_user_updated (user_id, …)` serves the delete.
- Migrations (evidence): V46 expo_push_tickets (≤ 24 h retention, no schema index), V47 web_push_subscriptions (`ix_web_push_subscriptions_user`), V50 tenant_module_activation (PK tenant_id, module_id), V60 discounts (`ix_discounts_tenant`) / offer_change_log, V61 payment_requests (`(tenant_id, created_at DESC)`), V64 ai_tool_calls (`(tenant_id, called_at DESC)`), V66 mcp_access_tokens (`(tenant_id, user_id, created_at DESC)`), V68 whatsapp_accounts (PK tenant_id).
- `keevo/backend/src/main/java/com/keevo/subscription/offer/application/service/OfferCatalogService.java:628-640` -- `discountSummary` embeds the company name (evidence).
- Tests: `AccountDeletionRgpdPostgresIntegrationTest` (seed, `assertTenantPublicRowsGone`, shared-user test), `TenantDeletionServiceTest`, `RemoveEmployeeServiceTest`, `AiAssistantFullContextTest` (member removal through `EmployeeCommandPort.remove`, the port the super-admin endpoint uses; purge without module table).

### Tasks & Acceptance (reprise)

**Execution:**
- `keevo/backend/src/main/java/com/keevo/shared/domain/port/out/MemberDataPurger.java` -- new port `void purgeMember(String tenantSchema, UUID userId)`.
- `keevo/backend/src/main/java/com/keevo/assistant/conversation/adapter/out/persistence/AiConversationMemberPurger.java` -- implementation (to_regclass check + delete).
- `RemoveEmployeeService` (+ `RemoveEmployeeServiceTest`) -- inject purgers, call after membership removal; owner refusal calls none.
- `TenantDeletionService` (+ `TenantDeletionServiceTest`) -- reprise statements.
- `AccountDeletionRgpdPostgresIntegrationTest` -- seed and assert the new tables (deleted tenant and shared-user tenant B).
- `AiAssistantFullContextTest` -- provisioned (member's rows gone, other member's kept) / no module table (no-op).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- 16-6 back to done with a dated summary; routed items ticked above.

**Acceptance Criteria:**
- Given a company whose deletion grace is over and that used WhatsApp Business, MCP, the assistant, Mobile Money and a company-specific discount, when the daily job runs, then no public row keeps its WhatsApp token, MCP tokens, tool-call journal, module activations, discount, push tickets, payer phone or company-specific label in a payment request / catalogue journal, and its billing rows are still there.
- Given a member with assistant conversations, when a super-admin removes the member, then the company schema holds none of that user's conversations and the other members' conversations are intact.

### Design Notes (reprise)

**Pattern:** member removal uses the *purger port + bean list* pattern of `TenantFilesPurger` (dependency inversion: identity never depends on the assistant module; any module holding per-member personal data adds a bean). It runs synchronously in the removal transaction rather than through an after-commit event, so removal and purge are atomic and a failure is visible to the super-admin. Account deletion stays a single transactional script (one statement per table, FK-safe order), the existing pattern of `executeDeletionForTenant`.

**Cost:** account deletion adds 11 statements per deleted tenant (daily job, a handful of tenants at most), all on indexed tenant id / user id except `expo_push_tickets` (no index on `tenant_schema`; the table only holds ≤ 24 h of tickets) and `offer_change_log` (`entity_key` unindexed; admin-written catalogue journal, small; the sub-select on `discounts` uses `ix_discounts_tenant`). No per-row loop. Member removal adds 2 queries (`to_regclass` + delete on indexed `user_id`). No index, no migration (V72 reserved, unused).

**Inventory delta (public tables V41–V68, fate on account deletion):**

| Table | Key | Decision | Reason |
|---|---|---|---|
| `whatsapp_accounts` (V68) | tenant (PK) | **deleted** | encrypted WABA token + number ids (routed item); the tenant-schema inbox goes with the DROP |
| `mcp_access_tokens` (V66) | tenant, user | **deleted** by tenant | token name, owner id; the user CASCADE only fires for orphans |
| `ai_tool_calls` (V64/V67) | tenant, actor | **deleted** | usage journal with actor ids, no billing value |
| `tenant_module_activation` (V50) | tenant | **deleted** | operational, actor id |
| `discounts` scope TENANT (V60) | tenant | **deleted** (after redactions) | admin label written for this company |
| `offer_change_log` (V60) | DISCOUNT entity_key | **kept, summary redacted** for the tenant's discounts | admin journal, but the summary quotes the company name |
| `payment_requests` (V61/V63) | tenant, requested_by | **kept, `phone` → NULL, TENANT `discount_label` → « Remise »** | payment trace (amounts, refs); payer number is personal; `requested_by` dangles once the user is deleted |
| `expo_push_tickets` (V46) | tenant schema, device token | **deleted** | device token; also keeps `ExpoReceiptJob` off a dropped schema |
| `web_push_subscriptions` (V47) | user (no FK) | **deleted for orphaned users** | endpoint + user agent of a deleted user |
| `user_authenticators` (V48) | user (CASCADE) | gone with the orphaned user | — |
| `otp_challenges` (V45) | identifier | kept | not tenant-linked; purged after 24 h by `AuthTokenCleanupJob` |
| `external_subscriptions`, `revenuecat_webhook_event` (V62) | tenant, app user id | kept | Apple billing / webhook routing trace (an active Apple subscription still sends events); ids only |
| `usage_*`, `tenant_credit*` (V44/V63), `invoice_legacy_links` (V57) | tenant | deleted (existing, 19.8 / 21.3 / 20.7) | — |
| catalogue tables (`plans`, `plan_*`, `billing_cycles`, `store_products`, `usage_packs`, `app_versions`, `data_migrations`, `module_activation_rule_*`) | none | n/a | global |

**Default decisions (no human input):** purge on removal only, not on deactivation (reversible); purge even if the assistant module is DISABLED (personal data of the removed person — AC2 of 16.6 concerns module lifecycle data); payment requests kept with the payer phone cleared (accounting, same rule as `tenant_payments`); external subscriptions kept (store routing).

**Mobile / Dashboard:** no section — no endpoint, body or response change; no new admin API (the super-admin removal endpoint is unchanged, it now also erases the assistant history).

### Spec Change Log (reprise)
- 2026-11-10 review: `McpTokenMemberPurger` added (removed former owner's MCP tokens of the company) and `payment_requests.failure_reason` cleared with the phone; the member-removal test lives in `AiAssistantFullContextTest` instead of a separate file.

### Auto Run Result (reprise 10/11/2026)

**Summary:** both items routed to 16-6 are fixed, and account deletion now covers the public tables added since V40.
- **Member removal** (super-admin, `RemoveEmployeeService`) calls every `MemberDataPurger` bean inside its transaction:
  - the assistant deletes the member's `ai_conversations` rows for the company (nothing to do if the module table does not exist);
  - the MCP module deletes the member's personal access tokens for that company.
- **Account deletion** (`TenantDeletionService.purgeLaterPublicRows`, same transaction):
  - deletes `whatsapp_accounts` (the encrypted WABA token), `mcp_access_tokens`, `ai_tool_calls`, `tenant_module_activation`, TENANT discounts, `expo_push_tickets`, and the `web_push_subscriptions` of orphaned users;
  - keeps `payment_requests`, but clears the payer phone and the provider failure text;
  - before deleting the discounts, replaces the company-specific discount label with « Remise » and the catalogue journal summary with « Remise réservée à une entreprise supprimée ».
- **App Store tables** are kept as the Apple billing and webhook routing trace (documented decision).

**Files changed:**

| File | Change |
|---|---|
| `shared/domain/port/out/MemberDataPurger.java` | new port |
| `assistant/.../AiConversationMemberPurger.java` | new purger |
| `mcp/token/.../McpTokenMemberPurger.java` | new purger |
| `RemoveEmployeeService.java` | calls the purgers after the membership delete |
| `TenantDeletionService.java` | `purgeLaterPublicRows`; web push of orphaned users |

Tests changed:

| Test | What it covers |
|---|---|
| `RemoveEmployeeServiceTest` | call order; a purger failure propagates; refused owner removal |
| `TenantDeletionServiceTest` | the new statements and the redaction order |
| `AccountDeletionRgpdPostgresIntegrationTest` | the new tables, seeded and asserted for the deleted tenant and for the shared-user tenant B |
| `AiAssistantFullContextTest` | removal through `EmployeeCommandPort.remove` erases the conversations and the MCP token; purge works without the module table |

**Review:** 24 findings.
- 3 low patched: the MCP tokens of a removed member (found by two layers) and the `failure_reason` of kept payment requests.
- 21 rejected, each with its reason in the 2026-11-10 triage entry.
- 0 deferred.
- Patched by verdict: high 0, medium 0, low 3.

**Follow-up review recommended:** false, because no high or medium finding was patched.

**Verification performed (2026-11-10):**

Tests, one package at a time, after the patches. All rc=0 with 0 failures:

| Package | Tests |
|---|---|
| assistant | 55 |
| identity/auth | 517 |
| identity/employee | 220 |
| mcp | 30 |
| architecture | 146 |
| shared | 1 354 |
| admin | 690 (run before the patches, which do not touch admin) |

Live check: worktree backend on port 4506, fresh DB `keevo_dev_t166`, WhatsApp noop, FCM off.
- The backend booted with every migration.
- Setup: an owner was registered, then a WhatsApp account, an MCP token, a web push subscription, an Expo ticket and a Mobile Money request were seeded.
- Super-admin `POST /api/v1/admin/tenants/{id}/force-delete` returned 200.
- Afterwards:
  - all the seeded rows were gone;
  - the payment request was kept, with phone and failure text NULL;
  - the tenant reads `DELETED / Commerce supprimé`;
  - the trace shows REQUESTED/ADMIN then EXECUTED/ADMIN;
  - the log has 0 ERROR.
- The backend was stopped and the DB dropped.

No migration (V72 unused) and no endpoint change, so `endpoints.md` is unchanged.

**Residual risks:**
- A deleted company's WhatsApp number stays subscribed on Meta's side until the owner removes the app from their WABA. Deliveries to an unknown number are dropped by the 21.5 routing.
- The rollback of a removal when a purger fails relies on the transactional design and a unit test. No real-DB test makes a purger fail.

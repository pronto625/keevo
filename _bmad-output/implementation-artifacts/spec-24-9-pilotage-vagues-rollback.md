---
title: '24.9 — Cutover command: import every owner of a restored Bref database, reconcile, global activation, report, rollback'
type: 'feature'
created: '2026-11-11'
status: 'in-progress'
baseline_revision: '00d43c7c0d8164cc6aa0af005e7cf322d4991519'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-24-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-8-reconciliation-bloquante.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-7-bridge-identite-collisions.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** 24.2–24.8 import and reconcile ONE Bref owner per API call. ADR-10 (one-shot cutover) needs one super-admin command that imports every owner of the restored Bref dump, reconciles each one, reports globally, then either activates every imported company at once (« go ») or removes them all (rollback = turn Bref back on). Seven routed items (24.2, 24.3, 24.4 ×2, 24.5, 24.7, 24.8) wait for it.

**Approach:** A `cutover` component in the `migration` module: an asynchronous coordinator lists the owners of the `DATABASE` source by keyset pages, runs the existing import service then the 24.8 reconciliation for each owner on a bounded pool (one failure never stops the others), tracks every owner in `public.migration_cutover_owners` and stores an archived JSON report in `public.migration_cutovers` (V91). Activation and rollback are set-based actions on the same cutover through identity's import port and the tenant deletion routine.

## Boundaries & Constraints

**Always:**
- Source = `DATABASE` only (a restored Bref copy, read-only role). Owners = `"user".role = 'owner' AND "deletedAt" IS NULL`, listed by `id` keyset (pages of 200), never all in memory.
- One open cutover at a time (open = not `ACTIVATED`/`ROLLED_BACK`); the command is resumable: a new run on the same cutover re-processes every owner not `RECONCILED`/`NOT_MIGRATED` and lists owners added since (import idempotent, FUS-MIG1).
- Per owner: `BrefOwnerImportService.importOwner` (the pipeline untouched, every step incl. 24.6's), then `BrefReconciliationUseCase.reconcile` on a `COMPLETED` batch; any exception → owner `FAILED` with a French label, the run goes on. Bounded parallelism (default 4, 1–8), source pool sized to it.
- Owner status: `PENDING`, `RECONCILED`, `GAP`, `BLOCKED`, `FAILED`, `NOT_MIGRATED` (batch BLOCKED only by `OWNER_INACTIVE`/`OWNER_NO_SHOP` — nothing to migrate, does not block the go), `ACTIVATED`, `ROLLED_BACK`.
- Report (FUS-MIG10), recomputed set-based at the end of a run, activation and rollback: owners per status with French reasons counted; counts per table and sums (sales, collected, expenses, outstanding, credit; Bref vs Keevo) aggregated from the reconciliation lines; anomalies (batch notes per label); durations (listing, imports Σ and wall, reconciliations Σ, per owner avg/p95/max, activation, rollback, total); whether the copy's helper indexes are present. No UUID nor personal data in labels (owners by position « Compte n »).
- Activation (« go »): `confirmation = "ACTIVER"`; gate = `BrefReconciliationUseCase.blockingOwners` over the cutover's owners except `NOT_MIGRATED` (one query); refused 409 with the owners in cause (handle, label, French reason) unless every one is in `acceptedOwners` with a reason (≥ 10 chars, stored with actor and time). Activates in one transaction the companies of `RECONCILED` and accepted `COMPLETED` owners: `SUSPENDED` with the import reason → `ACTIVE`, suspension trace cleared; their imported invitations (records `membership_invitations`) re-dated (`invited_at = now()`, `expires_at = now() + 7 d`, `last_sent_at = now() − 24 h`). No event, no message. Re-runnable after the go (activates owners cleared since).
- Rollback (before the go only): `confirmation = "RETOUR ARRIERE"`, asynchronous, per owner: mark the company `DELETION_PENDING` only if `SUSPENDED` with the import reason (scheduled far ahead so the daily deletion never takes it), detach `linked_users` memberships, delete traced `user_authenticators`, then the deletion routine without the final WhatsApp nor RGPD trace, removing the tenant row and its billing rows; leftover created `users` deleted only when orphan; then the owner's `migration_records` and `migration_batches` (reconciliations cascade). `linked_users` accounts are never deleted. Relaunchable; failures listed per owner.
- Reads: fetch size on the source reads; writes in chunks of 2 000 rows per statement (catalog, clients, documents/lines/payments, expenses, old links, records) — same transaction, same order.
- Logs `[ADMIN AUDIT] cutover=… migrationBatchId=… owner=…` (ids and counts only), MDC `cutoverId`/`migrationBatchId`; Micrometer timer `keevo.migration.cutover.owner` (stage, outcome).
- Super-admin only (`/api/v1/admin/**` + `CurrentActor.requireSuperAdmin`), `@EntitlementExempt("super-admin")`.

**Never:** writes to the Bref copy (helper indexes are a runbook script run by the DB owner before the read-only grant); notification of any kind from activation or rollback; rollback after the go; changes to the import steps' logic; a second open cutover; EXPORT source for the cutover.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Full run | 3 owners: clean, gap after tamper, identity blocked; 1 inactive | 202; owners RECONCILED / BLOCKED / NOT_MIGRATED; report counts and sums | — |
| Owner throws | Source row unreadable for one owner | That owner FAILED (label), others continue | logged by class |
| Go blocked | One owner BLOCKED, no acceptance | 409 `INVALID_STATE` « Activation refusée… », details.blockingOwners | — |
| Go accepted | Same + acceptedOwners with reason | 200; clean owners ACTIVE, invitations re-dated, blocked owner left out | — |
| Rollback after go | Cutover ACTIVATED | 409 « Après le « go », plus de retour arrière automatique » | — |
| Rollback | Cutover READY, one owner linked to a Keevo account | Companies, created accounts, records, batches gone; linked account and its own company kept; traced authenticator gone | per-owner failure listed |
| Second cutover | One open | 409 « Une bascule est déjà ouverte… » | — |
| Wrong source / confirmation | EXPORT, missing phrase | 400 `VALIDATION_FAILED` French message | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/migration/application/BrefOwnerImportService.java` -- `importOwner(owner, "DATABASE", actor)` reused as is (returns BLOCKED/COMPLETED/FAILED batch; throws `MIGRATION_*` DomainException before a batch).
- `.../migration/application/ReconcilingBrefOwnerImportUseCase.java` -- decorator semantics mirrored (reconcile only COMPLETED), not called (separate timings).
- `.../migration/domain/port/in/BrefReconciliationUseCase.java#blockingOwners` -- 24.8 gate (latest batch COMPLETED + latest run RECONCILED), French reasons.
- `.../migration/adapter/out/persistence/JdbcReconciliationStore.java` -- report JSON; lines' money values are `plain(v) + " F"` (`BrefReconciler.plain`), COUNTS values `Long.toString`.
- `.../migration/adapter/out/source/JdbcBrefSource.java` -- `readOnly(work)` (same package) for the owner listing; `rows()` gets a fetch size; pool size made configurable.
- `.../migration/adapter/out/persistence/JdbcMigrationRecordStore.java#insert` -- chunked; records `tenants`, `users`, `linked_users`, `membership_invitations`, `user_authenticators` per owner via `idx_migration_records_owner`.
- `.../migration/application/step/ProfileStep.java#SUSPENSION_REASON` -- the import's suspension reason (made public for the guard).
- `.../migration/domain/model/BlockedCase.java` -- `OWNER_INACTIVE`, `OWNER_NO_SHOP` (NOT_MIGRATED rule).
- `.../identity/api/LegacyIdentityImportPort.java` + `identity/legacyimport/application/LegacyIdentityImportService.java` + `.../port/LegacyIdentityStore.java` + `.../adapter/out/persistence/JdbcLegacyIdentityStore.java` -- add `activateImportedTenants`, `redateImportedInvitations`, `prepareImportedTenantRemoval`, `deleteOrphanImportedAccounts`.
- `.../identity/api/TenantDeletionPort.java` + `identity/auth/application/service/TenantDeletionService.java` -- add `forgetImportedNow` (same routine, no WhatsApp/trace, tenant row + `tenant_payments`/`tenant_plan_events` removed). `tenant_payments` FK is `ON DELETE RESTRICT` (V17); other public tables cascade (V44–V78).
- `.../invoicing/invoice/adapter/out/persistence/JdbcInvoiceImportWriter.java#insert`, `JdbcLegacyInvoiceLinkRegistry#registerAll`, `expenses/.../JdbcExpenseImportWriter`, `catalog/.../JdbcCatalogImportWriter#insertProducts/insertCategories`, `crm/.../JdbcClientImportWriter` -- chunk loops.
- `.../shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/ErrorMessages.java`, `GlobalExceptionHandler.java` -- `MIGRATION_CUTOVER_NOT_FOUND` (404).
- `keevo/backend/src/test/java/com/keevo/migration/BrefReconciliationFullContextTest.java` -- fixture pattern (synthetic Bref DB, MockMvc super-admin).
- `docs/RUNBOOK_BASCULE_BREF.md` -- add §3 (cutover command, helper indexes, measured throughput).

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/resources/db/migration/V91__migration_cutovers.sql` -- `migration_cutovers`, `migration_cutover_owners`, open-cutover unique index, batch list index `(started_at DESC, id DESC)`.
- `.../migration/domain/model/Cutover*.java` -- `CutoverStatus`, `CutoverOwnerStatus` (French labels), `Cutover`, `CutoverOwner`, `CutoverReport`.
- `.../migration/domain/service/CutoverFigures.java` -- pure: report → per-owner figures (counts, sums); owner outcome from batch/report; NOT_MIGRATED rule.
- `.../migration/domain/port/in/BrefCutoverUseCase.java`; `.../domain/port/out/BrefOwnerDirectory.java`, `CutoverStore.java`.
- `.../migration/adapter/out/source/JdbcBrefOwnerDirectory.java` -- keyset listing + helper-index probe.
- `.../migration/adapter/out/persistence/JdbcCutoverStore.java` -- cutover rows, owners, set-based report aggregates, batch list.
- `.../migration/application/BrefCutoverService.java` (+ `CutoverRunner` coordinator/pool) -- start, resume, activation, rollback, reads.
- `.../migration/adapter/in/rest/AdminBrefCutoverController.java` -- endpoints below; `AdminBrefMigrationController` gets `GET /batches`.
- Identity, deletion, writers, error code changes above; `docs/entitlements/endpoints.md` regenerated.
- `keevo/ops/migration/bref-copy-indexes.sql`, `keevo/ops/migration/bref-synthetic-load.sql` -- runbook script and the measurement data set.
- Tests: `CutoverFiguresTest`, `BrefCutoverServiceTest` (unit, fakes), `BrefCutoverFullContextTest` (synthetic Bref DB: run, report, gate 409, acceptance, go, rollback restoring the initial state, linked account kept), identity/deletion unit tests.

**Acceptance Criteria:**
- Given a restored Bref DB with N owners, when the super-admin POSTs a cutover, then GET shows progress then `READY` with every owner's status, and the report gives counts, sums and durations without any UUID in labels.
- Given a READY cutover with a blocking owner, when the go is posted without acceptance, then 409 lists it; with acceptance, the other companies become ACTIVE in one operation and no message is sent.
- Given a READY cutover, when the rollback is posted, then the imported companies, the accounts created by the import and their migration rows are gone and a pre-existing linked account keeps its own company.

## Spec Change Log

## Review Triage Log

## Design Notes

**Patterns:** Application service + asynchronous coordinator (Producer/Consumer: one listing thread feeds a bounded pool through a semaphore — memory O(page + parallelism)); Strategy reuse (source port); Gate (24.8 `blockingOwners`) as a precondition of a Command (activation); set-based Unit of Work for activation; Saga-like per-owner compensation for the rollback (each owner independent, relaunchable).

**Decisions (default, conservative, 11/11/2026):**
- D1 Deleted Bref owners are not listed (never migrated, RGPD); inactive or shop-less owners are `NOT_MIGRATED` (reported, not blocking).
- D2 `acceptedOwners` = the explicit signed exclusion list: owners whose gap or block was documented by the rehearsal (24.12); an accepted owner with a COMPLETED import is activated as is, one without stays out (company absent or still suspended). Stored with the actor and time in the cutover.
- D3 Reconciliation re-reads the source (D7 of 24.8 kept): an independent read of a static dump; its cost is measured per owner in the report (routed 24.8 item settled by measure).
- D4 Helper indexes on the restored copy come from a runbook script run as the copy's owner (the tool's role stays read-only); the report flags their absence.
- D5 Invitation re-dating sets `last_sent_at = now() − 24 h`: no message leaves; the owner can re-send at once (19.6b rule).
- D6 Rollback removes the tenant row (never-activated company), its `tenant_payments` and `tenant_plan_events` too: a later re-import with the same Bref ids must not collide; the cutover rows stay as history.
- D7 A coordinator interrupted by a restart leaves `RUNNING` with a stale heartbeat (> 15 min): a new run takes it over.

**Cost:** listing 1 indexed query per 200 owners; per owner the import's own reads/writes (+ reconciliation) — with the helper indexes every source read is an index lookup, so the whole cutover is O(total rows); 3 small updates per owner in `migration_cutover_owners`; report = constant aggregate queries; activation = 3 statements whatever N; rollback = per owner the deletion routine (one schema drop each).

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t249 migration identity/legacyimport identity/auth architecture invoicing expenses catalog crm shared/infrastructure/web` -- expected: all green.

**Manual checks:**
- Live (`bref_src_t249` = 03/11 dump + synthetic set, `keevo_dev_t249`, port 4649, WhatsApp noop, FCM off): full cutover → report → go → companies ACTIVE; second try: cutover → rollback → Keevo counts equal to the initial snapshot; throughput measured and extrapolated for 24.12; databases dropped.

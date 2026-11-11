---
title: '24.9 — Cutover command: import every owner of a restored Bref database, reconcile, global activation, report, rollback'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: '5661e2498f3dec50abef9a9b49b80d9312335d11'
review_loop_iteration: 0
followup_review_recommended: true
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

### 2026-11-11 — Review pass
- verdicts: 58 findings — high 0, medium 9, low 26, false 15, maybe-false 2 (+ 6 descriptive intent-auditor divergences counted as false/low below)
- note: implementation done in this session (common rules: no implementation subagent); the 24.6 routed items (assistant conversations read, web push rollback) were added after the rebase on 5661e249 and are in the reviewed diff.
- findings:
  - *Blind hunter*
  - `[medium]` `[patch]` « go » without a state guard, races a rollback/rerun; `activated()` overwrote the status — activation now moves READY|ACTIVATED → ACTIVATED by a conditional `transition` after the gate and before any write; `activated()` only records actor/time (`coalesce`, first go kept) under `status = 'ACTIVATED'`; unit test `theGoLosesAgainstARollbackStartedMeanwhile`.
  - `[medium]` `[patch]` `finish()` unfenced: a late coordinator could overwrite a decision — `FINISH_SQL` now requires `status IN ('RUNNING','ROLLING_BACK')`.
  - `[low]` `[patch]` Coordinator does not beat while blocked on the semaphore — `tryAcquire(1 min)` loop with heartbeat.
  - `[low]` `[patch]` 30-minute takeover not in the runbook — runbook §3.2 explains the wait and the « déjà en cours » owner.
  - `[medium]` `[patch]` Partial rollback then resume leaves ROLLED_BACK owners out of the go — `resume` refused once any owner is ROLLED_BACK (« Un retour arrière a commencé… »); unit test.
  - `[medium]` `[patch]` `forgetImportedNow` false counted as success — returns the owner failure, records kept; unit test `aCompanyNoLongerPendingDeletionKeepsItsRecords`.
  - `[low]` `[patch]` « go » not atomic (crash between statements) — the decision status is now written first (transition); a retried go is accepted on ACTIVATED; remaining low risk: owner lines of a crash window stay RECONCILED (report only).
  - `[low]` `[patch]` Owners the go could not activate not reported — audit log carries `notActivated`; their lines keep their status in the report.
  - `[low]` `[patch]` Accepted owners recorded when not blocking / duplicated on a second go — only blocking owners not already signed are appended.
  - `[low]` `[reject]` Resending the same acceptance list after the go fails `NOT_IN_CUTOVER` — the second go is for owners cleared since; resending is not a flow the runbook describes.
  - `[low]` `[patch]` Bref pool vs parallelism — runbook §3.1 (pool never below parallelism, Keevo pool headroom); default pool 8 = max parallelism.
  - `[low]` `[reject]` Missing helper indexes only reported after the run — the runbook makes the script a prerequisite; the report shows n / expected.
  - `[low]` `[patch]` Runbook 0/10 vs 11 — explained (24.6 added the `ai_conversation` index; older schemas count fewer).
  - `[medium]` `[patch]` (gap) `state()` did not cover the tables the rollback cleans — `state()` now counts 13 public tables; fixture has a Google identity, a Bref subscription (tenant_payments), a web push on the linked account and an invited member.
  - `[low]` `[patch]` Missing unit scenarios — added resume success, listing past a full page, forget false, FAILED-line gate, go/rollback race.
  - `[low]` `[patch]` Chunk boundaries untested; synthetic phone `lpad` overflow — full-context owner with 2 100 sales (invoices, lines, payments > 2 000; records > 5 000), reconciled clean; synthetic phones widened to 7 digits (9-digit local numbers).
  - *Edge case hunter*
  - `[medium]` `[patch]` activate CAS — same as above.
  - `[medium]` `[patch]` finish/activated without expected status — same as above.
  - `[medium]` `[patch]` `shutdownNow` without waiting: FAILED while workers still write — the coordinator waits for the pool to terminate (heartbeating) before closing.
  - `[low]` `[patch]` heartbeat independent of owners — same as above.
  - `[medium]` `[patch]` forget false — same as above.
  - `[medium]` `[patch]` resume after partial rollback — same as above.
  - `[medium]` `[patch]` FAILED line with an older reconciled batch treated as clear — every line not RECONCILED now blocks the go (reason = its own) unless accepted; unit test.
  - `[low]` `[patch]` invitations of non-activated companies re-dated — targets read for the owners activated in the same unit of work.
  - `[low]` `[patch]` second go overwrote actor/time — `coalesce`.
  - `[false]` `[reject]` activation by SQL without access-state invalidation — `public.tenants` has the `keevo_access_state` NOTIFY trigger (V36/V78): every instance evicts on the update.
  - `[low]` `[patch]` pool-size below parallelism — runbook (same row as above).
  - `[low]` `[reject]` `after` id unknown gives an empty page — display-only list; the dashboard only sends ids it received.
  - `[low]` `[patch]` BLOCKED with no case → empty reason — falls back to the French « Erreur inattendue… ».
  - `[low]` `[patch]` « 0 écart(s) » reason when no GAP line — falls back to « Import terminé mais réconciliation non enregistrée ».
  - `[low]` `[patch]` `Error` in a worker leaves PENDING and READY — worker catches `Error`; a run that leaves any PENDING line ends FAILED (« relancez-la »).
  - `[low]` `[patch]` synthetic `lpad` truncation — same as above.
  - `[low]` `[patch]` header « :owners owners » vs owners+1 — header corrected.
  - `[false]` `[reject]` claim: an accepted FAILED owner « avec import complet » stays suspended — the runbook says « activé si son import est complet » and FAILED means not reconciled/complete: documented D2 (FAILED stays out).
  - `[low]` `[patch]` claim: READY while owners PENDING — same as the `Error` row.
  - `[false]` `[reject]` claim: spec names a `CutoverRunner` — fixing it edits this build's spec (rule).
  - `[medium]` `[patch]` claim: rollback after go under concurrency — closed by the activation CAS and the fenced `finish`.
  - *Verification gap*
  - `[medium]` `[patch]` chunked writes untested past one block — 2 100-sale owner in `BrefCutoverFullContextTest` (crosses 2 000 and the 5 000 records block), RECONCILED; merge ordering kept by the planner (representatives first) — unchanged code path.
  - `[medium]` `[patch]` relaunch path untested — unit `resumeRelaunchesTheRunAndIsRefusedOnceARollbackStarted`; the live check exercised two full runs; a full-context `/runs` step was not added (low value over the unit + live).
  - `[medium]` `[patch]` invitation re-dating only on mocks — full-context invited member: after the go `expires_at > now + 6 d`, `invited_at` now, `last_sent_at` a day back.
  - `[medium]` `[patch]` rollback cleanup of identities/web push/billing not checked — `state()` extended, fixture enriched (same as blind row).
  - `[low]` `[patch]` listing past one page — unit `theListingFollowsTheKeysetPastAFullPage`; the `after=` read endpoints → routed 24-12 (display, checked in the dress rehearsal).
  - `[medium]` `[patch]` (other) activate race — same as above.
  - `[low]` `[patch]` (other) second go undocumented — `BrefCutoverUseCase#activate` javadoc.
  - `[low]` `[patch]` (other) GAP javadoc vs FAILED — javadocs aligned.
  - *Design / complexity (FUS-NFR11)*
  - `[low]` `[patch]` parallelism vs main pool (10) — runbook: pool ≥ parallelism + 2, parallelism 4 advised; measured live at 4.
  - `[low]` `[patch]` « cursor reads » overstated — comment reworded (rows still collected per owner).
  - `[low]` `[reject]` activation loads every owner line — O(owners) UUIDs, constant statements; bounded by the cutover (a few thousand).
  - `[low]` `[patch]` repeated go duplicates accepted owners — same as above.
  - `[low]` `[reject]` rollback pager uses the dashboard query (join) — one indexed page per 200 owners next to a schema drop per owner.
  - `[low]` `[reject]` report scans the owner lines six times — constant queries, index-only; revisit if 24.12 shows it.
  - `[low]` `[reject]` each status poll counts statuses — index-only scan on (cutover_id, status, position).
  - `[false]` `[reject]` nit: `CHUNK` declared between methods — style only.
  - *Intent alignment (descriptive)*
  - `[false]` `[reject]` exclusion list read as waiver (B1) vs left-out (B2) — D2: waiver for complete imports, left out otherwise; documented.
  - `[false]` `[reject]` one-pass reads (A2) — the routed items offered « ou index temporaires sur la copie » (D4), measured (§3.4).
  - `[false]` `[reject]` snapshot reuse — D3: independent re-read kept, its cost measured (≈ 1 % of the run).
  - `[false]` `[reject]` tests vs surfaces — enriched fixture now covers identities, web push, billing, invitations, chunks.
  - `[false]` `[reject]` new cutover after a rollback / second go — consistent with « one open cutover », documented in the runbook.
  - `[false]` `[reject]` changes outside the module — the routed items (« écritures par blocs », `TenantDeletionService`) live there.
  - *Unlisted maybe-false*
  - `[maybe-false]` `[defer→routed 24-12]` stale-takeover with a > 30 min owner (two coordinators) — fenced finish + heartbeat while waiting make it unlikely; settled by the rehearsal's largest owner (max measured 25 s).
  - `[maybe-false]` `[defer→routed 24-12]` concurrent tenant creation deadlocks in module baselines (seen live at parallelism 4, healed by 24.6's module step) — settled by measuring 4 vs 8 on the prod copy.

## Design Notes

**Patterns:** Application service (`BrefCutoverService`, coordinator and pool inside) + asynchronous coordinator (Producer/Consumer: one listing thread feeds a bounded pool through a semaphore — memory O(page + parallelism)); Strategy reuse (source port); Gate (24.8 `blockingOwners`) as a precondition of a Command (activation); set-based Unit of Work for activation; Saga-like per-owner compensation for the rollback (each owner independent, relaunchable).

**Decisions (default, conservative, 11/11/2026):**
- D1 Deleted Bref owners are not listed (never migrated, RGPD); inactive or shop-less owners are `NOT_MIGRATED` (reported, not blocking).
- D2 `acceptedOwners` = the explicit signed exclusion list: owners whose gap or block was documented by the rehearsal (24.12); an accepted owner with a COMPLETED import is activated as is, one without stays out (company absent or still suspended). Stored with the actor and time in the cutover.
- D3 Reconciliation re-reads the source (D7 of 24.8 kept): an independent read of a static dump; its cost is measured per owner in the report (routed 24.8 item settled by measure).
- D4 Helper indexes on the restored copy come from a runbook script run as the copy's owner (the tool's role stays read-only); the report flags their absence.
- D5 Invitation re-dating sets `last_sent_at = now() − 24 h`: no message leaves; the owner can re-send at once (19.6b rule).
- D6 Rollback removes the tenant row (never-activated company), its `tenant_payments` and `tenant_plan_events` too: a later re-import with the same Bref ids must not collide; the cutover rows stay as history.
- D7 A coordinator interrupted by a restart leaves `RUNNING` with a stale heartbeat (> 30 min): a new run takes it over; `finish` only closes a RUNNING / ROLLING_BACK cutover (a late coordinator never overwrites a decision).
- D8 (review) The « go » is gated by the 24.8 `blockingOwners` **and** by every cutover line not `RECONCILED` (its own French reason): an owner FAILED in this run never slips through on an older reconciled batch. The decision is a conditional transition to ACTIVATED written before any company is activated.
- D9 (review) Once a rollback has removed an owner, the cutover can no longer be re-run: finish the rollback, then open a new cutover.
- D10 Routed 24.6 items: the assistant conversations are read through the helper index `keevo_bref_ai_conversation_user` of the copy (11th index); the rollback deletes the traced `web_push_subscriptions` (`NotificationImportPort.deleteImportedWebPushSubscriptions`), which a linked account would otherwise keep.

**Cost:** listing 1 indexed query per 200 owners; per owner the import's own reads/writes (+ reconciliation) — with the helper indexes every source read is an index lookup, so the whole cutover is O(total rows); 3 small updates per owner in `migration_cutover_owners`; report = constant aggregate queries; activation = 3 statements whatever N; rollback = per owner the deletion routine (one schema drop each).

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t249 migration identity/legacyimport identity/auth architecture invoicing expenses catalog crm shared/infrastructure/web` -- expected: all green.

**Manual checks:**
- Live (`bref_src_t249` = 03/11 dump + synthetic set, `keevo_dev_t249`, port 4649, WhatsApp noop, FCM off): full cutover → report → go → companies ACTIVE; second try: cutover → rollback → Keevo counts equal to the initial snapshot; throughput measured and extrapolated for 24.12; databases dropped.

## Auto Run Result

Status: done.

**Summary.** The one-shot cutover command of ADR-10, in the `migration` module, without touching the import steps:
- `BrefCutoverService` (+ `AdminBrefCutoverController`): an asynchronous coordinator lists the owners of the restored Bref copy (keyset on `"user".id`, deleted accounts excluded), runs `BrefOwnerImportService` then the 24.8 reconciliation for each owner on a bounded pool (1–8, semaphore back-pressure, heartbeat), records each owner's outcome, figures and durations in `migration_cutover_owners`, and archives a report in `migration_cutovers.report` (V91). One open cutover at a time; relaunch (`/runs`) re-processes owners not reconciled and owners added since.
- « go » (`/activation`, confirmation `ACTIVER`): gate = 24.8 `blockingOwners` + every line not `RECONCILED`; 409 with `details.blockingOwners` unless each is accepted with a reason (stored, signed by the actor); a conditional transition to ACTIVATED, then one statement SUSPENDED (import reason) → ACTIVE and one re-dating the imported invitations; no event, no message.
- Rollback (`/rollback`, `RETOUR ARRIERE`, before the go only): per owner, `prepareImportedTenantRemoval` (marks only a company still suspended by the import, detaches linked accounts, deletes traced Google/Apple identities), traced Web Push subscriptions deleted, `TenantDeletionPort.forgetImportedNow` (deletion routine without WhatsApp nor RGPD trace; tenant row and billing rows removed), orphan created accounts deleted, records and batches purged.
- Routed items: chunked writes (2 000 rows, 5 000 records) in the catalog, client, expense, invoice, old-link and record writers; fetch size on the Bref reads; Bref pool sized for the parallelism; 11 helper indexes on the restored copy (`keevo/ops/migration/bref-copy-indexes.sql`, probed in the report); batch list `GET /api/v1/admin/migration/batches`.
- Measurement: `keevo/ops/migration/bref-synthetic-load.sql` and runbook §3.4 (extrapolation for 24.12).

**Files:** V91; domain `Cutover`, `CutoverOwner`, `CutoverOwnerStatus`, `CutoverReport`, `CutoverStatus`, `OwnerFigures`, `CutoverFigures`; ports `BrefCutoverUseCase`, `BrefOwnerDirectory`, `CutoverStore`, `MigrationRecordStore#listBatches`; adapters `JdbcBrefOwnerDirectory`, `JdbcCutoverStore`, `AdminBrefCutoverController`, `AdminBrefMigrationController#batches`, `JdbcBrefSource`/`JdbcBrefMonetisationSource` (fetch size, pool), `JdbcMigrationRecordStore` (chunks, list); `BrefMappings.SUSPENSION_REASON`; identity `LegacyIdentityImportPort` (+4 methods), `LegacyIdentityImportService`, `LegacyIdentityStore`, `JdbcLegacyIdentityStore`, `TenantDeletionPort#forgetImportedNow`, `TenantDeletionService`, `TenantDeletionAdapter`; messaging `NotificationImportPort#deleteImportedWebPushSubscriptions`; writers in catalog, crm, expenses, invoicing (chunks); `ErrorCode.MIGRATION_CUTOVER_NOT_FOUND` (+ message, 404); `docs/entitlements/endpoints.md`; `docs/RUNBOOK_BASCULE_BREF.md` §3; ops scripts; tests `CutoverFiguresTest`, `BrefCutoverServiceTest`, `BrefCutoverFullContextTest`.

**Review.** 58 findings: 9 medium and 22 low patched (medium root causes: go/rollback race and unfenced finish, forget false, resume after partial rollback, FAILED line slipping through the gate, coordinator closing while workers write, four verification gaps), 3 routed to 24-12, the rest rejected with reasons (triage log). Patched by verdict: 0 high, 9 medium, 22 low.

**Verification:** packages listed in the final report of the run (migration, architecture, identity/auth, messaging/legacyimport, expenses, crm/contact, catalog/product, invoicing/invoice/adapter/out, shared) — all green.

**Live check** (`bref_src_t249` = 03/11 dump + `bref-synthetic-load.sql` 200 + 1 large owner, SELECT-only role; `keevo_dev_t249`, port 4649, WhatsApp noop, FCM off; databases and role dropped afterwards):
- run 1 (before the rebase on 24.6, no helper index): 202 owners RECONCILED in 392.6 s; rollback 20.6 s → Keevo state identical to the initial snapshot (18 public counters + tenant schemas), the linked Keevo account kept with its own company; go after rollback 409.
- run 2 (after the rebase, 24.6 steps, 11/11 helper indexes): 202/202 RECONCILED in 343.3 s (avg 6.8 s, p95 8.6 s, largest owner of 20 000 documents 24.7 s, reconciliation ≈ 90 ms/owner); sums Bref = Keevo (sales 1 061 585 617 F, collected 860 874 207 F, outstanding 201 687 560 F, expenses 3 015 000 F); a synthetic browser subscription imported on the linked account then removed by the rollback (34 s) → state identical.
- run 3: 202/202 RECONCILED in 341.7 s; go 200 in 5 ms; no company left suspended by the import; rollback and relaunch after the go 409; the linked owner sees 2 companies at login; no message sent.
- run 2 logs: 30 module-provisioning failures (PostgreSQL `deadlock detected` between concurrent tenant baselines), healed by the 24.6 module step; routed to 24-12 (parallelism 4 vs 8).

**Follow-up review recommended:** `true` — nine medium entries were patched, several on concurrency (activation transition, fenced finish, pool drain) covered by unit tests and one full-context pass only; the unverified risk is a « go » and a rollback sent at the same moment under load.

**Residual risks:** module-baseline deadlocks under parallel tenant creation (24-12); one owner's rows held in memory per worker; the reconciliation re-reads the source (D3).

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10 (non répétés ici).

### Statut

Sans objet pour l'app : la commande de bascule est un outil super-admin. Pas de clé `m24-9`.

### Ce qui est fait côté API

Au « go », les entreprises importées passent ACTIVE d'un coup (la bannière de suspension de 24.2 disparaît, les écritures sont permises) ; les invitations des membres déjà inscrits sont redatées (7 jours à partir du « go ») et visibles à la connexion. Aucun message n'est envoyé par l'outil.

### À utiliser

Rien de nouveau : connexion, sélecteur d'entreprise, invitations 19.6b existants.

### À implémenter côté mobile

Rien.

### Critères d'acceptation mobile / scénarios de test

- **Given** un owner Bref importé puis activé par le « go », **When** il se connecte, **Then** son entreprise n'affiche plus la bannière de suspension.

### Clé de suivi mobile

Aucune.

## Dashboard super-admin (autre dépôt)

> Conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`.

### Statut

API livrée (story 24.9), contrôleurs `keevo/backend/src/main/java/com/keevo/migration/adapter/in/rest/AdminBrefCutoverController.java` et `AdminBrefMigrationController#batches`. Clé de suivi : `d24-9-pilotage-vagues-rollback`.

### Ce qui est fait côté API

Lancer une bascule sur la base Bref restaurée, la suivre, lire son rapport archivé, la relancer, donner le « go » ou faire le retour arrière ; liste des lots d'import.

### À utiliser

- `POST /api/v1/admin/migration/cutovers` `{"source":"DATABASE","parallelism":4}` → 202 `data` = bascule. 422 `VALIDATION_FAILED` « La bascule lit une base Bref restaurée… » ou « Le nombre de comptes importés en même temps va de 1 à 8 » ; 409 `INVALID_STATE` « Une bascule est déjà ouverte… » ; 503 `MIGRATION_SOURCE_UNAVAILABLE`.
- `GET /api/v1/admin/migration/cutovers?after=<id>&limit=20` → liste, plus récente d'abord.
- `GET /api/v1/admin/migration/cutovers/{id}` → `status` + `statusLabel` (`RUNNING`, `READY`, `FAILED`, `ACTIVATED`, `ROLLING_BACK`, `ROLLED_BACK`), `errorLabel`, `progress[]` (`status`, `label`, `count`), `acceptedOwners[]` (`ownerId` = poignée, `reason`, `acceptedAt`), `report` : `ownersTotal`, `owners[]`, `reasons[]`, `tables[]` et `sums[]` (`label`, `expected` = Bref, `actual` = Keevo, `difference`), `anomalies[]`, `durations` (ms), `sourceIndexes` (`present`/`expected`), `acceptedOwners`. 404 `MIGRATION_CUTOVER_NOT_FOUND` « Bascule introuvable ».
- `GET …/cutovers/{id}/owners?status=GAP&after=<position>&limit=100` → `label` (« Compte n »), `companyName`, `status`/`statusLabel`, `reason`, `importMs`, `reconcileMs`, dates ; `brefOwnerId`, `batchId` = poignées (le lot ouvre le rapport de réconciliation 24.8).
- `POST …/cutovers/{id}/runs` → 202 ; 409 « La bascule est en cours… », « Le « go » est donné : la bascule ne peut plus être relancée… », « Un retour arrière a commencé… ».
- `POST …/cutovers/{id}/activation` `{"confirmation":"ACTIVER","acceptedOwners":[{"ownerId":"…","reason":"…"}]}` → 200 ; 422 « Tapez « ACTIVER »… », « Chaque compte accepté demande un motif d'au moins 10 caractères… », « Un compte accepté ne fait pas partie de cette bascule » ; 409 « Activation refusée : n compte(s) bloquent la bascule… » avec `details.blockingOwners[]` (`ownerId`, `label`, `reason`).
- `POST …/cutovers/{id}/rollback` `{"confirmation":"RETOUR ARRIERE"}` → 202 ; 409 « Après le « go », plus de retour arrière automatique… ».
- `GET /api/v1/admin/migration/batches?after=<batchId>&limit=50` → lots (même forme que `GET /batches/{id}`).

### À implémenter côté dashboard

1. Écran « Bascule » : bouton « Lancer la bascule » (parallélisme 4 par défaut), suivi toutes les 3 s tant que `RUNNING`/`ROLLING_BACK` (barre par statut, durée écoulée).
2. Rapport : tuiles par statut, motifs, tableaux « Compteurs » et « Montants » (Bref / Keevo / écart, écarts non nuls en rouge), anomalies, durées en minutes et secondes, alerte si `sourceIndexes.present < expected` (« Index de la copie manquants : la bascule sera plus lente »).
3. Liste des comptes filtrable par statut (pagination `after` = dernière `position`), lien vers le rapport de réconciliation du lot.
4. « Go » : dialogue où l'on tape `ACTIVER` ; sur 409, afficher les comptes bloquants avec leur motif et, pour chacun, « accepter » + motif obligatoire, puis renvoyer.
5. « Retour arrière » : dialogue où l'on tape `RETOUR ARRIERE`, visible seulement avant le « go ».
6. Libellés français uniquement, jamais d'identifiant technique (« Compte n » et nom d'entreprise).

### Critères d'acceptation dashboard

- **Given** une base Bref restaurée, **When** le super-admin lance la bascule, **Then** il voit la progression puis « Prête pour la décision « go » » et le rapport (montants Bref = Keevo, durées).
- **Given** un compte en écart, **When** il tape ACTIVER, **Then** il voit le compte bloquant et son motif ; **When** il l'accepte avec un motif, **Then** la bascule passe « Activée (« go » donné) ».
- **Given** une bascule prête, **When** il confirme le retour arrière, **Then** il voit « Retour arrière effectué » et les comptes « Supprimé par le retour arrière ».

### Clé de suivi dashboard

`d24-9-pilotage-vagues-rollback`

---
title: '24.8 — Automatic, blocking reconciliation of an imported Bref owner'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: 'f5e864a8a9fea4540a96d5a5c5402d0c0eae7b42'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-24-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-4-import-documents-paiements-depenses.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-5-import-monetisation.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** 24.2–24.7 import a Bref owner (identity, shops, team, catalogue, clients, documents, payments, expenses, monetisation) but nothing proves the result matches Bref to the franc. FUS-MIG3 / ADR-10: an owner whose data differs beyond tolerance must not be activated by the cutover (24.9).

**Approach:** A reconciliation component of the `migration` module that runs automatically after every COMPLETED import (decorator over the import use case, the import pipeline untouched), compares set-based aggregates of the Bref snapshot (the same read-only source as the import) with aggregate SQL over the imported Keevo tenant, stores a per-owner JSON report (aggregates only, French labels) in a new `public.migration_reconciliations` table (V88) with status `RECONCILED` or `RECONCILIATION_FAILED`, and exposes it to the super-admin plus a set-based gate (`blockingOwners`) that 24.9's global activation will call.

## Boundaries & Constraints

**Always:**
- Checks of analysis §10.3, each line with expected (Bref), actual (Keevo), difference, tolerance, status `OK` / `EXPLAINED` / `GAP` and a French explanation:
  1. Counts (tolerance 0): shops; owner (1 OWNER membership); team (Bref members − deleted − deactivated left out vs Keevo non-owner memberships + pending invitations); products (Bref rows − rows merged by key, from `migration_records` distinct targets); clients; documents per type and status (live) + deleted documents; lines; payment rows (Bref instalments + sale acomptes − rows of 0 F, acompte on a quote explained); expenses.
  2. Σ non-cancelled live sales per shop and civil month (`date`, invalid → creation day Africa/Douala, the import's rule): Bref Σ `total` (decimal) vs Keevo Σ `total_ttc`; tolerance ±1 F × documents of the bucket (the larger side); 0 < |diff| ≤ tolerance → `EXPLAINED` « arrondis au franc ».
  3. Σ collected: Bref Σ sale `initialPayment` + Σ paid instalments (all documents) vs Keevo Σ `invoice_payments.amount` with `paid_at`; tolerance ±1 F per Bref amount.
  4. Σ live expenses per month (`expenseDate`) vs Keevo Σ `amount` of non-archived expenses: tolerance 0.
  5. Outstanding (Bref owner dashboard formula): live sales `pending|partial|overdue`, Σ max(0, total − initialPayment − paid instalments) vs Keevo live INVOICE `ISSUED` Σ max(0, `total_ttc` − paid rows); tolerance ±1 F per amount involved.
  6. Sample of 20 documents (the 5 largest sales + 15 drawn with a seed from the batch id), field by field, tolerance 0, expected Keevo value derived from Bref by the documented 24.4 mapping (HALF_UP francs, BRF numbering with suffixes, status, issue date, shop, client name default/cut, line count and Σ line HT, payment row count and Σ paid, public link, deleted).
  7. Subscription: plan and expiry = `BrefMonetisationPlanner.decidePlan` at the instant the import recorded the plan, credit = max(0, Bref credit) + scheduled periods converted to credit, vs Keevo current plan (`PlanAdministrationPort.currentPlan`) and `tenant_credits.balance_xaf`; tolerance 0.
  8. Public links: up to 5 live documents with a Bref token: `public.invoice_legacy_links` points to this tenant and to a live invoice.
- Notes the import kept (`migration_records.note`: totals ≠ recalcul, cents, …) are counted per kind of row in an `EXPLAINED` « anomalies notées » section; they never fail the owner because every comparison uses the Bref value the import stored as is.
- Any `GAP` → `RECONCILIATION_FAILED`; a reconciliation that cannot run (source or Keevo unreadable) is stored `RECONCILIATION_FAILED` with a French error label, never thrown over the import's own outcome.
- Report: no personal data and no technical id in labels (shops as « Boutique 1, 2… » by Bref creation order, months « mars 2026 », documents by their printed `BRF-…` number or « Devis n », amounts and counts only); ids (batch, owner, tenant) are handles only.
- Set-based: source = the snapshot read by `BrefSourcePort` + `BrefMonetisationSourcePort` (the import's own read: a few queries per owner), aggregated in one linear pass; target = a constant number of aggregate queries in one read transaction (≈ 10, whatever the owner's size); 1 insert for the report.
- Super-admin only (`/api/v1/admin/**` + `CurrentActor.requireSuperAdmin`), `@EntitlementExempt("super-admin")`.

**Never:**
- Activating, suspending or rolling back a tenant (24.9); HTTP fetch of old Bref links 302 → 200 (24.10 owns the redirect); correcting a Bref or Keevo value; changes to the import steps, `ImportRun`, `BrefOwnerImportService`, `MigrationBatch` or the batch endpoints' payloads.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clean import | COMPLETED import of the synthetic owner (cents, total anomaly, merges, deleted docs, overpaid, zero payment) | Report `RECONCILED`; rounding buckets `EXPLAINED`; merges explained; notes counted | — |
| Re-run import | Same owner again | New reconciliation, same figures, `RECONCILED` | — |
| Tampered target | An invoice `total_ttc` +10, an expense deleted, a payment removed | Re-reconcile → `RECONCILIATION_FAILED`, GAP lines on the bucket, expenses count/month, collected, sample | — |
| Blocked / failed import | Batch BLOCKED or FAILED | No automatic reconciliation; POST → 409 `INVALID_STATE` « Seul un import terminé peut être réconcilié » | — |
| Source unreachable | Bref DB down at reconciliation | Stored `RECONCILIATION_FAILED` with label | logged by class only |
| Unknown batch / none yet | GET on a batch without reconciliation | — | 404 `MIGRATION_BATCH_NOT_FOUND` / `MIGRATION_RECONCILIATION_NOT_FOUND` |
| Gate | Owners with latest COMPLETED batch reconciled OK, failed, not reconciled, never imported | `blockingOwners` returns failed + not reconciled + not imported, one query | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/migration/application/BrefOwnerImportService.java` -- the import (read-only for this story); decorated, never edited.
- `.../migration/domain/port/in/BrefOwnerImportUseCase.java` -- interface the `@Primary` decorator implements (controller injects it).
- `.../migration/domain/port/out/BrefSourcePort.java`, `BrefMonetisationSourcePort.java` -- source strategies reused (by `MigrationBatch.source`).
- `.../migration/domain/port/out/MigrationRecordStore.java#findBatch` -- batch status, tenant, source.
- `.../migration/domain/service/BrefMonetisationPlanner.java#decidePlan` -- plan rule (ADR-07) reused for the expected plan.
- `.../migration/domain/service/BrefDocumentPlanner.java` -- mapping rules mirrored independently (`legacyReference`, `civilDate`, `franc`, `acompteId`, defaults), not its `plan`.
- `.../migration/domain/service/BrefMappings.java` -- `trimmed`, `cut`, `uuidOrNull`.
- `.../migration/domain/model/MigrationRecord.java` -- table constants; `MonetisationStep.TENANT_PLAN = "tenant_plan"` (package-private: literal reused).
- `.../migration/application/MigrationUnitOfWork.java#platform` -- one read transaction for the target reads + `PlanAdministrationPort.currentPlan` (MANDATORY tx).
- `.../migration/adapter/in/rest/AdminBrefMigrationController.java` -- route prefix and guard pattern to mirror in a separate controller.
- `.../shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/ErrorMessages.java`, `GlobalExceptionHandler.java` (404 list) -- add `MIGRATION_RECONCILIATION_NOT_FOUND`.
- Keevo target shapes: tenant `invoices` (kind, status, issue_date, store_id, total_ttc, subtotal, discount_amount, delivery_fee, total_vat, client_name, number, legacy_public_token, deleted_at), `invoice_items` (invoice_id, total_ht), `invoice_payments` (invoice_id, amount, paid_at), `expenses` (amount, expense_date, archived_at), `products`, `clients`, `stores`; public `user_tenant_memberships` (tenant_id, role), `membership_invitations` (tenant_id, status), `tenant_credits` (balance_xaf), `invoice_legacy_links` (token, tenant_schema, invoice_id). Module tables may be absent (`to_regclass`).
- Bref outstanding formula: `api-bref/src/dashboard/owner-dashboard.service.ts` (open sales query).
- Tests to mirror: `keevo/backend/src/test/java/com/keevo/migration/BrefSalesImportFullContextTest.java` (synthetic Bref DB via `migration/bref-source-schema.sql`, MockMvc super-admin).

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/resources/db/migration/V88__migration_reconciliations.sql` -- table (FK batch ON DELETE CASCADE, status check, report JSONB), indexes `(bref_owner_id, finished_at DESC)` and `(batch_id, finished_at DESC)`.
- `.../migration/domain/model/Reconciliation*.java` -- `ReconciliationStatus`, `ReconciliationReport`, `ReconciliationCheck` (+ nested `Kind`), `ReconciliationLine` (+ nested `Status`), `ReconciliationFigures` (French labels).
- `.../migration/domain/service/ReconciliationFigures.java` + `BrefReconciliationExpectations.java` (pure: snapshot + monetisation + records summary → expected figures and sample expectations) + `BrefReconciler.java` (pure: expected vs actual → checks and status).
- `.../migration/domain/port/out/ReconciliationTargetReader.java`, `ReconciliationStore.java`; `.../migration/domain/port/in/BrefReconciliationUseCase.java`.
- `.../migration/adapter/out/persistence/JdbcReconciliationTargetReader.java`, `JdbcReconciliationStore.java`.
- `.../migration/application/BrefReconciliationService.java`, `ReconcilingBrefOwnerImportUseCase.java` (`@Primary` decorator).
- `.../migration/adapter/in/rest/AdminBrefReconciliationController.java` -- GET/POST `/api/v1/admin/migration/batches/{batchId}/reconciliation`, GET `/api/v1/admin/migration/bref/owners/{ownerId}/reconciliation`.
- Error code + message + 404 mapping; `docs/entitlements/endpoints.md` regenerated.
- Tests: `BrefReconcilerTest`, `BrefReconciliationExpectationsTest` (every matrix row at unit level), `ReconcilingBrefOwnerImportUseCaseTest`, `BrefReconciliationFullContextTest` (synthetic Bref DB: import → RECONCILED; tamper → FAILED; gate; endpoints; 409/404).

**Acceptance Criteria:**
- Given a COMPLETED import, when the import endpoint returns, then a reconciliation of that batch exists and `GET …/batches/{id}/reconciliation` returns it with every check of §10.3.
- Given a Keevo row changed after the import, when the super-admin re-runs the reconciliation, then the status is `RECONCILIATION_FAILED`, the changed aggregate lines are `GAP`, and `blockingOwners` lists the owner.
- Given the report JSON, then it holds no UUID nor personal data in any label (shops by ordinal, documents by printed number).

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 46 findings — high 0, medium 7, low 19, false 16, maybe-false 4 (plus 5 descriptive restatements of the intent auditor, grouped on the last row as false)
- findings:
  - *Design/complexity (FUS-NFR11)*
  - `[low]` `[defer]` Source read twice per import (import + reconciliation) — by design (D1/D7); routed to 24-9 (pass the snapshot or reconcile after all imports), measured by 24-12.
  - `[low]` `[patch]` Document numbering computed twice (sample and links) — computed once in `expected()` and passed down.
  - `[false]` Full shuffle of the sample candidates — O(n log n) on one owner's documents, the id sort is required for a deterministic draw; no measurable cost.
  - `[false]` Three scans of the tenant invoices — one owner's rows, once per run; merging into GROUPING SETS buys nothing measurable.
  - *Intent alignment*
  - `[false]` R1 vs R2 (owner state vs report + gate) — the intent assigns activation to 24-9; the state is the latest run of the latest batch, the gate is ready for 24-9 (routed item).
  - `[false]` R3(a) source-side SQL aggregates — D1: the import's own reader (both sources), aggregated in one pass; the independent side is the Keevo SQL + rules recomputed from raw Bref fields.
  - `[false]` Live check not in the diff — done (Auto Run Result), databases dropped.
  - `[false]` Synthetic dataset inline — versioned in `BrefReconciliationFullContextTest` over the versioned Bref schema files.
  - `[false]` Spec in-progress, no Mobile/Dashboard sections, no d24-8 — finalisation steps, done below.
  - `[false]` Tolerance not uniformly « 1 XAF per document » — D2: per document for sales, per rounded amount for collected/outstanding (a document can carry several rounded amounts), 0 elsewhere (§10.3).
  - *Verification gap*
  - `[medium]` `[patch]` Gate reason « Import non réconcilié » (COMPLETED batch without run) never tested — full-context test deletes the runs and asserts the reason.
  - `[medium]` `[patch]` Batch report after a second run never read — full-context test reads the batch report after the POST and asserts the failed run.
  - *Blind hunter*
  - `[false]` Bref live after import → false gaps — ADR-10: the source is the dump restored after Bref is stopped (D7); runbook item routed to 24-13.
  - `[medium]` `[patch]` Reconciliation while a newer import runs / on a superseded batch — only the owner's latest batch can be reconciled (409 « Un import plus récent… »), tested.
  - `[medium]` `[patch]` (same root) POST on an older COMPLETED batch rewrites latestForOwner — same guard.
  - `[medium]` `[patch]` Owner endpoint can disagree with the gate — it now returns the latest run of the owner's latest batch (same batch the gate judges).
  - `[low]` `[patch]` V88 comment and D5 said « latest COMPLETED batch » — comment and D5 aligned with the code (any status, conservative).
  - `[low]` `[patch]` D3 claimed seconds of drift accepted — D3 reworded: exact comparison, instant = record creation (the decision instant is not persisted; the pipeline stays untouched).
  - `[false]` OUTSTANDING status sets differ — Bref `invoice_status_enum` has only pending/partial/overdue/paid/cancelled: every non-paid non-cancelled sale is in the Bref set.
  - `[false]` Sample reader truncates amounts with getLong — the columns are BIGINT, never fractional.
  - `[low]` `[reject]` Only the exception class is logged — project rule (24.2): messages may quote source values; the batch id correlates the run.
  - `[maybe-false]` `[reject]` Null createdAt/subtotal NPE — `BrefInvoice` requires createdAt and zero-fills amounts; would be low.
  - `[low]` `[patch]` Missing export file gets the generic label — OWNER_NOT_FOUND / EXPORT_INVALID → « La source Bref ne contient plus ce compte ou est illisible… », tested.
  - `[low]` `[defer]` No correction path for a gap — D8: rollback + re-import; procedure routed to 24-13.
  - `[low]` `[patch]` SAMPLE gap not asserted end to end — full-context test asserts a « SAMPLE / Facture BRF-… » gap.
  - `[low]` `[reject]` No 403 test on the new endpoints — `/api/v1/admin/**` rule and `requireSuperAdmin` are the 24.2 pattern, covered by the admin security tests; live: 401 without token.
  - `[low]` `[reject]` Tampered public link not tested end to end — `BrefReconcilerTest.sampleAndLinks` covers absent/other/deleted states.
  - `[low]` `[reject]` JSON read-back of a failed report not tested — read path shared with the tested reports; empty checks handled.
  - `[false]` Spec incomplete — finalisation (this section, Mobile/Dashboard, d24-8).
  - `[low]` `[patch]` Code Map names `LineStatus`/`CheckKind` — corrected to the nested types.
  - `[low]` `[patch]` 404 message wrong for the owner endpoint — « Aucune réconciliation pour cet import ou ce compte Bref ».
  - `[low]` `[patch]` `tenant_id` nullable in V88 — NOT NULL (reconcile refuses a batch without tenant).
  - *Edge case hunter*
  - `[false]` Bref edited after import → false gaps — same as above (D7, ADR-10).
  - `[low]` `[patch]` OWNER_NOT_FOUND / EXPORT_INVALID mapped to NOT_RUN — same patch as above.
  - `[maybe-false]` `[reject]` Plan boundary between decision instant and record creation — milliseconds window; fixing needs a pipeline change (forbidden); would be low.
  - `[maybe-false]` `[reject]` Re-run after the plan expired / credit used — the reconciliation runs before activation (tenant suspended, nothing consumed, `expires_at` unchanged); would be low.
  - `[low]` `[patch]` Invitation declined after import lowers the team count — counts every imported invitation not accepted (`status <> 'ACCEPTED'`).
  - `[maybe-false]` `[reject]` Deactivated member missed by the import explained away — a deactivated unrecorded member is the 24.7 SKIP case; telling it from a missed create needs the identity bridge again; would be low.
  - `[low]` `[reject]` Newest BLOCKED batch after a reconciled one keeps the owner blocked — intended (D5, conservative FUS-MIG3): a blocked re-run means the source or Keevo changed.
  - `[medium]` `[patch]` POST on an older batch / while running — same guard as above.
  - `[medium]` `[patch]` latestForOwner on a superseded batch — same patch as above.
  - `[low]` `[defer]` Synchronous second read inside the import call — live 4.4 s for the real owner (< 60 s target); routed with the 24-9 item.
  - `[false]` Sample skipped when invoice_items/invoice_payments absent — the invoicing module scripts create invoices (V1), invoice_items (V1) and invoice_payments (V10) together.
  - `[false]` `valueOf` outside try — the column has a CHECK on the two values.
  - `[low]` `[patch]` Records read outside the tenant read transaction — records, expectations and tenant reads now run in one `uow.platform` transaction.
  - `[false]` Claim « same read-only source as the import » — it is the same source (re-read); D7 states the static-dump assumption.
  - *Remaining intent-auditor descriptive points (5)* — `[false]` restatements of the above (gate not yet called in production → 24-9; tests on Testcontainers + live; tolerance D2; spec finalisation; d24-8).

## Design Notes

**Patterns:** Decorator (`ReconcilingBrefOwnerImportUseCase` wraps the import use case: the pipeline and its tests stay untouched, every caller — the API today, 24.9's cutover tomorrow — gets the reconciliation); pure domain services (expected figures, comparison) unit-tested without I/O; port/adapter for the target reads and the report store; Strategy reuse for the source.

**Decisions (default, conservative, 11/11/2026):**
- **D1 Source side = the import's snapshot**, aggregated in Java: the same reader for both sources (export and database) and the same filters; the independent part is the Keevo side (aggregate SQL over what is stored) and the expected values, recomputed from raw Bref fields by the documented rules (not by the import's planner).
- **D2 Tolerances**: counts, expenses, sample, subscription, links 0; sales per bucket ±1 F × documents; collected and outstanding ±1 F per rounded Bref amount (each amount rounds by ≤ 0.5 F).
- **D3 Plan instant** = creation time of the `tenant_plan` record (milliseconds after the import's decision instant, which is not persisted — the pipeline is not changed); the comparison itself is exact (tolerance 0).
- **D4 Store as ordinal** « Boutique n » (Bref creation order) — a shop name can be a person's name.
- **D5 Reconciliation history kept** (one row per run); the gate and the owner endpoint read the latest run of each owner's latest batch of **any** status (a newer blocked, failed or running import blocks the owner until it completes and is reconciled); only that latest batch can be reconciled again.
- **D7 Static source** — the reconciliation re-reads the batch's source; under ADR-10 that source is the dump restored after Bref is stopped, so it does not move between the import and the reconciliation (a live Bref would show its new rows as gaps: never point the tool at a live Bref).
- **D8 Correcting a gap** — no in-place repair: find the cause, fix the importer if needed, then the cutover's rollback (24.9) deletes the owner's company and the import runs again (runbook 24.13).
- **D6 Old links**: registry and target invoice checked in-process; the HTTP 302 → 200 is 24.10's redirect (routed).

**Cost per owner:** source = the import's reads (≈ 10 queries, no per-row query) + O(n) aggregation; target ≈ 10 aggregate queries in one read transaction (sample: 1 query for 20 documents with their line and payment aggregates); 2 records queries; 1 insert. Memory: the owner's snapshot. Gate: 1 query for any number of owners (`DISTINCT ON`, indexes above).

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t248 migration architecture shared/infrastructure/web` -- expected: all green.

**Manual checks:**
- Live: restore the 03/11 dump into `bref_src_t248` (read-only role), backend on 4648 with `keevo_dev_t248`, import the real owner(s) → `RECONCILED`; tamper one invoice → re-reconcile → `RECONCILIATION_FAILED`; drop both databases.

## Auto Run Result

Status: done.

**Summary.** A reconciliation component of the `migration` module, added without touching the import pipeline:
- `ReconcilingBrefOwnerImportUseCase` (`@Primary` Decorator of `BrefOwnerImportUseCase`): every COMPLETED import is reconciled right after; the import's outcome is returned as is.
- `BrefReconciliationService`: re-reads the batch's source (snapshot + monetisation), computes the expected figures (`BrefReconciliationExpectations`, pure, from raw Bref fields by the 24.2–24.5 rules), reads the tenant in one read transaction (`JdbcReconciliationTargetReader`, ≈ 10 aggregate statements + the plan port), compares (`BrefReconciler`, pure) and stores the report (`JdbcReconciliationStore`, V88 `public.migration_reconciliations`). Nine controls: counts (merges and exclusions explained), sales per shop and month, collected, expenses per month, outstanding, sample of 20 documents, subscription and credit, old public links (registry), noted anomalies (explained). Any GAP → `RECONCILIATION_FAILED`; a run that cannot read is stored failed with a French label. Only the owner's latest batch can be reconciled.
- Gate for 24.9: `BrefReconciliationUseCase.blockingOwners(owners)` — one query, French reason per owner (not imported / latest import not completed / not reconciled / gap to fix).
- Endpoints (super-admin): `GET|POST /api/v1/admin/migration/batches/{batchId}/reconciliation`, `GET /api/v1/admin/migration/bref/owners/{ownerId}/reconciliation`; error `MIGRATION_RECONCILIATION_NOT_FOUND` (404); `INVALID_STATE` (409) for a batch not completed or superseded.

**Commits:** `2c0337ce` feat, `96b063d0` fix (review), then the docs commit (this spec, sprint status, epics routing).

**Files:** V88 migration; domain `ReconciliationStatus`, `ReconciliationLine`, `ReconciliationCheck`, `ReconciliationReport`, `ReconciliationFigures`, `BrefReconciliationExpectations`, `BrefReconciler`; ports `BrefReconciliationUseCase`, `ReconciliationTargetReader`, `ReconciliationStore`; adapters `JdbcReconciliationTargetReader`, `JdbcReconciliationStore`, `AdminBrefReconciliationController`; application `BrefReconciliationService`, `ReconcilingBrefOwnerImportUseCase`; `ErrorCode`, `ErrorMessages`, `GlobalExceptionHandler` (404 list); `docs/entitlements/endpoints.md` regenerated; tests `BrefReconciliationExpectationsTest`, `BrefReconcilerTest`, `BrefReconciliationServiceTest`, `ReconcilingBrefOwnerImportUseCaseTest`, `BrefReconciliationFullContextTest`.

**Review.** 46 findings (+5 descriptive): 18 patch rows (7 medium, 11 low; medium root causes: owner endpoint vs gate, superseded/running batch, two verification gaps), 3 deferred (routed: 24-9, 24-13; plus the HTTP link check routed to 24-10), 25 rejected (16 false, 4 maybe-false, 5 low not worth the complexity) — reasons in the triage log. Patched by verdict: 0 high, 7 medium, 11 low.

**Verification (all green):** migration 129, architecture 173 (1 skipped, pre-existing), shared/infrastructure/web 137.

**Live check** (backend on 4648, `keevo_dev_t248`, source `bref_src_t248` = 03/11 dump restored, read through a SELECT-only role; WhatsApp noop, FCM off; aggregates only; databases and role dropped afterwards): the real owner (1 shop, 19 documents, 22 payments, 6 products, 3 clients, 4 users) imported and reconciled in 4.4 s → `RECONCILED` (0 gap, 2 explained: noted anomalies); one paid invoice +50 F in Keevo → POST → `RECONCILIATION_FAILED` (2 gaps: « Boutique 1 — octobre 2026 » 957 777 F → 957 827 F, sample « Facture BRF-… » total); re-import (writes nothing, 0.12 s) → new batch reconciled, still failed; correction → POST → `RECONCILED`; owner endpoint = latest; 401 without token.

**Follow-up review recommended:** `true` — two or more medium entries were patched (the owner-endpoint/gate alignment and the latest-batch guard changed the store's queries after the review); they are covered by the full-context test, not by a second review.

**Residual risks:** the source is re-read (static dump assumption, D7); the plan instant is the record's creation time (D3); the HTTP check of old links belongs to 24.10.

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10 (non répétés ici).

### Statut

Sans objet pour l'app : la réconciliation est un contrôle super-admin de l'outil de migration (aucun endpoint ni champ vu par l'app, pas de clé `m24-8`).

### Ce qui est fait côté API

Rien de visible côté mobile ; une entreprise importée reste suspendue tant que la bascule (24.9) ne l'a pas activée, ce qui exige une réconciliation sans écart.

### À utiliser

Rien.

### À implémenter côté mobile

Rien.

### Critères d'acceptation mobile / scénarios de test

Aucun.

### Clé de suivi mobile

Aucune (pas de travail mobile).

## Dashboard super-admin (autre dépôt)

> Conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`.

### Statut

API livrée (commits `2c0337ce`, `96b063d0`), contrôleur `keevo/backend/src/main/java/com/keevo/migration/adapter/in/rest/AdminBrefReconciliationController.java`. Clé de suivi : `d24-8-reconciliation-bloquante`.

### Ce qui est fait côté API

Chaque import terminé est réconcilié automatiquement ; le rapport compare Bref et Keevo contrôle par contrôle (§10.3) et donne un statut bloquant pour la bascule.

### À utiliser

- `GET /api/v1/admin/migration/batches/{batchId}/reconciliation` → 200 `data` = rapport (dernière exécution du lot). 404 `MIGRATION_BATCH_NOT_FOUND` « Lot d'import introuvable » ; 404 `MIGRATION_RECONCILIATION_NOT_FOUND` « Aucune réconciliation pour cet import ou ce compte Bref » (lot bloqué ou en échec : afficher « Pas de réconciliation : l'import n'est pas terminé »).
- `POST /api/v1/admin/migration/batches/{batchId}/reconciliation` (sans corps) → 201 rapport. 409 `INVALID_STATE` avec le message « Seul un import terminé peut être réconcilié » ou « Un import plus récent de ce compte existe : réconciliez le dernier import » (afficher tel quel).
- `GET /api/v1/admin/migration/bref/owners/{ownerId}/reconciliation` → 200 rapport du dernier lot de l'owner ; 404 si aucun.
- Rapport : `status` (`RECONCILED` | `RECONCILIATION_FAILED`) + `statusLabel` (« Réconcilié » | « Écart à corriger avant l'activation »), `gaps`, `explained`, `startedAt`, `finishedAt`, `errorLabel` (réconciliation impossible : afficher tel quel, pas de contrôles), `checks[]` : `code` (clé stable), `label`, `toleranceLabel`, `status` (`OK` | `EXPLAINED` | `GAP`) + `statusLabel` (« Conforme » | « Écart expliqué » | « Écart hors tolérance »), `lines[]` : `label`, `expected`, `actual` (textes prêts à afficher), `difference`, `tolerance` (nombres, nuls hors montants), `status`/`statusLabel`, `explanation`. `id`, `batchId`, `brefOwnerId`, `tenantId` sont des poignées : ne jamais les afficher.

### À implémenter côté dashboard

1. Dans l'écran « Migration Bref », sous le lot : bandeau de statut (`statusLabel`, couleur selon `status`), compteurs « écarts » et « écarts expliqués », bouton « Relancer la réconciliation » (POST).
2. Une carte par contrôle (`label`, `toleranceLabel`, pastille `statusLabel`) ; tableau des lignes : Libellé / Attendu (Bref) / Keevo / Écart / Explication ; lignes `GAP` en premier et en rouge, `EXPLAINED` en orange.
3. Vue owner : dernier rapport (`GET …/bref/owners/{ownerId}/reconciliation`).
4. Libellés français uniquement, aucun identifiant technique.

### Critères d'acceptation dashboard

- **Given** un owner importé sans écart, **When** le super-admin ouvre le lot, **Then** il voit « Réconcilié » et des contrôles « Conforme » ou « Écart expliqué ».
- **Given** une facture modifiée dans Keevo après l'import, **When** il clique « Relancer la réconciliation », **Then** il voit « Écart à corriger avant l'activation » et la ligne du mois concerné en « Écart hors tolérance ».
- **Given** un lot remplacé par un import plus récent, **When** il relance sa réconciliation, **Then** le message « Un import plus récent de ce compte existe… » s'affiche.

### Clé de suivi dashboard

`d24-8-reconciliation-bloquante`

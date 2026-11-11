---
title: '24.5 — Bref import: monetisation (plan in force, payments, App Store, credit, SMS packs, counters) — ADR-07'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: 'd3ec4691a09d97a76b44ffc148d6553ffeae767e'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-24-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-3-import-produits-clients.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** 24.2 creates an imported company on the catalogue's registration plan (trial or free) and 24.3 brings the catalogue, but nothing the owner paid in Bref follows: plan and end date, Mobile Money and App Store payments, account credit, SMS recharges, the month's usage. FUS-MIG8: nobody loses what they paid. Three items are routed here: processed RevenueCat events + Apple subscriptions (21.2), plans paid with the account credit missing from the super-admin revenue (21.3), and the registration plan to replace by the plan in force (24.2).

**Approach:** One new pipeline step of the `migration` module (order 110, the slot 24.2 reserved) fed by its own read-only source section (both sources: restored Bref database, JSON export) loaded and validated in a new `check` hook of the pipeline, before any write. A pure planner applies Bref's `SUBSCRIPTION_IN_FORCE` rule at the cutover instant and maps every row; writes go through `PlanAdministrationPort.changePlan` (cause `MIGRATION_BREF`), a new `payments :: api` import port (payment requests, ledger, App Store subscriptions and events, credit), a new `subscription :: api` counters port and `UsageCredits` (packs) — one platform transaction with the records. V86 adds the credit part to the ledger so credit-paid plans count in the MRR.

## Boundaries & Constraints

**Always:**
- Plan = Bref `SUBSCRIPTION_IN_FORCE` at the cutover instant T (status `active`, started ≤ T or no start, end > T or no end or grace end > T, payment method set; latest start wins), else the due scheduled subscription Bref's reconcile would activate, else the catalogue's free plan (no grace, ADR-07). `user.plan` is never read. Expiry = `endsAt` (grace end when only the grace keeps it in force; none when Bref has no end).
- The registration plan is replaced in the same run by `changePlan(…, MIGRATION_BREF)`: trial module rows realigned by `PlanModuleRowSync`, `public.tenants.plan_type` synced, one `tenant_plan_events` row.
- Keep Bref ids: payment request and its ledger row = the Bref request id; App Store subscription = id of its latest Bref transaction; ledger row of an Apple purchase = the transaction id.
- Money to the franc: credit balance = Bref `account_credit.amount` (+ converted scheduled periods, D4); ledger amounts = Bref `amountDue` (cash) and `creditApplied` (credit part).
- Every Bref row read is traced in `migration_records` (RevenueCat event ids are text: source id = name-based UUID of the event id); the plan decision is one record (owner → `tenant_plan`). Idempotent: a re-run writes nothing, a partial re-run only the missing rows; the plan is decided once.
- Validation before any write (`MONETISATION_INVALID`): an in-force, scheduled or confirmed row whose Bref plan/cycle has no Keevo plan/cycle in the catalogue.
- Tables a Bref schema lacks (e.g. `revenuecat_webhook_event` before develop) read as empty.
- Set-based: one statement per target table (`unnest`/batch) + one records batch; constant source queries per owner.
- No personal data in logs (ids and counts), French notes without values; additive API changes only.

**Never:**
- No notification, no `PlanActivatedEvent`, no provider or RevenueCat call, no quota check.
- No import of in-flight requests (`INITIATING`/`PENDING`): a Bref `pending` request arrives `EXPIRED` (a late provider confirmation is honoured by the 21.1/21.3 rules).
- No change to api-bref; no change to the expiry job; no counters of past months/days.
- Documents (24.4), annexes (24.6), reconciliation (24.8), cutover command (24.9).

## I/O & Edge-Case Matrix

| Scenario | Input / State (T = cutover) | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| No subscription | owner never paid | free plan, no expiry, event MIGRATION_BREF, no ledger | — |
| Mobile Money in force | `pro`/`monthly` active T−5d…T+25d, confirmed request 3 500 (500 credit, 3 000 due, `orange_money`) | PRO until T+25d exactly; `payment_requests` CONFIRMED (same id, ORANGE_MONEY, base 3 500, credit 500, due 3 000); `tenant_payments` 3 000 MOBILE_MONEY + credit_xaf 500, plan/cycle, request id | — |
| Apple in grace | `business` apple, endsAt T−2d, grace T+10d; transactions of original O | BUSINESS until T+10d; `external_subscriptions` BILLING_ISSUE, grace and granted = T+10d; events of O imported APPLIED, `last_event_ms` = latest | — |
| Expired | `team` active but endsAt T−1d (no grace), or status `expired` | free plan, no expiry | — |
| Admin grant | `business` `admin_grant` until T+20d, amount 0 | BUSINESS until T+20d, no ledger row | — |
| Scheduled, same plan | in-force PRO until E, scheduled PRO E…E+1m paid | PRO until E+1m (note) | — |
| Scheduled, other plan | in-force PRO, scheduled TEAM paid 5 500 | PRO until its end, 5 500 added to credit (note) | — |
| Apple product change | scheduled apple row (no end) | ignored (note) | — |
| Credit | `account_credit` 1 750 | balance 1 750, one MIGRATION movement | — |
| SMS recharge | `completed` 100 SMS; `pending` 50 | 100 SMS pack units (MIGRATION); pending not imported (note) | — |
| Counters | `sms` 2026-11 = 4, `invoices` 2026-11 = 12, `daily_ai_credits` today = 3, `sms_free` all = 2, `email` 2026-10 = 7 | SMS MONTH 4, DOCUMENTS MONTH 12, AI_CREDITS DAY 3, SMS LIFETIME 2; October not imported | — |
| Apple ledger | production tx of `brefpoint.pro.month` with declared price 2 500; revoked or sandbox tx; undeclared price | one APP_STORE row 2 500 (store tx id); others none (note) | — |
| Request states | `failed`, `cancelled`, `pending`, `expired` | FAILED, CANCELLED, EXPIRED, EXPIRED; no ledger | — |
| Inconsistent request | due ≠ amount − credit − reduction ≥ 0, or amount ≤ 0 | not imported, note | — |
| Unknown plan | in-force `gold` or cycle `weekly` | nothing written | case MONETISATION_INVALID |
| Re-run | same owner | nothing written, `alreadyImported` counts the records | — |
| Apple id held | original transaction already bound to another company | not bound (note) | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/migration/application/ImportStep.java` -- add `default List<BlockedCase> check(ImportRun run)` (empty).
- `.../migration/application/BrefOwnerImportService.java` `check(ImportRun)` -- after the validator, add every step's `check` cases (one loop, nothing else changes).
- `.../migration/application/ImportRun.java` -- add a typed attachment map (`attach`/`attachment`) so a step keeps what its check loaded; counters added with `count`.
- `.../migration/application/step/ClientsStep.java` -- step pattern (records in the step's transaction, run updated after commit).
- `.../migration/application/MigrationUnitOfWork.java` `platform(…)` -- the monetisation transaction (public tables + schema-qualified plan change).
- `.../migration/domain/model/ImportNote.java`, `BlockedCase.java` -- append the 24.5 kinds.
- `.../migration/adapter/out/source/JdbcBrefSource.java` -- read-only pool and transaction to share (add a package-private `readOnly(work)`); `BrefRows.java` -- helpers to open to the package; `JsonExportBrefSource.java` `expectedChecksum` -- package-private.
- `.../subscription/api/PlanAdministrationPort.java` `changePlan` (MANDATORY) → `ChangeTenantPlanService` (subscription row, `plan_type`, event, `PlanModuleRowSync`).
- `.../shared/capability/entitlement/PlanCatalog.java` (`find`, `settings().freePlan()`), `UsageCredits.add(…, Grant.migration())`, `QuotaPeriod.key`, `UsageMetric`.
- `.../subscription/api/StoreProducts.java` `find(APP_STORE, productId)` -- plan/cycle and declared Apple price.
- `.../payments/credit/domain/port/out/AccountCreditStore.java` `credit(…, MIGRATION, …)`; `CreditMovementKind.MIGRATION`.
- `.../payments/checkout/adapter/out/persistence/JdbcPaymentRequestStore.java` `LEDGER_SQL`/`insertLedger`, `PaymentConfirmationService.apply` (ledger row when cash + credit > 0, credit part kept) -- 21.3 item.
- `.../admin/revenue/domain/model/RevenuePayment.java`, `RevenueMetrics.monthlyValue/revenueCollected`, `RevenueSummary`, `application/service/AdminRevenueService.java` (`PAYMENTS_SQL`, summary), `adapter/in/rest/dto/RevenueSummaryDto.java` -- 21.3 item.
- `.../admin/subscription/domain/model/PaymentMethod.java`, `adapter/in/rest/dto/TenantPaymentDto.java`, `adapter/out/persistence/JdbcTenantPaymentRepository.java` -- ACCOUNT_CREDIT, credit part.
- DB: `V17` (`tenant_payments`, amount > 0), `V60` (plan/cycle on ledger, `billing_cycles`), `V61` (`payment_requests`), `V62` (`external_subscriptions`, `revenuecat_webhook_event`, `store_products`), `V63` (credit, packs), `V44` (`usage_counters`), `V81` (records, MIGRATION_BREF).
- Bref (`api-bref` develop 6faa8b4, journal up to date 11/11): `entitlement/entitlement.service.ts` (`SUBSCRIPTION_IN_FORCE`, reconcile), `payment-gateway/payment-gateway.service.ts` (`confirmCheckout`, scheduled period → credit), `users/users.controller.ts` (credit-only confirmation), `storekit/storekit.service.ts` (Apple rows, grace, product change, event `subjectKey`), `entitlement/usage-counter.service.ts` + `quota.service.ts` (metrics `invoices|sms|email|ai_credits|sms_free|daily_*`, keys `YYYY-MM`, `YYYY-MM-DD`, `all`), `shop/shop.service.ts` (SMS recharge never credited).
- `keevo/contracts/bref-export.json` -- detail the 24.5 sections (+ `smsRechargeRequests`).

## Tasks & Acceptance

**Execution:**
- `db/migration/V86__ledger_credit_part.sql` -- `tenant_payments.credit_xaf` (≥ 0, default 0), amount check `amount_xaf >= 0 AND amount_xaf + credit_xaf > 0`, method + `ACCOUNT_CREDIT`.
- `migration/domain/model/BrefMonetisation.java` -- section record with row records (subscription, plan payment request, Apple transaction, RevenueCat event, account credit, SMS recharge, usage counter), canonical forms.
- `migration/domain/port/out/BrefMonetisationSourcePort.java` + `adapter/out/source/{JdbcBrefMonetisationSource, JsonExportBrefMonetisationSource, BrefMonetisationRows}.java` -- one read-only transaction (table existence, then one indexed query per table), streamed export sections, same mapping and checksums.
- `migration/domain/service/BrefMonetisationPlanner.java` -- pure: plan decision, notes, cases, rows to write (O(n log n)).
- `migration/application/step/MonetisationStep.java` (`@Order(110)`) -- check (load + validate + attach), run (one platform transaction: plan, payments port, counters port, packs, records).
- `payments/api/{package-info, LegacyMonetisationImportPort}.java` + `payments/legacyimport/…` adapter and JDBC writer -- requests, ledger, App Store subscriptions, events, credit; returns inserted ids.
- `subscription/api/UsageCounterImportPort.java` + adapter -- counters upsert `GREATEST`.
- `migration/package-info.java` -- + `payments :: api`, `subscription :: api`.
- 21.3 item -- confirmation ledger with credit part; revenue: MRR counts cash + credit, collected counts cash, summary `outstandingCreditXaf`; admin method `ACCOUNT_CREDIT` labelled.
- Tests -- planner (every matrix row), sources (JSON sections, missing tables), `BrefMonetisationImportFullContextTest` (synthetic Bref DB, both sources, re-run), revenue metrics (100 % credit plan), existing 24.2 test counts updated (+1 plan record, +1 event).
- `keevo/contracts/bref-export.json`, `docs/entitlements/endpoints.md` (no new endpoint expected).

**Acceptance Criteria:**
- Given an owner whose Bref Mobile Money PRO runs until D, when imported, then `GET /api/v1/subscription/me` of the company would show PRO until D (subscription row), `tenants.plan_type` = PRO, the last plan event is MIGRATION_BREF, and the trial module rows are gone.
- Given the super-admin revenue summary, when a plan was paid 100 % with credit, then it counts in the MRR and not in the revenue collected, and the outstanding credit is shown.
- Given the same owner imported again, then no row is written and the batch counts every record as already imported.
- Given the export of the same owner, then it yields the same monetisation section as the database (same checksums).

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 49 findings — high 1, medium 14, low 32, false 2, maybe-false 0 (live check 1; design 6, blind 14, edge 15, verification 6; intent alignment 7 descriptive observations logged as low rejects)
- findings:
  - **Live check (before the review layers)**
    - `[high]` `[patch]` The plan change of the import realigned the paid-module rows and published `ModuleLicencesChangedEvent`: the owner of an imported company (still suspended) got a WhatsApp + push « Le module X est désactivé » during the import (noop stub sends seen in the live log for O1 and O3). Fixed: `PlanModuleRowSync.syncSilently` (default = `sync`), used by `ChangeTenantPlanService` when the cause is `MIGRATION_BREF`; nobody is notified by an import (announcement = 24.13 campaign).
  - **Design layer (FUS-NFR11)**
    - `[medium]` `[patch]` Catalogue lookups per row (`offers.cycle`, `storeProducts.find` once per request / per Apple transaction, twice per run). Fixed: the run's `Catalogue` is memoised (`computeIfAbsent` per cycle / product) and kept in the `Loaded` attachment shared by `check` and `run`: one query per distinct code.
    - `[low]` `[defer→routed 24-9]` Bref `subscription`, `plan_payment_request`, `sms_recharge_request` have no owner index: one scan per owner. Fine for one owner; the cutover over every owner must read each section once for all owners (or index the restored copy before it becomes read-only) → routed to 24.9 with the 24.3 item of the same nature.
    - `[low]` `[reject]` `to_regclass` probe per owner — one cheap catalog query; caching it at bean level would need invalidation on a new restore (24.9 can compute it once per cutover).
    - `[low]` `[reject]` Export parsed and hashed twice per run — linear, bounded by one owner's file; the separate port keeps the snapshot untouched for 24.4/24.6/24.7 (decision of the spec).
    - `[low]` `[reject]` Map copy before JSON serialisation — two small allocations per row, cosmetic.
    - `[low]` `[reject]` Snapshot and section read in two transactions — the source is a frozen restore (ADR-10); documented in the source Javadoc.
  - **Blind hunter**
    - `[medium]` `[patch]` Apple purchases of a subscription held by another company were still written to the ledger. Fixed: an Apple ledger row is written only with its subscription (inserted now or by an earlier run); tested end to end (`appStoreSubscriptionHeldElsewhere`).
    - `[medium]` `[patch]` A store-transaction conflict made `requireAll` throw: the import failed for ever. Fixed: Apple ledger rows are inserted on their own; one already recorded by Keevo is noted `APPLE_PURCHASE_ALREADY_RECORDED`, never fails the run.
    - `[medium]` `[patch]` A confirmed paid request that could not be mapped (other currency, inconsistent amounts) was skipped with a generic note. Fixed: `validate` blocks any confirmed request with money (`amountDue + creditApplied > 0`) that `mapRequest` cannot import (`MONETISATION_INVALID`); unit-tested.
    - `[low]` `[patch]` Scheduled credit recomputed by matching a French label. Fixed: `PlanDecision.creditBySubscription` carries the amount per subscription.
    - `[low]` `[reject]` Notes of skipped rows repeat on every run — they describe rows that are never written (unpaid recharges, past counters, unprocessed events); repeating them in each batch report is the intended report of that batch.
    - `[medium]` `[patch]` App Store purchase without expiry → one-day period → MRR × 30. Fixed: the period is the product's cycle (`catalogue.cycle(...).months()`); unit-tested.
    - `[medium]` `[patch]` A live Apple subscription that does not give the plan was imported EXPIRED with auto-renew off. Fixed: not revoked and Apple expiry in the future → ACTIVE (auto-renew on); unit-tested.
    - `[low]` `[reject]` A Bref card payment shows « Paiement en ligne (Mobile Money) » in the payment list — Bref card payments are not live (no card provider in Bref's gateway); method `OTHER` is kept on the row.
    - `[low]` `[reject]` Apple ledger uses today's declared price — the 21.2 rule for every App Store row (Apple prices are not in Bref's rows); documented in the contract mapping and the Dashboard section.
    - `[low]` `[patch]` Export matched the Apple `ownerId` case-insensitively, the database in lower/upper case only. Fixed: same rule in both sources.
    - `[low]` `[patch]` A free subscription in force (admin grant with a method) prevented a paid future period from starting now (it went to the credit). Fixed: a free plan in force does not block the D4 rule; unit-tested.
    - `[low]` `[reject]` « Completed » SMS recharges not checked for price/currency — Bref never completes a recharge (no code path); a manual completion is the operator's statement that it was paid.
    - `[low]` `[patch]` Missing tests (export parity of Apple/events, member recharges, missing tables, Apple ledger end to end, held path, revenue summary, label). Added: held path and Apple-purchase filtering end to end, revenue summary on PostgreSQL, payment-list label and credit part, mixed ledger; missing tables are exercised by the 24.2/24.3 full-context tests (their Bref schema has no monetisation table). Export parity of Apple/events and member recharges: not added (same mapping class, unit-tested sections).
    - `[low]` `[reject]` `outstandingCreditXaf` ignores the date range — documented as « aujourd'hui » like MRR/paying in the summary (DTO comment, Dashboard section).
  - **Edge-case hunter**
    - `[medium]` `[patch]` Same as the blind-hunter held-subscription ledger finding.
    - `[low]` `[patch]` Two Bref requests with the same provider reference → unique conflict → failed run. Fixed: a duplicate reference is kept on the first request only.
    - `[low]` `[patch]` Re-run after Bref added a transaction to an imported original → wrong HELD note. Fixed: the subscription id recorded by the earlier run is reused and the new transactions are recorded against it.
    - `[medium]` `[patch]` Same as the blind-hunter confirmed-request finding.
    - `[medium]` `[patch]` Endless plan + same-plan scheduled period → also credited (paid twice). Fixed: a plan without end covers it (note EXTENDED); unit-tested.
    - `[low]` `[patch]` Apple transaction without original id silently dropped. Fixed: note `APPLE_TRANSACTION_UNREADABLE`; unit-tested.
    - `[low]` `[patch]` Original transaction id > 100 characters → insert error. Fixed: same note, not imported.
    - `[low]` `[patch]` Processed event with null type / long id → constraint error. Fixed: note `REVENUECAT_EVENT_UNREADABLE`, not imported.
    - `[low]` `[patch]` Subject key not trimmed for `last_event_ms`. Fixed: trimmed.
    - `[false]` `[reject]` Two current counters merged → one mislabelled past period — Bref's `(ownerId, metric, periodMonth)` is unique and every Bref metric/key maps to a distinct Keevo `(metric, period_key)`.
    - `[low]` `[patch]` Count overflow after commit. Fixed: counts clamped.
    - `[low]` `[patch]` Export section `null` rejected. Fixed: read as no row.
    - `[low]` `[patch]` Per-plan « payments » count included 100 % credit rows. Fixed: collected totals take cash rows only.
    - `[low]` `[patch]` Same as the blind-hunter owner-id case finding.
    - `[medium]` `[patch]` Insert conflicts surfaced as FAILED instead of handled — fixed by the held/already-recorded/duplicate-reference patches above; the remaining strict check (`requireAll` on requests and their ledger) guards a state the import cannot reach before the cutover (refs unknown to Keevo).
  - **Verification gap**
    - `[medium]` `[patch]` Credit part of the revenue summary not checked on PostgreSQL. Added `AdminRevenueServiceIntegrationTest.creditPaidPlan_realSql` (MRR, collected, outstanding credit, deleted company excluded).
    - `[medium]` `[patch]` Payment list label and credit part not asserted. Added to `AccountCreditFullContextTest.creditCoversAllAndNotChosen`.
    - `[medium]` `[patch]` Mixed cash + credit ledger not asserted. `partialCredit` now checks amount, credit part and method.
    - `[medium]` `[patch]` Held App Store path untested. Added `BrefMonetisationImportFullContextTest.appStoreSubscriptionHeldElsewhere`.
    - `[low]` `[patch]` Possible import failure for held purchases — same as the blind-hunter finding.
    - `[false]` `[reject]` In-repo `keevo/dashboard` labels miss ACCOUNT_CREDIT — that dashboard is a stale basic-check copy (API-only rule); the real dashboard gets the Dashboard section and `d24-5`.
  - **Intent alignment** (descriptive, logged as rejects)
    - `[low]` `[reject]` Live dump run not in the diff — done: see Auto Run Result (`bref_src_t245`, port 4645).
    - `[low]` `[reject]` « Expired » only unit-tested end to end — the live check imports a synthetic expired owner (O3 → FREE) on the real restored copy.
    - `[low]` `[reject]` Expiry job not exercised — decision D10: the plan is replaced in the same run; a failed run before step 110 leaves the registration trial (months long), re-run completes it.
    - `[low]` `[reject]` Live checkout/revenue behaviour changed — required by the 21.3 item routed here; additive API fields.
    - `[low]` `[reject]` Shared core types touched — additive extension points (check hook, attachments), no refactor.
    - `[low]` `[reject]` Payments rows written by a new JDBC writer, not the 21.x services — those services apply live rules (renewal from today, provider checks) that must not run on history; the writer keeps their tables' constraints.
    - `[low]` `[reject]` Synthetic schema vs real dump — the test DDL is the dump's own DDL (pg_dump of the 03/11 database).

## Design Notes

**Patterns.** Pipeline step + a `check` hook (validation before any write stays the orchestrator's contract); Strategy for the section source (database / export, one shared row mapping); pure Domain Service (`BrefMonetisationPlanner`) so every rule is unit-tested without I/O; ports owned by the writing modules (payments, subscription, shared capability). The section is a separate source port so 24.4/24.6/24.7 keep editing the snapshot without conflicts.

**Decisions (default, conservative, 11/11/2026):**
- **D1** T = the run's clock instant; the decision is recorded once (Bref is stopped at the cutover, ADR-10).
- **D2** Bref's own rule, reconcile included; an in-force row without end keeps a paid plan with no expiry (note).
- **D3** Grace: the plan runs to Bref's grace end (Apple grace included per ADR-07), `external_subscriptions` BILLING_ISSUE.
- **D4** A future paid period: same plan → expiry extended to its end; other plan → its paid value (cash + credit used) goes to the credit, Bref's rule at the next checkout; none in force → applied now to its end; Apple product change ignored (Apple drives renewals, 21.2).
- **D5** Requests: confirmed → CONFIRMED + ledger (cash and credit part); pending → EXPIRED (no credit reserved: Bref takes credit at confirmation, so a late confirmation takes it again from the imported balance — 21.3 rule); labels and months from the catalogue.
- **D6** Apple ledger = declared Apple price (21.2 rule), production and non-revoked only; events imported only when processed.
- **D7** SMS recharges: Bref never credits them; a `completed` one (manual) becomes pack units, others are not imported.
- **D8** Counters of the current WAT month, today and lifetime only (older periods are purged by Keevo anyway).
- **D9** 21.3 item: ledger `credit_xaf`; 100 % credit → amount 0, method ACCOUNT_CREDIT; MRR = cash + credit; collected = cash; outstanding credit = Σ balances of live companies. A late payment converted to credit stays out of the ledger (it counts when spent).
- **D10** 24.2 item: no change to the expiry job — the plan is replaced in the same run, so a registration trial can no longer expire an imported company; a Bref period that really ends while suspended expires as in Bref.

**Cost per owner:** source 1 existence query + ≤ 7 queries (indexed where Bref has an index; the three without one are routed to 24.9) or one streamed pass of the export; catalogue: one query per distinct cycle and App Store product (memoised per run, review); writes: 1 plan change (constant statements), ≤ 1 insert per target table (2 for the ledger: checkouts, then App Store purchases), ≤ 2 credit movements, ≤ 1 pack credit, 1 counters upsert, 1 records batch. Planner O(n log n), memory = the owner's rows.

**Review decisions (11/11/2026):**
- **D11** Nobody is notified by an import: `ChangeTenantPlanService` realigns the module rows silently (`PlanModuleRowSync.syncSilently`) when the cause is `MIGRATION_BREF` (found by the live check: the « module désactivé » WhatsApp would have reached every migrated owner).
- **D12** A confirmed payment with money that cannot be imported blocks the owner (`MONETISATION_INVALID`) — money is never dropped with a note.
- **D13** App Store conflicts never fail the run: a subscription held by another company is not moved and its purchases are not counted here (`APPLE_SUBSCRIPTION_HELD`); a purchase Keevo already recorded is noted (`APPLE_PURCHASE_ALREADY_RECORDED`).
- **D14** A live Apple subscription that does not give the plan stays ACTIVE (Apple renews it, 21.2 applies the renewal); an App Store purchase without expiry covers its product's cycle.
- **D15** A free plan in force does not stop a paid future period from starting; a plan without end covers a same-plan scheduled period (never credited twice).

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t245 migration payments subscription admin/revenue admin/subscription architecture` -- expected: all green.

**Manual checks:**
- Live: `bref_src_t245` (dump of 03/11 + synthetic monetisation rows, read-only role) → backend on 4645 with `keevo_dev_t245`: import, re-run; aggregates only; drop both databases.

## Auto Run Result

Status: done.

**Summary.** New step 110 « abonnement et paiements » of the Bref import pipeline, fed by its own read-only section (`BrefMonetisationSourcePort`: restored database — one read-only transaction, tables missing in an older Bref schema read as empty — and the JSON export, same mapping and checksums) loaded and validated in a new `ImportStep.check` hook before any write:
- plan in force at the cutover by Bref's `SUBSCRIPTION_IN_FORCE` (reconcile and Apple grace included; else the free plan, no grace) through `PlanAdministrationPort.changePlan` cause `MIGRATION_BREF` — the registration plan and its trial module rows are replaced in the same run, silently (item 24.2 done; D10, D11);
- checkouts → `payment_requests` (same ids, never in flight), confirmed ones → `tenant_payments` with cash and credit part; App Store → `external_subscriptions` (ACTIVE / BILLING_ISSUE / EXPIRED / REVOKED, grace, granted expiry, last event) + APP_STORE ledger rows with a declared price; processed RevenueCat events → `revenuecat_webhook_event` APPLIED (item 21.2 done);
- account credit to the franc (+ future paid periods of another plan, Bref's own rule) as MIGRATION movements; paid SMS recharges → SMS pack units; counters of the current WAT month, today and lifetime;
- V86 + checkout/revenue: the ledger keeps the credit part (`credit_xaf`, method ACCOUNT_CREDIT when 0 cash), the MRR counts cash + credit, the revenue collected counts cash, the summary shows the outstanding credit (item 21.3 done).

**Rebase on 24.7 (33b76868, orchestrator request):** conflicts in `ImportRun` (24.7 `identities`/`accountId` + 24.5 attachments) and `BrefOwnerImportService.check` (24.7 identity decisions + 24.5 step hooks) resolved keeping both sides; the step now writes the owner's Keevo account (`ImportRun.accountId`: the Bref id, or the existing account 24.7 linked) as `requested_by` of the checkouts and `app_user_id` of the App Store subscriptions and events.

**Files:** see the Code Map; new: `BrefMonetisation`, `BrefMonetisationSourcePort`, `JdbcBrefMonetisationSource`, `JsonExportBrefMonetisationSource`, `BrefMonetisationRows`, `BrefMonetisationPlanner`, `MonetisationStep`, `payments/api/LegacyMonetisationImportPort` + `payments/legacyimport/**`, `subscription/api/UsageCounterImportPort` + adapter, `V86__ledger_credit_part.sql`, test DDL `bref-source-monetisation-schema.sql`, tests `BrefMonetisationPlannerTest`, `JsonExportBrefMonetisationSourceTest`, `BrefMonetisationImportFullContextTest`; changed: `ImportStep` (check hook), `ImportRun` (attachments), `BrefOwnerImportService` (calls the hooks), `BrefRows`/`JsonExportBrefSource`/`JdbcBrefSource` (package helpers), `ImportNote`, `BlockedCase`, `PlanModuleRowSync(Service)` + `ChangeTenantPlanService` (silent sync for imports), payments confirmation and ledger, admin revenue and payment list, contract `bref-export.json`; existing tests updated (24.2 counts, credit-only ledger row).

**Review.** 49 findings (live check 1 + 5 layers): 31 patched (1 high, 14 medium, 16 low), 1 routed to 24.9, 17 rejected with reasons (Review Triage Log). No migration added beyond V86; no new endpoint (additive fields on two super-admin responses).

**Verification (all green, after the patches):** migration 66, payments 100, admin/revenue 66, admin/subscription 70, architecture 173 (1 skipped, pre-existing), subscription 153, shared/module 559; after the rebase on 24.7 (33b76868): migration 75, architecture 173 (1 skipped).

**Live check** (port 4645, `keevo_dev_t245`, source `bref_src_t245` restored from the 03/11 dump + 3 synthetic owners, read through a SELECT-only role; aggregates only; WhatsApp noop, FCM off):
- Import 1 (≈ 3.4 s per owner, COMPLETED): dump owner → BUSINESS (admin grant, end kept), 0 payment; O1 Mobile Money → PRO, 2 checkouts (CONFIRMED + pending → EXPIRED), 1 ledger row 3 000 + 500 credit, credit 1 750, 100 SMS units, 3 counters; O2 App Store in grace → BUSINESS until the grace end, subscription BILLING_ISSUE (grace = granted), 2 processed events (the unprocessed one noted), purchase noted « prix non renseigné »; O3 expired → FREE, 1 historical ledger row 5 500. Each company: 2 MIGRATION_BREF events, no trial module row left.
- Import 2: every owner COMPLETED in ≈ 0.05 s, every creation 0, `alreadyImported` = records (28, 15, 10, 9).
- The live log showed two « module désactivé » WhatsApp messages (noop stub) during import 1 → patched (D11). No real message sent; no personal data from the import in the log. Databases and role dropped afterwards.

**Follow-up review recommended:** `true` — a high (silent module sync) and fourteen medium entries were patched after the review (App Store conflict handling, blocking of unreadable confirmed payments, memoised catalogue, live Apple status, period without expiry, ledger credit part assertions). The App Store conflict paths (held subscription, purchase already recorded, later transaction on an imported original) are covered by one full-context test and the code path; a second pass on `MonetisationStep.write` is worth it.

**Residual risks:**
- Bref tables without an owner index are scanned per owner (routed to 24.9 with the cutover's one-pass reading).
- App Store ledger amounts use the declared Apple price of today (21.2 rule); Bref rows carry no price.
- A Bref « completed » SMS recharge is trusted (Bref never completes one by code).

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10 (non répétés ici).

### Statut

API livrée (commit de la story 24.5, branche de l'epic 24). **Aucun nouvel endpoint ni champ pour l'app** : l'import remplit des tables existantes (plan 9-2/19.7, paiements 21.1, App Store 21.2, crédit et packs 21.3, quotas 19.8). Pas de clé de suivi mobile.

### Ce qui est fait côté API

- Un compte importé arrive sur **le plan qu'il avait dans Bref** à la bascule, jusqu'à la même date de fin (période de grâce App Store comprise) ; sans abonnement en cours : plan gratuit.
- Son **crédit du compte** et ses **SMS achetés** sont repris au franc près (mouvement « Repris de votre ancien compte » dans l'historique du crédit et des packs, libellés 21.3 existants).
- Ses **consommations du mois** (documents, SMS, e-mails, IA du jour, SMS gratuits à vie) continuent : les quotas reprennent où Bref s'était arrêté.
- Son **abonnement App Store** est rattaché à l'entreprise : le prochain renouvellement Apple est appliqué par le webhook 21.2 sans action de l'app.

### À utiliser

Rien de nouveau : `GET /api/v1/subscription/me` (plan, échéance, quotas, packs), `GET /api/v1/payments/credit`, `GET /api/v1/payments/app-store/subscription` (21.2).

### À implémenter côté mobile

Rien. Vérifier seulement, au premier lancement d'un compte importé (après activation 24.9), que « Mon plan » affiche le plan et l'échéance Bref et que l'historique du crédit montre la ligne « Repris de votre ancien compte ».

### Critères d'acceptation mobile / scénarios de test

- **Given** un owner Bref Pro payé jusqu'au 10/12, importé puis activé, **When** il ouvre « Mon plan », **Then** il voit Pro jusqu'au 10/12, sans essai.
- **Given** un owner Bref avec 1 750 FCFA de crédit, **When** il ouvre son crédit, **Then** il voit 1 750 FCFA et le mouvement « Repris de votre ancien compte ».

## Dashboard super-admin (autre dépôt)

> Conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`.

### Statut

API livrée (commit de la story 24.5). Routes inchangées : import d'un owner (`POST /api/v1/admin/migration/bref/owners/{ownerId}/imports`, `GET /api/v1/admin/migration/batches/{batchId}`), revenus (`GET /api/v1/admin/revenue/summary`), paiements d'une entreprise (`GET /api/v1/admin/tenants/{id}/payments`). Ajouts additifs seulement.

### Ce qui est fait côté API

- Le lot d'import compte la monétisation reprise ; nouvelles remarques et un nouveau cas bloquant.
- Revenus : un plan payé avec le crédit du compte compte dans le MRR (pas dans l'encaissé) ; nouveau total « crédit en cours ».
- Paiements d'une entreprise : part payée avec le crédit, nouveau moyen « crédit du compte ».

### À utiliser

- Lot (`counts`, nouvelles clés) : `plansApplied`, `paymentRequestsImported`, `paymentsRecorded`, `appStoreSubscriptionsImported`, `appStoreEventsImported`, `creditImportedXaf` (FCFA), `smsPackUnitsImported`, `usageCountersImported`.
- Lot (`notes`, afficher `label (count)`) : `PLAN_IN_GRACE`, `PLAN_WITHOUT_END`, `PLAN_SCHEDULED_EXTENDED`, `PLAN_SCHEDULED_STARTED`, `PLAN_SCHEDULED_TO_CREDIT`, `APPLE_PRODUCT_CHANGE_IGNORED`, `PAYMENT_PENDING_EXPIRED`, `PAYMENT_REQUEST_SKIPPED`, `APPLE_PRODUCT_UNKNOWN`, `APPLE_PRICE_UNKNOWN`, `APPLE_PURCHASE_NOT_COUNTED`, `APPLE_SUBSCRIPTION_HELD`, `APPLE_TRANSACTION_UNREADABLE`, `APPLE_PURCHASE_ALREADY_RECORDED`, `REVENUECAT_EVENT_UNPROCESSED`, `REVENUECAT_EVENT_UNREADABLE`, `SMS_RECHARGE_NOT_PAID`, `COUNTER_PAST_PERIOD`.
- Lot (`cases`) : `MONETISATION_INVALID` (« Abonnement ou paiement Bref non repris : … »), `sourceTable` `subscription` / `plan_payment_request` / `account_credit` ; `sourceId` jamais affiché.
- `GET /admin/revenue/summary` : nouveau champ `outstandingCreditXaf` (crédit détenu **aujourd'hui** par les entreprises non supprimées, comme MRR/payants).
- `GET /admin/tenants/{id}/payments` : nouveau champ `creditXaf` par ligne ; `method` peut valoir `ACCOUNT_CREDIT` (`amountXaf` = 0) ; `recordedByLabel` « Paiement en ligne (crédit du compte) ».

### À implémenter côté dashboard

1. Écran « Migration Bref » : libellés des nouveaux compteurs — « Plan appliqué », « Paiements de plan repris », « Encaissements inscrits », « Abonnements App Store », « Événements App Store », « Crédit repris (FCFA) », « SMS repris », « Compteurs du mois ».
2. Revenus : tuile « Crédit en cours » (`outstandingCreditXaf`, FCFA, mention « aujourd'hui »).
3. Paiements d'une entreprise : afficher « dont crédit : X FCFA » quand `creditXaf > 0` ; libellé du moyen `ACCOUNT_CREDIT` = « Crédit du compte » (et `APP_STORE` = « App Store » si absent).

### Critères d'acceptation dashboard

- **Given** un owner Bref Pro payé 3 500 dont 500 de crédit, **When** le super-admin l'importe, **Then** le lot « Terminé » montre « Plan appliqué : 1 », « Encaissements inscrits : 1 », et la fiche paiements de l'entreprise montre 3 000 FCFA « dont crédit : 500 FCFA ».
- **Given** un plan payé entièrement avec le crédit, **Then** la tuile MRR l'inclut, l'encaissé non, et la ligne de paiement porte « Paiement en ligne (crédit du compte) ».
- **Given** un abonnement Bref d'un plan inconnu, **Then** le lot est « Bloqué » avec « Abonnement ou paiement Bref non repris : … ».

### Clé de suivi dashboard

`d24-5-import-monetisation`

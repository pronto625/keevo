---
title: '20-11 Module finance — Bref result (revenue excl. VAT − expenses), margin and trend'
type: 'feature'
created: '2026-11-08'
status: 'done'
baseline_revision: 'f9ce63f0ddf86f6e49549a8ceab33a41047bfc0b'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-9-reporting-multi-source.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-10-module-expenses.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref owners read their result (revenue excl. VAT − expenses), margin and trend on the « Finance » screen (`GET /v1/finance/summary`); Keevo has revenue (20.9) and expenses (20.10) but no view that combines them, so Bref users lose that screen when they move (FUS-FR26).

**Approach:** a new premium module `finance` (`requires: [core, expenses, reporting]`, OWNER only, no table) exposing `GET /api/v1/finance/summary`: revenue read from the single revenue source of 20.9 (two new columns: VAT and paid part), expense totals from a new query port of the expenses module api, and Bref's `finance-math.ts` reproduced exactly (contract `keevo/contracts/finance-math-cases.json` with every case of `finance-math.spec.ts`).

## Boundaries & Constraints

**Always:**
- **Bref math, exactly** (`api-bref/src/finance/finance-math.ts`, `finance.service.ts`): `days` inclusive; comparison period (see Period); granularity `day` up to 45 days, `month` beyond; series = every bucket key of the period, zeros included; `round2` = JS `Math.round(n × 100) / 100` on IEEE doubles (Java `Math.round(double)`, same half-toward-+∞ rule; every mirrored computation in doubles, so 2.675 → 2.68 and 1.005 → 1 as in Node); `computeDelta(cur, prev)` = `{previous, delta, deltaPct}`, `deltaPct` null when prev = 0, divided by `|prev|`; `pct(part, whole)` null when whole = 0. CA HT = TTC − VAT; result = CA HT − expenses; `marginPct = pct(net, CA HT)`; `expenseRatioPct = pct(expenses, CA HT)`; `avgPerDay = round2(expenses / days)`; `paid` = Bref's `SUM(total) FILTER (WHERE status = 'paid')` — status PAID only, partial payments (`invoice_payments`) not counted; `unpaid = TTC − paid`; categories sorted by amount desc with `sharePct` of the expense total; stores sorted by result desc and only when no store is selected.
- **Period:** `startDate` / `endDate` (`yyyy-MM-dd`, Bref's parameter names, what the Bref app sends; each defaults to the first / last day of the current month, WAT civil date — FUS-NFR9, revenue windows are WAT days) compared, as Bref, with the window of the same length just before; or `period` (`yyyy-MM` = that month, `yyyy` = that year — the two modes of the Bref screen) compared with the previous calendar month / year. Start > end (an `endDate` alone before the default start names `endDate`), > 366 days, years outside 2000–2100, malformed or both forms → 400 with a French message.
- **Revenue = the 20.9 source** (`RevenueQueryPort.current()`), never re-written: civil-date window in WAT (`occurred_at` from start 00:00 WAT to end+1 00:00 WAT), series keys from `occurred_at AT TIME ZONE 'Africa/Lagos'`. Two columns added to the documents relation (every branch, appended last): `tax_amount` (invoice `total_vat`; credit note `−total_vat`; sale 0) and `paid_amount` (sale = its total; invoice = `total_ttc` when PAID; credit note = `−total_ttc` when its invoice is PAID). A third relation `taxedDocuments` (same rows) gives a sale its VAT when the invoicing tables exist (a POS sale records no VAT itself): the active automatic invoice's `total_vat` when bridged; else, for a VAT-registered seller (`invoicing_settings.vat_registered`), `TTC − HALF_UP(TTC × 100 / (100 + vat_rate))` (Keevo's per-line extraction rule, set-based: one join on the single settings row); else 0. Finance reads it, the 20.9 consumers keep `documents` untouched. `storeScopedTaxed()` = the store-scoped (sales-only) relations with that taxed sales relation. `invoiceCount` = Σ `doc_count` (sales + invoices, as 20.9's transactions).
- **Store scope (as 20.10):** OWNER = every store or `storeId=` (unknown store → `VALIDATION_ERROR` « Établissement inconnu », the global mapping: HTTP 422); a member who would hold the permission = their store only, no store = zeros. A store filter reads `storeScopedTaxed()` (the store-scoped rule of reporting) with `store_id = ?`; without a store, `byStore` gains a « Sans boutique » row (storeId null) whenever the revenue without store is non-zero, so the rows add up to the total.
- **Expenses:** `ExpenseTotalsQueryPort` in `com.keevo.expenses.api` (comparison start + period + optional store; one grouped query with a period flag for both totals, categories, buckets and — without a store — stores); a tenant without the expenses table → zeros, never provisioned by finance; SQLSTATE 42P01 after caching → cache evicted, zeros (under a savepoint, so the request's transaction stays usable). Category key + French label.
- **Module:** manifest `finance` 1.0.0, premium, `free: false`, `requires [core, store, expenses, reporting]`, permission `FINANCE_VIEW` held by OWNER only (Bref: owner / superadmin), no routes; public `V58` grants it to the expenses offers (PRO, TEAM, BUSINESS, BUSINESS_TRIAL, PREMIUM, PREMIUM_TRIAL) and owner-disables it for existing tenants — no plan literal in Java. `@RequiresEntitlement(finance, READ)` + `@RequiresPermission(FINANCE_VIEW)`.
- Additive only: new endpoint, new columns at the end of the revenue relation, no change to an existing response.

**Never:** no superadmin cross-account `ownerId` (20.13), no store attribution of documents (20-14), no write, no table, no plan code / price in Java, no per-day / per-store / per-category query loop, no raw codes or ids as labels, no change to `sprint-status.yaml` / `deferred-work.md`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Month with data | invoice 11 925 TTC (VAT 1 925) issued 03/09, sale 5 000, expense 3 000 | `excludingTax` 15 000, `net` 12 000, `marginPct` 80, series of 30 days | — |
| Year | `period=2026` | `granularity` month, 12 series keys | — |
| Previous = 0 | nothing last period | `deltaPct` null | — |
| Store filter | `storeId` of store B | store B sales and expenses only, `byStore` empty | — |
| Unknown store | random `storeId` | refused | 422 VALIDATION_ERROR « Établissement inconnu » |
| Bad period | `period=2026-13`, `startDate=2026-02-30`, start > end, 400 days, period + dates | refused | 400 VALIDATION_ERROR, French message |
| Employee | EMPLOYEE token | refused | 403 FORBIDDEN |
| Module off | `finance` DISABLED | refused | 403 module |
| No expenses table | tenant never used expenses | expenses 0 | — |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/` unless stated.
- `java/com/keevo/reporting/revenue/adapter/out/persistence/RevenueRelations.java` -- the source SQL; `tax_amount` / `paid_amount` appended; new `taxedDocuments()` (sale branch with the bridge VAT when invoicing exists); `ProvisionedRevenueSource` builds it.
- `java/com/keevo/shared/capability/revenue/RevenueQueryPort.java` (moved there by 20-14) -- `Relations` gains `taxedDocuments` (2-arg constructor kept: = `documents`), new `storeScopedTaxed()`; finance depends on `shared` only for it (no named interface needed).
- `java/com/keevo/expenses/api/` (new named interface) + `expenses/expense/adapter/out/persistence/JdbcExpenseTotals.java` -- expense totals port and its grouped query.
- `java/com/keevo/finance/**` (new) -- module, `FinanceMath`, summary service, revenue adapter (only class importing reporting), REST controller + DTO.
- `resources/keevo/modules/finance/module.json`, `resources/db/migration/V58__finance_module_offer.sql` -- manifest and offer (pattern of V56).
- Tests: `src/test/java/com/keevo/finance/**`, `keevo/contracts/finance-math-cases.json`, module counts (architecture, shared/module, admin), `docs/entitlements/endpoints.md`.
- Bref (read-only): `api-bref/src/finance/{finance-math.ts, finance-math.spec.ts, finance.service.ts, finance.controller.ts, finance.types.ts}`, app `bref/features/finance/**`.

## Tasks & Acceptance

**Execution:**
- `RevenueRelations` -- `tax_amount`, `paid_amount` on every documents branch (EMPTY too) -- HT and paid part from the single source.
- `expenses/api/ExpenseTotalsQueryPort` + `JdbcExpenseTotals` -- `breakdown()` (GROUPING SETS) and `totals()`.
- `finance/**` -- manifest constants, math, use case, adapters, controller; `V58`; `module.json`.
- `keevo/contracts/finance-math-cases.json` + `FinanceMathContractTest` -- every Bref case (+ the service formulas on totals).
- Tests: math / service unit tests, PostgreSQL test of the adapters on a real tenant schema (HT, bridge VAT, store filter, series, EXPLAIN), full-context REST test (gate, permission, scope, 400s); updated counts.

**Acceptance Criteria:**
- Given `requires: [expenses, reporting]`, when `GET /finance/summary?period=&storeId=` is called, then CA HT (via 20.9), expenses, result, margin, variation vs the previous period and series equal `finance-math.ts` on the cases ported from `finance-math.spec.ts`.
- Given a POS sale bridged to its automatic invoice, when the summary is computed, then its revenue counts once and its VAT once.
- Given an EMPLOYEE, when calling the endpoint, then 403.

## Spec Change Log

- 2026-11-08 — review 1 (coordinator): V59 → V58; JS rounding on doubles (+ half-cent contract cases from Node); « payé » checked against Bref (status PAID only, kept); VAT of unbridged POS sales from the seller's rate when VAT-registered; default month in WAT; `period=` compared with the previous calendar month / year; store filter on `storeScopedTaxed()`, « Sans boutique » row, no `(store_id)` set under a filter; revenue and expense ports take the tenant schema and read both periods in one grouped query each; unknown store through the global `VALIDATION_ERROR` mapping (422); `requires` lists `store`; 42P01 fallback; endDate named when alone before the default start; years 2000–2100; contract summary cases run end to end. KEEP: the separate taxed relation (the 20.9 `documents` stays join-free).

## Review Triage Log

### 2026-11-08 — Review pass (5 layers: blind, edge case, verification gap, intent, patterns)
- verdicts: 34 findings — high 0, medium 12, low 13, false 4, maybe-false 5 (grouped by root cause)
- findings:
  - `[medium]` `[patch]` Edge: `round2` on exact decimals differed from JS `Math.round` on doubles — doubles at the mirror points, half-cent contract cases checked under Node.
  - `[medium]` `[patch]` Blind: paid/unpaid vs partial payments — verified Bref uses status `paid` only; kept, partial-payment test added.
  - `[medium]` `[patch]` Blind: unbridged POS sales of a VAT-registered tenant counted TTC as HT — VAT derived from the tenant rate (set-based join on settings).
  - `[medium]` `[patch]` Blind/edge: default month in UTC — WAT (FUS-NFR9).
  - `[medium]` `[patch]` Blind: `period=yyyy-MM` compared to a sliding window — previous calendar month/year; sliding window kept for startDate/endDate (Bref).
  - `[medium]` `[patch]` Blind: store filter rule differed from reporting; `byStore` did not add up — `storeScopedTaxed()`, « Sans boutique » row, grouping set dropped when a store is selected.
  - `[low]` `[patch]` Blind: revenue port without tenant guard — `requireCurrent`.
  - `[low]` `[patch]` Blind: controller string-matched remap — global VALIDATION_ERROR mapping (422).
  - `[low]` `[patch]` Blind: `requires` missing `store` — added.
  - `[low]` `[patch]` Blind: COUNT functions differed — `COUNT(*)`, one grouped query per side.
  - `[low]` `[patch]` Patterns/edge/blind: `withTable` cache never evicted — evict on 42P01 under a savepoint, zeros.
  - `[low]` `[patch]` Edge: endDate-only error named startDate; absurd years — fixed, 2000–2100 bounds.
  - `[medium]` `[patch]` Verification: production `taxedDocuments` wiring untested — `ProvisionedRevenueSourceTest`.
  - `[medium]` `[patch]` Verification: PAID invoice / credit-note VAT in the period untested — PostgreSQL cases.
  - `[medium]` `[patch]` Intent: contract cases never driven through the endpoint — 2 summary cases end to end through `GET /api/v1/finance/summary`.
  - `[low]` `[patch]` Verification: `assertSubset` accepted a missing field for an expected null — presence required.
  - `[low]` `[patch]` Blind: MANAGER/VIEWER 403 untested — added.
  - `[maybe-false]` `[patch]` Blind: public migration gap with outOfOrder off — renumbered V58, merge order enforced.
  - `[low]` `[reject]` Patterns: two scans per side where one would do — folded into one grouped query per side anyway.
  - `[low]` `[reject]` Patterns: index-only scan for expenses — not needed at current volumes.
  - `[maybe-false]` `[reject]` Edge: credit note of a bridged sale under a store filter — fixed by 20-14 (credit notes follow their invoice's store).
  - `[false]` `[reject]` Intent: `period=` format — `yyyy-MM|yyyy` plus Bref's startDate/endDate; the AC leaves the format open.
  - `[false]` `[reject]` Intent: endpoint values not numerically equal to Bref — the AC says « CA HT via 20.9 », formula-equivalent by design.
  - `[maybe-false]` `[reject]` Intent: `byShop` renamed `byStore` — Keevo naming; documented in the mobile section.


## Design Notes

**Patterns.** Hexagonal module without table. *Adapter* (`RevenueSourceFinanceAdapter`) behind a finance-owned port isolates the 20.9 port — it lives in `shared.capability.revenue` (20-14) and only this adapter imports it. *Query object* ports: the expenses module publishes totals, not its table. `FinanceMath` is a pure *function library* (mirror of `finance-math.ts`) checked by a shared contract; the use case is a thin *orchestrator* (fixed number of queries, then pure math).

**Hot paths and cost (FUS-NFR11)** — one request = at most 4 SQL statements (+ one-time catalogue lookups: invoicing tables in 20.9's source, expenses table here, both cached per tenant once found) whatever the period length, the number of stores or categories:
1. revenue of the period **and** of its comparison period (contiguous: the comparison ends the day before the period): one aggregate over the taxed relation with a period flag, `GROUP BY GROUPING SETS ((cur), (cur, bucket)[, (cur, store_id)])` — totals of both periods, series and per store in one scan; the `(store_id)` set is dropped under a store filter. The window is pushed into each `UNION ALL` branch (sales `idx_sales_completed_occurred_at` / `(store_id, occurred_at)`, invoices `idx_invoices_revenue_day`, credit notes `idx_credit_notes_issued_at` — all existing). The sales' VAT costs a left join on the unique partial index of `invoices.source_sale_id` (one index probe per sale in the window) and one on the single settings row. PostgreSQL does **not** remove those joins from a `UNION ALL` branch whose `tax_amount` is unread (seen by EXPLAIN during the build), hence the separate taxed relations: the 20.9 consumers' `documents` has no join (EXPLAIN check in the PostgreSQL test).
2. expenses of both periods: one `GROUPING SETS ((cur), (cur, category), (cur, bucket)[, (cur, store_id)])` over `idx_expenses_date` / `idx_expenses_store_date` (partial, live rows, existing), under a savepoint (42P01 fallback).
3. store names of `byStore`: one `StoreQueryPort.namesByIds`; 4. store check for `storeId=`: one `findById`.
Series filling (`bucketKeys`, ≤ 366 keys) and the merges are O(k) hash-map lookups in memory. **No new filter → no new index** (every predicate already has one).

**CA HT.** Bref: `SUM(total) − SUM(tax)` of non-cancelled sale invoices. Keevo: sales are VAT-inclusive and store no VAT; the VAT of a POS sale exists only on its automatic invoice (excluded from revenue to avoid double counting), hence the bridge join; an unbridged sale of a VAT-registered seller gets the VAT extracted with the seller's rate (`TTC − HALF_UP(TTC × 100 / (100 + rate))`, the Keevo per-line rule), a non-registered seller's sale none; invoices carry `total_ht` / `total_vat`, and `total_ttc − total_vat` (= HT + delivery fee) is exactly Bref's `total − tax`. A column `tax_amount` is enough (HT = total − tax), no `ht` column.

**Bref deviations (documented):** `period=` compares with the previous calendar month / year (Bref's app only sends dates: sliding window kept for them); `byStore` gains a « Sans boutique » row for the documents (no store until 20-14), so the rows add up; Keevo's credit notes (absent from Bref) are deducted, from « payé » too when their invoice is PAID; a member's store scope exists for a future role grant (Bref: owner only); `shopId` → `storeId`, `byShop` → `byStore`, `shopName` → `storeName`, categories add `categoryLabel`.

## Verification

**Commands:**
- `kmvn.sh -o -q test -Dtest='com/keevo/finance/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: pass
- same for `expenses`, `reporting`, `architecture`, `shared`, `admin` -- expected: pass

## Auto Run Result

Status: in-review (no review pass run in this session — `followup_review_recommended: true`).

**Change:** new premium module `finance` (manifest 1.0.0, `requires [core, store, expenses, reporting]`, `FINANCE_VIEW` OWNER only, no route, no table; `FinanceModule`), public `V58` (granted to PRO, TEAM, BUSINESS, BUSINESS_TRIAL, PREMIUM, PREMIUM_TRIAL; owner-disabled for existing tenants). `GET /api/v1/finance/summary?period=|startDate=&endDate=&storeId=` (`@RequiresEntitlement(finance, READ)` + `@RequiresPermission(FINANCE_VIEW)`) returning Bref's `FinanceSummary` (period, revenue TTC / VAT / HT / paid / unpaid / count, expenses total / count / per day, result net / margin / expense ratio, comparison with the previous period, categories, series, stores). `FinanceMath` = `finance-math.ts` on `BigDecimal`; `FinanceSummaries` = `getSummary`'s assembly; `FinancePeriods` = period resolution and French refusals. Revenue: the 20.9 source gains `tax_amount` / `paid_amount` (every branch) and a third relation `taxedDocuments` (sales with the VAT of their automatic invoice — the join stays out of the 20.9 consumers' `documents`, PostgreSQL would not remove it), read through `RevenueSourceFinanceAdapter` (the only finance class reading the port, `shared.capability.revenue` since 20-14). Expenses: new api `ExpenseTotalsQueryPort` (`expenses :: api`) + `JdbcExpenseTotals` (one `GROUPING SETS` query + one aggregate; zeros without table, cached lookup).

**Files:** `finance/**` (module, domain model, port, adapters, service, controller), `resources/keevo/modules/finance/module.json`, `db/migration/V58__finance_module_offer.sql`, `expenses/api/{package-info,ExpenseTotalsQueryPort}`, `expenses/.../JdbcExpenseTotals`, `reporting/revenue/{RevenueRelations,ProvisionedRevenueSource,RevenueQueryPort,package-info}`, `keevo/contracts/finance-math-cases.json`, `docs/entitlements/endpoints.md` (regenerated: 258 handlers, 130 gated). Tests: new `FinanceMathContractTest` (every Bref case + 2 summary cases), `FinancePeriodsAndMathTest`, `FinanceSummaryServiceTest`, `FinanceSummaryPostgresIntegrationTest` (HT, bridge VAT once, credit note, WAT boundary, store filter, year, no expenses table, EXPLAIN), `FinanceFullContextTest` (REST, gate, permission, 400s); `FixedRevenueSource` builds the taxed relation; module counts / lists updated in 15 test classes (architecture, shared/module, admin).

**Verification (serialized wrapper, one package at a time, after a clean build):**
- finance: 18 tests — first run 1 failure (EXPLAIN showed PostgreSQL keeps the bridge join in a `UNION ALL` branch whose `tax_amount` is unread → join moved to the separate `taxedDocuments` relation), then 1 failure (expense plan assertion too strict on empty tables, relaxed to « date range is an index condition ») → 18/18 green.
- expenses: 39/39 green.
- reporting: 451/451 green (20.9 consumers unchanged on `documents`).
- architecture: `EndpointEntitlementCoverageTest` regenerated `endpoints.md` (2/2), then the package 125/125 green (Modulith boundaries with `expenses :: api`).
- shared: 1 320/1 320 green (manifests, catalogue, registry, backfill, expiry, owner activation, plan catalogue).
- admin: 587 tests, 1 failure (`ModuleConsoleFullContextTest.cascadeAndHistory`: deactivating « Rapports » now also cascades to Finance, which requires it — 5 → 6 dependents) → fixed, class re-run 4/4 green.

**Review 1 fixes (2026-11-08):** see Spec Change Log. Verification after a clean build: finance 21/21 (new: half-cent rounding, calendar comparison, PAID invoice + credit note with VAT, partly paid invoice, non-zero deltaPct, unbridged sale of a VAT-registered seller, « Sans boutique », contract summary cases end to end, MANAGER / VIEWER 403); expenses 39/39; reporting 453/453 (`ProvisionedRevenueSourceTest` + 2); architecture 125/125; admin 587/587; shared/module 555/555 (manifest `requires`, V58).

**Rebase on 20-14 (2a0b848b, 2026-11-08):** port moved to `shared.capability.revenue` (`taxedDocuments`, `storeScopedTaxed()`, tax / paid columns and the VAT joins carried over, following 20-14 store levels; `reporting :: revenue` dropped), `endpoints.md` regenerated. Clean build, one package at a time: finance 21/21, expenses 41/41, reporting 458/458, invoicing 411/411, architecture 131/131, shared 1 320/1 320, admin 587/587.

**Residual risks:** (1) rebased on 20-14 (2a0b848b): the taxed relations follow its store levels (V16 documents with their store, a credit note its invoice's; below V16 store-scoped = taxed sales only); « Sans boutique » now holds only pre-V16 documents; (2) V58 must be applied after 20-7's V57 (`outOfOrder` off) — 20-7 merges first; (3) documents carry no store until 20-14: the store-filtered figures are POS-only and the documents appear under « Sans boutique »; the credit note of a bridged sale under a store filter is fixed by 20-14; (4) a corrected POS sale whose automatic invoice is superseded falls back to the seller-rate VAT (or 0) until a new active invoice exists.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée sur la branche du worktree 20-11 (non poussée, non fusionnée). Conventions générales : spec-16-11. Aucune opération de synchro (lecture seule, en ligne).

**Ce qui est fait côté API :** l'écran « Finance » de Bref a son équivalent Keevo. Le résumé d'une période donne le chiffre d'affaires TTC, la TVA, le chiffre d'affaires hors taxes (base du résultat), la part payée et le reste à encaisser, le nombre de ventes et factures, les dépenses (total, nombre, moyenne par jour), le résultat (CA HT − dépenses), la marge et le poids des dépenses, l'évolution par rapport à la période de comparaison (mois ou année calendaire précédent avec `period`, fenêtre de même durée juste avant avec des dates, comme Bref), les dépenses par catégorie, la courbe jour par jour (période ≤ 45 jours) ou mois par mois (au-delà), et le résultat par boutique. Les calculs sont ceux de Bref (mêmes arrondis, mêmes cas, fichier partagé `keevo/contracts/finance-math-cases.json`). Le chiffre d'affaires est celui des rapports Keevo (ventes caisse + factures, chacune une seule fois) ; la TVA d'une vente caisse est celle de sa facture automatique quand il y en a une, sinon, pour un vendeur assujetti à la TVA, elle est extraite du montant TTC avec son taux (sinon 0). « Payé » = factures au statut payé et ventes caisse (une facture partiellement payée reste entière dans « reste à encaisser », comme Bref). Réservé au propriétaire ; module « Finance » (offres PRO et au-dessus, à activer dans « Mes modules » pour les comptes existants ; il exige le module « Dépenses »).

**À utiliser :**
1. `GET /api/v1/finance/summary` (jeton du propriétaire). Paramètres, au choix :
   - `period=AAAA-MM` (un mois) ou `period=AAAA` (une année) — les deux modes de l'écran Bref ;
   - ou `startDate=AAAA-MM-JJ` et/ou `endDate=AAAA-MM-JJ` (noms Bref ; chaque borne absente = premier / dernier jour du mois en cours, heure du Cameroun ; années 2000 à 2100) ;
   - `storeId=` (facultatif, remplace `shopId` de Bref) : une seule boutique.
2. Réponse `data` (forme Bref, noms de boutique Keevo) : `period {startDate, endDate, days, granularity: "day"|"month"}` ; `revenue {ttc, tax, excludingTax, paid, unpaid, invoiceCount}` ; `expenses {total, count, avgPerDay}` ; `result {net, marginPct, expenseRatioPct}` (pourcentages `null` quand le CA HT vaut 0) ; `comparison {previousPeriod {startDate, endDate}, revenue, expenses, result}` avec chacun `{previous, delta, deltaPct}` (`deltaPct` `null` quand la période précédente vaut 0) ; `byCategory [{category, categoryLabel, amount, sharePct}]` (afficher `categoryLabel`, ex. « Loyer ») ; `series [{key, revenue, expenses, result}]` (toutes les dates ou tous les mois, zéros compris ; `key` = `AAAA-MM-JJ` ou `AAAA-MM`) ; `byStore [{storeId, storeName, revenue, expenses, result}]` (vide quand une boutique est choisie ; trié par résultat décroissant ; une ligne `storeId: null`, `storeName` « Sans boutique » regroupe les factures, qui n'ont pas encore de boutique, pour que les lignes fassent le total). Montants en FCFA, nombres JSON (au centime près pour les dépenses).
3. Erreurs (vérifiées dans le code) :
   - 400 `VALIDATION_ERROR` « Période invalide : utilisez AAAA-MM (un mois) ou AAAA (une année). »
   - 400 « Indiquez soit une période, soit des dates de début et de fin, pas les deux. »
   - 400 « Date invalide : utilisez le format AAAA-MM-JJ. »
   - 400 « La date de début doit précéder la date de fin. »
   - 400 « La période ne peut pas dépasser 366 jours. »
   - 400 « L'année doit être comprise entre 2000 et 2100. »
   - 422 `VALIDATION_ERROR` « Établissement inconnu » (`storeId` qui n'est pas une boutique du compte)
   - 403 `FORBIDDEN` « Vous n'avez pas les droits nécessaires » (employé, gérant, lecteur)
   - 403 `MODULE_NOT_ENTITLED` « Ce module n'est pas inclus dans votre abonnement » ; 403 `MODULE_DISABLED_BY_OWNER` « Ce module est désactivé : le propriétaire peut l'activer dans Paramètres › Mes modules »
   Code : `FinanceController.java`, `FinancePeriods.java`, `FinanceSummary.java`, `FinanceSummaryService.java`.

**À implémenter côté mobile :**
1. Brancher `features/finance/api/finance.api.ts` sur `/api/v1/finance/summary` : envoyer `period` (mois `AAAA-MM`, année `AAAA`) ou garder `startDate`/`endDate` ; `shopId` → `storeId`.
2. Adapter les types : `byShop` → `byStore`, `shopId`/`shopName` → `storeId`/`storeName` ; afficher `categoryLabel` au lieu de traduire la clé `category`.
3. Garder l'écran réservé au propriétaire (403 pour les autres : message « réservé au propriétaire ») et gérer les 403 module avec le message du serveur (lien vers « Mes modules »).
4. Section « Par boutique » : afficher la ligne « Sans boutique » telle quelle (pas de lien vers une boutique, `storeId` nul) ; ne rien recalculer côté app.
5. Aucune donnée technique à l'écran (ni identifiant, ni clé brute).

**Critères d'acceptation mobile :**
- Given une facture de 11 925 FCFA dont 1 925 de TVA et une dépense « Loyer » de 3 000 en septembre, when l'écran Finance de septembre s'affiche, then CA HT 10 000, dépenses 3 000, résultat 7 000, marge 70 %.
- Given un mois sans rien le mois précédent, when l'écran s'affiche, then l'évolution n'affiche pas de pourcentage (`deltaPct` nul).
- Given une boutique choisie, when l'écran s'affiche, then seules ses ventes et dépenses apparaissent et la section « Par boutique » est masquée.
- Given un employé connecté, when il ouvre Finance, then le message « réservé au propriétaire » s'affiche.
- Mise en place : `curl -H "Authorization: Bearer $TOKEN" "$API/api/v1/finance/summary?period=$(date +%Y-%m)"`.

**Clé de suivi mobile :** `m20-11-module-finance`.

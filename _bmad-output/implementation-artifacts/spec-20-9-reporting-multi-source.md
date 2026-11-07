---
title: '20-9 Multi-source reporting without double counting (R4)'
type: 'feature'
created: '2026-11-07'
status: 'in-review'
baseline_revision: 'e1862565157a106d3d695924d98bf7c5e19404ee'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-5-remise-livraison-tva-echeance.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** every Keevo revenue figure (owner dashboard, daily/weekly reports, profitability, seller ranking, exports) reads `sales` only, each consumer with its own copy of `status = 'COMPLETED'`. A Bref-style merchant who works with invoices (invoicing module, 20.3–20.5) sees a revenue of 0, and a consumer that would simply add invoices would count twice every POS sale already bridged to an automatic invoice (`source_sale_id`). FUS-FR24 / R4.

**Approach:** one revenue source in the reporting module — `RevenueQueryPort`, two derived tables (revenue documents, revenue lines) built in one place: COMPLETED sales ∪ issued/paid invoices with `source_sale_id IS NULL` (quotes, drafts, cancelled, soft-deleted, superseded excluded) ∪ credit notes of those invoices as negative rows. Every consumer reads it instead of `sales`; the union exists nowhere else.

## Boundaries & Constraints

**Always:**
- **Definition (R4):** revenue = Σ `sales.total_amount` (status COMPLETED) + Σ `invoices.total_ttc` (kind INVOICE, status ISSUED or PAID, `source_sale_id IS NULL`, `deleted_at IS NULL`, `superseded_at IS NULL`) − Σ `credit_notes.total_ttc` of those same invoices. Delivery fee is inside `total_ttc` (20.5), as in Bref (`i.total`). A quote (kind QUOTE) never counts; its converted invoice counts once.
- **Dates (FUS-NFR9):** a sale counts at `occurred_at` (WAT windows, unchanged); an invoice at its civil `issue_date` (WAT midnight of that date — the Bref rule `i.date`); a credit note at its own `issued_at` (deduction on the day it was issued). Africa/Lagos = Africa/Douala = UTC+1.
- **Transactions:** a sale or an invoice counts 1 in counts and averages (Bref counts invoices); a credit note counts 0.
- **Seller:** a sale's `employee_id`; an invoice's `created_by` (Bref `userId`); a credit note is deducted from the seller of its invoice.
- **Store:** invoices carry no store today → documents appear in every all-stores figure and in no per-store figure (store overview, store comparison, per-store filters, store-scoped daily/weekly closure reports). Same rule everywhere, in the source.
- **Lines (per-product figures):** sale lines; invoice lines (`total_ttc` of the line, `description` as name, `product_id` when set by 20.5); credit-note lines negative, product taken from the credited invoice line. A line without product counts in line revenue but never in a per-product margin; profitability exposes it as « marge inconnue » through a new additive summary endpoint.
- **Module autonomy (review 1):** a branch is read when its data exists, not according to the module's current activation: sales always (foundation tables); invoices and credit notes when the invoicing tables are in the tenant schema (`invoice_items.product_id`, invoicing V11+, one catalogue lookup, cached per tenant once found). A tenant that never used invoicing reads sales only; an owner who switches invoicing off keeps the revenue he invoiced. Reporting never provisions a module and never answers 503 for one. Store-scoped figures (a store filter or a store-joined query) read the sales-only relations (`storeScoped()`) and never touch invoicing. Each figure resolves the source once (`current()` returns documents + lines together).
- **Credit notes of bridged invoices (review 1):** a manual credit note (`source_event IS NULL`, IssueCreditNoteUseCase) issued on an invoice bridged from a sale is deducted — the sale stays COMPLETED; automatic ones (`source_event` set: sale cancelled / corrected) are not.
- **Windows (review 1):** every dashboard total and series ends before tomorrow WAT (a post-dated invoice inflates nothing); average basket is 0 when revenue ≤ 0.
- **Additive /api/v1:** response shapes unchanged; values change only where documents were missing. New: `GET /api/v1/reporting/profitability/summary`.
- **Exports:** the revenue-bearing export is `reports` (stored daily/weekly reports, built from the source); the `sales` / `sale_items` / `day_closures` datasets are journals, not KPIs. No new export dataset here: the dataset catalogue is mirrored by the Flutter app contract test (`keevo/contracts/export-datasets.json`), which this backend-only story must not touch — a « Chiffre d'affaires » dataset on the source is routed to **22.12** (RN exports).

**Never:**
- No consumer re-writes the union or the status filters; no SQL view (invoicing tables exist only in tenants that provisioned the module).
- No per-store / per-tenant / per-row query loops; no unbounded load in Java (aggregation in SQL).
- No change to invoicing writes, no store attribution of documents (routed, see Design Notes), no change to the POS cash-closure figures (cash / mobile money / pending stay sales-only by nature).
- No public Flyway migration.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| POS only | 2 COMPLETED sales 1 000 + 2 000, invoicing inactive | CA 3 000, 2 transactions | — |
| Documents only | invoices 10 000 (ISSUED) + 5 000 (PAID), pos inactive | CA 15 000, 2 transactions | — |
| POS → invoice bridge | sale 3 000 + its automatic invoice 3 000 (`source_sale_id`) | CA 3 000 (not 6 000) | — |
| Credit note | invoice 10 000, credit note 4 000 issued today | CA 6 000, 1 transaction | — |
| Quote | quote 50 000 (DRAFT or CONVERTED) | not counted; converted invoice counted once | — |
| Cancelled / draft / deleted | invoice CANCELLED, DRAFT, soft-deleted; sale CANCELLED | not counted | — |
| Line without product | invoice line 8 000 without `product_id` | in CA and line revenue; absent from per-product list; summary `revenueWithUnknownMargin` 8 000 | — |
| Store filter | storeId given | sales of that store only (documents have no store) | — |
| Tenant without invoicing tables | invoicing never provisioned (or provisioning failed) | sales only | no error, never 503 |
| Manual credit note on a bridged invoice | sale 3 000 + its invoice, manual credit note 1 000 | CA 2 000 | — |
| Post-dated invoice | issue date tomorrow | not in today / month / all time | — |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/java/com/keevo/` unless stated.
- New `reporting/revenue/` -- `domain/port/out/RevenueQueryPort` (the single source), `adapter/out/persistence/RevenueRelations` (the SQL of the union, pure), `adapter/out/persistence/ProvisionedRevenueSource` (branches from the tables present, store-scoped = sales only).
- Revenue consumers (all switched to the port):
  - `reporting/dashboard/application/service/DashboardService` -- today/yesterday/month/period totals, counts, average basket, top/worst products, day/week/month/year charts, store overviews (N+1 loop replaced by grouped queries).
  - `reporting/report/application/service/EndOfDayReportBuilder` -- daily store closure report: count, revenue, cash/mobile money split, top 3, employee breakdown (store-scoped → sales only by the store rule).
  - `reporting/report/application/service/WeeklyReportBuilder` -- weekly store report, same fields.
  - `reporting/report/application/service/MultiStoreSummaryService` -- sums the stored daily reports (inherits).
  - `reporting/profitability/adapter/out/persistence/JdbcProfitabilityRepository` -- product list, store comparison, daily margin, top store; new revenue split for the summary.
  - `reporting/leaderboard/adapter/out/persistence/JdbcSellerLeaderboardRepository` -- seller ranking.
  - `reporting/report/adapter/out/export/ReportsExportDatasetProvider` -- exports stored reports (inherits).
- Not switched here (stated so the list is complete): `commerce/sale/adapter/out/export/Sales*/SaleItems*/DayClosures*ExportDatasetProvider` (sales journals, not KPIs), `commerce/sale/application/service/DayClosureSummaryBuilder` (cash drawer), `identity/employee/adapter/out/persistence/JdbcEmployeeActivityRepository` (activity). Revenue figures outside reporting, **routed**: `crm/contact/adapter/out/export/ClientsExportDatasetProvider` (`total_spent`, `sales_count` from sales only) and `messaging/notification/application/scheduler/SalesTrendDetectionScheduler` (sales-trend alert) → **`20-14-boutique-des-documents`** (they need documents attributed to a store / client first); `admin/analytics/application/service/AdminAnalyticsService` (GMV) and `admin/revenue|operations` → **20.13**.
- Data: invoicing `db/modules/invoicing/V1,V3,V4,V7,V8,V11` (`invoices`, `invoice_items`, `credit_notes`, `credit_note_items`); pos `db/modules/pos/V1__pos_baseline.sql` (`sales`, `sale_items`, index `(store_id, occurred_at)`).
- Profitability REST: `reporting/profitability/adapter/in/rest/ProfitabilityController` (+ summary DTO), `domain/port/in/GetProductProfitabilityUseCase`, `application/service/ProfitabilityService`.
- Boundaries: `reporting/package-info.java` (allowed: commerce/identity/store api, shared) — the established cross-module read is SQL on tenant tables in the request's search_path (`sales`, `employees`, `products`, `stores` already); no Java dependency on invoicing is added. `ModuleRegistry` / `ModuleProvisioner` are `shared.module`.
- Bref (read-only): `api-bref/src/dashboard/owner-dashboard.service.ts` L85 (`SALE` = not deleted, type sale, not cancelled), L170 (Σ `i.total` on `i.date`), L281 (per shop), L293 (per `userId`), L300 (per item name).

## Tasks & Acceptance

**Execution:**
- `db/modules/invoicing/V14__revenue_indexes.sql` -- partial index on the invoice civil-date expression for counted invoices, index `credit_notes(issued_at)` -- the two new filters of the source.
- `reporting/revenue/**` -- port, relations, adapter (tables present → branches).
- `db/modules/pos/V2__sales_completed_occurred_at_index.sql` (review 1) -- partial index `sales(occurred_at) WHERE status = 'COMPLETED'` for the all-stores windows (the `(store_id, occurred_at)` index only serves store-scoped ones).
- Consumers above -- read the port; counts become `SUM(doc_count)`; product lists ignore lines without product; dashboard store overviews set-based.
- Profitability summary use case + endpoint + DTO.
- Tests: module-gating unit test; acceptance test on PostgreSQL (tenant schema built from the foundation scripts + invoicing V1–V14) running the real consumers on the scenarios, the index check and the Bref fixture harness; existing reporting tests kept green.

**Acceptance Criteria:**
- Given the scenarios of the matrix on one tenant, when the dashboard, the seller ranking, the product profitability and its summary are computed, then each shows the R4 figures and the bridged sale is counted once.
- Given a tenant without invoicing (V1 Keevo), when any reporting endpoint is called, then its values are unchanged.
- Given the synthetic Bref fixture, when its monthly sales totals are computed with Bref's rule and with the Keevo source on the same documents, then they are equal (the prod-copy comparison stays with 24.12).

## Spec Change Log

- 2026-11-08 — review 1 (coordinator): branch selection moved from module activation + `ensureProvisioned` to « tables present » (reporting never provisions, never 503 — the rebase regression came from a duplicate invoicing V13 left in `target/classes` by the rename, surfaced as 503 through `ensureProvisioned`); one port call per figure (`current()` / `storeScoped()` → documents + lines); manual credit notes of bridged invoices deducted; dashboard windows bounded before tomorrow, basket 0 when revenue ≤ 0, top/worst grouped by product (worst `HAVING SUM(quantity) > 0`), share total once; summary margin 0 when cost ≤ 0, unknown lines = unknown-revenue rows, explicit store filters, 400 on bad / inverted dates, injected Clock; pos V2 partial index; routing of client totals / trend alert (20-14) and GMV (20.13). KEEP: the parameter-free UNION ALL relations and their pushed-down windows.

## Review Triage Log

### 2026-11-08 — review 1 (coordinator, 11 items)
- verdicts: 11 patch, 0 reject, 0 defer — every item fixed in `fix(20-9)`.

## Design Notes

**Pattern:** *single query object behind a port* — `RevenueQueryPort.documents()` / `lines()` return a parameter-free derived table (`(SELECT … UNION ALL …)`) that consumers put in their `FROM` and filter / group as before (`occurred_at`, `store_id`, `employee_id`, `payment_mode`, `total_amount`, `doc_count`; lines add `product_id`, `product_name`, `quantity`, `subtotal`, `price_amount`). Columns keep the `sales` names so each consumer's SQL keeps its shape (minimal churn, same tests). The adapter decides which branches exist (module gating) — a consumer never knows the sources. Columns of the documents relation:

```sql
SELECT 'SALE' source, s.id doc_id, s.store_id, s.employee_id, s.occurred_at, s.created_at, s.payment_mode,
       s.total_amount::bigint total_amount, 1 doc_count FROM sales s WHERE s.status = 'COMPLETED'
UNION ALL SELECT 'INVOICE', i.id, NULL, i.created_by, (i.issue_date::timestamp AT TIME ZONE 'Africa/Lagos'), …, i.total_ttc, 1
UNION ALL SELECT 'CREDIT_NOTE', cn.id, NULL, i.created_by, cn.issued_at, …, -cn.total_ttc, 0  -- invoice counted as above
```

**Cost (FUS-NFR11):** PostgreSQL pushes the consumers' window predicates into each `UNION ALL` branch: sales use the new partial `idx_sales_completed_occurred_at` (pos V2) for all-stores windows and `(store_id, occurred_at)` for store-scoped ones; invoices use the new partial expression index on the civil date (only counted invoices are in it); credit notes the new `issued_at` index; invoice / credit lines join on the existing unique `(invoice_id, position)` and `(credit_note_id, position)` keys. Every figure is one aggregate query — no loop: the dashboard goes from 9 + 6×stores queries to a fixed 20 (store overviews: 2 grouped queries + 4 grouped chart queries for all stores). Branch selection costs one catalogue lookup per resolution until the invoicing tables are found, then nothing (cached per tenant); each figure resolves the source once; store-scoped figures need no lookup. Verified: with sequential scans off, `EXPLAIN` of a window on the source shows the window pushed into every branch — sales on their `occurred_at` index, invoices on `idx_invoices_revenue_day`, credit notes filtered on `issued_at` (on empty tables the planner reaches them through the invoice index; `idx_credit_notes_issued_at` serves large tables) (acceptance test).

**Routed (not in this story):** documents carry no store, so per-store figures and the store-scoped closure reports show sales only. Writing `invoices.store_id` at creation (current establishment) and importing Bref `shopId` belong to **24.4** (import of documents — it needs it for the per-store reconciliation of 24.8) — once the column exists, only `RevenueRelations` changes. A « Chiffre d'affaires » export dataset on the source → **22.12**. Client totals (`ClientsExportDatasetProvider`) and the sales-trend alert → **`20-14-boutique-des-documents`**. Platform KPIs (`AdminAnalyticsService` GMV) without double counting → **20.13**. KPI comparison on the prod copy (AC2) → **24.12** (harness in the acceptance test).

## Verification

**Commands:**
- `kmvn.sh -o -q test -Dtest='com/keevo/reporting/**/*Test*'` -- expected: pass
- same for `com/keevo/invoicing/**` (V14) and `com/keevo/architecture/**` -- expected: pass

## Auto Run Result

Status: in-review (no review pass run in this session — `followup_review_recommended: true`).

**Change:** one revenue source for all reporting (`RevenueQueryPort`, `RevenueRelations`, `ProvisionedRevenueSource`): COMPLETED sales ∪ ISSUED/PAID invoices with `source_sale_id IS NULL` (quotes, drafts, cancelled, deleted, superseded excluded) − credit notes of those invoices; invoice on its civil issue date, credit note on its own date; transactions = sales + invoices. Branches follow the tables present (sales always, documents when the invoicing tables exist; never provisions, never 503; store-scoped figures sales only). Consumers switched: owner dashboard (all totals, counts, averages, top/worst products, charts; store overviews now a fixed set of grouped queries instead of 6 queries per store), daily and weekly store reports (revenue, counts, cash/mobile money, top products, team), seller ranking (invoices by creator, credit notes from their invoice's seller), profitability (product list, store comparison, daily margin, top store). New additive endpoint `GET /api/v1/reporting/profitability/summary` (revenue, known-margin part, « marge inconnue » part). Invoicing V14: partial expression index on the counted invoices' civil date, `credit_notes(issued_at)`.

**Files:** `db/modules/invoicing/V14__revenue_indexes.sql`; `reporting/revenue/domain/port/out/RevenueQueryPort`, `reporting/revenue/adapter/out/persistence/{RevenueRelations,ProvisionedRevenueSource}`; `DashboardService`, `EndOfDayReportBuilder`, `WeeklyReportBuilder`, `JdbcSellerLeaderboardRepository`, `JdbcProfitabilityRepository`, `ProfitabilityRepository`, `ProfitabilityService`, `ProfitabilityController`, new `ProfitabilitySummary`, `GetProfitabilitySummaryUseCase`, `ProfitabilitySummaryDto`; `backend/docs/entitlements/endpoints.md` (regenerated). Tests: new `RevenueSourceAcceptancePostgresIntegrationTest` (10), `ProvisionedRevenueSourceTest` (7), `FixedRevenueSource` double, summary controller test; constructors updated in 6 reporting tests; invoicing version pins 12 → 13 in 3 tests.

**Verification (serialized wrapper, one package at a time):**
- reporting: 440 tests, 1 failure (index-plan assertion too strict on empty tables) → fixed, `reporting/revenue` re-run 17/17 green.
- invoicing: 308 tests, 4 failures (latest-version pins 12 and downgrade simulations not deleting history 13) → fixed, the 3 classes re-run 29/29 green.
- architecture: 122 tests, 1 failure (generated `endpoints.md` out of date) → regenerated, `EndpointEntitlementCoverageTest` green.
- AC2: harness on the synthetic Bref fixture (owner A: Sep 1 500, Oct 1 193, Nov 1 193 — Bref rule = Keevo monthly chart). The prod-copy comparison stays with 24.12.

**Review 1 fixes (2026-11-08):** see Spec Change Log. Verification: reporting 458 tests 0 failures (acceptance 17); invoicing 324 tests, 1 pin (13 → 14) fixed, `InvoiceSyncFullContextTest` re-run 15/15; architecture 122/122; `shared/infrastructure/persistence` (tenant shape, foundation migrations, pos V2) 124/124.

**Residual risks:** (1) documents have no store: per-store figures and the store closure reports stay POS-only until 24.4 adds `invoices.store_id`; (2) revenue of a tenant whose invoicing tables are below V11 (never touched since 20.5) is sales-only until its next invoicing call provisions them; (3) product figures use line amounts before document discount/delivery (as Bref's top items), so their sum may differ from the document revenue; (4) invoicing V14 number may collide with a parallel story's invoicing migration — renumber at merge if so.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée, commit `0a4c0edd` (branche de la story 20.9), à fusionner dans `refonte/modulaire` / `develop`. Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10 (aucune nouvelle opération de synchro ici).

**Ce qui est fait côté API :** le chiffre d'affaires de tous les écrans de rapports compte maintenant les ventes caisse et les factures, chacune une seule fois : une vente caisse qui a produit une facture automatique n'est comptée qu'une fois ; les devis, brouillons et factures annulées ne comptent pas ; un avoir est déduit le jour où il est émis. Une facture compte à sa date de facture (date civile, comme Bref). Le nombre de transactions compte les ventes et les factures (pas les avoirs). Les factures n'ont pas encore de boutique : elles apparaissent dans les chiffres « toutes boutiques », pas dans les chiffres d'une boutique. Les factures déjà émises restent comptées même si le module Facturation est ensuite désactivé. Un avoir saisi à la main sur la facture automatique d'une vente caisse est déduit ; les avoirs automatiques (vente annulée ou corrigée) ne le sont pas, la vente le reflète déjà. Une facture datée de demain n'entre dans aucun chiffre avant sa date.

**À utiliser :**
1. Mêmes routes qu'avant, mêmes champs, valeurs complétées : `GET /api/v1/dashboard/summary` (CA du jour / d'hier / du mois, totaux de période, panier moyen, graphiques jour/semaine/mois/année, meilleurs et moins bons produits), `GET /api/v1/reporting/leaderboard` (un vendeur cumule ses ventes caisse et les factures qu'il a créées), `GET /api/v1/reporting/profitability/products` et `/products/{id}` (les lignes de facture liées à un produit du catalogue comptent), `/profitability/stores` (ventes caisse par boutique), rapports quotidiens et hebdomadaires (par boutique : ventes caisse).
2. Nouveau : `GET /api/v1/reporting/profitability/summary?from=yyyy-MM-dd&to=yyyy-MM-dd&storeId=` (OWNER, module Rentabilité) → `data` : `totalRevenue` (CA de la période, même chiffre que le tableau de bord), `revenueWithKnownMargin`, `costOfKnownMargin`, `grossMarginXaf`, `marginPercent` (marge sur coût, même formule que la liste produits), `revenueWithUnknownMargin` et `unknownMarginLines` (lignes de facture sans produit : comptées dans le CA, sans marge). Les montants de lignes sont pris avant remise et livraison du document : les deux parts n'additionnent pas forcément `totalRevenue`.
Code : `RevenueRelations.java`, `ProfitabilityController.java`, `ProfitabilitySummaryDto.java`.

**À implémenter côté mobile :**
1. Écran Rentabilité : un encadré « Marge inconnue » avec `revenueWithUnknownMargin` et le nombre de lignes, affiché seulement s'il est > 0, avec un court texte « lignes de facture sans produit du catalogue ».
2. Aucun calcul de CA côté app : afficher les valeurs du serveur (ne pas additionner ventes et factures localement, sinon double comptage).
3. Filtre boutique : prévenir que les factures n'ont pas de boutique (elles ne sont visibles que sur « Toutes les boutiques »).
4. Aucune donnée technique à l'écran.

**Critères d'acceptation mobile :**
- Given une vente caisse de 3 000 qui a produit une facture automatique, when le tableau de bord s'affiche, then le CA du jour vaut 3 000 (pas 6 000).
- Given une facture de 10 000 et un avoir de 4 000 émis aujourd'hui, when le tableau de bord s'affiche, then le CA du jour vaut 6 000 et 1 transaction.
- Given une facture avec une ligne sans produit de 8 000, when l'écran Rentabilité s'affiche, then « Marge inconnue : 8 000 FCFA (1 ligne) » apparaît.
- Mise en place : `curl -H "Authorization: Bearer $TOKEN" "$API/api/v1/reporting/profitability/summary?from=$(date +%Y-%m-01)&to=$(date +%F)"`.

**Clé de suivi mobile :** `m20-9-reporting-multi-source`.

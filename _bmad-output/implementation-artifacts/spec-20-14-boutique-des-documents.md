---
title: '20-14 Store of documents and last revenue consumers'
type: 'feature'
created: '2026-11-08'
status: 'done'
baseline_revision: 'f912cca68d04a0835a055a13ea07bb9aed25f6c8'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-9-reporting-multi-source.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** invoices carry no establishment, so every store-scoped revenue figure of 20.9 (end-of-day and weekly store reports, store overviews, store comparison, store-filtered seller ranking and profitability) shows POS sales only: a store that invoices sees a revenue of 0 (FUS-FR24). Two revenue readers outside reporting still read `sales` alone: the clients export (`total_spent`, `sales_count`, `last_purchase`) and the hourly sales-trend alert.

**Approach:** a nullable `invoices.store_id` (invoicing V16) written at creation from the actor (member → assigned store; owner → requested active store, else the oldest active shop), copied by quote conversion, taken from the sale by the POS bridge; credit notes follow their invoice. The revenue source reads it, so store-scoped figures include documents once; the source port moves to the shared core so crm and messaging read it too.

**Décision utilisateur 2026-11-08 — visibility of documents per role (as Bref `invoice.controller.ts` `assertCanAccess` / `assertListScope` / `findAll`):** an EMPLOYEE sees and acts only on the documents they created (invoices, quotes, credit notes through their invoice), a MANAGER or VIEWER on those of their assigned store (the VIEWER stays read-only by its permissions), the OWNER on everything; a member without store is refused.

## Boundaries & Constraints

**Always:**
- **Attribution at creation only** (REST `POST /invoices`, sync `CREATE_INVOICE`): a store member (EMPLOYEE / MANAGER / VIEWER) → its assigned store (another `storeId` requested → 403 « Vous ne pouvez créer un document que dans votre boutique »; its store deactivated → 403 « Votre boutique est désactivée : demandez au propriétaire de la réactiver pour créer des documents. »; no assigned store → none, and any `storeId` sent → 403 with its own message); an OWNER → the requested `storeId` when it is an active store of the tenant (else 422 `VALIDATION_ERROR`, field `storeId`, « Boutique inconnue ou désactivée » — the one message of `StoreQueryPort.UNKNOWN_OR_INACTIVE_STORE`, crm included), else the tenant's default store (`StoreQueryPort.defaultStore`: a shop before a warehouse, then the oldest, then the id; none when the tenant has no active store — the same policy for new clients). A re-sent creation (same id) returns the first invoice unchanged. REST and sync answer with the same French message (sync: `REJECTED` + `conflictData.message`).
- **Kept by every later step:** draft edits (`PUT`, `UPDATE_INVOICE` ignore `storeId`), derivation (20.6), issue, payment, cancellation, sending. The client derived (20.6) from an owner's invoice goes to the invoice's store (still active), else the default store. Quote conversion copies the quote's store while it is active, else the default store. `Invoice.inStore` enforces it: only a `DRAFT` without store gets one, another store → refused. POS bridge (sale completed, sale corrected) → the sale's `storeId`. Credit notes have no column: they read the store of their invoice.
- **Existing rows** keep `store_id` NULL: no backfill, totals and numbering untouched. They count in every all-stores figure as today and in no per-store figure.
- **Revenue (R4 unchanged):** the documents relation gains `store_id` from the invoice (credit notes: from their invoice), `client_id`, and `recorded_at` (sale `occurred_at`, invoice / credit note `issued_at` — the instant, for hourly figures); `storeScoped()` = `current()` once the column exists. Tenant-wide figures unchanged.
- **Module autonomy (20.9):** the branches follow the tables present — invoicing V16 present → documents with their store; V11–V15 → documents in all-stores figures only (store NULL), store-scoped figures sales only; no invoicing → sales only. Never provisions, never 503.
- **Clients export:** per client, `sales_count` = sales + invoices (credit notes 0), `total_spent` = Σ revenue (credit notes deducted), `last_purchase` = latest sale or invoice (`recorded_at`). Columns and labels unchanged.
- **Sales trend:** per store, transactions (sales + invoices) of the last full hour vs the average of the same hour over the 3 previous days, in one grouped query per tenant.
- **Additive /api/v1:** request `storeId` optional; responses gain `storeId` and `storeName` (null when none). No new sync operation, no event change.

- **Visibility (décision utilisateur 2026-11-08):** one policy (`DocumentAccess` + `DocumentScope`, same pattern as `ClientVisibility` / `ExpenseStoreScope`) for REST, sync and the pull. Lists (`GET /invoices`, every `kind` / `paymentState` filter) and the pull add one indexed equality (`created_by = actor` or `store_id = assigned store`). Every read and action on one document (get, PDF, update / delete draft, issue, convert, cancel, pay / add / settle payment, credit note, credit note read / PDF, send, public link, rotation, layout, signatures; REST and sync) checks the document first: out of scope → 404 `INVOICE_NOT_FOUND` (`CREDIT_NOTE_NOT_FOUND` for a credit note) — the Keevo convention of clients (20.2) and expenses (20.10), Bref answers 403. A creation reusing the id of a document the actor does not see → 404 (never a replay). Explicit `storeId` list filter: OWNER only, anyone else → 403 « Seul le propriétaire peut filtrer les documents par boutique. » (Bref `onlyOwnerShopId`). Member without store → 403 « Votre compte n'est rattaché à aucune boutique : demandez au propriétaire de vous en attribuer une. » (Bref `noShopAssigned`) on lists, reads, actions and creation; its pull reads no document. System work without actor (POS bridge, jobs) is unrestricted.

**Never:**
- No backfill of old invoices' store, no change of totals, numbers, counters or quotas.
- No tombstones when a puller's role or store changes (documents never change creator or store) — routed to 22.5.
- No public Flyway migration; no change to invoicing V15 (reserved for 20-7).
- No per-store / per-row query loop; no UUID or raw code in a label or an export.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Employee of B | EMPLOYEE assigned to B creates and issues 10 000 | `storeId` = B; B's end-of-day report: revenue +10 000, +1 transaction; tenant totals +10 000 once | — |
| Employee asks another store | EMPLOYEE of B sends `storeId` = A | nothing created | 403 French message |
| Owner with storeId | OWNER sends `storeId` = B (active) | `storeId` = B, `storeName` « Boutique B » | unknown / inactive → 422 `VALIDATION_ERROR` field `storeId` |
| Owner without storeId | OWNER, stores A (oldest) and B | `storeId` = A | no active store → `storeId` null |
| Quote conversion | quote of B converted (REST or `CONVERT_QUOTE`) | invoice `storeId` = B | — |
| POS bridge | sale completed in B | automatic invoice `storeId` = B; revenue counts the sale once | — |
| Credit note | invoice of B, credit note 4 000 | B's figures −4 000 on the credit note's day | — |
| Old invoice | `store_id` NULL | all-stores figures only | — |
| Client export | client with 1 sale 3 000 + 1 invoice 10 000 − credit note 4 000 | count 2, total 9 000, last purchase = latest of the two | — |
| Tenant at invoicing V14 | no `store_id` column yet | documents in all-stores figures, store figures sales only | no error |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/` unless stated.
- `resources/db/modules/invoicing/V16__invoice_store.sql` -- new column + partial indexes.
- `java/com/keevo/invoicing/invoice/domain/model/Invoice.java` -- new last component `storeId`, kept by every transition; `inStore(UUID)`.
- `invoicing/invoice/application/usecase/DocumentStoreResolver.java` (new) -- the store of a new document from the actor (`CurrentActor`) and `StoreQueryPort`; the store names of a page (`namesOf`).
- `ManageDraftInvoiceUseCase` (creation), `Invoice.conversionDraft` (copy, used by `ConvertQuoteUseCase`), `InvoiceSaleUseCase.paidInvoice` + `SaleCreditNoteUseCase` (sale's store; a corrected sale's new invoice keeps the replaced one's store).
- `invoicing/.../adapter/out/persistence/JdbcInvoiceRepository` -- insert / read `store_id` (never updated).
- `invoicing/.../adapter/in/rest/dto/InvoiceRequestDto`, `InvoiceResponseDto`, `InvoiceController`, `adapter/in/sync/InvoiceDeltaProvider` -- `storeId` in, `storeId` + `storeName` out.
- `store/api/StoreQueryPort` (+ `StoreApiAdapter`, `StoreRepository.findAllById`) -- `namesOf(tenant, ids)`: one query.
- `shared/capability/revenue/RevenueQueryPort` -- moved from `reporting.revenue.domain.port.out` (core capability: crm and messaging read it without a module edge; crm → reporting would close a cycle through commerce).
- `reporting/revenue/adapter/out/persistence/RevenueRelations`, `ProvisionedRevenueSource` -- store, client, recorded_at columns; three levels by tables present.
- `crm/contact/adapter/out/export/ClientsExportDatasetProvider` -- reads the source.
- `crm/api/DocumentClientInput` + `DeriveClientFromDocumentUseCase` -- the invoice's store for a client derived from an owner's invoice; `invoicing/.../DocumentDerivationService` passes it.
- `messaging/notification/application/scheduler/SalesTrendDetectionScheduler` + new `domain/port/out/StoreActivityRepository` + `adapter/out/persistence/impl/JdbcStoreActivityRepository` -- one grouped query on the source.
- Consumers already on `storeScoped()` (no change): `EndOfDayReportBuilder`, `WeeklyReportBuilder`, `DashboardService` store overviews, `JdbcProfitabilityRepository`, `JdbcSellerLeaderboardRepository`.

## Tasks & Acceptance

**Execution:**
- `V16__invoice_store.sql` -- `store_id UUID NULL`; `idx_invoices_store_revenue_day (store_id, civil day) WHERE counted`; `idx_invoices_counted_issued_at (issued_at) WHERE counted` (hourly trend, all stores of the tenant at once); `idx_invoices_client (client_id) WHERE issued/paid live` (client totals).
- Domain + persistence + resolver + use cases + DTOs as in the Code Map.
- Revenue source: columns, levels, port move.
- Clients export and trend on the source.
- Tests: resolver unit; use-case units (creation, conversion, bridge); PostgreSQL acceptance (store B end-of-day once, tenant unchanged, client export, trend counts, V14 tenant level); full-context REST (employee of B, owner storeId, conversion, `storeName`); version pins 14 → 16 and upgrade simulations.

**Acceptance Criteria:**
- Given an EMPLOYEE of store B issuing an invoice, when the end-of-day report of store B is built, then the invoice's revenue is in it once, and the tenant-wide figures are unchanged.
- Given an owner requesting store B, a converted quote of B and a sale of B bridged to an invoice, when each document is read, then its `storeId` is B and the bridged sale still counts once.
- Given a client with sales, invoices and a credit note, when the clients export runs, then its purchases count and total include the invoices minus the credit note.

## Spec Change Log

- 2026-11-08 — review 1 (coordinator): revenue levels below V16 cached with a 5-minute TTL (level only goes up); clients export counted without the revenue lateral (`countFromClause`); one default-store policy (`StoreQueryPort.defaultStore`, one query) and one « unknown or inactive store » message for documents and clients; derivation absorbs only that refusal; `Invoice.inStore` refuses an overwrite or a non-draft; store activity counts `doc_count > 0`, the trend job takes a `Clock` and isolates a tenant's activity failure; end-of-day employee list from the store-scoped source; member with an inactive store / without store, conversion of a quote of a deactivated store; REST store refusals through the global mapping, sync rejections carry the French message; delta provider always gets its store resolver. KEEP: attribution at creation only, the three source levels, the shared-capability port.

## Review Triage Log

### 2026-11-08 — Review pass (5 layers: blind, edge case, verification gap, intent, patterns)
- verdicts: 37 findings — high 0, medium 12, low 15, false 3, maybe-false 7 (grouped by root cause)
- findings:
  - `[medium]` `[patch]` Patterns/blind/edge: levels below V16 no longer cached (catalogue query per figure) — 5-minute TTL for lower levels, V16 cached for good, level never goes down.
  - `[medium]` `[patch]` Patterns: clients export COUNT computed the revenue lateral for every client — `countFromClause()` hook.
  - `[medium]` `[patch]` Patterns/blind: two default-store policies (shop-first vs any store), two messages — `StoreQueryPort.defaultStore`, one French message.
  - `[medium]` `[patch]` Blind/edge: ownerStore caught every DomainException — only the unknown-store refusal.
  - `[medium]` `[patch]` Blind: `Invoice.inStore` could overwrite a store on issued documents — refused in the domain, test.
  - `[low]` `[patch]` Patterns/blind: store activity scanned credit notes — `doc_count > 0`.
  - `[low]` `[patch]` Blind: trend scheduler read the system clock — injected Clock.
  - `[medium]` `[patch]` Edge: one tenant's activity failure aborted the run — per-tenant WARN.
  - `[medium]` `[patch]` Edge: employees with invoices only got no end-of-day report — distinct employees from the source.
  - `[medium]` `[patch]` Edge: member of an inactive store / storeless member with storeId / conversion of an inactive store's quote — 403 actionable messages, default store on conversion.
  - `[low]` `[patch]` Blind/patterns: controller-only try/catch, sync refusal message — global mapping (422) and `conflictData.message` in sync.
  - `[low]` `[patch]` Blind/patterns: nullable test constructors in production (delta provider, scheduler) — removed.
  - `[low]` `[patch]` Blind: stale javadocs (clients export, level javadoc, sync payload) — rewritten.
  - `[medium]` `[patch]` Verification: derived client store for an owner invoice in store B untested — full-context test.
  - `[medium]` `[patch]` Verification: corrected sale replacement invoice store untested — assertions added.
  - `[medium]` `[patch]` Blind/intent: sync refusals, weekly report and employee-only case untested — tests added.
  - `[maybe-false]` `[defer]` Verification: scheduler tenant routing only tested with a preset schema — routed to 17-9 (dashboards/reports use the same routing and are covered).
  - `[maybe-false]` `[defer]` Blind: invoice reads not filtered by store for members — routed to 19-5b.
  - `[low]` `[reject]` Blind: no backfill of bridged invoices from `sales.store_id` — sales belong to the pos module; documented.
  - `[low]` `[reject]` Blind: store rows do not sum to the tenant total — pre-V16 documents have no store; documented.
  - `[low]` `[reject]` Patterns: per-batch store lookup in sync — one tiny indexed query per operation (single defaultStore query now).
  - `[low]` `[reject]` Patterns: 29-argument Invoice record — pre-existing shape.
  - `[maybe-false]` `[reject]` Edge: V15/V16 ordering — merge order 20-7 then 20-14 (enforced by the orchestrator).
  - `[false]` `[reject]` Intent: tenant totals « unchanged » taken literally — the invoice is counted once; tenant-wide figures without stores are unchanged (AC-ii test).
  - `[low]` `[reject]` Intent: trend comparison window moved to the same hour — fixes a pre-existing off-by-one-hour bug, documented.


## Design Notes

**Patterns:** *creation policy* — `DocumentStoreResolver` is a small application service (strategy by actor kind: member / owner) consulted once per creation; the domain only carries the value (`Invoice.storeId`, kept by every transition like `sourceSaleId`). *Single query object behind a port* (20.9) is kept: one place (`RevenueRelations`) writes the union; the port moves to `shared.capability` (the core-capability pattern of 12.6a: interface in the core, implementation in reporting) so the clients export and the trend job are plain consumers.

**Cost (FUS-NFR11):**
- Creation: member → 0 query; owner → 1 indexed store read (`findById`) or 1 read of the active stores (a handful of rows). Conversion and bridge: 0 extra query (value copied).
- Store names: one `SELECT id, name FROM stores WHERE id = ANY(?)` per response or per list / delta page — never per invoice.
- Store-scoped windows: the store predicate and the window are pushed into each `UNION ALL` branch — sales on `(store_id, occurred_at)`, invoices on the new partial `idx_invoices_store_revenue_day`, credit notes on `idx_credit_notes_issued_at` then the invoice by primary key.
- Clients export: `LEFT JOIN LATERAL` on the source with `client_id = c.id` pushed into each branch → `idx_sales_client_id`, new partial `idx_invoices_client`, `idx_credit_notes_invoice`: O(page × log n), bounded pages.
- Trend: from one query per store × 2 to one grouped query per tenant (four one-hour windows, `recorded_at`): sales on the partial `idx_sales_completed_occurred_at`, invoices on the new partial `idx_invoices_counted_issued_at`, credit notes on `idx_credit_notes_issued_at` (they count 0) — four bitmap ranges per branch, grouped by store.
- Source resolution: one catalogue lookup per tenant every 5 minutes while the V16 column is missing, then none (cached for good; the level only goes up).
- Clients export count: the plain clients join (`countFromClause`), never the revenue lateral.
- Default store: one `LIMIT 1` query (`StoreQueryPort.defaultStore`), for documents and clients.

**Trend window fix:** the previous-days windows were the hour *after* the compared one (`[h − Nd, h − Nd + 1h)` against `[h − 1h, h)`); they now cover the same hour (`[h − 1h − Nd, h − Nd)`), as the job's description says.

**Not done on purpose:** no backfill of the existing bridged invoices from `sales.store_id` (that table belongs to the pos module; invoicing never reads it — they stay « no store »); per-store rows do not add up to the tenant total, since documents made before V16 have no store.

**Visibility cost:** one primary-key read of the document's creator and store per action (none for the owner); lists and the pull one equality served by the V16 indexes `idx_invoices_created_by_sync (created_by, updated_at, id)` / `idx_invoices_store_sync (store_id, updated_at, id)` (the pull keyset exactly; the list narrowed to the actor's documents before its sort).

**Routed (not in this story):** pull tombstones when a member's role or store changes → **22.5** (same item as the clients leaving a store's scope); the scheduler full-context test (sales-trend job on a real tenant) → **17-9**; filling `store_id` from Bref `shopId` on import → **24.4** (already stated in the epic); a « Chiffre d'affaires » export dataset → **22.12** (20.9); platform GMV → **20.13**.

## Verification

**Commands (serialized wrapper, one package at a time):**
- `kmvn.sh -o -q test -Dtest='com/keevo/<pkg>/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` for invoicing, reporting, commerce, crm, messaging, architecture, shared, sync -- expected: pass

## Auto Run Result

Status: in-review (no review pass run in this session — `followup_review_recommended: true`).

**Change:** invoices and quotes carry the store they were made in (`invoices.store_id`, invoicing V16, nullable, no backfill), written once at creation: a store member's own store (another store requested → 403), an owner's requested active store (unknown / inactive → 400 field `storeId`) or the oldest active shop; quote conversion copies the quote's store, the POS bridge takes the sale's store (a corrected sale's new invoice keeps the replaced one's), credit notes follow their invoice. `storeId` optional in `POST /invoices` and `CREATE_INVOICE`; `storeId` + `storeName` in every invoice response and in the pull (one store lookup per response / page). The revenue source reads the documents' store (three levels by tables present: V16 → documents with their store, also store-scoped; V11–V15 → all-stores only; none → sales), and gains `client_id` and `recorded_at`; its port moves to `shared.capability.revenue` so the clients export (purchases = sales + invoices − credit notes) and the sales-trend job (one grouped query per tenant, same-hour windows fixed) read it. An owner's client derived from an invoice (20.6) goes to the invoice's store when it is still active.

**Files:** `db/modules/invoicing/V16__invoice_store.sql`; invoicing `Invoice`, `DocumentStoreResolver` (new), `ManageDraftInvoiceUseCase`, `InvoiceSaleUseCase`, `SaleCreditNoteUseCase`, `DocumentDerivationService`, `JdbcInvoiceRepository`, `InvoiceRequestDto`, `InvoiceResponseDto`, `InvoiceController`, `InvoiceDeltaProvider`; store `StoreQueryPort`, `StoreApiAdapter`, `StoreRepository`, `StoreRepositoryAdapter`; `shared/capability/revenue/{RevenueQueryPort,package-info}` (moved from reporting); reporting `RevenueRelations`, `ProvisionedRevenueSource` + comment updates in the store-scoped consumers; crm `DocumentClientInput`, `DeriveClientFromDocumentUseCase`, `ClientsExportDatasetProvider`; messaging `StoreActivityRepository` (new), `JdbcStoreActivityRepository` (new), `SalesTrendDetectionScheduler`. Tests: new `InvoiceStoreFullContextTest` (5), `DocumentStoreResolverTest` (5); new cases in `RevenueSourceAcceptancePostgresIntegrationTest` (+4), `ProvisionedRevenueSourceTest` (rewritten, 6), `ManageDraftInvoiceUseCaseTest` (+2), `InvoiceSaleUseCaseTest` (+1), `InvoiceQuoteTest` (+1), `DeriveClientFromDocumentUseCaseTest` (+1), `SalesTrendDetectionSchedulerTest` (rewired on the port, +1), `SaleInvoicingFullContextTest` (bridge store), `InvoicingTermsMigrationPostgresIntegrationTest` (V16 on an existing tenant); pins 14 → 16 (`InvoicingMigrationPostgresIntegrationTest`: 15 scripts applied) and V16 in the two upgrade simulations; `FixedRevenueSource` (store flag), `ExportDatasetCatalogTest` (the revenue port is an allowed provider dependency).

**Verification (serialized wrapper, clean build, one package at a time):**
- invoicing: 338 tests, 0 failures (after 3 setup fixes in the new full-context test and 2 upgrade fixtures reading `store_id`).
- reporting: 455 tests, 3 failures (EXPLAIN assertions too strict on empty tables: the planner may take the V16 index, or any sales index) → relaxed to check the push-down, `RevenueSourceAcceptancePostgresIntegrationTest` re-run 21/21.
- commerce: 268 / 0. crm: 237 / 0 (+ `DeriveClientFromDocumentUseCaseTest` re-run 5/5 with the new case). messaging: 369 / 0. architecture: 122 / 0 (Modulith boundaries, ArchUnit rules, endpoint coverage unchanged). shared: 1 320 / 0. sync: 675 / 0. store: 128 / 0.
- Interim AC (`InvoiceStoreFullContextTest.employeeOfStoreB`): an EMPLOYEE of B issues 10 000 → B's end-of-day report +10 000 and +1 transaction, A's 0, tenant `todayCA` +10 000, store overview B 10 000.

**Review 1 fixes (2026-11-08):** see Spec Change Log. Verification after a clean build (serialized wrapper, one package at a time): invoicing 346 / 0, reporting 456 / 0, crm 238 / 0, messaging 370 / 0, commerce 268 / 0, store 128 / 0, architecture 122 / 0.

**Rebase on 20-7 + visibility per role (décision utilisateur 2026-11-08):** rebased on refonte/modulaire (dc300775: invoicing V15, public V57); the store names go through `StoreQueryPort.namesByIds` (20.10, now one IN query) and 20-7's `InvoiceResponseAssembler`; `DocumentAccess` / `DocumentScope` guard lists, the pull, every REST and sync action (V16 adds `idx_invoices_created_by_sync` / `idx_invoices_store_sync`). New tests: `InvoiceVisibilityFullContextTest` (4), `DocumentAccessTest` (7); four existing full-context tests where an employee acted on the owner's documents were moved to the employee's own documents or now expect 404. Verification after a clean build: invoicing 411 / 0, reporting 456 / 0, crm 238 / 0, commerce 268 / 0, messaging 370 / 0, architecture 131 / 0, sync 675 / 0, shared 1 320 / 0.

**Residual risks:** (1) invoicing V16 is applied after V14 with V15 absent: if 20-7's V15 lands after a tenant already reached V16, Flyway (`outOfOrder` off) will not run it — merge 20-7 first or renumber at merge (no production tenant has invoicing V14+ yet: the refonte is not live); (2) a tenant whose invoicing tables are below V16 keeps its level for 5 minutes (TTL, the level only goes up), so store figures may lag that long after V16 is provisioned; (3) stored daily / weekly reports are not recomputed: documents appear in store reports built from now on; (4) visibility per role is now in this story (décision utilisateur 2026-11-08); pull tombstones on a role / store change → 22.5; (5) `contracts/invoice-sync-payloads.json` was left unchanged (read by the Flutter app test): `storeId` is optional and documented below; (6) no backfill of existing bridged invoices from `sales.store_id` (pos table); (7) store rows do not add up to the tenant total (pre-V16 documents have no store); (8) the sales-trend job has no full-context test — routed to 17-9.

**Verification (orchestrator):** full suite package by package on the rebased branch (clean build): 6 436 tests, 0 failures. Live on keevo_dev (WhatsApp noop, FCM/Expo off), invoicing activated for the check then deactivated: invoice created with `storeId` → `storeName` « Boutique Test », issued `FAC-2026-000009`, `GET /invoices?storeId=` lists it, unknown store → 422 « Boutique inconnue ou désactivée », dashboard store overview todayCA 4 000 includes the invoice.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commits `b5b41077` + `676dc274` + `d1cdf583`, refonte/modulaire / develop).

**Ce qui est fait côté API :** chaque facture et chaque devis est rattaché à la boutique où il a été fait, une fois pour toutes à la création. Un employé (ou gérant, ou lecteur) crée toujours dans sa boutique. Le propriétaire choisit la boutique ; sans choix, c'est sa plus ancienne boutique active (une boutique avant un entrepôt). La conversion d'un devis garde la boutique du devis ; la facture automatique d'une vente caisse prend la boutique de la vente ; un avoir appartient à la boutique de sa facture. Les factures faites avant cette version n'ont pas de boutique : elles restent comptées dans « Toutes les boutiques » seulement. Conséquence : les rapports de fin de journée et hebdomadaires d'une boutique, l'aperçu par boutique du tableau de bord, la comparaison des boutiques, le classement des vendeurs et la rentabilité filtrés sur une boutique comptent maintenant les factures de cette boutique, une seule fois (le total « Toutes les boutiques » ne change pas). L'export Clients compte les factures dans « Nombre d'achats », « Total des achats » (avoirs déduits) et « Dernier achat ». L'alerte de tendance des ventes compte ventes et factures de chaque boutique.

**À utiliser :**
1. `POST /api/v1/invoices` et l'opération de synchro `CREATE_INVOICE` : champ optionnel `storeId` (identifiant de boutique). Employé : ne pas l'envoyer (ou envoyer la sienne) — une autre boutique → 403 « Vous ne pouvez créer un document que dans votre boutique » (synchro : `REJECTED`). Propriétaire : la boutique choisie ; inconnue ou désactivée → 422 `VALIDATION_ERROR`, champ `storeId`, « Boutique inconnue ou désactivée » ; absent → la boutique par défaut (une boutique avant un entrepôt, puis la plus ancienne). Employé dont la boutique est désactivée → 403 « Votre boutique est désactivée : demandez au propriétaire de la réactiver pour créer des documents. » ; employé sans boutique qui envoie un `storeId` → 403 « Votre compte n'est rattaché à aucune boutique : … ». En synchro, ces refus donnent `REJECTED` avec `reason` = `FORBIDDEN` ou `VALIDATION_ERROR` et le même message français dans `conflictData.message` ; rien n'est créé. Un devis converti après la désactivation de sa boutique donne une facture dans la boutique par défaut. `PUT /api/v1/invoices/{id}` et `UPDATE_INVOICE` ignorent `storeId` (un document ne change jamais de boutique).
2. Réponses de `GET /api/v1/invoices`, `GET /api/v1/invoices/{id}`, de toutes les actions (`issue`, `pay`, `cancel`, `convert`, `payments`, `send`) et lignes `invoices` de `GET /api/v1/sync/pull` : `storeId` (null si aucune) et `storeName` (nom de la boutique, null si aucune).
3. Mêmes routes de rapports, valeurs complétées : `GET /api/v1/dashboard/summary` (`storeOverviews[].todayCA` / `yesterdayCA`), `GET /api/v1/reporting/leaderboard?storeId=`, `GET /api/v1/reporting/profitability/products?storeId=`, `/products/{id}?storeId=`, `/stores`, `/summary?storeId=`, `GET /api/v1/reports` (rapports générés à partir de maintenant).
Code : `InvoiceRequestDto.java`, `InvoiceResponseDto.java`, `DocumentStoreResolver.java`, `RevenueRelations.java`.

**À implémenter côté mobile :**
1. Création de facture / devis par le propriétaire qui a plusieurs boutiques : un sélecteur « Boutique » (pré-rempli avec la boutique courante de l'app) qui envoie `storeId` ; employé : aucun sélecteur.
2. Fiche et liste des documents : afficher `storeName` (« Boutique : … ») quand il est présent ; rien quand il est null. Jamais l'identifiant.
3. Remplacer l'avertissement posé pour m20-9 sur le filtre boutique (« les factures n'ont pas de boutique ») par « Les factures faites avant la mise à jour n'apparaissent que dans Toutes les boutiques ».
4. Erreurs : afficher le message français du serveur (champ `error` en REST, `conflictData.message` en synchro ; 403 / 422 ci-dessus) ; en synchro, une opération `REJECTED` pour cette raison est montrée sur le document concerné.

**Critères d'acceptation mobile :**
- Given un employé de la boutique B, when il crée et émet une facture de 10 000, then la fiche affiche « Boutique : B » et le rapport de fin de journée de B affiche 10 000 de plus et 1 transaction de plus ; le CA « Toutes les boutiques » n'augmente que de 10 000.
- Given le propriétaire qui choisit la boutique B, when il convertit un devis de B, then la facture obtenue affiche « Boutique : B ».
- Given une facture créée avant la mise à jour, when le filtre boutique B est actif, then elle n'apparaît pas dans les chiffres de B mais reste dans « Toutes les boutiques ».
- Mise en place : `curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$API/api/v1/invoices" -d '{"client":{"name":"Awa"},"lines":[{"description":"Riz","quantity":1,"unitPrice":10000}],"storeId":"'$STORE_ID'"}'`.

**Visibilité par rôle (décision utilisateur 2026-11-08, comme Bref) :**
- Employé : ne voit et ne manipule que les documents qu'il a créés (factures, devis, avoirs de ces factures) — liste, fiche, PDF, encaissements, envoi, lien public, rotation, synchro.
- Gérant et lecteur : les documents de leur boutique (le lecteur reste en lecture seule).
- Propriétaire : tout ; il peut filtrer la liste par boutique avec `GET /api/v1/invoices?storeId=` ; un autre rôle qui envoie `storeId` → 403 « Seul le propriétaire peut filtrer les documents par boutique. »
- Un document hors de portée répond 404 `INVOICE_NOT_FOUND` (avoir : `CREDIT_NOTE_NOT_FOUND`) ; en synchro `REJECTED` avec `reason` = `INVOICE_NOT_FOUND`. L'app retire ce document de son cache et n'affiche pas d'erreur technique.
- Compte sans boutique → 403 « Votre compte n'est rattaché à aucune boutique : demandez au propriétaire de vous en attribuer une. » ; son pull ne renvoie aucun document.
- À implémenter : ne plus afficher le filtre « Boutique » de la liste des documents pour un employé, un gérant ou un lecteur ; masquer les documents d'autrui dans le cache local (le pull ne les renvoie plus, mais un cache ancien peut en contenir : le vider à la mise à jour).
- Critère : un employé A et un employé B de la même boutique ne voient chacun que leurs factures ; le gérant de la boutique voit les deux ; une facture de A ouverte par B (lien interne) affiche « Document introuvable ».

**Clé de suivi mobile :** `m20-14-boutique-des-documents`.

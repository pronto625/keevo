---
title: '24.4 — Bref import: documents (quotes, invoices, lines, payments) and expenses'
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

**Problem:** 24.2/24.3 import a Bref owner's identity, shops, team, catalogue and clients, but not the commercial history: quotes, invoices, their lines and payments, and the expenses. The one-shot cutover (ADR-10) needs them in Keevo, to the franc, traced and idempotent, with the old public links and the seller header of the invoices working.

**Approach:** Two new pipeline steps of the `migration` module at the reserved slots — documents (90) and expenses (100). The snapshot gains `invoices` (with `items` and `payments`) and `expenses`, read by both sources (database: four set-based reads, export: the two declared sections). A pure planner maps every Bref document to a Keevo document (anomalies kept and noted, never corrected); writes go through new import ports of `invoicing` (no `issue`, no event, no quota; settings, documents, lines, payments, `payment_state`, legacy links in one tenant transaction) and `expenses`.

## Boundaries & Constraints

**Always:**
- Bref ids kept for invoices, lines, payments, expenses; the acompte (`initialPayment`) becomes one paid payment row with a stable name-based id (`UUID.nameUUIDFromBytes("bref:initialPayment:<invoice id>")`). Original `createdAt`/`updatedAt` kept on every row.
- Every Bref invoice, item, payment and expense row (and each acompte) gets one `migration_records` line (SHA-256 of the canonical row), with a short French note (≤ 300) quoting the Bref values of an anomaly. The settings write is recorded once per tenant (shop → `invoicing_settings`).
- Quote (`type=quote`) → `kind=QUOTE`, status DRAFT (cancelled → CANCELLED), no number/issue date (Keevo quote invariant). Sale → `kind=INVOICE`, `number = legacy_reference = BRF-<first 8 hex, upper>` (a duplicate prefix in the tenant gets `-2`, `-3`…, noted), status pending/partial/overdue → ISSUED, paid → PAID, cancelled → CANCELLED (reason « Annulée dans Bref » when Bref has none), `issue_date` = Bref civil `date` (absent/invalid → creation day Africa/Douala, noted), `issued_at` = `validatedAt` ?? `createdAt`.
- Amounts: Keevo columns are integer francs. Each Bref amount is rounded HALF_UP; a non-integer amount is noted with its exact Bref value. The stored total is Bref's `total` even when it differs from `(subtotal − discount) + tax + deliveryFee` (or `subtotal` from Σ lines) by more than 1 F — noted, never recomputed. Expenses keep their exact decimals (Keevo `NUMERIC(14,2)`).
- Lines: `product_id` = Keevo product of the line's Bref `(shop, productKey(name, reference))` (Bref's `buildProductKey`, 24.3 records); none → free line, noted. `client_id` = the imported client of the invoice's `(shop, phone)` when there is one.
- `payment_state` is computed by the invoicing module (`PaymentStateCalculator`) from the imported rows (OVERDUE set directly, so the daily job sends no notification); a Bref PAID invoice stays PAID (state PAID) even when its rows do not cover the total — noted.
- `store_id` = the invoice's/expense's shop (20.9 item); seller snapshot = the invoice's own shop (name, address, phone, NIU, RCCM). Tenant `invoicing_settings` (once, `ON CONFLICT DO NOTHING`) = oldest live shop's identity, country VAT rate, not VAT-registered, prices without VAT (24.2 item); `invoicing_document_preferences.public_link_days = 90` (20.7 item).
- Legacy links: `legacy_public_token`/`legacy_reference` on the inserted rows and the cross-tenant registry `public.invoice_legacy_links` filled in one statement (set-based equivalent of `InvoicePublicLinkUseCase.attachLegacy`); deleted documents get no token.
- Module tables (`invoicing`, `expenses`, paid modules) are created in the tenant schema before the step's transaction, whatever the plan (data exists, the plan only gates access — 24.5).
- Set-based: per step one insert per table (`unnest`/`jsonb_to_recordset` … `ON CONFLICT (id) DO NOTHING RETURNING id`), one records batch, one state read + one update; source: one query per Bref table per owner.
- Idempotent: a re-run writes nothing (`alreadyImported`), a partial re-run writes only the missing rows; validation blocks before any write.
- No personal data in logs/notes (notes quote amounts and dates only).

**Never:**
- `InvoiceLifecycleUseCase.issue`, invoice numbering counter, quota (`UsageQuotaPort`), bridge/domain events, notifications, sending.
- Silent correction of a Bref value; credit notes (Bref has none: cancellation only).
- Stored client/product counters: Keevo derives them (sales + invoices − credit notes, `ClientsExportDatasetProvider`, `JdbcMarketingAudienceQuery`), so the 24.3 item is settled by linking `client_id`/`product_id`.
- Portal index (24.6), reconciliation (24.8), cutover (24.9), monetisation (24.5), identity collisions (24.7); changes to api-bref.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy path | 2 shops; a quote, a paid sale with acompte = total, a partial sale with instalments (one late), a cancelled sale with a payment, a deleted sale; expenses in both shops | Quote QUOTE/DRAFT; sales INVOICE with BRF- numbers, lines linked to products, payments (+acompte rows), state PAID / OVERDUE; cancelled CANCELLED (state null); deleted row with `deleted_at`; expenses with exact amounts; settings from the oldest shop, links 90 days; records for every source row | — |
| Re-run | Same owner | Every creation counter 0, `alreadyImported` += records | — |
| Partial re-run | One sale and one expense added | Only those rows (+ their lines/payments/records) written; state of the new sale computed | — |
| Total anomaly | `total` ≠ recalcul by > 1 F | Bref total stored; note `DOCUMENT_TOTAL_MISMATCH` with both values | — |
| Cents | `total` 1500.50 | Stored 1501; note `DOCUMENT_AMOUNT_NOT_INTEGER` with 1500.50 | — |
| Paid not collected | status paid, rows < total | Status PAID, state PAID; note `DOCUMENT_PAID_NOT_COLLECTED` | — |
| Collected not paid | status pending, rows ≥ total | ISSUED, state PAID; note `DOCUMENT_STATE_DIFFERS` | — |
| Overpaid | rows > total | Rows kept; note `DOCUMENT_OVERPAID` | — |
| Payment edge | amount 0; paid without/invalid `paidDate`; unpaid with invalid `dueDate` | 0 → not imported, noted; paid_at = payment `updatedAt`; due = issue date; noted | — |
| Prefix clash | Two sales whose ids share 8 first chars | Older `BRF-XXXXXXXX`, newer `BRF-XXXXXXXX-2`; both legacy_reference `BRF-XXXXXXXX`; noted | — |
| Long text | Client name > 200, line description > 500, … | Cut, noted `DOCUMENT_FIELD_SHORTENED` | — |
| Invalid document | Unknown shop or duplicate id; negative amount, discount > subtotal, quantity ≤ 0, unreadable/duplicate line or payment, duplicate public token | — | Case `DOCUMENT_INVALID`, nothing written for the owner |
| Expense | Unknown category / method; deleted; label > 500 | `other` / method dropped / `archived_at` / cut — each noted | Amount < 0.01 or > 999 999 999 999, unknown shop, duplicate, no date → `EXPENSE_INVALID` |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/migration/application/ImportStep.java`, `ImportRun.java` -- pipeline contract; counters merged with `run.count` (new keys initialised by the steps, no edit of the shared key list).
- `.../migration/application/step/ProductsStep.java`, `ClientsStep.java` -- step pattern to copy (plan, one tenant transaction for rows + records, `remember` after commit, `ProductsStep.note(kinds)`).
- `.../migration/application/MigrationUnitOfWork.java` -- `tenant(schema, work)`.
- `.../migration/domain/model/BrefSnapshot.java` -- add `invoices`, `expenses` (keep 4- and 6-arg constructors).
- `.../migration/domain/model/MigrationRecord.java`, `ImportNote.java`, `BlockedCase.java`, `Canonical.java` -- constants, notes, cases appended at the end of each list.
- `.../migration/domain/service/BrefSnapshotValidator.java` -- one call to a new `BrefSalesValidator.validate(snapshot, shopIds, cases)` after `catalogue(...)`.
- `.../migration/domain/service/BrefMappings.java` -- `e164`, `cut`, `trimmed`, `uuidOrNull` reused.
- `.../migration/adapter/out/source/BrefRows.java`, `JdbcBrefSource.java`, `JsonExportBrefSource.java` -- read the new sections (same mapping; decimals as text).
- `.../migration/package-info.java` -- allow `invoicing :: api`, `expenses :: api`.
- `.../shared/module/application/port/ModuleMigrationPort.java` -- `migrate(schema, moduleId)` (Flyway of a module, no activation check) used to create the paid modules' tables.
- `.../invoicing/invoice/domain/model/PaymentStateCalculator.java`, `PaymentState.java`, `InvoicePayment.java`, `InvoicingSettings.java`, `DocumentPreferences.IMPORTED_LINK_DAYS`, `InvoicePresentation.LEGACY_REFERENCE` -- rules reused by the invoicing import adapter.
- `.../invoicing/invoice/adapter/out/persistence/JdbcInvoiceRepository.java` (columns), `JdbcLegacyInvoiceLinkRegistry.java` (registry insert rule: never move a token), `JdbcInvoicingSettingsRepository.countryVatRate`, `InvoicingTables` -- shapes to mirror.
- `.../invoicing/invoice/application/usecase/InvoicePublicLinkUseCase.java#attachLegacy` -- per-invoice rule the batch registration mirrors.
- `.../expenses/expense/domain/model/ExpenseCategory.java`, `ExpensePaymentMethod.java`, `ExpenseContent.java` -- keys and limits.
- DB: `db/modules/invoicing/V1,V8,V10,V11,V15,V16` (constraints: quote check, issued fields, `invoice_payments_dated_check`, `amount > 0`, `discount ≤ subtotal`, `legacy_reference ~ '^BRF-[0-9A-F]{8}$'`, `ticket_width`), `db/modules/expenses/V1` (category/method checks, amount ≥ 0.01), `db/migration/V57` (`invoice_legacy_links`).
- Bref (api-bref `origin/develop` 6faa8b4, journal 18.3 up to date 11/11): `invoice` (shopId/userId varchar, decimals 14,2, `date` text, `dueDate`/`publicTokenExpiresAt`/`cancelledAt`/`validatedAt`/`deletedAt` timestamps, `type` sale|quote, status enum, customer* columns), `invoice_item` (no productId, quantity 12,3), `invoice_payment` (dueDate/paidDate text, isPaid), `expense` (shopId varchar, amount 14,2, expenseDate date, deletedAt); no index on `invoice.shopId` (per-owner scan, as products in 24.3). `product/product-key.ts` (`buildProductKey`). Paper width (53/80/110 mm) is device-local in the Bref app (`invoice-templates/types.ts`), never stored server-side.
- `keevo/backend/src/test/resources/migration/bref-source-schema.sql`, `keevo/contracts/bref-export.json` -- add the four tables / document the two sections.
- `keevo/backend/src/test/java/com/keevo/invoicing/module/InvoicingMigrationPostgresIntegrationTest.java` -- pinned latest invoicing version (18 → 19).

## Tasks & Acceptance

**Execution:**
- `migration/domain/model/BrefInvoice.java`, `BrefInvoiceItem.java`, `BrefInvoicePayment.java`, `BrefExpense.java` -- Bref rows (canonical forms, no personal data in `toString`); `BrefSnapshot` gains `invoices`, `expenses`.
- `migration/adapter/out/source/BrefRows.java`, `JdbcBrefSource.java`, `JsonExportBrefSource.java` -- `INVOICES_SQL`, `ITEMS_SQL`, `PAYMENTS_SQL`, `EXPENSES_SQL` (shop filter `lower(btrim("shopId")) = ANY`, decimals as text, items in physical order); export sections `invoices` (nested `items`, `payments`) and `expenses`.
- `migration/domain/service/BrefProductKeys.java` -- Java port of Bref `buildProductKey` (NFD, combining marks U+0300–U+036F stripped, lower-case, JS whitespace set collapsed, trimmed; `bc:` for 8–14 digits).
- `migration/domain/service/BrefDocumentPlanner.java` -- pure: documents, lines, payments, notes and records per row; deterministic numbering; anomalies. O(n log n).
- `migration/domain/service/BrefExpenseMapper.java` -- pure mapping with notes.
- `migration/domain/service/BrefSalesValidator.java` + `BlockedCase.Kind` -- `DOCUMENT_INVALID`, `EXPENSE_INVALID`.
- `migration/application/step/DocumentsStep.java` (90), `ExpensesStep.java` (100), `ModuleTables.java` -- module tables, one tenant transaction each, records, counters `quotesCreated`, `invoicesCreated`, `invoiceLinesCreated`, `paymentsCreated`, `expensesCreated`.
- `invoicing/api/InvoiceImportPort.java` (+ `package-info` named interface), `invoicing/invoice/adapter/in/api/InvoiceImportApiAdapter.java`, `invoicing/invoice/domain/port/out/InvoiceImportWriter.java`, `invoicing/invoice/adapter/out/persistence/JdbcInvoiceImportWriter.java`, `LegacyInvoiceLinkRegistry.registerAll` -- settings + preferences, documents, lines, payments, `payment_state`, legacy links.
- `invoicing` module migration `db/modules/invoicing/V19__invoice_legacy_notes.sql` -- `legacy_notes TEXT` (Bref free-text notes kept verbatim; no Keevo field shows them yet).
- `expenses/api/ExpenseImportPort.java`, `expenses/expense/adapter/in/api/ExpenseImportApiAdapter.java`, `.../adapter/out/persistence/JdbcExpenseImportWriter.java`.
- Tests: planner/mapper/product-key/validator units (every matrix row), JSON source sections, invoicing adapter check, `BrefSalesImportFullContextTest` (synthetic Bref DB: quotes, cancelled, unpaid/overdue, multi-shop, total anomaly, cents, prefix clash, expenses; import, re-run, partial re-run, export = database checksums, seller header, link registry, payment states), invoicing migration test 19.
- `keevo/contracts/bref-export.json`, `docs/entitlements/endpoints.md` (regenerated if needed; no new endpoint).

**Acceptance Criteria:**
- Given an imported owner, when the import runs, then Σ `total_ttc` of non-cancelled live invoices per store and civil month equals Σ Bref `total` of non-cancelled live sales per shop and `date` month within ±1 F per document, and Σ paid payment rows equals Σ (`initialPayment` + paid Bref payments).
- Given an imported invoice, when its row is read by `JdbcInvoiceRepository`, then it maps without error (seller header = its shop, number `BRF-…`, lines with `product_id`).
- Given an imported Bref public token, then `public.invoice_legacy_links` maps it to the tenant and invoice, and `invoicing_document_preferences.public_link_days = 90`.
- Given the same owner from the export, then the snapshot's invoices and expenses equal the database ones (same canonical forms).

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 44 findings — high 0, medium 10, low 28, false 6, maybe-false 0 (the 6 descriptive intent-alignment observations are counted: 1 medium, 5 low)
- findings:
  - **Design layer (FUS-NFR11)**
    - `[medium]` `[patch]` Bref invoice table scanned three times per owner (items/payments sub-selects). Fixed: invoices read first, lines and payments filtered by `invoiceId = ANY(uuid[])` (one invoice scan); the unindexed line/payment scans remain → routed 24.9.
    - `[medium]` `[defer→routed 24-9]` `EXPENSES_SQL` / invoice filters cannot use Bref indexes (`lower(btrim(shopId))`): O(owners × table) over the cutover. Same shape as the 24.3 item; one-pass read or temporary indexes on the restored copy belong to the cutover command.
    - `[low]` `[defer→routed 24-9]` One unbounded statement per table for an owner's whole history. Chunking (2 000–5 000 rows) in the cutover command, like 24.3's item.
    - `[low]` `[reject]` Whole-owner snapshot in memory — existing 24.2/24.3 pattern, bounded per owner; covered by the 24.9 item.
  - **Verification gap**
    - `[medium]` `[patch]` `registerAll` conflict / refresh paths untested. Added `legacyLinksBatch` (insert with Bref expiry, replay refreshes, other invoice or tenant → `IllegalStateException`, row unchanged).
    - `[medium]` `[patch]` No multi-line document. s2 now has three lines (order asserted), planner `linesAndMissingValues`.
    - `[medium]` `[patch]` Payment added to an already-imported invoice not tested. Partial re-run now settles s2 → `paymentsCreated` 2, state PAID.
    - `[low]` `[patch]` `ExpenseImportApiAdapter.check` untested. Added `ExpenseImportApiAdapterTest`.
    - `[low]` `[patch]` Validator: expense too large / 3 decimals. Added to `BrefSalesValidatorTest`.
  - **Intent alignment** (descriptive)
    - `[medium]` `[defer→routed 20-2]` Counters derived (A2): the client profile (`ClientSalesStatsAdapter`) counts sales only, so an imported DOCUMENT client shows 0 purchases there (exports and audiences count invoices). Pre-existing; routed to 20-2 (spec « Deferred items routed », story back to in-progress).
    - `[low]` `[reject]` Cents rounded rather than stored (B2) — Keevo document columns are integer francs (D1); every rounding is noted with the Bref value.
    - `[low]` `[reject]` `attachLegacy` replaced by its set-based equivalent (C2) — FUS-NFR11; same rule (never move a token), now tested.
    - `[low]` `[reject]` Paper mapping in the Mobile section (D2) — Bref never stores it server-side (D7).
    - `[low]` `[reject]` Real-dump check not in the diff (E/F) — done live, see Auto Run Result.
    - `[low]` `[reject]` Shared core files touched — append-only (constants, enum values, one validator call, two snapshot fields with the old constructors kept).
  - **Blind hunter**
    - `[low]` `[reject]` Line positions from `ctid` unstable — D12: Bref lines have no other order; Bref is stopped during the cutover; the export producer is retired (ADR-10).
    - `[false]` `[reject]` Header/line totals disagree — Keevo semantics: `total_ht` excludes delivery by definition, line VAT is a display split, document totals authoritative (`InvoiceLine` javadoc).
    - `[medium]` `[patch]` Acompte on a quote dropped without note. Fixed: `PAYMENT_ON_QUOTE` with its value.
    - `[low]` `[patch]` Missing names noted as « shortened ». Fixed: `DOCUMENT_VALUE_MISSING`.
    - `[low]` `[reject]` Client phone stored in E.164 — same number, same normalisation as the imported clients (24.3 D5), needed to match the client and the portal index.
    - `[false]` `[reject]` Due date off by one day — api-bref writes `new Date("YYYY-MM-DD")` (UTC midnight, server in UTC): the UTC civil date is exact.
    - `[low]` `[reject]` Token already registered globally fails the step — Bref tokens are globally unique; a rollback deletes the tenant's registry rows (`ix_invoice_legacy_links_tenant`); the step fails loudly with nothing half-written.
    - `[medium]` `[patch]` Author not mapped to Keevo accounts. Fixed through `ImportRun.accountId` (24.7, D10), tested with a linked owner.
    - `[low]` `[patch]` Notes counted for rows not written. Fixed: notes of inserted ids only (documents, lines, payments, expenses).
    - `[low]` `[patch]` Import adapter looser than its contract. Fixed: rate ≤ 100, unique numbers and (document, position) per call, no token on a deleted document. A foreign key violation still fails the transaction loudly (accepted).
    - `[low]` `[reject]` `ModuleTables.ensure` without unit test — exercised by both full-context tests (tables created on a fresh tenant, no-op on re-runs).
    - `[medium]` `[patch]` Invoice table scanned three times — same fix as the design layer.
    - `[false]` `[reject]` VAT rate wrong when delivery is taxed — api-bref: « deliveryFee n'est pas soumis à la TVA ».
    - `[false]` `[reject]` PAID date from `updatedAt` without note — only when no row is paid, which is already noted `DOCUMENT_PAID_NOT_COLLECTED`.
    - `[low]` `[patch]` Fully-qualified names. Planner imports `MigrationRecord`.
  - **Edge-case hunter**
    - `[medium]` `[patch]` Quote acompte (same as blind hunter).
    - `[false]` `[reject]` Due date WAT/UTC (same refutation).
    - `[low]` `[patch]` Unparseable public token silently dropped. Fixed: `DOCUMENT_LINK_DROPPED`.
    - `[low]` `[patch]` Tax without taxable base unnoted. Fixed: noted in `DOCUMENT_TOTAL_MISMATCH` with the value.
    - `[low]` `[reject]` Paid payment below 0.50 F skipped — noted `PAYMENT_ZERO_SKIPPED`, within the ±1 F per document tolerance.
    - `[low]` `[reject]` Numbering collision on re-run — numbers follow creation order; Bref is stopped (a new sale is always newer).
    - `[low]` `[reject]` New line on an imported document — same as D12 (frozen source).
    - `[low]` `[patch]` Duplicate positions / numbers in one call. Fixed (adapter checks).
    - `[false]` `[reject]` Legacy token already on another invoice row — Bref `publicToken` is UNIQUE over the whole Bref table.
    - `[low]` `[reject]` `ctid` order (duplicate of D12).
    - `[low]` `[patch]` Notes for unwritten expenses (same fix).
    - `[low]` `[reject]` Claim: export/database order parity — payments ordered `(createdAt, id)` on both; lines: physical order vs array order of the same save; the producer is retired.
    - `[low]` `[patch]` Claim: Σ collected vs source — the quote acompte is now noted; sub-franc rounding stays within tolerance.
    - `[low]` `[patch]` Claim: nothing lost without note — quote acompte and bad tokens now noted.

## Design Notes

**Patterns:** Pipeline (two new `ImportStep`s, nothing earlier changed); pure domain services for planning (unit-tested without I/O); ports and adapters for writes (invoicing and expenses own their tables and rules: `payment_state` is computed inside invoicing with its own calculator); Strategy unchanged for sources (shared `BrefRows`).

**Decisions (default, conservative, 11/11/2026):**
- **D1 Amounts** — Keevo document columns are integer francs: HALF_UP rounding + note with the Bref value; Bref totals never recomputed (R3).
- **D2 Status vs collection** — Keevo keeps Bref's legal status; `payment_state` recomputed; a Bref « paid » keeps PAID (no invented payment).
- **D3 Acompte** — one paid row (name-based id) dated `validatedAt` ?? `createdAt`; paid instalments dated their civil `paidDate` at 12:00 Africa/Douala.
- **D4 Seller header** per invoice = its own shop (Bref printed the invoice's shop); tenant settings = oldest live shop.
- **D5 Deleted documents/expenses** imported soft-deleted/archived (history kept, invisible).
- **D6 Notes** kept verbatim in `legacy_notes` (new nullable column, not displayed) — no data lost.
- **D7 Paper** — Bref's width is chosen per screen on the device (default 80 mm, never stored): nothing to import; the tenant keeps Keevo's 80 mm default. The 53 → 58 / 110 → A4 mapping belongs to the RN app's local preferences (Mobile section).
- **D8 Lines** all `STANDARD` at the document's rate (`EXEMPT` would print a legal mention Bref never printed); `vat_mention` null; `vat_rate` = `tax / (subtotal − discount)` (2 decimals, 0 without tax).
- **D9 Module tables** created by the module's own Flyway scripts whatever the plan.
- **D10 Authors** (item routed by 24.7) — every Bref user reference (`invoice.userId`, payments, `expense.createdById`) goes through `ImportRun.accountId` (Bref id when created, linked Keevo account, `null` when not imported or only invited); without account → the owner's Keevo account, noted `AUTHOR_REPLACED`. Never the Bref id as is.
- **D11 Missing values** — a blank client or line name becomes « Client » / « Article », noted `DOCUMENT_VALUE_MISSING`; an unreadable public token is noted `DOCUMENT_LINK_DROPPED`; an acompte on a quote (never taken by Bref) is noted with its value.
- **D12 Line order** — Bref lines have neither position nor date: their physical order (`ctid`, the array order api-bref saved) gives positions 1..n. Bref is stopped during the cutover (ADR-10), so it does not move between runs.

**Cost per owner:** source +4 queries (one per Bref table, per-owner scans like 24.3); documents: 1 Flyway check, 1 settings insert, 1 preferences insert, 3 inserts (documents, lines, payments), 1 payments read + 1 state update, 1 registry insert, 1 records batch; expenses: 1 Flyway check, 1 insert, 1 records batch. Planner O(n log n) (sort for numbering), all lookups by hash map. Memory: the owner's rows.

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t244 migration invoicing expenses architecture` -- expected: all green.

**Manual checks:**
- Live: `bref_src_t244` (dump of 03/11, read-only role) → backend on 4644 with `keevo_dev_t244`: import twice; Σ per shop/month and Σ collected vs source; then drop both databases.

## Auto Run Result

Status: done.

**Summary.** Two new steps of the Bref import pipeline (the 24.2 slots), rebased on 24.7 (`ImportRun.accountId`):
- `DocumentsStep` (90): tenant `invoicing_settings` from the oldest live shop and 90-day public links (once, record shop → `invoicing_settings`); quotes then invoices planned by `BrefDocumentPlanner` (BRF- numbers, statuses, civil dates, seller = the invoice's shop, `store_id`, client by (shop, phone), lines linked by Bref product key, acompte + instalments, anomalies kept and noted with their Bref values, authors through `ImportRun.accountId`), written by the new `invoicing :: api` `InvoiceImportPort` (no issue, counter, quota, event; one insert per table; `payment_state` computed by `PaymentStateCalculator`; old links registered in one statement).
- `ExpensesStep` (100): `BrefExpenseMapper` → new `expenses :: api` `ExpenseImportPort` (exact amounts, same keys, archived when deleted).
- Sources: `invoices` (with lines and payments) and `expenses` read by both adapters (database: 4 queries, one invoice scan; export: the contract's sections), same canonical checksums. Validator: `DOCUMENT_INVALID`, `EXPENSE_INVALID`. Paid modules' tables created whatever the plan (`ModuleTables`).
- Invoicing module migration `V19__invoice_legacy_notes.sql` (Bref notes kept verbatim). No public Flyway migration (V85 unused), no endpoint (endpoints.md unchanged).

**Files:** see Code Map; new `BrefInvoice`, `BrefInvoiceItem`, `BrefInvoicePayment`, `BrefExpense`, `BrefDocumentPlanner`, `BrefExpenseMapper`, `BrefProductKeys`, `BrefSalesValidator`, `DocumentsStep`, `ExpensesStep`, `ModuleTables`, `InvoiceImportPort` (+ `package-info`), `InvoiceImportApiAdapter`, `InvoiceImportWriter`, `JdbcInvoiceImportWriter`, `LegacyInvoiceLinkRegistry.registerAll`, `ExpenseImportPort`, `ExpenseImportApiAdapter`, `ExpenseImportWriter`, `JdbcExpenseImportWriter`; tests `BrefDocumentPlannerTest`, `BrefProductKeysTest`, `BrefSalesValidatorTest`, `BrefExpenseMapperTest`, `JsonExportBrefSourceDocumentsTest`, `InvoiceImportApiAdapterTest`, `ExpenseImportApiAdapterTest`, `BrefSalesImportFullContextTest`; pinned invoicing version 18 → 19 in three invoicing tests; contract `bref-export.json` (sections, example, mapping); test DDL `bref-source-schema.sql`.

**Review.** 44 findings: 20 patched (8 medium, 12 low), 3 routed (24-9 ×2, 20-2 ×1), 21 rejected with their reasons (6 false). Patched by verdict: 0 high, 8 medium, 12 low. Routed: Bref reads in one pass / temporary indexes → 24-9; chunked writes → 24-9; client profile counts sales only → 20-2 (back to in-progress).

**Verification (all green):** migration 77 (after rebase on 24.7), expenses 43, invoicing 439 (2 upgrade tests then fixed for V19 and re-run: `InvoiceSendingFullContextTest` 9, `InvoiceSyncFullContextTest` 15, `InvoicingMigrationPostgresIntegrationTest` 6), invoicing import adapter 2, architecture 173 (1 skipped, pre-existing).

**Live check** (backend on 4644, `keevo_dev_t244`, source `bref_src_t244` = 03/11 dump restored read-only + one synthetic owner; aggregates only; databases and role dropped afterwards):
- Real owner (1 shop; 19 invoices, 36 lines, 8 payments, no expense): import 201 COMPLETED in 3.9 s — 3 quotes, 16 invoices (14 PAID/PAID, 2 CANCELLED), 36 lines (36 linked to their product), 22 payments (14 acomptes + 8 instalments); no document note. Re-run: 0.05 s, nothing created, `alreadyImported` 101. Σ per store and month = source (2026-10: 957 777 F / 12 docs, 2026-11: 920 F / 2 docs); Σ collected 1 934 847 F = source.
- Synthetic owner (2 shops, quote, paid, partial-overdue, cancelled, deleted, total anomaly with cents, 4 expenses): 201 COMPLETED in 4.1 s; notes TOTAL_MISMATCH, AMOUNT_NOT_INTEGER, DELETED, NUMBER_SUFFIXED ×4 (synthetic ids share their 8 first characters), LINE_WITHOUT_PRODUCT, EXPENSE_*; states OVERDUE / UNPAID / PAID; Σ per month = source (1 501 vs 1 500.50, within ±1 F); Σ collected 31 200 = source; expenses 35 350.50 = source; settings + 90-day links; 1 legacy link registered. Re-run: nothing created.

**Follow-up review recommended:** `true` — eight medium patches touched the read path (single invoice scan), the authors (24.7 accounts) and the payment-state recompute on partial re-runs; they are covered by the full-context and live runs, but the rebased author mapping was reviewed only by these tests.

**Residual risks:**
- Bref line order comes from the physical order of `invoice_item` (no position nor date in Bref); stable while Bref is stopped (ADR-10).
- Per-owner scans of the unindexed Bref document tables and one statement per table per owner: fine per owner, routed to 24.9 for the whole cutover.
- The client profile does not count invoices (pre-existing, routed to 20-2).

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10 (non répétés ici).

### Statut

API livrée (commit de la story 24.4, branche de l'epic 24, en attente de fusion). **Aucun nouvel endpoint ni champ** : la story importe l'historique Bref dans les tables existantes (documents 20.3–20.5, paiements 20.4, liens 20.7, boutique 20.14, dépenses 20.10). Clé de suivi mobile : `m24-4-import-documents-paiements-depenses` (préférence locale du papier, ci-dessous).

### Ce qui est fait côté API

- **Devis et factures** Bref repris avec leur identifiant : un devis reste un devis (`kind = QUOTE`, référence affichée `DV-XXXXXXXX` comme dans Bref) ; une vente devient une facture numérotée **`BRF-XXXXXXXX`** (les 8 premiers caractères de l'identifiant Bref, la référence que le client connaît ; un doublon rarissime reçoit `-2`). Dates d'origine (`issueDate` = date civile Bref).
- **Lignes** reliées au produit du catalogue importé (24.3) quand l'article existe dans la boutique ; sinon ligne libre (comportement 20.5 existant).
- **Paiements** : l'acompte Bref devient un paiement reçu ; les échéances deviennent des échéances (payées ou à venir). L'**état d'encaissement** (`paymentState` : Non payée / Partiellement payée / Payée / En retard) est recalculé par Keevo ; une facture « payée » dans Bref reste payée.
- **Boutique** (`storeId`) = la boutique Bref de la facture ; **en-tête vendeur** = cette boutique.
- **Liens publics Bref** : valables 90 jours pour une entreprise importée ; les anciens liens Bref restent ouvrables (redirection 24.10).
- **Dépenses** Bref reprises à l'identique (montant au centime, catégorie, moyen de paiement) ; supprimées → archivées.

### À utiliser

Rien de nouveau : pull des factures, paiements et dépenses (spec-16-10), écrans existants des documents (20.3–20.8) et des dépenses (20.10).

### À implémenter côté mobile

1. **Papier des tickets (item 20.7)** : dans Bref, la largeur du ticket (53, 80 ou 110 mm) est choisie à l'écran sur l'appareil et jamais envoyée au serveur ; il n'y a donc rien à importer. Si l'app RN reprend une préférence locale Bref, la convertir vers les valeurs Keevo : **53 mm → `58`**, **80 mm → `80`**, **110 mm → `A4`** (`ticketWidth` de `PUT /api/v1/invoicing/settings/documents`, existant, 20.7). Défaut : 80 mm (identique à Bref).
2. Afficher le numéro `BRF-…` tel quel ; aucun identifiant technique à l'écran.

### Critères d'acceptation mobile / scénarios de test

- **Given** un owner Bref importé puis activé (24.9), **When** il ouvre ses factures au premier full pull, **Then** il voit ses ventes numérotées `BRF-…` avec leurs dates Bref, leurs paiements et leur état (« En retard » pour une échéance passée non réglée), et ses devis `DV-…`.
- **Given** une préférence locale Bref de 110 mm, **When** l'app la reprend, **Then** les tickets s'impriment en `A4` (colonne de 110 mm centrée).

### Clé de suivi mobile

`m24-4-import-documents-paiements-depenses`

## Dashboard super-admin (autre dépôt)

> Conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`.

### Statut

API livrée (commit de la story 24.4). Mêmes routes que 24.2/24.3 (`POST /api/v1/admin/migration/bref/owners/{ownerId}/imports`, `GET /api/v1/admin/migration/batches/{batchId}`, `AdminBrefMigrationController.java`) ; le lot porte de nouveaux compteurs, remarques et cas bloquants.

### Ce qui est fait côté API

Le lot d'import compte et explique les devis, factures, lignes, paiements et dépenses importés.

### À utiliser

- `counts` (nouvelles clés) : `quotesCreated`, `invoicesCreated`, `invoiceLinesCreated`, `paymentsCreated` (acomptes compris), `expensesCreated`.
- `notes` (nouveaux `code`, afficher `label (count)`) : `DOCUMENT_TOTAL_MISMATCH`, `DOCUMENT_AMOUNT_NOT_INTEGER`, `DOCUMENT_DATE_INVALID`, `DOCUMENT_PAID_NOT_COLLECTED`, `DOCUMENT_STATE_DIFFERS`, `DOCUMENT_OVERPAID`, `DOCUMENT_FIELD_SHORTENED`, `DOCUMENT_DELETED`, `DOCUMENT_NUMBER_SUFFIXED`, `LINE_WITHOUT_PRODUCT`, `PAYMENT_ZERO_SKIPPED`, `PAYMENT_DATE_INVALID`, `PAYMENT_ON_QUOTE`, `EXPENSE_FIELD_SHORTENED`, `EXPENSE_CATEGORY_UNKNOWN`, `EXPENSE_METHOD_UNKNOWN`, `EXPENSE_DELETED`.
- `cases` (nouveaux `code`, afficher `label`) : `DOCUMENT_INVALID` (« Document invalide : remise supérieure au sous-total » …), `EXPENSE_INVALID` (« Dépense invalide : date invalide » …). `sourceTable` vaut `invoice` ou `expense` ; `sourceId` ne s'affiche jamais.

### À implémenter côté dashboard

1. Libellés français des nouveaux compteurs de l'écran « Migration Bref » : « Devis importés », « Factures importées », « Lignes de documents », « Paiements importés », « Dépenses importées ».
2. Rien d'autre : remarques et cas bloquants s'affichent déjà par leur `label`.

### Critères d'acceptation dashboard

- **Given** un owner dont une facture Bref a un total incohérent, **When** le super-admin lance l'import, **Then** le lot « Terminé » montre « Total de document Bref différent du recalcul (plus de 1 F) : total Bref conservé (1) ».
- **Given** une facture Bref dont la remise dépasse le sous-total, **Then** le lot est « Bloqué » avec « Document invalide : remise supérieure au sous-total ».

### Clé de suivi dashboard

`d24-4-import-documents-paiements-depenses`

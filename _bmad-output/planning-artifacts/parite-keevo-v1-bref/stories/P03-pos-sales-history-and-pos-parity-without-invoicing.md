---
title: 'P03 — POS sales history and POS parity without the invoicing module (V1 companies)'
type: 'feature'
created: '2026-11-28'
status: 'in-review'
baseline_revision: 'c5400dc4173575379ab7a65392861eaa47df5b4a'
backend_baseline_revision: '4a2e55bb97f453ef0255e41a2a5964778bc57a7d'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/parite-keevo-v1-bref/SPEC.md'
  - '{project-root}/_bmad-output/test-artifacts/parite-v1-bref-2026-11-28/lot-B-caisse.md'
warnings: ['multiple-goals', 'oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The RN app reads sales only as invoices (`GET /invoices`), but V50 disabled invoicing for every V1 company: for a V1 tenant the Sales tab, home figures, sale detail, receipt and cancel are empty or broken, V1 history (`/sales/history`) is invisible, and `GET /invoices` answers 403 `MODULE_DISABLED_BY_OWNER` (failure banner). The POS also lacks V1 functions (change due, % discount, price negotiation, typed quantity, categories, clear/kept cart, best sellers), older-sale cancel and line correction, pending sales, and the seller can no longer close his day or see his day's sales (lot B 1–9, 13–16, 18–19; lot D 8).

**Approach:** Mirror POS sales from `GET /sales/history` into the local `invoices` table as rows marked `source='pos'` (one row per sale; a sale whose automatic invoice is local is shown once, as its invoice, when invoicing is active; as the sale when it is not), so every existing list/detail/receipt/offline path works. Revenue figures come from a new additive endpoint `GET /api/v1/sales/summary` on the single revenue source (20-9: sales ∪ invoices − credit notes, a bridged sale counted once), local computation only offline. POS and closure gaps are filled in the app.

## Boundaries & Constraints

**Always:**
- App code in `/var/tmp/keevo-e2e/bref-parite` (branch `feat/parite-keevo-v1`); backend `keevo/backend` on `refonte/modulaire`, additive only, V1 2.0.1 compatible.
- Server rules stay: CANCEL_SALE (sync, offline-capable) only cancels a sale of the same business day (`SALE_CANCEL_NOT_SAME_DAY`); `POST /sales/{id}/cancel` (owner, online, justification ≥ 10 chars) cancels any COMPLETED or pending sale — that is the V1 route, used for an earlier day; `POST /sales/{id}/correct` (owner, online, quantities only, ≥ 10 chars). Refusals show the server's French message.
- French labels (fr + en keys), FCFA, no ids/codes on screen. Tenant `kv_6s0qob` keeps invoicing disabled at the end; Awa stays without username.
- No double counting: never two local rows for one sale; revenue online from `/sales/summary`.

**Never:**
- No business-rule change server-side; no change to other AI/bref branches; no `.env*` commit.
- Not here: draft products created by a seller at the POS with pending validation (P06 drafts), durable outbox (P09), home charts/per-store status (P07), module activation UI (P04).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| V1 tenant, invoicing off | sales in `/sales/history` | Sales tab lists them (client, seller, total, MoMo ref, status); home CA/count from `/sales/summary`; no invoices call | `/invoices` not called → no failure banner |
| Invoicing on | sale X with automatic invoice local | one line (the invoice); sale-only rows (V1 history) listed too | POS row of X destroyed when its invoice arrives |
| Invoicing off | local bridged invoice of sale X | replaced by the POS row of X | — |
| New POS sale | CREATE_SALE applied | POS row inserted at once (deterministic item ids) | refused → no row, existing error path |
| Cancel same day | owner/admin | CANCEL_SALE queued (offline ok), row "Annulée" at once | server refusal → row restored + server message |
| Cancel earlier day | owner, online, reason ≥ 10 | `POST /sales/{id}/cancel` then row "Annulée" | offline → "connexion requise"; non-owner → server message "Une vente ne s'annule que le jour même" |
| Correct a line | owner, online, POS row COMPLETED | `POST /sales/{id}/correct {justification, itemQuantities{itemId:qty}, correctionId}`; row totals updated | qty < 1, reason < 10, discount > new subtotal → French error before sending |
| Pending sale | `PENDING_VALIDATION` in history | shown "À valider", not counted in revenue; owner: Valider / Annuler | server message |
| Change due | cash, received 10 000, total 7 500 | "Monnaie à rendre : 2 500 FCFA"; received < total → warning, sale still allowed | — |
| Discount | 10 % of 7 500 | discount 750 sent as `discountAmount` | > 100 % or > subtotal → French error, not applied |
| Price negotiation | applied 4 000 vs catalogue 4 500 | `appliedUnitPrice` 4 000, badge "modifié" | ≤ 0 → error |
| Typed quantity | 50 typed | quantity 50 | > stock → stock message, kept at max |
| Seller closure | seller, today | preview (sales, revenue, cash, MoMo, pending, top) then confirm → `POST /day-closures` | 409 already closed → server message |

</intent-contract>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo`):
- `commerce/sale/adapter/in/rest/SaleHistoryController.java` -- `/sales/history` (storeId required, page size ≤ 100, sorted `occurredAt` desc, items with `id`, `mobileMoneyRef`, `status`); pattern for scope + DTO.
- `commerce/sale/adapter/in/SalesReadScope.java` -- owner: any store or null; member: own store, own sales unless `STORE_SALES_VIEW`.
- `shared/capability/revenue/RevenueQueryPort.java` -- `current()` / `storeScoped()` relations with `store_id, employee_id, occurred_at, total_amount, doc_count, paid_amount`; pattern `crm/contact/adapter/out/persistence/JdbcClientPurchaseStats.java` (+ its Mockito SQL-shape test).
- New: `commerce/sale/domain/port/out/SalesSummaryPort.java`, `commerce/sale/adapter/out/persistence/JdbcSalesSummary.java`, `commerce/sale/adapter/in/rest/SaleSummaryController.java` (`GET /api/v1/sales/summary?from&to&storeId&employeeId`, any role, `@RequiresEntitlement(pos, READ)`, scope via `SalesReadScope`, WAT day bounds like `/sales/history`, response `{amount, count, paidAmount}`), tests in `src/test/java/com/keevo/commerce/sale/...` and a PostgreSQL case on the real relations (bridged sale once).
- `commerce/sale/adapter/in/rest/PendingSaleController.java` -- cancel/correct/validate (owner) routes used as is.

App (`/var/tmp/keevo-e2e/bref-parite`):
- `lib/database/schema.ts` (v18 → 19), `lib/database/migrations.ts`, `lib/database/models/invoice.model.ts` -- add `source`, `sale_status`, `mobile_money_ref` to `invoices`; `toInvoice` exposes them; `features/invoice/types/invoice.types.ts` Invoice/InvoiceItem (`productId?`).
- `lib/database/sale-mirror.ts` (new, pure) -- sale DTO → row fields, local-day helpers, dedup decision, best-seller ranking, closure preview, cancel route decision; tested by `scripts/sale-mirror.test.mjs`.
- `lib/database/sync.ts` -- new `syncSales()` (per shop, paged, incremental window, batched writes), `syncInvoices` skipped when invoicing inactive and destroys POS rows of synced bridged invoices; `syncAll` runs it.
- `features/dashboard/hooks/use-module-access.ts` -- export `fetchTenantModules`, `useInvoicingActive()`.
- `features/pos/services/sale-writes.ts`, `lib/sdk/mutation-defaults.ts` (`create-sale` onSuccess) -- local POS row after the server applied the sale.
- `features/invoice/hooks/use-sales-stats.ts` (new) -- `/sales/summary` online, local `computeInvoiceStats` offline; `lib/database/report-aggregations.ts` excludes pending-validation POS rows.
- `features/invoice/screens/invoices-screen.tsx` -- `useSalesStats`, "Cette semaine", server search only with invoicing, empty-state button → POS without invoicing.
- `features/dashboard/screens/dashboard-screen.tsx` -- "Cette semaine", CA/count from `useSalesStats`, pending/overdue tiles only with invoicing; members: card "Mes ventes du jour" + "Clôturer la journée".
- `features/invoice/screens/invoice-detail-screen.tsx` + `features/invoice/hooks/use-sale-actions.ts` (new) -- POS row: MoMo ref, no payment/send actions, cancel route (sync same day / REST earlier day), correction sheet, pending validate/cancel.
- `features/pos/screens/pos-screen.tsx`, `features/pos/hooks/use-pos-cart.ts` (new, module-level store), `features/pos/utils/pos-cart.ts` (new, pure: totals, discount %, change due) -- POS gaps; `scripts/pos-cart.test.mjs`.
- `features/product/screens/product-form-screen.tsx` -- `name` param prefill (create from POS search).
- `features/governance/screens/day-closure-screen.tsx` -- preview before confirm, seller access.
- `lib/i18n/locales/{fr,en}/*.json` -- keys.

## Tasks & Acceptance

**Execution:**
- Backend summary port/adapter/controller + tests (controller scope, SQL shape, PostgreSQL figures) -- revenue for any role/store/period without invoicing.
- App schema/model/types + `sale-mirror.ts` + test -- one row per sale, mapping and decisions pure.
- `sync.ts` `syncSales` + invoices gating + create-sale local row -- V1 history and new sales visible.
- `use-sales-stats` + Sales tab + home -- figures and "Cette semaine".
- Detail screen actions (MoMo ref, cancel routes, correction, pending) -- V1 sale management.
- POS cart store + screen (change due, % discount, price edit, typed qty, categories, clear, kept cart, best sellers, create product from search) + test.
- Closure preview + seller access + "Mes ventes du jour".
- Build APK, play and film scenarios, DB checks.

**Acceptance Criteria:**
- Given `kv_6s0qob` (invoicing off) on the emulator, when the owner makes a cash sale, then it appears in Ventes (with V1 historical sales) and in the home CA/count, and no failure banner shows.
- Given invoicing temporarily enabled, when the owner makes a sale, then the Sales tab shows it once and the home CA equals `/dashboard/summary` today figure (no double count); invoicing is disabled again afterwards.
- Given a sale dated the previous day, when the owner cancels it online with a reason ≥ 10 characters, then `kv_6s0qob.sales.status = 'CANCELLED'` and stock is restored; a seller gets the server's French refusal.
- Given the POS, when the cashier sets 10 % discount, edits a line price, types a quantity, pays cash with an amount received, then the change due is shown and the server sale carries the discount and applied price.
- Given the seller (paul.vendeur or Awa), when he opens the home, then "Mes ventes du jour" shows his sales and "Clôturer la journée" shows the preview then closes the day (`day_closures` row).
- Given each scenario above, then videos `videos/P03-*.mp4` and `videos/P03.md` exist and DB effects are checked.

## Spec Change Log

- 2026-11-28 (implementation, found on the emulator): owner REST actions on a sale (`/cancel`, `/correct`, `/validate`)
  run with `networkMode: 'always'`, no retry — with the default `offlineFirst` an earlier-day cancel started while the
  probe still said "online" was paused and would have fired on reconnect; now it fails at once ("Pas de connexion au
  serveur") and the cancel sheet stays open until the server accepts.
- 2026-11-28: a refused sync operation (`/sync/push`) shows `conflictData.message` (server's French text), never the
  raw reason code — the seller's refused cancel showed "FORBIDDEN".
- 2026-11-28: the cancel action is shown on POS rows to every non-viewer role (the server decides: owner, manager,
  custom roles holding `SALE_CANCEL`); a seller gets the server's refusal, as the AC requires.
- 2026-11-28: `useInvoiceStats` / `useTopProducts` no longer call `/invoices/stats` / `/invoices/top-products` when
  invoicing is inactive (local figures, POS rows included) — avoids 403 on shop/user/report screens of V1 companies.

## Review Triage Log

## Design Notes

Patterns. **Read-model mirror** (CQRS-style): the server's sales are projected into the existing local read model (`invoices`), so screens stay on one query path and work offline; the projection is a **pure mapper** (`sale-mirror.ts`) plus a thin IO sync. **Strategy by module state** for dedup (invoicing active → invoice wins; inactive → sale wins). **Ports and adapters** backend: a read port on the shared revenue capability, scope decided once by `SalesReadScope`. POS cart: a **module-level store** (survives leaving the screen, like V1's provider) with pure total/discount/change functions.

Costs. Sales sync: per shop, one page of 100 for the incremental window (yesterday → today) at each opportunistic sync; a full window (365 days, pages of 100, ≤ 2 000 rows per shop) at most every 12 h or on first run. Writes per page: 3 local queries (`id IN`, `sale_id IN`, customers/users `id IN`) and one `database.batch` — no per-row lookup. `/sales/summary`: one aggregate statement on the revenue relations, predicates on `occurred_at`/`store_id`/`employee_id` pushed into each branch (`idx_sales_store_occurred`-style indexes already used by the dashboard); react-query cached 30 s. Best sellers: one O(rows × items) pass over local rows in a memo, then sort O(p log p). Closure preview: one pass over today's local rows.

Example row (sale → `invoices`): `{id: sale.id, source:'pos', sale_id: sale.id, type:'sale', status: COMPLETED→'paid' | CANCELLED→'cancelled' | PENDING_VALIDATION→'pending', sale_status, total: totalAmount, discount: discountAmount, subtotal: Σ item.subtotal, initial_payment: total, payment_method: cash|mobile_money, mobile_money_ref, items: [{id: item.id, productId, name, quantity, unitPrice: appliedUnitPrice, total: subtotal}], sale_date: local YYYY-MM-DD of occurredAt}`.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='SaleSummaryControllerTest'` then `-Dtest='JdbcSalesSummaryTest'` then the PostgreSQL test -- expected: pass (one class at a time).
- `cd /var/tmp/keevo-e2e/bref-parite && pnpm test` -- expected: all pass.
- `pnpm exec tsc --noEmit` -- expected: no error in changed files (42 pre-existing); `pnpm lint` -- no new problem (baseline 5).

**Manual checks (if no CLI):**
- Release APK on `emulator-5554`, backend restarted with messaging off, scenarios filmed (`videos/P03-*.mp4`, index `videos/P03.md`), DB effects checked, screenshots read.

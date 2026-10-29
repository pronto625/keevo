---
title: '12.2b — Public API of inventory (stock, transfers, counting); consumers go through it'
type: 'refactor'
created: '2026-10-29'
status: 'done'
baseline_revision: '7fcb701699043516cb9a1cdaec78b95eef597842'  # shared with 12-2a (batched review; 12-2a uncommitted underneath)
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2a-api-publique-catalog-crm.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `catalog`, `commerce`, `messaging` and `sync` use 35 internal `inventory` types (`StockOperationService`, `StockLevel`, `StockLevelRepository`, `MovementType`, transfer/counting use cases and models), and `catalog`'s CSV import seeds stock by calling inventory internals, closing `catalog ↔ inventory` cycles (MOD-NFR3).

**Approach:** Same API pattern as 12.2a (read its Decisions): `inventory/api` ports + records, `domain.event` packages exposed as `events`, all non-`shared` consumers rewired. Transfers get their own sub-domain package `inventory.transfer` (out of `inventory.stock`) during this story, since its API is being drawn here.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- Stock writes requested by other modules (sale, cancellation/correction, CSV-import initial stock) go through one command port in `inventory.api` (e.g. record movement / seed initial stock) with an API-level movement-type enum; `MovementType` stays internal.
- `catalog`'s CSV import seeds initial stock through that port; the cycle `catalog ↔ inventory` becomes `catalog → inventory :: api` only if inventory no longer needs catalog internals — inventory reads products through `catalog :: api` (12.2a).
- Transfer classes (`StockTransfer`, `Execute/Complete/GetTransferHistory` services, `StockTransferController`, transfer repository/adapters, transfer events, `TransferValidationStrategy`) move to `com.keevo.inventory.transfer.*`, REST path `/api/v1/stock/transfers` unchanged.

## Boundaries & Constraints

**Always:** REST/sync contracts, tables, Flyway unchanged; stock movement atomicity with the sale transaction preserved (port call in the same transaction); all test packages green; not-allowed stays 0.

**Never:** no change to `shared`, no event relocation (12.3), no new behaviour.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Sale validated (POS / sync push) | stock 20, sell 2 | stock 18, SALE movement | insufficient stock → same domainCode |
| Sale cancelled | completed sale | stock restored, SALE_CANCELLED movement | unchanged |
| CSV import with initial stock | csv rows with qty | products + initial stock as today | row errors unchanged |
| Transfer execute/complete | two stores | same states and movements | same scope rules (employee own store) |
| Inventory counting via sync | session/count/validate ops | same results | unchanged |
| Architecture report | after change | 0 non-exposed violations from modules other than `shared` into `inventory`; no `catalog ↔ inventory` cycle | — |

</frozen-after-approval>

## Code Map

- Consumers (violations report 2026-10-29): `commerce` → `StockOperationService`, `StockLevel`, `StockLevelRepository`, `MovementType`; `catalog` → `StockOperationService`, `MovementType` (`catalog/product/application/usecase/ImportCsvProductsUseCase.java`); `messaging` → `MovementType`, `StockAdjustedEvent`, `StockThresholdBreachedEvent`, `TransferCreatedEvent` (listeners `StockAlertNotificationListener`, `TransferCreatedNotificationListener`, `OwnerAndDestinationEmployeeStrategy`); `sync` → `AdjustStockUseCase`, `RecordStockEntryUseCase`, `TransferStockUseCase`(+command), `StockTransfer`, counting `Create/Save/ValidateInventory*` ports + models, `InventorySessionRepository`.
- Event packages: `inventory/stock/domain/event`, `inventory/counting/domain/event` (+ transfer events after the split).
- Inventory's own reads of products (`Product`, `ProductRepository`, `ProductStatus`, `ProductResponseDto`) must already be on `catalog :: api` after 12.2a — finish any leftover here.
- `inventory.counting.domain.port.in.*` already are port interfaces: reuse them behind `inventory.api` rather than duplicating.

## Tasks & Acceptance

**Execution:**
- [x] Move transfer classes to `inventory.transfer.*` (git mv + reference rewrite, tests follow).
- [x] Create `inventory/api` with the stock-command, stock-query, transfer and counting ports/records consumers need; internal adapters delegate to existing services.
- [x] Declare event packages `@NamedInterface("events")`; rewire all consumers; update `allowedDependencies` to named-interface form.
- [x] Unit-test new adapters; adapt existing tests' mocks to the ports.
- [x] Record before/after violation counts (by target module, and cycles) in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the architecture report, when regenerated, then no module other than `shared` violates into `inventory`, no cycle contains both `catalog` and `inventory`, and the total decreases vs 12.2a.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Transfer sub-domain (2026-10-29).** `git mv` of 21 main + 10 test classes from `inventory.stock.*` to `inventory.transfer.*` (same sub-package layout): `StockTransfer`, `Complete/GetTransferHistory/TransferStock` ports + commands, `StockTransferRepository`, `(Default)TransferValidationStrategy`, `TransferCreatedEvent`, `StockTransferredEvent`, `Execute/Complete/GetTransferHistoryService`, `StockTransferController` (+ `StockTransferResponseDto`, `TransferStockRequestDto`; path `/api/v1/stock/transfers` unchanged), `StockTransferRepositoryAdapter`, `StockTransferSpringRepository`, `StockTransfersExportDatasetProvider`. Nothing left in `stock` referenced them. Import-only rewrites outside inventory: `shared/.../StockTransferJpaEntity` (`TransferStatus` import — unavoidable once `StockTransfer` moves; no other change to `shared`), and the tests `NativeSqlPostgresIntegrationTest`, `SchedulerLockCoverageTest`, `ExportContractFileTest` (class-name string).

**Published API.**
- `inventory.api` (`@NamedInterface("api")`): `StockCommandPort` (`recordMovement`, `adjustTo`, `recordEntry`), `StockQueryPort` (`availableQuantity`), `TransferCommandPort` (`transfer` → id), `CountingCommandPort` (`openSession`, `saveCount`, `validate` → ids), `CountingQueryPort` (`findSessionStoreId`, `isSessionValidated`); records `StockMovementInput`, `TransferInput`, `OpenCountingSessionInput`, `SaveCountInput`, `ValidateCountingInput`; enums `StockMovementKind` (SALE, SALE_CANCELLED, STOCK_ENTRY, ADJUSTMENT — mapped by name; transfer legs not offered) and `CountingScope` (FULL, PARTIAL). `MovementType` stays internal for commands.
- Internal adapters: `inventory.stock.adapter.in.api.{StockCommandAdapter, StockQueryAdapter, CatalogInitialStockAdapter}`, `inventory.transfer.adapter.in.api.TransferCommandAdapter`, `inventory.counting.adapter.in.api.{CountingCommandAdapter, CountingQueryAdapter}`. Counting adapters reuse the existing `counting.domain.port.in` use cases. No adapter has its own `@Transactional`: `StockOperationService`/use cases keep theirs (REQUIRED), so the sale and its movements commit or roll back together, as before.

**Deviation — CSV initial stock (dependency inversion).** A `catalog → inventory :: api` call plus the required `inventory → catalog :: api` edge would still be a Modulith cycle (cycle detection ignores named interfaces), so the acceptance criterion "no `catalog ↔ inventory` cycle" could not hold with the Decision's literal wording. Instead `catalog.api.InitialStockPort` (a port the catalog *requires*) is implemented by inventory's `CatalogInitialStockAdapter`, which goes through `StockCommandPort.recordMovement(STOCK_ENTRY)` — the import still seeds stock through the inventory command port, same movement, notes, transaction and errors, and catalog has no dependency on inventory at all (`"inventory"` removed from catalog's `allowedDependencies`).

**Accepted by the orchestrator (2026-10-29).** The CSV-import port direction — `catalog.api.InitialStockPort`, declared by catalog, implemented by inventory, and injected **optionally** (`ObjectProvider`: without a stock module the import creates the products without initial stock and logs one WARN) — and the import-only edit in `shared/.../StockTransferJpaEntity` were both accepted by the orchestrator on 2026-10-29.

**Review follow-ups (2026-10-29).** `ApplicationModulesBaselineTest` now fails on any non-exposed / internal-of-declared violation from a module other than `shared` into `catalog`, `crm` or `inventory` (everything else stays warning-only). `ProductCreatedEvent` for the quick-add is published by catalog's `ProductCommandAdapter.register` (payload unchanged; `RegisterProductInput` carries the actor context) instead of `QuickAddProductService`. API adapters and view mappers are package-private (cross-package tests go through `CatalogProductApiTestSupport` / `CrmContactApiTestSupport`). Null `StockMovementInput.kind` / `OpenCountingSessionInput.scope` → `VALIDATION_ERROR`.

**`inventory :: events`.** `inventory.stock.domain.event` is declared at package level; the 2 transfer events, the 5 counting events and `MovementType` (a field of `StockAdjustedEvent`, read by messaging) carry a type-level `@NamedInterface("events")` (same Modulith 1.4 merge workaround as catalog in 12.2a; documented in each `package-info`). `MovementType` is reachable only as part of that event payload, until 12.3 replaces the events with catalogue records.

**allowedDependencies.** catalog: `inventory` removed; commerce `inventory :: api`; messaging `inventory :: events`; sync `inventory :: api`.

**Violations (`target/modulith-violations.txt`, before = 12.2a working tree, after = this change).**

| Edge into inventory | Before | After |
|---|---|---|
| catalog → inventory | 2 | 0 |
| commerce → inventory | 14 | 0 |
| messaging → inventory | 8 | 0 |
| sync → inventory | 20 | 0 |
| shared → inventory (out of scope, 12.2d) | 137 | 8 (`TransferStatus` in `StockTransferJpaEntity` ×7, `InventoryScope` ×1) |
| **Total** | **1031** (165 cycles, 866 non-exposed) | **833** (140 cycles, 693 non-exposed) |

Cycles: direct `catalog → inventory → catalog` 1 → 0; cycles containing both catalog and inventory 70 → 45, all through other modules (e.g. `catalog → identity → messaging → inventory → catalog`, `catalog → store → subscription → … → inventory`) whose edges belong to 12.2c–e (catalog→identity/store/subscription, messaging→inventory events, identity→messaging). The stricter reading of the AC ("no cycle contains both") is not reachable within this story's scope. Not-allowed stays 0.

**Small behavioural notes.**
- `VALIDATE_INVENTORY` sync: the idempotency check (`isSessionValidated`) and the EMPLOYEE store check of `SAVE_INVENTORY_COUNT` (`findSessionStoreId`) read the session through the counting query port — same single repository lookup, same outcomes.
- `SaleValidationCascadeService` / `ValidateSaleService` keep reading the no-variant stock level (`availableQuantity(product, null, store)`), exactly as before.

### Verification & live device run (2026-10-29)

After review patches: 14 packages sequential, 3,674 tests, 0 failures/errors; violations 833 (140 cycles, 0 not-allowed), new published-module assertion green. Live: dev backend + Redmi debug app — sync pull of an API-created product (stock 10) and of a +5 stock entry (23), POS sale of 1 → server `COMPLETED` 8000, stock 10 → 9; checkout now offers the optional CRM client picker; API smoke all 200/201; backend log 0 ERROR.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (batched 12-2a + 12-2b, one diff)
- verdicts: 23 findings — high 0, medium 6, low 13, false 4, maybe-false 0 (+ intent-alignment notes)
- findings:
  - `[medium]` `[patch]` V1/IA/B1 narrowed boundaries unenforced (re-import of internals or missing type-level events annotation passes) — assertion: 0 non-exposed/internal violations into catalog/crm/inventory from non-shared modules
  - `[medium]` `[patch]` V2 variant stock check in `RecordSaleService` untested — variant case added
  - `[medium]` `[defer]` B2 `ValidateSaleService`/`SaleValidationCascadeService` check availability on the no-variant level but decrement the variant — pre-existing; fixing changes validation outcomes → routed → 12-2c
  - `[medium]` `[patch]` B5 inventory publishes catalog's `ProductCreatedEvent` via `ProductCommandPort.register` — publication moved into catalog adapter
  - `[medium]` `[patch]` InitialStockPort mandatory → catalog cannot run without inventory — made optional
  - `[medium]` `[defer]` B7 tenant convention differs across ports — routed → 12-2d (TenantId with core extraction)
  - `[low]` `[patch]` B3/E4 `SupplierCommandPort.update` view reports no products when links kept — real links reloaded
  - `[low]` `[patch]` B11 `ContactViews.of(s, null)` → `[]` vs old `null` — null preserved + JSON parity test with app ObjectMapper
  - `[low]` `[patch]` B4 `findStatusName` uses JPA repository from inbound adapter — domain port used, Javadoc fixed
  - `[low]` `[patch]` B6 public internal adapters/mappers — package-private
  - `[low]` `[defer]` B8/E5/IA `MovementType` exported via `inventory :: events` — routed → 12-3 (API-level kinds in the event catalogue)
  - `[low]` `[patch]` B9 type-level events annotation has no safety net — covered by the V1 assertion
  - `[low]` `[reject]` B10 `ValidateCountingInput` string key — sync-only caller; revisit with 12.7
  - `[low]` `[patch]` B12 `seedTemplateCategories` emits no event / not idempotent (same as pre-12.2a) — documented in Javadoc
  - `[low]` `[patch]` E1–E3 null `scope`/`kind`/template names → NPE — VALIDATION_ERROR
  - `[low]` `[patch]` wrong test reference in `OnboardingServiceTest` comment
  - `[low]` `[reject]` IA "no change to shared" — one import line forced by the transfer move
  - `[low]` `[reject]` IA cycle AC reading (b) — 45 remaining cycles go through identity/messaging/store/subscription edges owned by 12.2c–e; direct cycle removed
  - `[false]` `[reject]` IA sync payload invariance only unit-tested — live API smoke on this tree all 200/201, stock 18→23; device run after patches
  - `[false]` `[reject]` IA sale/stock atomicity untested — adapters carry no `@Transactional`, delegate inside caller tx; device run verifies
  - `[false]` `[reject]` supplier lookup 404 in smoke — original behaviour (`SUPPLIER_NOT_FOUND`)
  - `[false]` `[reject]` bare ObjectMapper in DTO test — superseded by the JSON parity patch

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -DskipTests compile test-compile` -- expected: success
- Same 14-package sequential loop as spec-12-2a -- expected: green
- `grep -E "within module 'inventory'|depends on module 'inventory'" keevo/backend/target/modulith-violations.txt | grep -v "^- Module 'shared'" | wc -l` -- expected: 0

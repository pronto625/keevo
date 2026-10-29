---
title: '12.2c — Public API of commerce (sales, day closure); consumers go through it'
type: 'refactor'
created: '2026-10-29'
status: 'done'
baseline_revision: 'abe9c74c5b6a7cc6a7fe94df69d9b04a3501453d'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2a-api-publique-catalog-crm.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2b-api-publique-inventory.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `sync`, `messaging` and `reporting` use 22 internal `commerce` types (`Sale`, `SaleItem`, `DayClosure`, `DayClosureRepository`, `SaleRepository`, sale/closure use cases and commands, status/payment enums), so the POS module cannot evolve or be gated on its own (MOD-NFR3).

**Approach:** Apply the 12.2a/12.2b API pattern (read their Decisions and Implementation Notes): `commerce/api` ports + records, event types exposed as `events`, all non-`shared` consumers rewired, the published-module architecture assertion extended to `commerce`. Also fix the pre-existing variant mismatch routed here.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- Sale/closure events (`DayClosedEvent`, `SalePendingValidationEvent`, `SaleManuallyValidatedEvent`, `SaleDraftProductsUpgradedEvent`, `SaleCompletedEvent`, `SaleCancelledEvent`, `SaleCorrectedEvent`…) currently live in `commerce.sale.domain.model` next to entities: move them to `commerce.sale.domain.event` (package-level `@NamedInterface("events")`) — one events package for the module, no type-level workaround.
- Payloads that messaging/reporting read from `Sale`/`SaleItem`/`DayClosure`/`DayClosureSummary` become API records (views), never the entities.
- `ValidateSaleService` and `SaleValidationCascadeService` check availability with the item's variant (`item.getVariantId()`), aligned with `RecordSaleService` (routed from review 12-2a/12-2b) — pinned by tests.

## Boundaries & Constraints

**Always:** REST/sync contracts, tables, Flyway unchanged; sale + stock + closure transactional boundaries preserved; all test packages green; not-allowed 0; published-module assertion green for catalog, crm, inventory, commerce.

**Never:** no change to `shared` beyond imports forced by moves, no new business behaviour except the variant-check fix.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Sync push sale / cancel / validate / close day | same payloads | same server state, same responses | same domainCodes |
| Validate pending sale of a variant item | variant stock 1, product-level stock 0, qty 1 | validated (checked on the variant level) | variant stock 0 → INSUFFICIENT_STOCK as today's RecordSale |
| End-of-day report / WhatsApp closure message | day closed | same report and message content | unchanged |
| Pending-sale notifications | sale pending validation | same notifications | unchanged |
| Architecture report | after change | 0 non-exposed violations from non-`shared` modules into `commerce` | — |

</frozen-after-approval>

## Code Map

- Consumers (violations report after 12.2b): `sync` → `RecordSaleUseCase`(+`RecordSaleCommand`, `SaleItemCommand`), `ValidateSaleUseCase`(+command), `CancelPendingSaleUseCase`(+command), `CloseDayUseCase`(+command), `DayClosure`, `PaymentMode`, `SaleStatus`, `SaleDraftProductsUpgradedEvent`; `messaging` → `Sale`, `SaleItem`, `SaleRepository`, `DayClosureSummary`, `DayClosedEvent`, `SalePendingValidationEvent`, `SaleManuallyValidatedEvent`, `SaleDraftProductsUpgradedEvent`; `reporting` → `DayClosure`, `DayClosureRepository`, `ClosureWindow`, `DayClosedEvent`.
- `commerce.sale.domain.port.in.*` already are use-case interfaces — reuse them behind `commerce.api` instead of duplicating.
- Event types today in `commerce/sale/domain/model/` — move to `commerce/sale/domain/event/` (git mv + references; `shared/AuditEventListener` imports change only).
- `keevo/backend/src/test/java/com/keevo/architecture/ApplicationModulesBaselineTest.java` — add `commerce` to `PUBLISHED_API_MODULES`.

## Tasks & Acceptance

**Execution:**
- [x] Move commerce events to `commerce.sale.domain.event` (events named interface).
- [x] Create `commerce/api` (ports/records for sales, closures, closure windows/summaries) with package-private adapters delegating to existing use cases/repositories (no `@Transactional` of their own); null-validated input records.
- [x] Rewire sync, messaging, reporting; update `allowedDependencies` to `commerce :: api` / `commerce :: events`.
- [x] Variant availability fix in `ValidateSaleService` and `SaleValidationCascadeService` + tests.
- [x] Extend the published-module assertion; unit-test adapters; record before/after counts in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the architecture report, when regenerated, then no module other than `shared` violates into `commerce`, and the total decreases vs 833.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Events (2026-10-29).** `git mv` of the 9 event records (`DayClosedEvent`, `SaleAutoValidatedEvent`, `SaleCancelledEvent`, `SaleCompletedEvent`, `SaleCorrectedEvent`, `SaleDraftProductsUpgradedEvent`, `SaleManuallyValidatedEvent`, `SalePendingValidationEvent`, `StockForcedZeroEvent`) from `commerce.sale.domain.model` to `commerce.sale.domain.event`, declared at package level `@NamedInterface("events")` — the module's only event package, no type-level workaround. Payloads unchanged. `shared` edits are import-only: `AuditEventListener` (`domain.model.*` → `domain.event.*`) and `BusinessMetricsListener`.

**Published API — `commerce.api` (`@NamedInterface("api")`).**
- Ports: `SaleCommandPort` (`recordSale`, `validateSale`, `cancelSale`), `SaleQueryPort` (`findById` → `SaleView`), `DayClosureCommandPort` (`closeDay` → `DayClosureView`), `DayClosureQueryPort` (`findById`, `findByStoreAndDate`, `findPreviousForStore`).
- Records: inputs `RecordSaleInput` (+ `SaleLineInput`), `ValidateSaleInput`, `CancelSaleInput`, `CloseDayInput` — required ids / payment mode / items null-checked → `VALIDATION_ERROR` (the sync handlers already reject those fields earlier, so sync outcomes are unchanged); views `SaleView` (+ `SaleItemView`), `DayClosureView` (`receivedAt` with the legacy fallback to `closedAt`); enums `SalePaymentMode`, `SaleState` (same constants as `PaymentMode` / `SaleStatus`, mapped by name, pinned by a test).
- `ClosureWindow` and `DayClosureSummary` were moved (`git mv`) into `commerce.api`: both are pure value records, carried by `DayClosedEvent` and used by reporting to build its windows. `ClosureWindow.forClosure` now takes `DayClosureView` instead of the `DayClosure` aggregate; `DayClosure.toView()` gives the view (used by `CloseDayService` and the adapters). Window computation unchanged (`ClosureWindowTest` / `CloseDayServiceTest` green).
- Internal adapters, package-private, in `commerce.sale.adapter.in.api`: `SaleCommandAdapter` (→ `RecordSaleUseCase`, `ValidateSaleUseCase`, `CancelPendingSaleUseCase`), `SaleQueryAdapter` (→ `SaleRepository` port), `DayClosureApiAdapter` (→ `CloseDayUseCase`, `DayClosureRepository` port), mapper `SaleViews`. None has `@Transactional`: the use cases keep theirs, so sale + stock movements and closure stay atomic as before. Cross-package tests use `CommerceSaleApiTestSupport` (test source), which builds the real adapters over the mocked use cases, so the existing sync/messaging/reporting assertions on the use-case commands still hold.

**Consumers.** sync: `SaleSyncHandler`, `ValidateSaleSyncHandler`, `CancelSaleSyncHandler` → `SaleCommandPort`; `DayClosureSyncHandler` → `DayClosureCommandPort` (`closure.id()`); `SaleSyncHandler` still publishes `SaleDraftProductsUpgradedEvent` (via `commerce :: events`). messaging: `SaleDraftValidatedNotificationListener` → `SaleQueryPort`/`SaleView`; `DayClosureNotificationListener` reads `DayClosureSummary` from the api. reporting: `ReportController` (`trigger-daily`) → `DayClosureQueryPort.findByStoreAndDate`; `EndOfDayPdfModel` → `DayClosureQueryPort` + `ClosureWindow.forClosure(DayClosureView, …)`; report builders/generators import `ClosureWindow` from the api.

**allowedDependencies.** sync, messaging, reporting: `"commerce"` → `"commerce :: api", "commerce :: events"`. `ApplicationModulesBaselineTest.PUBLISHED_API_MODULES` now includes `commerce`.

**Variant availability fix.** `ValidateSaleService.validateSale` and `SaleValidationCascadeService` now read `availableQuantity(product, item.getVariantId(), store)` — the level they decrement — as `RecordSaleService` does. Pinned by 4 tests (variant stock 1 / product-level 0 → validated with a −1 SALE movement on the variant; variant stock 0 → manual validation forced to zero with no decrement, cascade leaves the sale pending). Simple products (variant null) behave exactly as before.

**Deviation — matrix row "variant stock 0 → INSUFFICIENT_STOCK".** Manual validation is a force-validation (Story 4.3 AC9): an item short of stock is forced to zero (`StockForcedZeroEvent`), never refused; the cascade simply leaves the sale pending. Making it throw `INSUFFICIENT_STOCK` would be a new business behaviour (forbidden by "Never"). With the fix the variant level drives the existing force-to-zero / stay-pending rules; the old failure mode (product-level stock > 0 allowing a decrement on an empty variant, which the inventory then refused with `INSUFFICIENT_STOCK`) is gone. Needs the orchestrator's confirmation.

**Violations (`target/modulith-violations.txt`, before = 12.2b / abe9c74c, after = this change).**

| Edge into commerce | Before | After |
|---|---|---|
| messaging → commerce | 8 | 0 |
| reporting → commerce | 14 | 0 |
| sync → commerce | 13 | 0 |
| shared → commerce (out of scope, 12.2d) | 113 | 0 (only events were referenced) |
| **Total** | **833** (140 cycles, 693 non-exposed) | **685** (140 cycles, 545 non-exposed) |

Not-allowed stays 0; cycles unchanged (they run through identity/store/subscription/messaging edges owned by 12.2d–e). Published-module assertion green for catalog, crm, inventory, commerce.

- **Orchestrator ruling (2026-10-29):** matrix row "variant stock 0 → INSUFFICIENT_STOCK" was a planning error — manual/cascade validation is a *forced* validation (short items forced to zero via `StockForcedZeroEvent`, auto-validation leaves the sale pending). The implemented behaviour (variant level decides, existing force-to-zero / stay-pending rules) is accepted as the intent.

## Spec Change Log

## Review Triage Log

Reviewed together with 12-2d — full triage in `spec-12-2d-api-publiques-identity-store-subscription.md` § Review Triage Log (2026-10-29).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -DskipTests compile test-compile` -- expected: success
- Same 14-package sequential loop as spec-12-2a -- expected: green
- `grep -E "within module 'commerce'|depends on (module|named interface\(s\)) 'commerce" keevo/backend/target/modulith-violations.txt | grep -v "^- Module 'shared'" | wc -l` -- expected: 0

---
title: '12.2a — Public APIs of catalog and crm; every other module goes through them'
type: 'refactor'
created: '2026-10-29'
status: 'done'
baseline_revision: '7fcb701699043516cb9a1cdaec78b95eef597842'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/spec-socle-modulaire.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Other modules reach into `catalog` (36 internal types: JPA entities, Spring repositories, concrete use cases, web DTOs) and `crm` (24 types), so neither can later be activated/deactivated or evolve on its own (MOD-NFR3, spec-socle §6/§13). `catalog` itself calls crm use cases and DTOs directly (`ProductController`).

**Approach:** Publish a small `api` named interface per module (ports + API records), expose the existing `domain.event` packages as an `events` named interface, and rewire every non-`shared` consumer to depend only on those. Behaviour, REST and sync contracts unchanged.

**Decisions (autonomous run, user delegated all choices 2026-10-28) — API pattern shared by 12.2a–e:**
- `com.keevo.<module>.api` (`@NamedInterface("api")` in its `package-info.java`) holds only interfaces (query/command ports), immutable records (inputs/outputs) and API exceptions; implementations stay internal (adapters implementing the port). No JPA entity, Spring repository, concrete use case or web DTO in `api`.
- Domain events stay where they are; each module's event packages are declared `@NamedInterface("events")`. Moving them to `core.event.catalog` is 12.3 (avoids a double move).
- `sync` handlers stay in `sync` but use only `catalog :: api` / `crm :: api` / `* :: events`; relocating handlers into modules is 12.7.
- `shared → catalog/crm` dependencies are out of scope (12.2d core extraction).
- `allowedDependencies` reference named interfaces (`"catalog :: api"`, `"catalog :: events"`, `"crm :: api"`, `"crm :: events"`) for every consumer of these two modules.

## Boundaries & Constraints

**Always:** REST paths/payloads, sync push/pull payloads, tables and Flyway unchanged; transactional boundaries preserved (a port call inside an existing transaction stays inside it); all test packages green; `ApplicationModulesBaselineTest` not-allowed stays 0.

**Never:** no change to `shared`, no event relocation, no new business behaviour, no Flutter/dashboard change.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Sync push product/category/supplier/client ops | same payloads as today | same server state and responses | same domainCodes |
| Onboarding sector template | owner picks a sector | same categories created (identity → catalog api) | unchanged |
| Product supplier lookup | `GET /api/v1/products/{id}/supplier` | same JSON | unchanged |
| Architecture report | after the change | 0 non-exposed violations from any module except `shared` into `catalog`/`crm` | — |

</frozen-after-approval>

## Code Map

Consumers to rewire (from `target/modulith-violations.txt`, 2026-10-29):
- `sync` → catalog: `Create/Update/Archive/Unarchive/CreateDraftProductUseCase` (+ nested DTO/Command records), `Create/Rename/ToggleCategoryUseCase`, `Product`, `ProductRepository`, `Category`, `CategoryRepository` -- sync handlers under `keevo/backend/src/main/java/com/keevo/sync/`.
- `sync` → crm: `Update/Archive/Unarchive{Client,Supplier}UseCase` (+ commands), `Client`, `Supplier`, `ClientRepository`, `SupplierRepository`.
- `inventory` → catalog: `Product`, `ProductRepository`, `ProductStatus`, `ProductCreatedEvent`, `ProductResponseDto`, `CategoryRepository`.
- `identity` → catalog: `CategoryRepository`, `Category` (onboarding sector templates).
- `commerce` → catalog: `ProductSpringRepository` (!), `ProductStatus`, `ProductActivatedEvent`, `SalePriceOverriddenEvent`.
- `messaging` → catalog: `ProductCreatedEvent`, `CsvImportCompletedEvent` (events → fine via `events`).
- `catalog` → crm: `ProductController` uses `GetSupplierByProductUseCase`, `GetSupplierProfileUseCase(+Result)`, `SupplierResponseDto`, `Supplier`.
- Event packages: `catalog/{product,category}/domain/event`, `crm/contact/domain/event`.
- Reuse existing in-module ports where they already fit (`domain/port/in`), adapt them into `api` interfaces rather than duplicating logic.

## Tasks & Acceptance

**Execution:**
- [x] Create `catalog/api` and `crm/api` (package-info with `@NamedInterface("api")`) with the ports/records needed by the consumers above; internal adapters implement them by delegating to the existing use cases/repositories.
- [x] Declare the event packages `@NamedInterface("events")`.
- [x] Rewire every consumer listed above; update `allowedDependencies` to the named-interface form.
- [x] Unit-test each new port adapter (delegation + mapping); keep existing handler/controller tests green, adapting mocks to the ports.
- [x] Record before/after violation counts by target module in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the architecture report, when regenerated, then no module other than `shared` has a non-exposed or not-allowed violation into `catalog` or `crm`, and total violations decrease.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Published API (2026-10-29).**
- `catalog.api` (`@NamedInterface("api")`): `ProductQueryPort`, `ProductCommandPort`, `CategoryQueryPort`, `CategoryCommandPort`; records `ProductView`, `CategoryView`, `CreateProductInput`, `UpdateProductInput`, `CreateDraftProductInput`, `RegisterProductInput`; enum `ProductState` (same constants as the internal `ProductStatus`, so JSON is unchanged).
- `crm.api` (`@NamedInterface("api")`): `ClientQueryPort`, `ClientCommandPort`, `SupplierQueryPort`, `SupplierCommandPort`; records `ClientView`, `SupplierView` (with `productIds`), `CreateClientInput`, `UpdateClientInput`, `CreateSupplierInput`, `UpdateSupplierInput`.
- Internal adapters: `catalog.product.adapter.in.api.{ProductQueryAdapter, ProductCommandAdapter, ProductViews}`, `catalog.category.adapter.in.api.{CategoryQueryAdapter, CategoryCommandAdapter, CategoryViews}`, `crm.contact.adapter.in.api.{ClientApiAdapter, SupplierApiAdapter, ContactViews}`. They only delegate to the existing use cases/repositories and carry no `@Transactional` of their own, so a port call joins the caller's transaction exactly as the former direct call did.
- REST parity: `GET /products/{id}/supplier` now answers `ProductSupplierResponseDto` (catalog) and `PATCH /products/{id}/threshold` answers `ProductThresholdResponseDto` (inventory); both mirror the replaced DTOs field-for-field, guarded by JSON-equality tests (`ProductSupplierResponseDtoTest`, `ProductThresholdResponseDtoTest`).
- Status lookup for commerce (`ProductQueryPort.findStatusName`) still reads the JPA entity's status directly (no domain mapping), as `ProductStatusPortAdapter` did.

**`catalog :: events` and Modulith 1.4.** Two *package-level* `@NamedInterface("events")` declarations in one module are not merged (`NamedInterfaces.getByName` returns the first), so only one catalog event package was reachable through `"catalog :: events"` (messaging/commerce/inventory kept 6 violations on product events). Fix: `catalog.product.domain.event` and `crm.contact.domain.event` are declared at package level; the 3 category events carry `@NamedInterface("events")` at type level, which Modulith merges into the package-level interface. The category `package-info` documents that a new event there must carry the annotation.

**allowedDependencies** (named-interface form for every consumer of catalog/crm): catalog `crm :: api`; commerce `catalog :: api`, `catalog :: events`; identity `catalog :: api`; inventory `catalog :: api`, `catalog :: events`; messaging `catalog :: events`; sync `catalog :: api`, `crm :: api`.

**Violations (target/modulith-violations.txt, before = 7fcb7016, after = this change).**

| Edge into catalog / crm | Before | After |
|---|---|---|
| commerce → catalog | 4 | 0 |
| identity → catalog | 2 | 0 |
| inventory → catalog | 25 | 0 |
| messaging → catalog | 3 | 0 |
| sync → catalog | 18 | 0 |
| catalog → crm | 5 | 0 |
| sync → crm | 14 | 0 |
| shared → catalog (out of scope, 12.2d) | 131 | 8 (`ProductStatus` in `ProductJpaEntity`) |
| shared → crm (out of scope, 12.2d) | 83 | 0 (only events were referenced) |
| **Total** | **1308** (165 cycles, 1143 non-exposed) | **1031** (165 cycles, 866 non-exposed) |

Not-allowed stays 0. Cycles unchanged (catalog↔identity through `PlanType` etc. are 12.2d).

**Small behavioural notes.**
- `CREATE_SUPPLIER` sync: the `productIds` strings are parsed before the insert instead of after it; a malformed id is now rejected before anything is written (previously after the supplier row was saved, inside the same rejected operation).
- Onboarding: the seeded categories' `createdAt` is taken inside the catalog adapter (a few microseconds apart from the onboarding `now`).
- `SetStockThresholdUseCase` reads the product through the query port, then changes it through the command port (two lookups in the same transaction instead of one).
- Hard edge `catalog → crm :: api` (product supplier lookup) remains a `requires`-style dependency on an optional module; turning it into a capability is a target-catalogue concern for Epic 13, not changed here.

## Spec Change Log

## Review Triage Log

Reviewed together with 12-2b — full triage in `spec-12-2b-api-publique-inventory.md` § Review Triage Log (2026-10-29).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -DskipTests compile test-compile` -- expected: success
- For each `P` in `architecture catalog crm inventory commerce sync identity messaging reporting shared admin store subscription feedback`: `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest="com.keevo.$P.**" -DforkCount=1 -DargLine=-Xmx1536m -Dsurefire.failIfNoSpecifiedTests=false` -- expected: green, sequential
- `grep -E "within module '(catalog|crm)'|depends on module '(catalog|crm)'" keevo/backend/target/modulith-violations.txt | grep -v "^- Module 'shared'" | wc -l` -- expected: 0

# Epic 12 Context: Découplage en Bounded Contexts (Phase 2)

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Turn Keevo's implicit cross-domain coupling into explicit composition, so each business domain can later become a self-contained "mini-app" that is activated or deactivated per tenant (Epic 13 onward). Today, domains import each other directly. This epic replaces those imports with published `{module}.api` contracts and with events from a shared catalogue. It also realigns misplaced code toward the target module catalogue and makes Spring Modulith `verify()` and ArchUnit blocking in CI. The business logic is not rewritten. Work follows a strangler approach: production keeps running and the tests stay green after every step.

## Stories

- Story 12.1: Declare the Spring Modulith application modules
- Story 12.2a: Public API for `catalog` (the most depended-on module)
- Story 12.2b: Public API for `inventory`
- Story 12.2c: Public API for `commerce`
- Story 12.2d: Public APIs for `identity`, `store` and `subscription`
- Story 12.2e: Public APIs for `reporting`, `messaging` and `sync`, and `verify()` made BLOCKING
- Story 12.3: Move events to the Modulith registry and a shared event catalogue
- Story 12.4: Make the event registry work with schema-per-tenant
- Story 12.5: Re-align misplaced modules (inventory, payment, warehouse)
- Story 12.6: Additional ArchUnit rules, plus the items deferred from 10-6
- Story 12.7: Make the sync engine module-aware

## Requirements & Constraints

- **Allowed dependencies (target).** A module may depend only on `core`, on another module's `{module}.api`, and on `core.event.catalog`. Every other package is internal.
- **No cross-module table access.** A module never reads another module's tables. It goes through the other module's API port or capability, or through events.
- **Dependency model to prepare for.** `requires` is a hard dependency: pos needs catalog and inventory, inventory needs catalog, transfers need inventory and store. `enhances` is an optional dependency, satisfied by events or capabilities. The golden rule: never add a hard dependency on an optional business module. Any decoupling choice made now must not create a `requires` edge that the target catalogue does not list.
- **Reliable events.** Events are persisted atomically with the business change. No event is lost on commit and no ghost event is sent on rollback. Events are replayed after a restart, so every handler must be idempotent, keyed on `(eventId, listenerId)`.
- **Tenant isolation.** The schema-per-tenant model is kept. Replayed or async handlers must run in the event's tenant schema.
- **Open/Closed.** Adding a module must not require changing `core` or any other module.
- **Stable contracts.** REST endpoints stay stable, and Flyway table definitions are unchanged by package moves.
- **Testing.** Run tests in chunks, never the whole suite at once.

## Technical Decisions

- **Stack.** Spring Boot 3.5.0, Java 21, Spring Modulith BOM 1.4.0, `archunit-junit5`.
- **Current state (verified).**
  - There are 12 top-level packages under `com.keevo`: `admin`, `catalog`, `commerce`, `feedback`, `identity`, `inventory`, `messaging`, `reporting`, `shared`, `store`, `subscription`, `sync`. No `core` package exists yet. `feedback` is missing from the epic's module list.
  - Every package has a `package-info.java` with `@ApplicationModule`. `shared` is `Type.OPEN`. `identity` and `messaging` have no `allowedDependencies`. The other modules have permissive lists that omit `shared`.
  - The baseline tests live in `src/test/java/com/keevo/architecture/`. They write `target/modulith-violations.txt` and `target/archunit-violations.txt` and never fail the build. Documenter output goes to `target/spring-modulith-docs/`.
- **Modulith baseline: 2,153 violations.**
  - 77 are cycles, for example catalog↔identity through `PlanType` and the onboarding `CategoryRepository`.
  - 1,196 are non-exposed-type violations: `shared`→others 739, `messaging`→others 397, `identity`→others 60.
  - About 880 are disallowed dependencies. About 560 of these are only X→`shared` caused by allow-lists that omit `shared`. The real ones include sync→catalog 37, reporting→identity 32, inventory→catalog 25, subscription→identity 23 and commerce→catalog 18.
  - ArchUnit reports 8 `@Entity` classes in `shared.infrastructure.persistence.entity`. These include Product, Stock*, Supplier and Client. `shared` also uses identity repositories, which means `shared` is reaching back into business modules.
- **Target vs current packages.**
  - **`core`** is extracted from `shared` plus the identity and tenant parts of `identity`. It holds identity, tenant, provisioning, audit and the shared kernel (`Money`, `TenantId`, `ActorId`, `DomainEvent`) and has an `event/catalog/` sub-package. `core` must not depend on any business module, so the misplaced entities and repositories in `shared` move back to the modules that own them.
  - **`crm`** (clients and suppliers) is separated from `catalog/contact`. **`inventory`** takes stock from `catalog/stock`. **Transfers** are isolated out of `catalog/stock` into their own module, with `inventory` and `store` as hard dependencies. **`counting`** stays as `inventory/counting`. `pos` corresponds to `commerce/sale`.
  - Locked decisions: remove the `commerce/payment` placeholder (payment mode stays on `Sale`). Remove `store/warehouse` (a warehouse is a store type). Remove the empty `inventory/stock` and `inventory/transfer` placeholders.
- **API pattern (12.2x).**
  - `{module}/api/` contains only interfaces and ports, DTOs and events. It never exposes entities or services.
  - Capability `*Port` interfaces live in the provider module's `api`, for example `ClientLookupPort` in crm.
  - Each broken dependency is replaced either by an API call or by an event.
- **Events (12.3/12.4).**
  - Producers publish through the standard `ApplicationEventPublisher`. Consumers use `@ApplicationModuleListener`.
  - Each event implements the `DomainEvent` envelope: `eventId`, `tenantId`, `actorId`, `occurredAt`, `type`. Contracts are lightweight records in `core.event.catalog`, so a consumer never imports the producer's `api`.
  - Use `spring-modulith-starter-jdbc` with the `event_publication` table in `public`. Flyway owns that table, so schema initialization is disabled.
  - Set `republish-outstanding-events-on-restart=true`, use completion mode UPDATE or ARCHIVE, and enable the staleness monitor. This replaces any home-grown outbox.
  - A decorator sets `TenantContext` from `event.tenantId()` before each async listener runs and clears it in `finally`.
- **Guardrails.** `verify()` covers internal access, allowed dependencies and cross-module imports. ArchUnit adds four rules:
  - `@Entity` and `@Repository` only under `adapter/out/persistence`.
  - No HTTP or Spring-web types in `port/in` or `application/service`.
  - Schema interpolation must be validated against `^kv_[a-z0-9]+$`.
  - An acyclic `requires` graph. This rule is written now but only enabled in Epic 13, once manifests exist.

## Cross-Story Dependencies

- **Ordering.**
  - 12.1 comes first. The 12.2a…e stories follow from the most depended-on module down.
  - 12.2e makes `verify()` blocking, together with 12.6.
  - 12.5 moves stock, transfers and the crm split. Coordinate it with 12.2a and 12.2b, or do it before them, so the APIs are not published twice.
  - 12.3 comes before 12.4. Events created to break dependencies in 12.2x go straight into `core.event.catalog`.
- **Items routed to 12.6 from 10-6.**
  - Add a ratchet on the known violation set.
  - Align the identity, messaging, catalog and store allow-lists. These must include `shared`/`core`.
  - Wrap Documenter generation in a try/catch.
  - Use `DO_NOT_INCLUDE_TESTS`.
  - Set `if-no-files-found: warn` on the CI artifact upload.
  - Manage dependency scopes and versions through the BOM.
- **Cross-epic.**
  - 12.7 depends on 13.2 (`ModuleRegistry`). Either schedule it after 13.2 or stub the registry. It turns the hard-coded `SyncOperationHandlerRegistry` into handlers contributed by each module.
  - Epic 11 is being finished in parallel on `refonte/modulaire`.

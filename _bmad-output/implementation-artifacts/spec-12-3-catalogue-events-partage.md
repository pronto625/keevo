---
title: '12.3 — Shared event catalogue: cross-module event contracts live in the core, with a common envelope'
type: 'refactor'
created: '2026-10-29'
status: 'done'
baseline_revision: '4dacb9ad98ed37742b700ee30a5050d986193b84'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2f-extraction-core-shared.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Inter-module events still live in their producer modules (`*.domain.event`, exposed as `:: events` with a type-level `@NamedInterface` workaround, journal A9). Consumers therefore depend on producers: messaging listens to catalog/commerce/identity events while those modules call `messaging :: api`, which forms the 4 remaining two-module cycles (55 cycle violations); events also carry internal or API types (`MovementType`, `SectorType`, `InventoryScope`, `DayClosureSummary`, `StoreType`) that force extra `shared` allow-list entries (spec-socle §7.4: "the consumer never depends on the producer's api").

**Approach:** Move every event consumed outside its producer module into `com.keevo.shared.event.catalog` (the core-to-be; spec-socle `core/event/catalog`), give them a common `DomainEvent` envelope, and replace carried internal/API types by catalogue-level types. Producers publish catalogue events; consumers depend on `shared` only. No change to publication or listener semantics (that is 12.4).

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- Package `com.keevo.shared.event.catalog` (sub-packages per producer domain allowed, e.g. `catalog.sale`, `catalog.stock`); renaming `shared` → `core` happens later, so no `core` package is created now.
- `DomainEvent` interface in the catalogue: `String tenantId()` (tenant schema), `Instant occurredAt()`, `UUID actorId()` (nullable for system events). `eventId`/`type` are NOT added now (12.4 registry identifies publications itself); existing payload fields and names stay so audit/notification content is identical.
- Event-carried types become catalogue types: `MovementKind`, `SectorKind`, `InventoryScopeKind` enums and value records for `DayClosureSummary` / store type, mapped by name from the internal ones (same constants → same audit JSON).
- Events used only inside their producer module stay where they are.
- Remove the type-level `@NamedInterface("events")` workaround and the `:: events` named interfaces that become empty; `allowedDependencies` drop the matching `X :: events` entries.
- Routed items handled here: `MovementType` no longer exported (review 12-2a/b); draft tracking via events instead of `DraftValidationCommandPort` (review 12-2e) — do it if it removes the catalog ↔ messaging cycle without behaviour change, otherwise leave routed to 12.4 and say so.

## Boundaries & Constraints

**Always:** event payload field names/values unchanged (audit rows, notifications, metrics identical); all listeners keep their current annotations and transactional phase; all test packages green; not-allowed 0; published-module and shared assertions green.

**Never:** no listener converted to `@ApplicationModuleListener`, no registry, no new behaviour (12.4).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Any audited business action | event published | identical `audit_log` row (action, details JSON) | unchanged |
| Notifications (stock alert, transfer, closure, product creation, draft validated, plan) | event published | same recipients and texts | unchanged |
| Business metrics | sale completed | same counters | unchanged |
| Architecture report | after change | no two-module cycle involving messaging and catalog/commerce/identity; fewer cycles than 55; `shared` allow-list has no `X :: api` entry | — |

</frozen-after-approval>

## Code Map

- Producers' event packages: `catalog/{product,category}/domain/event`, `crm/contact/domain/event`, `inventory/{stock,transfer,counting}/domain/event`, `commerce/sale/domain/event`, `identity/**/domain/model/*Event` (+ onboarding/auth events), `store/**/event`, `subscription/plan/domain/model/event`, `reporting/export/domain/event`.
- Consumers: `shared/infrastructure/web/AuditEventListener.java` (most events), `shared/infrastructure/observability/BusinessMetricsListener.java`, `messaging/notification/application/listener/*`, `reporting/report/application/service/EndOfDayReportListener.java`, `commerce/sale/application/service/DayClosureWhatsAppListener.java`, `admin/**` listeners, `subscription/plan/application/service/PlanWhatsAppNotificationListener.java`, `identity/onboarding/application/service/DayClosureSchedulerNotifier.java`.
- Use the violations report's cycle section and `grep -r "domain.event\|Event;" ` per consumer to list cross-module events; anything with a consumer outside its producer moves.
- `ApplicationModulesBaselineTest` / `shared/package-info.java` — update allow-lists and comments.

## Tasks & Acceptance

**Execution:**
- [x] Create the catalogue + `DomainEvent`; git mv each cross-module event, implement the envelope, swap carried internal types for catalogue types (mapping at publication).
- [x] Rewire producers and consumers; drop emptied `:: events` interfaces and the type-level workaround; update `allowedDependencies`.
- [x] Add a test that serialises representative events (audit details JSON) before/after to prove identical payloads.
- [x] Record before/after cycles and violations in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the architecture report, when regenerated, then the messaging ↔ catalog/commerce/identity cycles are gone and total cycles < 55.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Progress — task 1 done (2026-10-29).** 55 cross-module events `git mv`'d to `com.keevo.shared.event.catalog.{auth,category,contact,counting,employee,export,onboarding,plan,product,sale,stock,store,transfer}` (+ `commerce.api.DayClosureSummary` → `catalog.sale`); `DomainEvent` + `MovementKind`/`SectorKind`/`InventoryScopeKind`/`StoreKind` created, mapped by name at publication; `DayClosedEvent.window()` replaced by `commerce.api.ClosureWindow.of(event)`. `compile test-compile` OK.

**Progress — tasks 2–3 done (2026-10-29).** All `:: events` named interfaces and type-level workarounds removed; allow-lists trimmed; legacy `DayClosureWhatsAppListener` + report strategies moved commerce → messaging; `CatalogueEventPayloadTest` goldens captured on a baseline worktree (identical). Architecture tests green, violations 55 → 38. Next: 14-package run.

**Catalogue.** 55 events consumed outside their producer moved (`git mv`, payload untouched) to `com.keevo.shared.event.catalog.<domain>`: `category` (3), `product` (8), `sale` (9 + `DayClosureSummary`, moved from `commerce.api` — a pure value record, one type instead of a duplicate), `contact` (8), `auth` (6), `employee` (3), `onboarding` (2), `counting` (5), `stock` (3), `transfer` (1), `export` (1), `store` (4), `plan` (2). Producer-only events stay (`ProductCreatedProgressively`, `ProductImportedFromCsv`, `EmployeeCreated/Deactivated/PasswordSet/StoreReassigned`, `PreferenceUpdated`, `StockTransferred`, `PlanExpired`, admin/sync events).

**Envelope.** `DomainEvent { String tenantId(); Instant occurredAt(); UUID actorId(); }` implemented by 48 events from their existing components; `UserAuthenticatedEvent.actorId()` = `userId`; `SaleCompletedEvent` (class) gets the three accessors next to its getters. **Not enveloped** (would require renaming a payload field, forbidden): `AccountDeletionRequested/Cancelled`, `UserRegistered`, `PlanActivated/Downgraded` (component `UUID tenantId`) and `PasswordReset/PasswordResetRequested` (no tenant, cross-tenant) — **routed to 12.4** (registry tenant key / TenantContext decorator must handle them). `PlanType` (identity :: api) stays in the plan events — shared → identity :: api is already accepted.

**Carried types.** `MovementKind`, `SectorKind`, `InventoryScopeKind`, `StoreKind` (same constants) mapped by name at publication (`StockOperationService`, `OnboardingService`, `CreateInventorySessionService`, `CreateStoreService`, null-safe). `DayClosedEvent.window()` (returned `commerce.api.ClosureWindow`) removed; `ClosureWindow.of(DayClosedEvent)` added in commerce.api (same values; `EndOfDayReportListener`, `CloseDayServiceTest`). `MovementType` no longer exported (routed item 12-2a/b closed).

**Cycle-breaking relocation.** The legacy `DayClosureWhatsAppListener` (off by default, `keevo.reporting.legacy-listener`) and its `ClosureReportStrategy`/`Manual`/`Auto` strategies moved commerce → `messaging.notification.application.{listener,strategy}` (same condition, AFTER_COMMIT, same texts; test moved). It was commerce's only use of messaging, so commerce no longer depends on messaging.

**Named interfaces / allow-lists.** All `@NamedInterface("events")` removed (package-level and type-level on `MovementType`, `SectorType`, `InventoryScope`, `StockTransferredEvent`, `PreferenceUpdatedEvent`, identity auth/onboarding events); emptied event packages' `package-info` deleted, remaining ones made internal. Dropped entries: messaging `catalog/commerce/inventory :: events`; admin `subscription :: events`; sync and reporting `commerce :: events`; commerce `catalog :: events` + `messaging :: api`; inventory `catalog :: events`. **shared** is now `{"identity :: api"}` only (no other `X :: api`, no `:: events`); identity :: api remains because audit uses `UserQueryPort` (12.2f, identity = core-to-be).

**Guards.** `ApplicationModulesBaselineTest` asserts no two-module cycle messaging ↔ catalog / messaging ↔ commerce (`isTwoModuleCycle`, covered in `ModulithViolationClassifierTest`). `CatalogueEventPayloadTest` (shared) snapshots 8 representative events (StockAdjusted/TRANSFER_OUT, OnboardingCompleted, InventorySessionCreated, StoreCreated/WAREHOUSE, DayClosed, SaleCompleted, ProductCreated, ClientCreated): Jackson JSON of the event + the full `AuditEntry` written by `AuditEventListener`. Goldens were produced by the same snapshot code (types reverse-mapped) run on a throwaway worktree at baseline `4dacb9ad` — **byte-identical** to the post-change output; plus an envelope test.

**Draft tracking via events (routed from 12-2e).** Not needed: catalog ↔ messaging disappeared once messaging stopped importing catalog events (catalog → messaging via `DraftValidationCommandPort` alone is acyclic). Left **routed to 12.4** as spec allows.

**Violations (`target/modulith-violations.txt`).**

| | Before (12.2f) | After |
|---|---|---|
| Total | 55 | **38** |
| Cycles | 55 | **38** |
| Non-exposed | 0 | 0 |
| Not-allowed | 0 | 0 |

Remaining two-module cycles: `identity ↔ messaging` and `identity ↔ subscription` (16.8). **AC gap:** `identity ↔ messaging` is API-only in both directions (identity → `WhatsAppPort` for OTP / deletion / test report; messaging → `EmployeeQueryPort`, `OwnerRecipientResolver`, `TenantPreferencesQueryPort`, `UserQueryPort`), no event involved, so the event catalogue cannot remove it. Fix needs a decision (e.g. a WhatsApp/notification outbound port owned by core, implemented by messaging) — **routed to 12.6 / core carving, needs orchestrator confirmation**. `messaging → commerce :: api` (`SaleQueryPort` in `SaleDraftValidatedNotificationListener`) remains but no longer closes a two-module cycle.

**Verification (2026-10-29).** `compile test-compile` OK. 14 packages sequential, all rc=0, 0 failures/errors: architecture 16, catalog 240, crm 114, inventory 376, commerce 264, sync 496, identity 435, messaging 155, reporting 389, shared 609, admin 504, store 60, subscription 69, feedback 52 (counts from surefire XML; may include stale reports of moved tests). Not live-tested on a dev backend.

- **Orchestrator rulings (2026-10-29):** (1) AC "messaging ↔ identity cycle gone" partly met — the remaining identity ↔ messaging cycle is API-only (identity → `WhatsAppPort`; messaging → identity query ports), not an event edge; accepted, routed → 12-6 (core-owned notification port implemented by messaging). (2) Relocation of `DayClosureWhatsAppListener` + strategies commerce → messaging accepted (commerce's only messaging use; breaks commerce ↔ messaging). (3) 7 non-enveloped events (UUID tenant / no tenant) → 12-4.

## Spec Change Log

## Review Triage Log

Reviewed together with 12-4 — full triage in `spec-12-4-registre-events-modulith.md` § Review Triage Log (2026-10-29).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -DskipTests compile test-compile` -- expected: success
- Same 14-package sequential loop as spec-12-2a -- expected: green
- `head -6 keevo/backend/target/modulith-violations.txt` -- expected: cycles < 55, not-allowed 0

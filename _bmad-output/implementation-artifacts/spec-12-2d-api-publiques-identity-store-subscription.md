---
title: '12.2d — Public APIs of identity, store and subscription; consumers go through them'
type: 'refactor'
created: '2026-10-29'
status: 'done'
baseline_revision: 'abe9c74c5b6a7cc6a7fe94df69d9b04a3501453d'  # shared with 12-2c (batched review)
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2a-api-publique-catalog-crm.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2b-api-publique-inventory.md'
  - '{project-root}/plan-refonte-modulaire.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Almost every module reaches into `identity` (26 internal types: `Tenant`, `TenantRepository`, `OwnerRecipientResolver`, `Employee`, `TenantPreferences`, `PlanType`, employee use cases…), `store` (8: `Store`, `StoreRepository`, `StoreType`, store use cases) and `subscription` (15: `PlanLimitGuard`, `Subscription`, `SubscriptionRepository`, plan use cases), so tenant, store and plan concepts — the future `core` and the licensing base — have no stable contract (MOD-NFR3, plan §4/§6).

**Approach:** Same API pattern as 12.2a/12.2b (read their Decisions and Implementation Notes): `identity/api`, `store/api`, `subscription/api` ports + records (+ `events` where these modules publish events), all non-`shared` consumers rewired, the published-module architecture assertion extended to the three modules.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- `PlanType` stays a tenant attribute exposed by `identity.api` (as an API enum or the tenant view's field) until 16.8 replaces it with `plan_profiles`; `subscription` and `catalog` read it through `identity.api`.
- Ports a module *needs from others* stay declared by that module in its own `api` and are implemented by the providers (12.2b `InitialStockPort` precedent): `subscription.api.ProductCountPort` (implemented by catalog/inventory), `subscription.api.PlanChangeRecorder`; providers inject nothing internal.
- One tenant convention for the three new APIs: tenant passed explicitly as a `String tenantSchema` parameter on every port method that is tenant-scoped (resolves the review item routed here for these modules; catalog/crm/inventory are aligned in 12.2e).
- `shared → X` violations are NOT in scope: they move to the new story 12-2f (core extraction).

## Boundaries & Constraints

**Always:** REST/sync contracts, JWT claims, tables, Flyway unchanged; plan-limit checks still run before the guarded write in the same transaction; all test packages green; not-allowed 0; published-module assertion green for catalog, crm, inventory, commerce, identity, store, subscription.

**Never:** no change to `shared` beyond imports forced by moves, no plan/limit behaviour change, no new business behaviour.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Plan limit reached (FREE: 1 store / 500 products / 2 employees) | create beyond limit (REST or sync) | rejected as today | same domainCode `PLAN_LIMIT_REACHED` |
| Super-admin activates / changes a plan | admin endpoint | same subscription state, events, WhatsApp message | unchanged |
| Owner-recipient notifications | stock alert / closure / feedback | same recipients | unchanged |
| Employee create/deactivate/reactivate/reassign via sync | same payloads | same state | same domainCodes |
| Store update/deactivate via sync; store lookups by any module | same payloads | same results | unchanged |
| Architecture report | after change | 0 non-exposed violations from non-`shared` modules into identity, store, subscription | — |

</frozen-after-approval>

## Code Map

- Consumers (violations report after 12.2b; rerun after 12.2c):
  - identity ← admin (`AccountDeletionPolicy`, `PlanType`, `Role`, `TokenRevocationPort`, `OwnerRecipientResolver`), catalog (`PlanType`), commerce (`Tenant`, `TenantStatus`, `TenantRepository`, `OwnerRecipientResolver`, `TenantPreferences*`), messaging (`User`, `UserRepository`, `Employee*`, `OwnerRecipientResolver`, `StockAlertChannel`, `TenantPreferences*`), reporting (`Tenant*`, `Employee*`, `ReportChannel`, `TenantPreferences*`, `OwnerRecipientResolver`), subscription (`PlanType`, `Tenant`, `TenantRepository`, `EmployeeCountPort`, `OwnerRecipientResolver`), sync (employee use cases + commands, `Employee`, `CreateEmployeeResult`).
  - store ← catalog, commerce, identity, inventory, messaging, reporting, sync (`Store`, `StoreRepository`, `StoreType`); sync (`StoreFactory`, `Update/DeactivateStoreUseCase` + commands).
  - subscription ← catalog, identity, inventory, store, sync (`PlanLimitGuard`, `Subscription`, `SubscriptionRepository`); catalog/inventory (`ProductCountPort`); identity (`PlanChangeRecorder`, `PlanChangeCause`); admin (`ActivatePlanUseCase`, `ChangeTenantPlanUseCase` + commands, `CurrentPlan`, `PlanActivatedEvent`, `PlanDowngradedEvent`, `ActivatePlanRequest`, `PlanWhatsAppMessages`).
- `OwnerRecipientResolver` is already a port (`identity.auth.domain.port.out`) used by 5 modules — move/alias it into `identity.api`.
- Existing `domain.port.in` interfaces → reuse behind the APIs.
- `ApplicationModulesBaselineTest` `PUBLISHED_API_MODULES` — add identity, store, subscription.

## Tasks & Acceptance

**Execution:**
- [x] Create the three `api` packages (+ `events` named interfaces for their event packages) with package-private adapters delegating to existing code; null-validated input records; explicit `tenantSchema` on tenant-scoped methods.
- [x] Rewire every non-`shared` consumer listed above; update `allowedDependencies` to named-interface form everywhere.
- [x] Extend the published-module assertion; unit-test adapters; record before/after counts and remaining cycles in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the architecture report, when regenerated, then no module other than `shared` violates into identity, store or subscription, and the total decreases vs the 12.2c figure.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Moves (`git mv`, references rewritten; `shared` edits are import-only).**
- into `identity.api`: `PlanType`, `TenantStatus`, `Role`, `AccountDeletionPolicy`, `OwnerRecipientResolver`, `TokenRevocationPort`, `ReportChannel`, `StockAlertChannel`.
- into `store.api`: `StoreType`.
- into `subscription.api`: `PlanChangeRecorder`, `PlanChangeCause`, `CurrentPlan`, `ProductCountPort` (now `countActiveProducts(String tenantSchema)`).
- `ProductCountAdapter` subscription → `catalog.product.adapter.out.persistence` (catalog owns `products`; same JDBC, same NOT_SUPPORTED propagation; test moved too). `EmployeeCountAdapter` subscription → `identity.employee.adapter.out.persistence.impl` (identity owns `employees`; removes `subscription → identity` internal edge). `ActivatePlanRequest` DTO subscription → `admin.tenant.adapter.in.rest.dto` (only admin used it; JSON unchanged). Suspension/reactivation WhatsApp texts moved from `PlanWhatsAppMessages` into admin's `TenantStatusWhatsAppNotificationListener` (same strings).

**Published APIs.**
- `identity.api`: `TenantQueryPort` (`findAll`, `findBySchemaName` → `TenantView`), `TenantPreferencesQueryPort` (`find(tenantSchema)` → `TenantPreferencesView`), `EmployeeQueryPort` (`findById`, `findByUserId`, `findActiveByStoreId` → `EmployeeView`), `UserQueryPort` (`findPhoneNumber`, `findOwnerUserIds`), `EmployeeCommandPort` (`create` → id, `reassignStore`, `deactivate`, `reactivate`; input `CreateEmployeeInput`). Adapters: `identity.{auth,employee,onboarding}.adapter.in.api` (package-private).
- `store.api`: `StoreQueryPort` (`findById`, `findAllActive`, `countActive`), `StoreCommandPort` (`validateName`, `createWithId`, `update`, `deactivate`); records `StoreView`, `UpdateStoreInput`, `CreateStoreWithIdInput`. Adapter `store.store.adapter.in.api.StoreApiAdapter`. `createWithId` carries the former CREATE_STORE handler logic, lock included: per-tenant `store-limit:<schema>` lock (same key as `CreateStoreService`) → existing id → no-op (`false`) → plan check → warehouse uniqueness → insert (`true`), no event. The handler only parses the payload (type parsing now happens before the idempotency read: a replay with an invalid `type` would be REJECTED instead of APPLIED — impossible for a genuine replay, whose first attempt already passed the same parsing).
- `subscription.api`: `SubscriptionQueryPort.findCurrentPlan(tenantSchema)` → `PlanView` (plan, active, expiry + `isPremium/isPremiumTrial/isFree/max*`), `PlanLimitPort.check{Store,Product,Employee}Limit(PlanView, current)` (delegates to `PlanLimitGuard`), `PlanAdministrationPort` (`activatePlan` — REQUIRES_NEW; `currentPlan` / `changePlan` — `@Transactional(MANDATORY)` on the adapter, so the FOR UPDATE lock can only be taken inside the caller's transaction; the admin callers are `@Transactional`; inputs `ActivatePlanInput(UUID actorId, String tenantSchema, …)` and `ChangePlanInput(UUID tenantId, String tenantSchema, …)` — `UUID tenantId` only for tenant UUIDs, `tenantSchema` for schema names). `PlanView` rejects a null plan type; the limit checks reject a null `PlanView` with `SUBSCRIPTION_NOT_FOUND`. `findCurrentPlan` returns what `findActivePlan()` always did (oldest row, any status); `active=false` does not affect limits. Adapters in `subscription.plan.adapter.in.api`. Consumers keep their own `orElseThrow(SUBSCRIPTION_NOT_FOUND, <same message>)` and call order, so messages/domainCodes and "check before write in the same transaction" are unchanged.
- Input records null-check their required ids → `VALIDATION_ERROR`. Apart from the two MANDATORY methods, no adapter has `@Transactional`; every port Javadoc / package-info states its propagation (reads and REQUIRED use cases join the caller's transaction; `activatePlan` REQUIRES_NEW). 12.2c's `ValidateSaleInput` now copies its two maps (`Map.copyOf`, null stays null).
- Events: `store.store.domain.event` and `subscription.plan.domain.model.event` package-level `@NamedInterface("events")`; identity: `employee.domain.event` package-level + type-level on the 9 events living in `auth.domain.model` / `onboarding.domain.model` (12.2a merge workaround, documented in the package-info).

**Tenant convention.** Every tenant-scoped port method takes `String tenantSchema` first. Adapters over tenant-bound JPA repositories call the single helper `TenantContext.requireCurrent(tenantSchema)` (`shared.infrastructure.persistence`, the only `shared` addition): null/blank schema or a schema other than the current context → `IllegalStateException` (fail fast instead of silently reading another, or no, tenant). All consumers — sync handlers included — pass `TenantContext.getCurrentTenant()`, so a request/listener/scheduler that has bound its tenant behaves exactly as before; consumer unit tests now bind a tenant context. `TenantQueryPort`/`UserQueryPort.findPhoneNumber` are public-schema reads (not tenant-scoped).

**Deviation (improvement) — `PlanType` and catalog.** Catalog no longer reads `PlanType` at all: CSV import takes `PlanView.maxProducts()` (same `PlanType.getMaxProducts()` value). `"identity"` was dropped from catalog's `allowedDependencies`, which breaks the `catalog ↔ identity` cycle. Only subscription and admin read `PlanType` (via `identity :: api`).

**allowedDependencies.** admin `identity :: api`, `subscription :: api`, `subscription :: events`; catalog `store :: api`, `subscription :: api` (identity removed); commerce `identity :: api`, `store :: api`; identity `store :: api`, `subscription :: api`; inventory `store :: api`, `subscription :: api`; messaging `identity :: api`, `store :: api`; reporting `identity :: api`, `store :: api`, `subscription :: api`; store `subscription :: api`; subscription `identity :: api`; sync `identity :: api`, `store :: api` (subscription removed). `PUBLISHED_API_MODULES` now includes identity, store, subscription (assertion green); `ModulithViolationClassifierTest` updated (store now pinned, messaging used as the unpinned example).

**Violations (`target/modulith-violations.txt`, before = 12.2c working tree, after = this change).**

| Edge into identity / store / subscription | Before | After |
|---|---|---|
| admin → identity / subscription | 9 / 12 | 0 / 0 |
| catalog → identity / store / subscription | 1 / 4 / 12 | 0 |
| commerce → identity / store | 6 / 5 | 0 |
| identity → store / subscription | 4 / 8 | 0 |
| inventory → store / subscription | 13 / 4 | 0 |
| messaging → identity / store | 20 / 9 | 0 |
| reporting → identity / store / subscription | 32 / 11 / 2 | 0 |
| store → subscription | 6 | 0 |
| subscription → identity | 23 | 0 |
| sync → identity / store / subscription | 10 / 12 / 3 | 0 |
| shared → identity / store / subscription (12-2f) | 174 / 51 / 9 | 49 / 0 / 9 |
| **Total** | **685** (140 cycles, 545 non-exposed) | **270** (107 cycles, 163 non-exposed) |

Not-allowed 0. Remaining direct 2-module cycles: `identity ↔ subscription` (identity checks plan limits / records the registration plan event; subscription reads tenants and `PlanType`), `identity ↔ messaging`, `identity ↔ reporting`, `catalog ↔ messaging`, `commerce ↔ messaging` — the last four are messaging/reporting edges owned by 12.2e; `identity ↔ subscription` is inherent until `PlanType`/tenant move to `core` (12-2f) or plan profiles (16.8). Removed: `catalog ↔ identity`.

**Accepted by the orchestrator (2026-10-29).** Catalog reading `PlanView.maxProducts()` instead of `PlanType` (no catalog → identity edge), and the fail-fast tenant guard — made null-safe and centralised in `TenantContext.requireCurrent` after review.

**Tests.** New adapter tests: `StoreApiAdapterTest`, `SubscriptionApiAdaptersTest`, `IdentityAuthApiAdaptersTest`, `IdentityEmployeeApiAdaptersTest`, `TenantPreferencesQueryAdapterTest`, `TenantContextRequireCurrentTest` (mapping, delegation, tenant guard, null-validated inputs, FREE limits unchanged); `ProductCountAdapterTest` moved to catalog. Consumer tests drive the real adapters over their existing internal mocks through test-only `*ApiTestSupport` classes (`StoreApiTestSupport`, `SubscriptionApiTestSupport`, `IdentityAuthApiTestSupport`, `IdentityEmployeeApiTestSupport`, `IdentityOnboardingApiTestSupport`), so their assertions are unchanged; `@InjectMocks` consumers were switched to explicit construction. 14 packages sequential (2026-10-29): 3,935 tests, 0 failures/errors.

### Verification & live run (2026-10-29)

Full suite package by package: 3,727 tests; 6 failures in `reporting` (`WeeklyReportControllerTest`, `ReportControllerTest`: standalone MockMvc without a bound tenant hit the new null-safe guard — production binds it in `JwtAuthFilter`) fixed by binding/clearing `TenantContext` in those tests → `reporting` green. Violations 270 (107 cycles, 0 not-allowed). Live (dev backend): registration via public path OK; FREE tenant (forced in dev DB) → 3rd store and 4th employee rejected `PLAN_LIMIT_EXCEEDED` 403; trigger-weekly 200; products/suppliers/overview/pull 200; Redmi POS sale → server COMPLETED, stock 23→22; backend log 0 ERROR. Note: FREE employee limit is 3 (enum, as in V1) vs 2 in the plan docs — journal I14 → 16-8.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (batched 12-2c + 12-2d, one diff)
- verdicts: 27 findings — high 0, medium 9, low 13, false 2, maybe-false 0 (+ intent-alignment notes; verification-gap: none)
- findings:
  - `[medium]` `[patch]` B3/B4/E5 tenant guard duplicated ×3, passes null/null, tautological on most paths — centralised null-safe `TenantContext` helper
  - `[medium]` `[patch]` E2 sync handlers pass their `tenantId` arg — now pass `TenantContext.getCurrentTenant()`
  - `[medium]` `[patch]` B10/E1 store-limit lock + idempotency left in sync — moved into `StoreCommandPort.createWithId`
  - `[medium]` `[patch]` B6/E6 transaction claims in package-info/port docs contradict REQUIRED / REQUIRES_NEW reality — corrected per port
  - `[medium]` `[patch]` B7 `currentPlan`/`changePlan` FOR UPDATE without enforced tx — `Propagation.MANDATORY`
  - `[medium]` `[defer]` B1 identity ↔ subscription cycle (identity checks limits/records plan events; subscription reads `PlanType`/tenants) — needs tenant/plan ownership split → routed → 12-2f (and 16-8 for `PlanType`)
  - `[medium]` `[defer]` B2 identity still depends on whole `reporting` + its internals (`DailyReportFormatter`, `EndOfDayReportBuilder`) — reporting API is 12.2e scope → routed → 12-2e
  - `[medium]` `[defer]` IA5 `StoreCountAdapter`/`UserCountAdapter` in subscription read store/identity tables over JDBC (rule: no cross-module table access) → routed → 12-2f
  - `[medium]` `[defer]` B9 `ProductCountPort` returns 0 on invalid schema (limit fails open) — same JDBC code as before 12.2d → routed → 16-8 (configurable plan limits)
  - `[low]` `[defer]` B5 commerce ports rely on ambient tenant (no `tenantSchema`) — aligned with catalog/crm/inventory in 12.2e → routed → 12-2e
  - `[low]` `[patch]` B8 `PlanChangeRecorder` misdescribed in subscription package-info
  - `[low]` `[patch]` B12/E3 `ValidateSaleInput` maps not copied
  - `[low]` `[patch]` B13 subscription input naming/types inconsistent — aligned
  - `[low]` `[patch]` B14 `findCurrentPlan` Javadoc overstates semantics
  - `[low]` `[patch]` B15/E4 `PlanView` null planType / null plan → NPE — DomainException
  - `[low]` `[reject]` B11 `DayClosure` domain uses API value types (`DayClosureView`, `DayClosureSummary`) — intra-module, allowed; cosmetic
  - `[low]` `[reject]` IA3 new `VALIDATION_ERROR` in API input records — unreachable on sync path (handlers parse first); only rejects programming errors
  - `[low]` `[reject]` IA4 tenant parameter mostly asserted, not used — chosen convention (explicit + fail-fast) recorded as accepted
  - `[low]` `[reject]` IA6 catalog reads `PlanView` instead of `identity.api.PlanType` — accepted deviation (removes catalog ↔ identity cycle)
  - `[low]` `[reject]` IA2 behaviour invariance shown by preserved unit/integration assertions, not contract tests — mitigated by live device run
  - `[false]` `[reject]` IA1 variant fix diverges from matrix "INSUFFICIENT_STOCK" — matrix row was a planning error; orchestrator ruling recorded in spec-12-2c (manual validation is forced: short variant → `StockForcedZeroEvent`, cascade stays pending). **Intended visible change:** a validate push with product-level stock > 0 and variant stock 0 is now validated (forced to zero) instead of failing with INSUFFICIENT_STOCK from the wrong-level check.
  - `[false]` `[reject]` scheduled/async callers would break the tenant guard — verified: all 7 schedulers/listeners set `TenantContext` before calling and pass `getCurrentTenant()`

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -DskipTests compile test-compile` -- expected: success
- Same 14-package sequential loop as spec-12-2a -- expected: green
- `grep -E "within module '(identity|store|subscription)'|depends on (module|named interface\(s\)) '(identity|store|subscription)" keevo/backend/target/modulith-violations.txt | grep -v "^- Module 'shared'" | wc -l` -- expected: 0

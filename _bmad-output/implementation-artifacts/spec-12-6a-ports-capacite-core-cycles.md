---
title: '12.6a — Core-owned capability ports and event-driven seeding: zero module cycles'
type: 'refactor'
created: '2026-10-29'
status: 'done'
baseline_revision: '1501d8e9984f2ff75f53d566846241e311976ef7'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-4-registre-events-modulith.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 38 module cycles remain (Modulith report after 12.4), all built from two "capability" modules that are both called by others and call others: `messaging` (its `WhatsAppPort`/`NotificationPort` are called by identity, catalog, subscription, admin, reporting while it reads commerce/identity/store) and `subscription` (its plan-limit/subscription ports are called by catalog, store, identity, inventory, sync while it reads identity and calls messaging); plus identity → catalog (onboarding seeds categories) and catalog → messaging (draft tracking). `verify()` cannot become blocking while any cycle exists (spec-socle §13).

**Approach:** Move the contracts of cross-cutting capabilities into the core (`shared`), implemented by their provider module (spec-socle §6 capabilities, §9 entitlements in core): notification sending and plan limits/entitlement reads. Replace the remaining direct business back-edges by catalogue events consumed by the owning module (onboarding → catalog seeding, draft tracking), and move the four pre-commit `@Async @EventListener` notification listeners to after-commit module listeners.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- `com.keevo.shared.capability.notification`: `WhatsAppSender` + `PushNotifier` (+ payload/result records) — moved from `messaging.api.WhatsAppPort`/`NotificationPort`, implemented in messaging; callers depend on `shared` only.
- `com.keevo.shared.capability.entitlement`: `PlanLimitCheck` + `TenantPlanQuery` (plan view, limits) — moved from `subscription.api.PlanLimitPort`/`SubscriptionQueryPort`, implemented in subscription. `ProductCountPort`/employee/store counts stay SPIs declared by subscription (they point the right way).
- Onboarding: identity publishes `TenantOnboardedEvent` (catalogue, carries sector and template category names); catalog seeds categories in an after-commit module listener — idempotent (skip if categories exist). Same observable result for the owner (categories present on the first catalogue load; tolerate the async delay by seeding before the onboarding response if Modulith publishes synchronously in the same tx — choose synchronous `@TransactionalEventListener(BEFORE_COMMIT)` in catalog if the app reads categories right after onboarding).
- Draft tracking: catalog publishes draft lifecycle events; messaging records/acknowledges pending drafts in module listeners; `DraftValidationCommandPort` removed; the count query stays a `messaging.api` read used by catalog only if it creates no cycle, else the count endpoint moves to messaging.
- `DayClosureNotificationListener`, `StockAlertNotificationListener`, `StockModificationNotificationListener`, `ProductCreationNotificationListener.onCsvImportCompleted` → `@ApplicationModuleListener` (after commit, durable, tenant-bound); `@DeliverOnce` on those that send. Intended visible change: no push for an action whose transaction rolled back.

## Boundaries & Constraints

**Always:** notification texts, recipients and channels identical; plan-limit outcomes and domainCodes identical; onboarding result identical (same categories); all test packages green; not-allowed 0; published-module and shared assertions green.

**Never:** no blocking `verify()` yet (12.6b), no new business behaviour beyond the stated after-commit timing fix.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner onboarding with a sector | register + sector | same template categories | replay → no duplicates |
| Plan limit reached | create beyond limit | `PLAN_LIMIT_EXCEEDED`, same details | unchanged |
| WhatsApp/push flows (OTP, closure, alerts, plan, test report) | as today | same messages | provider failure → as today / 12.4 rules |
| Stock alert in a transaction that rolls back | threshold crossed then rollback | no push | — |
| Draft product created / validated | as today | pending count and owner notification as today | — |
| Architecture report | after change | 0 cycles | — |

</frozen-after-approval>

## Code Map

- Cycle evidence: `keevo/backend/target/modulith-violations.txt` cycle section (edges messaging → commerce/identity/store, subscription → identity/messaging, store → subscription, identity → catalog/messaging, catalog → messaging/store/subscription).
- `messaging/api/WhatsAppPort.java`, `messaging/api/NotificationPort.java` (+ payload records) → `shared/capability/notification`.
- `subscription/api/PlanLimitPort.java`, `subscription/api/SubscriptionQueryPort.java`, `PlanView` → `shared/capability/entitlement`.
- `identity/onboarding/application/service/OnboardingService.java` → `CategoryCommandPort.seedTemplateCategories` call becomes an event.
- `catalog/product/application/usecase/{CreateDraftProductUseCase,UpdateProductUseCase}.java` → `DraftValidationCommandPort`.
- The four listeners under `messaging/notification/application/listener/`.
- `allowedDependencies` of every module; `ApplicationModulesBaselineTest`.

## Tasks & Acceptance

**Execution:**
- [x] Move notification + entitlement contracts to `shared/capability/*`; rewire callers and providers; update allow-lists.
- [x] Onboarding seeding via event; draft tracking via events; remove the replaced ports.
- [x] Convert the four listeners (after commit, durable, `@DeliverOnce` where sending).
- [x] Assert in `ApplicationModulesBaselineTest` that cycles == 0; record before/after in `## Implementation Notes`.

**Acceptance Criteria:**
- Given the architecture report, when regenerated, then it contains 0 cycles and 0 violations of any kind.
- Given all test packages run one at a time, then all pass.

## Implementation Notes

**Before:** 38 violations, all cycles (report after 12.4, `target/modulith-violations.txt`).

**Progress — task 1 done (2026-10-29).** `git mv` + mechanical rename: `messaging.api.{WhatsAppPort→WhatsAppSender, NotificationPort→PushNotifier, NotificationPayload, PushSendResult}` → `shared.capability.notification`; `subscription.api.{PlanLimitPort→PlanLimitCheck, SubscriptionQueryPort→TenantPlanQuery, PlanView}` → `shared.capability.entitlement`. **Deviation (needed for 0 cycles):** `PlanChangeRecorder` + `PlanChangeCause` moved to `shared.capability.entitlement` too — identity's `TenantFactory` records the REGISTRATION plan event in its transaction, and subscription → identity (PlanType, TenantQueryPort) would otherwise keep the identity ↔ subscription cycle; same contract, same transaction. `ProductCountPort` stays in `subscription.api`. package-info for both capability packages; allow-lists: admin/identity/reporting/subscription lose `messaging :: api`, identity/reporting/store lose `subscription :: api`. main + test compile OK.

**Progress — task 2 done (2026-10-29).** Onboarding: identity publishes `shared.event.catalog.onboarding.TenantOnboardedEvent` (sector + non-blank template names; count = names, as `saveAll().size()` before); catalog `TenantOnboardedCategorySeeder` = synchronous `@TransactionalEventListener(BEFORE_COMMIT)` in the onboarding tx (the app loads categories right after onboarding) — idempotent per name (root category with that name, case-insensitive, is skipped); `CategoryCommandPort.seedTemplateCategories` removed. Drafts: catalog publishes `DraftProductPendingEvent` (where `recordPending` was) / `DraftProductValidatedEvent` (where `acknowledge` was); messaging `DraftValidationTrackingListener` = plain synchronous `@EventListener`s (same thread + caller tx as the former call — `UpdateProductUseCase` is not transactional, so BEFORE_COMMIT would have dropped the ack). `GET /api/v1/products/drafts/count` moved to messaging `PendingDraftsCountController` (same path/RBAC/body; a catalog → messaging read would keep catalog → messaging → commerce → catalog). `messaging.api` package removed entirely (DraftValidation ports, PendingDraftInput, adapter, test support); tests moved/rewritten (`DraftValidationTrackingListenerTest`, `PendingDraftsCountController{,Security}Test`, `TenantOnboardedCategorySeederTest`, OnboardingService/CreateDraft/UpdateProduct/ProductImportController tests). Allow-lists: catalog loses `messaging :: api`, identity is now `{shared, store :: api}`. Compile OK.

**Progress — task 3 done (2026-10-29).** `@ApplicationModuleListener` (async, after commit, registry, tenant bound by the 12.4 interceptor — manual `TenantContext` set/clear removed): `DayClosureNotificationListener` + `@DeliverOnce`, `StockAlertNotificationListener` + `@DeliverOnce`, `ProductCreationNotificationListener.onCsvImportCompleted` + `@DeliverOnce`, `StockModificationNotificationListener` (no receipt: only accumulates into the in-memory window, sends from the scheduler — same rule as `onProductCreated` in 12.4). Internal try/catch kept → provider failures stay logged, as today. Publishers checked: `CloseDayService` and `StockOperationService` transactional; `ImportCsvProductsUseCase` is deliberately not → the `CsvImportCompletedEvent` publication now runs in a short `TransactionTemplate` (otherwise an `@ApplicationModuleListener` never fires). Tests: listener tests wrapped with `ModuleListenerTestSupport.bound`, `RegistryEventSerializationTest` + 3 fixtures (StockThresholdBreached, StockAdjusted, CsvImportCompleted), `EventPublicationRegistryIntegrationTest.stockAlertListener_afterCommitOnly` (real listener: commit → publication completed; rollback → no row) — green.

**Progress — task 4 done (2026-10-29).** `ApplicationModulesBaselineTest.verifyModuleBoundaries_warningModeOnly` asserts no `Cycle detected` message (verify() still non-blocking — 12.6b). **After:** `target/modulith-violations.txt` = `✓ No module violations detected.` (0 cycles, 0 not-allowed, 0 non-exposed). Resulting module graph (acyclic): admin → feedback, identity, subscription; catalog → crm, store, subscription (ProductCountPort SPI); commerce → catalog, identity, inventory, store; identity → store; inventory → catalog, store, subscription; messaging → commerce, identity, store; reporting → commerce, identity, store; subscription → identity; sync → catalog, commerce, crm, feedback, identity, inventory, store; store/crm/feedback → shared only.

**Verification (2026-10-29).** `compile test-compile` OK. 14 packages sequential, all rc=0, 0 failures/errors: architecture 16, catalog 239, crm 114, inventory 376, commerce 251, sync 496, identity 435, messaging 161, reporting 389, shared 643, admin 504, store 60, subscription 70, feedback 52 (surefire XML; commerce count differs from the 12.4 note although no commerce test changed — counting artefact of the loop, all green). `target/modulith-violations.txt`: `✓ No module violations detected.` Not live-tested on a dev backend (onboarding categories, drafts badge, closure/stock pushes).

**Review fixes (2026-10-29), done.**
- Seeder: template de-duplicated case-insensitively (first spelling kept), then one existence query (`CategoryRepository.findExistingRootNamesIgnoreCase`, trimmed/lower-cased JPQL `IN`); `fallbackExecution = true`. Name cleaning in one place: `TenantOnboardedEvent` compact constructor (null/blank dropped, trimmed); copies removed from `OnboardingService` and the seeder. `categoriesCreated` documented as "template categories requested" (= rows created on the single first onboarding). Tests: seeder 9, `OnboardingCategorySeedingPostgresIntegrationTest` (commit → 5 CLOTHING roots active/non-custom; rollback → none) — green.
- Draft events: compact constructors reject null `productId` / null-blank `tenantId` (`VALIDATION_ERROR`); tested.
- Drafts counter: controller → `PendingDraftsQueryService` (application layer, `TenantContext.requireCurrent`), `@Tag`/`@Operation`; security test class/mocks renamed; catalog `ProductImportControllerSecurityTest` restored (anonymous POST /draft → 401; no role POST /draft and /import → 403, use case never called). Green.
- CSV import: a failing completion transaction is caught (WARN), the import result is returned; unit test checks begin → publish → commit order and the failure path; registry IT `csvImportCompletedListener_recordedAndCompleted` (row recorded + completed). Green.
- Stock alert: immediate-send path rethrows `DeliveryFailedException` after both channels (12.4 R2; listener tx rollback also releases the cooldown) — tests updated; both stock listeners' Javadoc now state that batched notifications are in-memory and not durable (pre-existing). Green.
- Stale names: fields/params of the moved types renamed across ~60 main/test files (`whatsAppPort` → `whatsAppSender`, `notificationPort` → `pushNotifier`, `subscriptionQuery` → `tenantPlanQuery`); column alignment fixed in `CreateDraftProductUseCase` / `ImportCsvProductsUseCase`.
- Stale docs: `ProductImportController` endpoint list, `DayClosureWhatsAppListener` (core capability implemented by messaging), entitlement `package-info` (catalog/inventory still use `subscription :: api` for `ProductCountPort`), `shared/package-info` (`PlanView`/`PlanChangeRecorder` carry `identity.api.PlanType`, accepted until 16.8).
- Tests run (touched files only): 66 test classes in 3 chunks — 622 tests green; architecture green, report still `✓ No module violations detected.`

**Orchestrator acceptance (2026-10-29).** Accepted: moving `PlanChangeRecorder` / `PlanChangeCause` / `PlanView` to `shared.capability.entitlement` (needed for 0 cycles, same contract and transaction), and relocating the drafts-count endpoint to messaging (`PendingDraftsCountController`, same URL `GET /api/v1/products/drafts/count`, same RBAC and body).

### Verification & live run (2026-10-29, after review patches)

14 packages sequential: 3,819 tests, 0 failures/errors. Modulith: `✓ No module violations detected.` (0 cycles). Live: API onboarding CLOTHING → 5 root categories; Redmi — new owner registered in the app, onboarding sector Électronique + shop name → 5 electronics categories seeded through `TenantOnboardedEvent`, shown as chips in the POS (« Votre boutique est prête ! »); backend log 0 ERROR.

## Spec Change Log

## Review Triage Log

### 2026-10-29 — Review pass (12-6a)
- verdicts: 24 findings — high 0, medium 8, low 13, false 1, maybe-false 2 (+ intent-alignment notes)
- findings:
  - `[medium]` `[patch]` B1 batched stock listeners complete the publication (and `@DeliverOnce` receipt) before the in-memory flush; StockAlert swallows send failures — immediate path rethrows; batching documented as not durable (pre-existing)
  - `[medium]` `[patch]` B3/E1 seeder duplicates within one template (case variants); N queries — case-insensitive distinct + one query
  - `[medium]` `[patch]` B5/E4 seeder silently skipped without a transaction — `fallbackExecution = true`
  - `[medium]` `[patch]` V1 no full-context test of onboarding → categories persisted / rolled back — IT added (live run already showed 5 categories seeded)
  - `[medium]` `[patch]` V2/E6 CSV completion publish inside `TransactionTemplate` unobserved; failure would error a committed import — verification + registry IT + catch/log
  - `[medium]` `[patch]` B8/E7 drafts-count controller bypasses application layer and lost the tenant fail-fast — query service with `requireCurrent`
  - `[medium]` `[patch]` B9 security test half-renamed; ProductImportController security slice lost — renamed + restored
  - `[medium]` `[defer]` B12 no check that every publisher of `DayClosedEvent`/`StockThresholdBreachedEvent`/`StockAdjustedEvent` is transactional (after-commit listeners ignore non-tx publishes) → routed → 12-6b (ArchUnit/registry rule)
  - `[low]` `[patch]` B2/E3/V-other onboarding count = template size — documented (equals rows created on a first onboarding; onboarding runs once)
  - `[low]` `[patch]` B4 triple null/blank filtering — one place (event constructor)
  - `[low]` `[patch]` B6 draft events lost null validation — compact constructors
  - `[low]` `[patch]` B10 stale field names after type moves — renamed
  - `[low]` `[patch]` B11 stale docs (4 places) — fixed
  - `[low]` `[reject]` E2 concurrent onboardings both seed — guarded by `hasOnboardingCompleted`; same race existed with the direct call; single-instance
  - `[low]` `[reject]` E5 `UpdateProductUseCase` non-transactional: listener failure after save — same failure mode as the former direct port call
  - `[low]` `[reject]` B13 `Instant.now()` instead of injected `Clock` in new events — consistent with most existing events
  - `[low]` `[reject]` IA scope: `PlanChangeRecorder`/`PlanChangeCause`/`PlanView` moved to shared — needed to cut identity ↔ subscription; accepted
  - `[low]` `[reject]` IA draft tracking via synchronous listeners (not `@ApplicationModuleListener`) — keeps joining the caller's transaction as before; accepted
  - `[low]` `[reject]` IA onboarding via BEFORE_COMMIT (not durable/replayable) — chosen branch of the spec decision (categories must exist on first catalogue load); accepted
  - `[low]` `[reject]` IA after-commit runtime proof only for StockAlert — CSV publication IT added; DayClosure/StockModification share the same mechanism (12.4 registry IT)
  - `[low]` `[defer]` B11b core contracts `PlanView`/`PlanChangeRecorder` carry `identity.api.PlanType` → routed → 16-8 (plan profiles replace PlanType)
  - `[maybe-false]` `[reject]` IA endpoint `/api/v1/products/drafts/count` served by messaging — URL kept for app compatibility; accepted
  - `[maybe-false]` `[reject]` IA cycle assertion text-based, not `verify()` — by design until 12.6b
  - `[false]` `[reject]` scheduled/async callers losing tenant after listener conversion — 12.4 interceptor binds the tenant (proven by registry IT two-tenant case)

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -DskipTests compile test-compile` -- expected: success
- Same 14-package sequential loop as spec-12-2a -- expected: green
- `head -6 keevo/backend/target/modulith-violations.txt` -- expected: Total violations: 0 (or "No module violations detected")

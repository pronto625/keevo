---
title: 'v1s-14-13 — Inventory quick-add enforces the FREE plan product limit'
type: 'bugfix'
created: '2026-10-13'
status: 'done'
baseline_revision: '8f6c4c1eb2fec94e1033790e2adb7dc08ff49a01'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-v1s-14-13-reactivate-employee-plan-limit.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** `QuickAddProductService.execute` (POST `/api/v1/inventory/sessions/{id}/quick-add`) saves an ACTIVE product with no `product-limit:<schema>` lock and no `PlanLimitGuard.checkProductLimit`, so a FREE tenant (500 products max) can exceed its cap during a stock count and race a concurrent create at max−1. The app's quick-add sheet then shows « Erreur réseau : <English Dio text> » instead of the plan sheet.

**Approach:** Apply exactly the `CreateProductUseCase` rule (v1s-13-9 / v1s-15-9): take `transactionLock.lock("product-limit:" + schema)` as the first statement of the existing `@Transactional execute`, then after the session and name checks read the active plan and call `planLimitGuard.checkProductLimit(planType, productCountPort.countActiveProducts())` → `PLAN_LIMIT_EXCEEDED` (403) before any write. In the app, a quick-add refused with `PLAN_LIMIT_EXCEEDED` opens `showPlanLimitBottomSheet(entity: 'products')`.

## Boundaries & Constraints

**Always:** same lock key, error code, count port (`ProductCountPort`), guard and `SUBSCRIPTION_NOT_FOUND` message as `CreateProductUseCase`; lock before the session read, the name check and the count; refusal writes nothing (no product, stock level, count, event); `QuickAddProductService.execute` listed in `SchedulerLockCoverageTest.lockTakingUseCasesAreTransactional` (switch that `Map.of` — full at 10 pairs — to `Map.ofEntries`); app version 1.1.45+44 → 1.1.46+45; no code, id or English/technical text shown to the user (rule 11) — the generic Dio branch of the sheet uses `appErrorMessage(e)`.

**Never:** no new ErrorCode, no DDL/Flyway change, no change to `CreateProductUseCase`, `PlanLimitGuard` or `ProductCountAdapter`; no offline pre-count in the app (offline quick-add queues `CREATE_PRODUCT`, which `ProductSyncHandler` → `CreateProductUseCase` already guards, and a refusal goes through the v1s-16-12 ghost-product rollback); no full mvn/flutter suites.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Under the cap | FREE, 499 ACTIVE products, session IN_PROGRESS | product + stock level + count saved, 201 | — |
| At the cap | FREE, 500 ACTIVE | nothing written, no event | `PLAN_LIMIT_EXCEEDED` (403) |
| Name exists at cap | FREE, 500 ACTIVE, name taken | dedup wins (same order as CreateProduct: name before plan) | `PRODUCT_NAME_ALREADY_EXISTS` (409) |
| Session not in progress | any | unchanged | `INVENTORY_SESSION_*` unchanged |
| No active subscription | session OK, name free | nothing written | `SUBSCRIPTION_NOT_FOUND` |
| Race with create | FREE, 499 ACTIVE; A holds `product-limit:<schema>` and inserts an ACTIVE product | B (quick-add) blocks on the lock, then refused; 500 ACTIVE, B's name absent | `PLAN_LIMIT_EXCEEDED`, tx rolled back |
| App refusal | POST → 403 body `domainCode: PLAN_LIMIT_EXCEEDED` | plan sheet « Vous avez atteint la limite de 500 produits sur votre plan gratuit », no snackbar, `onResult` not called | — |

</intent-contract>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo/`):
- `inventory/counting/application/service/QuickAddProductService.java:66-95` -- target. Inject `ProductCountPort`, `SubscriptionRepository`, `PlanLimitGuard`, `TransactionLockPort` (imports as `CreateProductUseCase.java:7-13`). Order: lock → session lookup/status → `existsByName` → `findActivePlan` + `checkProductLimit` → SKU → saves → events.
- `catalog/product/application/usecase/CreateProductUseCase.java:107-138` -- reference pattern (read-only).
- `subscription/plan/adapter/out/persistence/impl/ProductCountAdapter.java:43-48` -- `status = 'ACTIVE'` count via JdbcTemplate, sees the lock winner's commit (read-only).
- `inventory/counting/adapter/in/rest/InventoryCountController.java` -- REST entry, no change; 403 mapping via `GlobalExceptionHandler`.
- No dedicated sync op for quick-add: the app's offline path queues `CREATE_PRODUCT` (already guarded).

Backend tests (`keevo/backend/src/test/java/com/keevo/`):
- `inventory/counting/application/service/QuickAddProductServiceTest.java` -- add mocks for the 4 deps (existing tests stub an active FREE plan under the cap); add InOrder lock → `findById` → `existsByName` → count/guard → `productRepository.save`; at-cap (guard throws → no save, no stock/count save, no event); no-subscription; name-taken-at-cap (409 wins, guard not called).
- `inventory/counting/QuickAddProductIntegrationTest.java`, `adapter/in/rest/QuickAddProductControllerTest.java` -- must stay green (adjust only if bean wiring requires).
- `shared/infrastructure/config/SchedulerLockCoverageTest.java:134` -- `Map.ofEntries(Map.entry(...), ...)` + `QuickAddProductService.class, "execute"`.
- `shared/infrastructure/persistence/ResidualRacesPostgresIntegrationTest.java:145-200` -- reuse `race(...)`, `failure`, `domainCode`, `count`; tenant via `tenantFactory.create()` + `plan_type='FREE'`; seed 499 ACTIVE products with `generate_series` (sku NOT NULL), an IN_PROGRESS `inventory_sessions` row on the seeded store (`started_by` NOT NULL, scope 'FULL'); `@Autowired QuickAddProductUseCase`.

App (`keevo/app/`):
- `lib/features/inventory/presentation/widget/quick_add_product_sheet.dart:334-341` -- `on DioException`: if `response.data['domainCode'] == 'PLAN_LIMIT_EXCEEDED'` and mounted → `showPlanLimitBottomSheet(context: context, entity: 'products')`; else snackbar `appErrorMessage(e)` (replaces the raw `e.message`).
- `lib/features/inventory/presentation/provider/quick_add_product_provider.dart:60-77` -- rethrows non-409/non-network DioException; no change.
- `lib/features/settings/presentation/widget/plan_limit_bottom_sheet.dart:39` -- reuse as is.
- `test/features/inventory/presentation/widget/quick_add_product_sheet_test.dart` -- widget tests.
- `pubspec.yaml:4` -- bump.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/.../QuickAddProductService.java` -- lock + plan check as mapped -- closes the cap bypass and the race.
- `keevo/backend/.../QuickAddProductServiceTest.java` -- unit tests listed above -- prove order and refusal.
- `keevo/backend/.../SchedulerLockCoverageTest.java` -- `Map.ofEntries` + entry -- guards MANDATORY tx.
- `keevo/backend/.../ResidualRacesPostgresIntegrationTest.java` -- `productLimit_quickAddRacesCreate_refused`: A holds `product-limit:<schema>` and inserts the 500th ACTIVE product; B = `quickAddProduct.execute(...)`; assert B blocked then `PLAN_LIMIT_EXCEEDED`, 500 ACTIVE, no product with B's name, no inventory_counts row -- real PostgreSQL proof.
- `keevo/app/.../quick_add_product_sheet.dart` -- plan-limit branch + `appErrorMessage` -- French plan sheet, no technical text.
- `keevo/app/test/.../quick_add_product_sheet_test.dart` -- 403 `PLAN_LIMIT_EXCEEDED` → plan sheet text shown, no « PLAN_LIMIT_EXCEEDED » text, sheet result null; other 4xx → French `appErrorMessage` snackbar, no « Erreur réseau ».
- `keevo/app/pubspec.yaml` -- 1.1.45+44 → 1.1.46+45.

**Acceptance Criteria:**
- Given a FREE tenant with 500 ACTIVE products and a session in progress, when an employee POSTs a quick-add, then the response is 403 `PLAN_LIMIT_EXCEEDED` and no product/stock/count row is created.
- Given the same refusal in the app, when the user taps « Ajouter » in the quick-add sheet, then the plan-limit bottom sheet appears in French and no code/id is displayed.
- Given a concurrent create holding `product-limit:<schema>` at max−1, when a quick-add runs, then it waits for the lock and is refused (real PostgreSQL test).

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 21 findings — high 0, medium 0, low 13, false 8, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent a) No controller/HTTP test asserting 403 + domainCode on quick-add — the mapping is the shared GlobalExceptionHandler PLAN_LIMIT_EXCEEDED → 403 path already exercised by StoreControllerTest/EmployeeControllerTest; the controller is unchanged.
  - `[low]` `[reject]` (intent b) Race competitor is a raw INSERT under the lock, not CreateProductUseCase / a second quick-add — same v1s-13-13 harness; B is the real bean, and removing the lock line made the test fail (implementer mutation check).
  - `[false]` `[reject]` (intent c) Offline/sync path unverified — offline quick-add queues CREATE_PRODUCT → ProductSyncHandler → CreateProductUseCase (lock + guard since v1s-13-9), contract test `ProductSyncHandlerTest.handle_createProduct_planLimitExceeded_*` (v1s-16-12); no quick-add sync op exists.
  - `[low]` `[patch]` (intent d) App tests inject the error below the provider, so the provider's 403 rethrow is untested — grouped with the verification-gap finding below.
  - `[false]` `[reject]` (intent e) Story file / sprint-status not updated — done at finalize.
  - `[false]` `[reject]` (intent f) Plan check not directly after the lock — intent requires « même règle que CreateProductUseCase », whose order is lock → name → plan → save (v1s-15-9); InOrder pins it.
  - `[low]` `[patch]` (verification-gap) Provider rethrow of 403 PLAN_LIMIT_EXCEEDED unproven — added `v1s-14-13 — quickAdd online 403 PLAN_LIMIT_EXCEEDED rethrows, nothing written offline` (same exception rethrown, no product/stock/count row, no queued op).
  - `[low]` `[reject]` (verification-gap other / blind 3) Unused `AuthInterceptor.onPlanLimitExceeded` hook could later double the sheet — no caller wires it today; consolidating plan-limit detection is a refactor, same decision as the reactivation spec.
  - `[false]` `[reject]` (edge 1 / blind 2) Plan sheet ignores server `details.limit` — PREMIUM and PREMIUM_TRIAL are Integer.MAX_VALUE, only FREE (500) can refuse and kFreePlanLimits mirrors PlanType.FREE; same as the reactivation spec.
  - `[false]` `[reject]` (edge 2) ProductCountAdapter swallowing a SQL error returns 0 and bypasses the cap — inside the @Transactional the JdbcTemplate shares the connection, so a failed count aborts the PostgreSQL transaction and the following save fails; tenant is non-null (the lock key used it).
  - `[low]` `[patch]` (blind 1) 404 widget fixture omits the UUID the backend puts in the message — fixture now carries the real « … introuvable : <uuid> » text; test asserts the generic French message and that no UUID fragment is shown.
  - `[false]` `[reject]` (blind 2) Plan sheet limit — see edge 1.
  - `[low]` `[reject]` (blind 3) Duplicate detection vs interceptor hook — see verification-gap other.
  - `[low]` `[reject]` (blind 4) No controller-level 403 test — same as intent a.
  - `[low]` `[reject]` (blind 5) No real-DB under-cap success path — the PG race test already runs B through the real MANDATORY lock and the real ProductCountAdapter inside the service transaction; the write path after the guard is unchanged and covered by the existing unit/integration tests.
  - `[low]` `[reject]` (blind 6) « Session not in progress » row lacks a no-plan-check assertion — the row is « unchanged » and covered by the existing `Session not IN_PROGRESS throws INVENTORY_SESSION_NOT_IN_PROGRESS` test; the lock-first/no-plan-check property is pinned by `execute_sessionNotFound_noPlanCheck`.
  - `[low]` `[patch]` (blind 7) Dead `countActiveProducts` stub in the name-taken test — stub removed, display name now says the name check precedes any count.
  - `[false]` `[reject]` (blind 8) `onResult` not asserted directly — `showQuickAddProductSheet` returns `sheetResult`, which only `onResult` sets, so a null return proves onResult was never called.
  - `[low]` `[patch]` (blind 9) Server-refusal branch logs nothing — added `dev.log('QuickAddSheet refused: status=…')` before the branch (not user-visible).
  - `[low]` `[reject]` (blind 10) Spec AC says « Ajouter » while the button is « Créer le produit » — fix would edit this build's spec.
  - `[false]` `[reject]` (blind 11) Offline rollback may leave the quick-add stock level / count — `rest_sync_service.dart:432-440` drops every queued op whose payload productId is the ghost product (stock entry and count), v1s-16-12.

## Design Notes

Plan check after the name check (not before): same position as `CreateProductUseCase` since v1s-15-9, so the existing « Produit existant / Compter l'existant » dedup flow still wins at the cap — counting an existing product does not grow the catalogue. The lock is still first, so the name check and the count both see a concurrent winner's commit.

Epic context cache: `epics-refonte-modulaire.md` is newer than `epic-14-context.md`, but it covers the refonte epics, not v1s-epic-14 (`epics/epics-remediation-audit.md` is older); the cache was kept.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=QuickAddProductServiceTest,QuickAddProductControllerTest,QuickAddProductIntegrationTest,SchedulerLockCoverageTest,ResidualRacesPostgresIntegrationTest -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend inventory catalog sync shared architecture` -- expected: 0 failures/errors (one package at a time)
- `cd keevo/app && flutter test --concurrency=4 test/features/inventory/presentation/widget/quick_add_product_sheet_test.dart`, then `run-tests-chunked.sh app test/features/inventory` -- expected: 0 failures
- `cd keevo/app && flutter analyze` -- expected: 0 errors, 0 warnings

## Auto Run Result

Status: done

**Summary:** `QuickAddProductService.execute` now takes the `product-limit:<schema>` advisory lock first, then — after the session and name checks — reads the active plan and calls `PlanLimitGuard.checkProductLimit(plan, countActiveProducts())` (same rule, `PLAN_LIMIT_EXCEEDED` code and lock as `CreateProductUseCase`) before any write. The app's quick-add sheet opens the French plan sheet on that refusal and no longer shows raw Dio text for other refusals.

**Files changed:**
- `keevo/backend/src/main/java/com/keevo/inventory/counting/application/service/QuickAddProductService.java` -- lock + plan limit check.
- `keevo/backend/src/test/java/com/keevo/inventory/counting/application/service/QuickAddProductServiceTest.java` -- InOrder, at-cap, no-subscription, name-taken, session-not-found tests.
- `keevo/backend/src/test/java/com/keevo/inventory/counting/QuickAddProductIntegrationTest.java` -- constructor wiring.
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/config/SchedulerLockCoverageTest.java` -- `Map.ofEntries` + quick-add entry.
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/ResidualRacesPostgresIntegrationTest.java` -- real-PostgreSQL race quick-add vs create at max−1.
- `keevo/app/lib/features/inventory/presentation/widget/quick_add_product_sheet.dart` -- plan sheet on PLAN_LIMIT_EXCEEDED, `appErrorMessage` otherwise, log.
- `keevo/app/test/features/inventory/presentation/widget/quick_add_product_sheet_test.dart` -- 2 widget tests.
- `keevo/app/test/features/inventory/presentation/provider/quick_add_product_provider_test.dart` -- 403 rethrow test.
- `keevo/app/pubspec.yaml` -- 1.1.45+44 → 1.1.46+45.

**Review:** 21 findings; 4 entries patched (all low), 0 deferred, 16 rejected (reasons in the triage log). Patches applied by the orchestrating session (the implementation subagent had already handed back). Follow-up review recommended: false (patched: high 0, medium 0, low 4).

**Verification:** targeted mvn — QuickAddProductServiceTest 13/13, QuickAddProductControllerTest 7/7, QuickAddProductIntegrationTest 3/3, SchedulerLockCoverageTest 5/5, ResidualRacesPostgresIntegrationTest 11/11 (new race test fails with the lock removed); chunked backend inventory 165, catalog 395, sync 436, shared 496, architecture 6 — 0 failures/errors; app quick_add sheet + provider tests 20/20, test/features/inventory 189/189; flutter analyze 0 errors / 0 warnings (625 infos).

**Residual risks:** an offline quick-add at the cap shows local success, then the French sync notice and the ghost product rollback (v1s-16-12); the plan sheet opens over the quick-add sheet, which the user closes with « Annuler ».

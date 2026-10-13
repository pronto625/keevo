---
title: 'v1s-14-13 — Employee reactivation enforces the FREE plan employee limit'
type: 'bugfix'
created: '2026-10-13'
status: 'done'
baseline_revision: '1a4cc5833e90a60b12e79f510de0fb52448e77c6'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-v1s-13-13-residual-check-then-act-races.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `ReactivateEmployeeService` sets an INACTIVE employee back to ACTIVE with no `PlanLimitGuard.checkEmployeeLimit` and no `employee-limit:<schema>` lock, so a FREE tenant (3 employees max) can exceed its cap by deactivating/reactivating, and a reactivation can race a concurrent create at max−1. The app then shows a generic snackbar instead of the plan sheet.

**Approach:** Apply exactly the `CreateEmployeeService` rule (v1s-13-13): take `transactionLock.lock("employee-limit:" + schema)` before the count, then `planLimitGuard.checkEmployeeLimit(activePlan, employeeCountPort.countActiveEmployees())` → `PLAN_LIMIT_EXCEEDED` (403). In the app, a reactivation refused with `PLAN_LIMIT_EXCEEDED` opens the same `showPlanLimitBottomSheet(entity: 'employees')` as creation.

## Boundaries & Constraints

**Always:** same error code (`ErrorCode.PLAN_LIMIT_EXCEEDED` via `PlanLimitGuard.checkEmployeeLimit`) and same count port as creation; lock taken inside the existing `@Transactional` BEFORE reading the employee and the count; the limit is checked only when the employee is not already ACTIVE (an ACTIVE employee is not re-counted: idempotent replay of `REACTIVATE_EMPLOYEE` must not be refused at the cap); refusal writes nothing (no status change, no membership change); `ReactivateEmployeeService.execute` listed in `SchedulerLockCoverageTest.lockTakingUseCasesAreTransactional`; app version 1.1.43+42 → 1.1.44+43; the user never sees a code, id or technical text (rule 11).

**Never:** no new ErrorCode, no DDL/Flyway change, no change to `CreateEmployeeService` or `PlanLimitGuard`; no offline pre-check of the limit in the app (an offline `REACTIVATE_EMPLOYEE` refused by the server is already handled by the generic sync rejection path: dropped after the French notice « Limite de votre forfait atteinte — passez à un forfait supérieur », the next server refresh restores the INACTIVE status); no full mvn/flutter suites.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Under the cap | FREE, 2 ACTIVE, target INACTIVE | target ACTIVE, membership reactivated | — |
| At the cap | FREE, 3 ACTIVE, target INACTIVE | nothing written | `PLAN_LIMIT_EXCEEDED` (REST 403; sync REJECTED) |
| Already ACTIVE | FREE, 3 ACTIVE incl. target | no limit check, status/membership re-set (unchanged behaviour) | — |
| Race with create | FREE, 2 ACTIVE + target INACTIVE; A holds `employee-limit:<schema>` and inserts an ACTIVE employee | B (reactivate) blocks on the lock, then refused; 3 ACTIVE, target still INACTIVE | `PLAN_LIMIT_EXCEEDED`, tx rolled back |
| Not found | unknown id | `EMPLOYEE_NOT_FOUND` | unchanged |
| No active subscription | target INACTIVE, no active plan | nothing written | `SUBSCRIPTION_NOT_FOUND` (same as create) |
| App refusal | PATCH /reactivate → 403 body `domainCode: PLAN_LIMIT_EXCEEDED` | plan bottom sheet « Vous avez atteint la limite de 3 employés sur votre plan gratuit », no snackbar, `onActionComplete` not called | — |

</intent-contract>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo/`):
- `identity/employee/application/service/ReactivateEmployeeService.java:40-58` -- target. Inject `PlanLimitGuard`, `EmployeeCountPort`, `SubscriptionRepository`, `TransactionLockPort` (same types/imports as `CreateEmployeeService.java:15-22`). New order: schema null-check → `lock("employee-limit:" + schema)` → `findById` → tenant lookup → if status != ACTIVE: `findActivePlan()` (`SUBSCRIPTION_NOT_FOUND`) + `checkEmployeeLimit(planType, countActiveEmployees())` → `updateStatus` → `membershipRepository.reactivate`.
- `identity/employee/application/service/CreateEmployeeService.java:85-94` -- reference pattern (read-only).
- `subscription/plan/application/service/PlanLimitGuard.java:55-67` -- `current >= max` → `PLAN_LIMIT_EXCEEDED` with details entity/limit/current (read-only).
- `subscription/plan/adapter/out/persistence/impl/EmployeeCountAdapter.java` -- JdbcTemplate count of ACTIVE rows, sees the lock winner's commit (read-only).
- `sync/sync/application/handler/EmployeeSyncHandler.java:76-81` -- `REACTIVATE_EMPLOYEE` calls the same use case inside the per-op transaction → MANDATORY lock satisfied; no change.

Backend tests (`keevo/backend/src/test/java/com/keevo/`):
- `identity/employee/application/service/ReactivateEmployeeServiceTest.java` -- add mocks for the 4 new deps; InOrder test lock → count → guard → updateStatus; at-cap test (guard throws → no `updateStatus`, no membership); already-ACTIVE test (no guard call); no-subscription test.
- `shared/infrastructure/config/SchedulerLockCoverageTest.java:121-131` -- add `ReactivateEmployeeService.class, "execute"`.
- `shared/infrastructure/persistence/ResidualRacesPostgresIntegrationTest.java:311-343` -- reuse `race(...)`, `freeTenantWithoutActiveStore()`, `failure`, `domainCode`; add a v1s-14-13 test with `@Autowired ReactivateEmployeeUseCase`.

App (`keevo/app/`):
- `lib/features/team/presentation/widget/employee_card.dart:166-176` (`_runAction`) and `:483-516` (`_confirmReactivate`) -- on a `DioException` whose `response.data['domainCode'] == 'PLAN_LIMIT_EXCEEDED'`, show `showPlanLimitBottomSheet(context: context, entity: 'employees')` (if `context.mounted`) instead of the snackbar.
- `lib/features/settings/presentation/widget/plan_limit_bottom_sheet.dart:39` -- reuse as is.
- `lib/features/team/data/repository/employee_repository_impl.dart:130-155` -- already rethrows 4xx refusals (no local write, no queue); no change.
- `test/features/team/presentation/widget/employee_card_test.dart:122-140` -- pattern for a reactivate widget test.
- `pubspec.yaml:4` -- bump version.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/.../ReactivateEmployeeService.java` -- add lock + plan check as mapped -- closes the cap bypass and the race.
- `keevo/backend/.../ReactivateEmployeeServiceTest.java` -- unit tests listed above -- prove order and refusal.
- `keevo/backend/.../SchedulerLockCoverageTest.java` -- add the entry point -- guards MANDATORY tx.
- `keevo/backend/.../ResidualRacesPostgresIntegrationTest.java` -- add `employeeLimit_reactivateRacesCreate_refused`: FREE tenant, 2 ACTIVE employees + 1 INACTIVE target; A holds `employee-limit:<schema>` and inserts a 3rd ACTIVE employee; B = `reactivateEmployee.execute(...)` with owner auth; assert B blocked then `PLAN_LIMIT_EXCEEDED`, 3 ACTIVE, target still INACTIVE -- real PostgreSQL proof.
- `keevo/app/lib/features/team/presentation/widget/employee_card.dart` -- plan-limit branch in `_runAction` -- French plan sheet as for creation.
- `keevo/app/test/features/team/presentation/widget/employee_card_test.dart` -- widget test: repo throws 403 DioException with `domainCode: PLAN_LIMIT_EXCEEDED` → plan sheet text shown, no snackbar, `done == 0`, no "PLAN_LIMIT_EXCEEDED" text on screen.
- `keevo/app/pubspec.yaml` -- 1.1.43+42 → 1.1.44+43.

**Acceptance Criteria:**
- Given a FREE tenant with 3 ACTIVE employees, when the owner reactivates an INACTIVE one via `PATCH /api/v1/employees/{id}/reactivate`, then the response is 403 `PLAN_LIMIT_EXCEEDED` and the employee stays INACTIVE.
- Given the same refusal in the app, when the owner confirms « Réactiver », then the plan-limit bottom sheet appears in French and no code/id is displayed.
- Given a concurrent create holding `employee-limit:<schema>` at max−1, when a reactivation runs, then it waits for the lock and is refused (real PostgreSQL test).

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 18 findings — high 0, medium 0, low 11, false 7, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) Offline/queued REACTIVATE_EMPLOYEE refusal surfaces as the French sync notice, not the plan sheet — real; the backend maps it to REJECTED `PLAN_LIMIT_EXCEEDED` (AbstractSyncOperationHandler.mapDomainException), not transient → dropped after « Limite de votre forfait atteinte — passez à un forfait supérieur », next online `listEmployees` restores INACTIVE. Creation has no offline path, so « comme pour la création » is met; an offline local pre-count adds a new branch for a rare case.
  - `[low]` `[reject]` (intent) Race competitor is a raw INSERT under the held lock, not the real CreateEmployeeService — same v1s-13-13 harness; B is the real service, and the lock-removal mutation made the test fail (implementer check).
  - `[false]` `[reject]` (intent) Status-dependent bypass diverges from « exactly the rule » — reactivating an already-ACTIVE employee does not grow the active count; the rule (count ≥ max → PLAN_LIMIT_EXCEEDED) and error code are identical to creation.
  - `[low]` `[reject]` (intent) App detects the refusal via `response.data['domainCode']` rather than create page's `toString().contains` — same sheet, more precise check; unifying is a refactor.
  - `[false]` `[reject]` (intent) Story file not touched — updated at finalize (item ticked, status done).
  - `[low]` `[reject]` (blind) Offline path untested / local ACTIVE until refresh — same root as the first row.
  - `[false]` `[reject]` (blind) Sync handler has no PLAN_LIMIT_EXCEEDED test — generic `mapDomainException` maps any DomainException to REJECTED with its ErrorCode name; already covered for other handlers, no handler change.
  - `[false]` `[reject]` (blind) No REST-level test for 403 — GlobalExceptionHandler.java:302 maps PLAN_LIMIT_EXCEEDED to 403 with domainCode, the same mapping creation relies on; unchanged.
  - `[low]` `[reject]` (blind) Widget test uses string `details` values — the card never reads `details`; repository 4xx rethrow is covered by employee_repository_backend_first_test.dart:319.
  - `[false]` `[reject]` (blind/edge) Sheet ignores server `details.limit` and says « plan gratuit » — PREMIUM and PREMIUM_TRIAL are Integer.MAX_VALUE (PlanType.java:11-15), so only FREE (3) can be refused; kFreePlanLimits mirrors it.
  - `[low]` `[reject]` (blind) Plan-limit detection duplicated three ways — refactor with no observed divergence.
  - `[low]` `[reject]` (blind) Reactivate-vs-reactivate race untested — rare owner-only double tap; serialisation follows from the lock already proven by the PG test.
  - `[low]` `[patch]` (blind) Not-found test not extended to new collaborators — added `verify(transactionLock).lock(...)` + `verifyNoInteractions(planLimitGuard, employeeCountPort, subscriptionRepository, membershipRepository)`.
  - `[low]` `[reject]` (blind) Integration test inserts without explicit status — mirrors the existing v1s-13-13 test; the ACTIVE count assertion (=3) fails loudly if the default changes.
  - `[low]` `[patch]` (blind) InOrder omits `subscriptionRepository` — added `order.verify(subscriptionRepository).findActivePlan()` after findById.
  - `[low]` `[patch]` (edge) Refusal swallowed when the card unmounted — else-branch now shows the French snackbar via `appErrorMessage(e)`.
  - `[false]` `[reject]` (edge) Hard-coded limit may differ from server — same as the details.limit row.
  - `[low]` `[reject]` (edge) Offline reactivation at the cap shows success then a later sync notice — same root as the first row.

## Design Notes

Lock before `findById` (not after): with the lock first, two concurrent reactivations of the same employee serialise and the second reads ACTIVE, so it skips the check instead of being wrongly refused at the cap. The lock is cheap (xact advisory lock) and also taken for already-ACTIVE targets — acceptable.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=ReactivateEmployeeServiceTest,SchedulerLockCoverageTest,ResidualRacesPostgresIntegrationTest -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend identity shared architecture` -- expected: 0 failures/errors
- `cd keevo/app && flutter test --concurrency=4 test/features/team/presentation/widget/employee_card_test.dart` then `run-tests-chunked.sh app test/features/team` -- expected: 0 failures
- `cd keevo/app && flutter analyze` -- expected: 0 errors, 0 warnings

## Auto Run Result

Status: done

**Summary:** `ReactivateEmployeeService` now takes the `employee-limit:<schema>` advisory lock first, then — only when the target is not already ACTIVE — checks the active plan and `PlanLimitGuard.checkEmployeeLimit` (same rule and `PLAN_LIMIT_EXCEEDED` code as creation) before writing. The app's employee card opens the French plan sheet on that refusal.

**Files changed:**
- `keevo/backend/src/main/java/com/keevo/identity/employee/application/service/ReactivateEmployeeService.java` -- lock + plan limit check.
- `keevo/backend/src/test/java/com/keevo/identity/employee/application/service/ReactivateEmployeeServiceTest.java` -- InOrder, at-cap, already-ACTIVE, no-subscription, not-found tests.
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/config/SchedulerLockCoverageTest.java` -- entry point listed (Map.of now at its 10-pair max).
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/ResidualRacesPostgresIntegrationTest.java` -- real-PostgreSQL race reactivate vs create.
- `keevo/app/lib/features/team/presentation/widget/employee_card.dart` -- plan sheet on PLAN_LIMIT_EXCEEDED (snackbar fallback if unmounted).
- `keevo/app/test/features/team/presentation/widget/employee_card_test.dart` -- widget test.
- `keevo/app/pubspec.yaml` -- 1.1.43+42 → 1.1.44+43.

**Review:** 18 findings; 3 patched (all low), 0 deferred, 15 rejected (reasons in the triage log). Follow-up review recommended: false (patched: high 0, medium 0, low 3).

**Verification:** ReactivateEmployeeServiceTest 8/8, SchedulerLockCoverageTest 5/5, ResidualRacesPostgresIntegrationTest 9/9 (new test fails with the lock removed), EmployeeSyncHandlerTest 6/6; chunked backend identity 371, shared 490, architecture 6 — 0 failures/errors; app employee_card_test 23/23, test/features/team 122/122; flutter analyze 0 errors / 0 warnings.

**Residual risks:** an offline reactivation at the cap shows local success, then the French sync notice; the local status is corrected at the next online refresh.

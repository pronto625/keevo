# Story HF-2: Stabilisation Online + RBAC Employe + Notifications Transferts

Status: ready-for-dev

## Story

As an owner (Simon),
I want online transfer, pending-sale, employee navigation and notification flows to be stable and role-consistent,
so that operations run without crashes, stock inconsistencies, or broken routing/permissions.

## Scope Summary

- Fix production regressions observed in online mode tests.
- Expand EMPLOYEE operational permissions (catalog, transfer operations, pending-sale validation/cancel).
- Keep financial confidentiality for EMPLOYEE (no monetary values exposed in UI, and minimized exposure in API).
- Preserve existing architecture and behavior from Story 3.3, Story 4.3, Story 5.6, Story 8.0.

## Acceptance Criteria

### AC1 - Two-step transfer stock consistency (no premature destination credit)
- Given `POST /api/v1/stock/transfers` succeeds (Step 1)
- When destination users open POS, catalog and stock overview before reception
- Then destination stock remains unchanged
- And transfer status is `IN_TRANSIT`
- And destination increment occurs only on `POST /api/v1/stock/transfers/{id}/complete` (Step 2)
- And stock delta is applied once (idempotent against duplicate local/remote replay).

### AC2 - Transfer notification to correct recipients with valid deep link
- Given a transfer is created toward destination store B
- When transfer creation event is published
- Then notifications are sent to:
  - all OWNER users of tenant
  - all EMPLOYEE users assigned to destination store B
- And deep link is router-valid and opens transfer reception context (`/stock/transfers`)
- And tap from foreground/background/cold-start resolves to the expected screen without fallback error.

### AC3 - Employee creation flow no longer freezes or crashes
- Given owner creates an employee from Team screen
- When success bottom sheet is shown and dismissed
- Then the UI remains responsive and allows back navigation immediately
- And no `Bad state: Cannot use "ref" after the widget was disposed` is thrown
- And listener side effects execute once only (no duplicated callbacks).

### AC4 - Pending sale validation/cancel is deterministic
- Given a pending sale containing draft and active products
- When OWNER or authorized EMPLOYEE validates or cancels online
- Then operation completes without `Bad state: Too many elements`
- And stock decrement is applied once at final validation only
- And repeated action attempts are idempotent and return stable domain errors.

### AC5 - EMPLOYEE RBAC expansion for operations
- Given authenticated role is `EMPLOYEE`
- Then user can access:
  - catalog operations route and list screens (`/catalog`, `/products` data access path)
  - transfer history/reception (`/stock/transfers`)
  - pending sales list/detail and validate/cancel actions (`/pos/pending`, `/pos/pending/:id`)
- And backend authorization allows OWNER + EMPLOYEE on these use cases with strict tenant + assigned-store scoping.

### AC6 - Financial data hidden for EMPLOYEE
- Given role is `EMPLOYEE`
- When viewing catalog, pending sales, POS related operational screens
- Then monetary values are not shown (price, buyPrice, subtotal, total, margin, profitability)
- And OWNER retains full financial visibility
- And employee-facing API responses avoid returning sensitive financial fields whenever endpoint contract permits.

### AC7 - Notification deep-link contract matches GoRouter
- Given backend emits deep links for pending sale and transfer flows
- Then all links map to existing routes in `app_router.dart`
- And invalid legacy paths are removed (example: `/pos/pending-sales`)
- And pending-sale link points to valid route (`/pos/pending` or `/pos/pending/{id}` by payload context).

## GoF Pattern Decision (MANDATORY)

| Concern | Selected Pattern | Why this pattern | Expected implementation anchor |
|---|---|---|---|
| Recipient selection for transfer notifications | Strategy | Recipient rules vary by event and role/scope (owner-only vs owner+destination-employees) without changing publisher logic | Extend notification application layer around `NotificationPort` with recipient strategy per event type |
| Transfer/pending-sale event propagation | Observer | Domain/application events trigger decoupled side effects (push notification, audit) | Reuse existing event-listener style (`...notification/application/listener/...`) |
| Role-based field visibility (financial masking) | Decorator | Same base DTO/view model, role-specific projection for monetary fields | Add role-aware presenter/mapper decorator in read path; UI remains second defensive layer |

Design constraint: prefer extension over branching in controllers/services. Keep use cases closed for modification and open for new notification/masking strategies.

## Full TDD Protocol (MANDATORY)

For every bugfix/feature below, follow strict cycle: RED -> GREEN -> REFACTOR.

1. Write failing test(s) first.
2. Implement minimal code to pass.
3. Refactor while keeping tests green.
4. Add regression test for reproduced bug log.

No task is considered done without:
- automated tests green,
- regression test proving the original failure,
- cURL/API validation steps passing for backend-facing behavior.

## Tasks / Subtasks

- [ ] Task 1 - Fix transfer stock timing and replay safety (AC: 1)
  - [ ] 1.1 Add failing repository/use-case tests proving destination stock does not increment on execute.
  - [ ] 1.2 Correct `executeTransfer` local/remote path to keep destination unchanged until complete.
  - [ ] 1.3 Add replay/idempotency tests for duplicate sync operations.

- [ ] Task 2 - Implement transfer notification routing by destination store (AC: 2, 7)
  - [ ] 2.1 Add failing listener test for transfer-created event recipient matrix (owners + destination employees).
  - [ ] 2.2 Implement Strategy-based recipient resolution without breaking existing owner notifications.
  - [ ] 2.3 Add deep-link contract test ensuring emitted route is valid in Flutter router.

- [ ] Task 3 - Fix employee creation page lifecycle freeze (AC: 3)
  - [ ] 3.1 Add widget test reproducing post-success freeze/back failure.
  - [ ] 3.2 Move `ref.listen` side effects to safe lifecycle hook and guard with `mounted` + single-fire control.
  - [ ] 3.3 Add regression test: no disposed-ref error on sheet close + back navigation.

- [ ] Task 4 - Stabilize pending-sale validate/cancel query semantics (AC: 4)
  - [ ] 4.1 Add failing test for `Too many elements` scenario (multi-row selection edge case).
  - [ ] 4.2 Fix selection logic to deterministic first/single behavior with domain guard.
  - [ ] 4.3 Add integration tests for mixed draft/active items with repeated validate/cancel attempts.

- [ ] Task 5 - Expand EMPLOYEE permissions on pending + transfer + catalog operations (AC: 5)
  - [ ] 5.1 Add backend authorization tests (OWNER/EMPLOYEE/forbidden store matrix).
  - [ ] 5.2 Update controller/use-case RBAC to allow EMPLOYEE where required.
  - [ ] 5.3 Update Flutter route guards for `/pos/pending`, `/pos/pending/:id`, `/stock/transfers`, catalog screens.

- [ ] Task 6 - Enforce financial masking for EMPLOYEE (AC: 6)
  - [ ] 6.1 Add failing UI snapshot/widget tests for employee role (no monetary labels/values visible).
  - [ ] 6.2 Add backend response-shaping tests for employee-facing endpoints.
  - [ ] 6.3 Implement role-based decorator/mapper and keep OWNER payload unchanged.

- [ ] Task 7 - Deep-link cleanup for pending sale notifications (AC: 7)
  - [ ] 7.1 Replace invalid pending-sale deep links with router-valid paths.
  - [ ] 7.2 Add listener-level tests and app route parsing tests.

## Test Requirements

### Backend (JUnit)
- Role matrix tests:
  - OWNER can list/validate/cancel pending sales.
  - EMPLOYEE can list/validate/cancel only for assigned store.
  - EMPLOYEE gets 403 for unauthorized store or owner-only endpoints.
- Transfer notification tests:
  - recipient selection includes destination employees only.
  - deep link path contract is valid.
- Pending sale stability tests:
  - no multi-result crash.
  - idempotent validate/cancel behavior.

### Flutter (widget/unit/integration)
- Team screen lifecycle regression:
  - no freeze after employee creation success sheet.
  - no disposed `ref` usage.
- Pending sale pages:
  - EMPLOYEE access allowed.
  - financial widgets hidden for EMPLOYEE.
- Transfer screen:
  - destination stock not shown as received before completion.

## cURL Validation Suite (MANDATORY)

Create `keevo/backend/scripts/curl-tests-hf-2.sh` with reproducible steps (OWNER token + EMPLOYEE token).

Required checks:

1. Transfer two-step behavior
- `POST /api/v1/stock/transfers` then verify destination stock unchanged.
- `POST /api/v1/stock/transfers/{id}/complete` then verify destination stock incremented.

2. Pending sale permissions and flow
- EMPLOYEE: `GET /api/v1/sales/pending` -> 200
- EMPLOYEE: `POST /api/v1/sales/{id}/validate` -> 200 (assigned store)
- EMPLOYEE: same action on non-assigned store -> 403

3. Notification deep-link contract
- Trigger pending-sale and transfer events, assert payload deepLink is one of:
  - `/pos/pending`
  - `/pos/pending/{id}`
  - `/stock/transfers`

4. Financial masking checks
- EMPLOYEE calls catalog/pending endpoints and does not receive/see monetary fields intended to be hidden.

Exit rule: script must fail fast (`set -euo pipefail`) and print PASS/FAIL per step.

## Dev Notes

- Keep coherence with existing architecture decisions:
  - transfer two-step lifecycle from Story 3.3,
  - pending-sale lifecycle from Story 4.3,
  - offline-first sync behavior from Story 5.6,
  - notification infrastructure from Story 8.0.
- Do not introduce direct DB/manual SQL changes; use existing migration pipeline only.
- RBAC enforcement order:
  1. backend authorization + store scoping (security boundary),
  2. Flutter route/UI guard (UX boundary).

### Project Structure Notes

- Flutter targets:
  - `keevo/app/lib/core/router/app_router.dart`
  - `keevo/app/lib/features/team/presentation/page/create_employee_page.dart`
  - `keevo/app/lib/features/pos/presentation/page/pending_sale_detail_page.dart`
  - `keevo/app/lib/features/inventory/data/repository/stock_transfer_repository_impl.dart`
  - `keevo/app/lib/features/catalog/presentation/widget/product_card.dart`
- Backend targets:
  - `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleController.java`
  - `keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/SaleDraftValidatedNotificationListener.java`
  - `keevo/backend/src/main/java/com/keevo/messaging/notification/domain/port/out/NotificationPort.java`
  - transfer and stock use cases under `keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/`

### References

- `_bmad-output/implementation-artifacts/3-3-transferts-inter-boutiques-avec-tracabilite-complete.md`
- `_bmad-output/implementation-artifacts/4-3-vente-brouillon-produits-draft-validation-admin.md`
- `_bmad-output/implementation-artifacts/8-0-fcm-push-notifications-whatsapp-wassender.md`
- `keevo/app/lib/core/router/app_router.dart`
- `keevo/app/lib/features/inventory/data/repository/stock_transfer_repository_impl.dart`
- `keevo/app/lib/features/team/presentation/page/create_employee_page.dart`
- `keevo/app/lib/features/pos/presentation/page/pending_sale_detail_page.dart`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/in/rest/PendingSaleController.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/SaleDraftValidatedNotificationListener.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/domain/port/out/NotificationPort.java`

## Dev Agent Record

### Agent Model Used

GPT-5.3-Codex

### Debug Log References

- User-provided runtime logs/screenshots in this conversation (online mode).

### Completion Notes List

- Story aligned to reported production bugs and requested permission changes.
- Added mandatory TDD and cURL validation gates.
- Added explicit GOF decision table to guide implementation design.

### File List

- `_bmad-output/implementation-artifacts/HF-2-stabilisation-online-rbac-employe-notifications-transferts.md`

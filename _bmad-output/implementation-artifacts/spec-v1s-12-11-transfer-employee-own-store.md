---
title: 'v1s-12-11 — Transfer: an employee transfers only from their own store (REST + sync)'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: 'cb26cef67990769c3c63cb452a03a96987add88a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Commit 4302eba removed the OWNER-only guard on transfer initiation and let an EMPLOYEE start a transfer from *any* store, on both REST (`POST /api/v1/stock/transfers`) and sync (`STOCK_TRANSFER`). PO decision 2026-10-11: an EMPLOYEE may transfer stock only out of their own assigned store (FR36, v1s-12-8 AC4).

**Approach:** Restore a store-scope guard (not the old OWNER-only guard) in `StockTransferController.transfer()` and `TransferSyncHandler.apply()`: when the actor is an EMPLOYEE, `sourceStoreId` must equal the JWT-assigned store, else `DomainException(FORBIDDEN)` before the use case runs. OWNER stays unscoped. Mirror it in the Flutter transfer form (EMPLOYEE source locked to their store) and fix the misleading 4302eba comments.

## Boundaries & Constraints

**Always:** Role and assigned store come from the authenticated actor (`AuthDetails` / authorities), never from the payload. Reuse the existing `extractAssignedStoreId()` helpers (controller + `AbstractSyncOperationHandler`); an EMPLOYEE token without storeId fails closed (existing helper behavior). The check runs before `TransferStockUseCase.execute` (no stock movement, no idempotent replay lookup). Destination may be any store of the tenant. Keep 12.9's destination scope on `complete()` untouched. Keep the `SyncErrorLogRepositoryAdapter` part of 4302eba (unrelated fix).

**Never:** No roles/privileges module, no new permission model, no use-case role awareness (epic decision: scope lives in controller + handler). Do not change `SyncOperation`/`SyncOperationHandler` signatures. Do not change null-auth handling in `AbstractSyncOperationHandler` (owned by v1s-12-10). Do not re-add OWNER-only on transfer initiation.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Employee own store (REST) | EMPLOYEE storeId=A, source=A, dest=B | 201, use case called | — |
| Employee other store (REST) | EMPLOYEE storeId=A, source=C | 403 FORBIDDEN, use case never called | DomainException FORBIDDEN |
| Employee without store claim (REST) | EMPLOYEE, AuthDetails.storeId null / no AuthDetails | 403 | existing helper throws FORBIDDEN |
| Owner cross-store (REST) | OWNER, source=C | 201 | — |
| Employee own store (sync) | STOCK_TRANSFER source=A, EMPLOYEE storeId=A | APPLIED | — |
| Employee other store (sync) | source=C | REJECTED, reason FORBIDDEN, use case never called | mapped by AbstractSyncOperationHandler |
| Owner (sync) | OWNER, source=C | APPLIED | — |
| Malformed sourceStoreId (sync) | "not-a-uuid" | REJECTED | IllegalArgumentException → REJECTED (existing) |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockTransferController.java` -- `transfer()` (~L71) has no scope check since 4302eba; `extractAssignedStoreId()` (~L161) already exists (added by 12.9) and returns null for OWNER, throws FORBIDDEN for EMPLOYEE without store. Class-level `@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")` stays.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/TransferSyncHandler.java` -- `apply()` (L37) has the misleading "any store" comment; `validate()` checks required fields only.
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/AbstractSyncOperationHandler.java` -- read-only: `extractAssignedStoreId()` (L50), `mapDomainException` → REJECTED with `ErrorCode.name()`.
- `keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/ExecuteTransferService.java` -- read-only: asserts source/destination exist in the tenant schema (STORE_NOT_FOUND) — covers the "store belongs to tenant" half of item D4.
- `keevo/backend/src/test/java/com/keevo/catalog/stock/adapter/in/web/StockTransferControllerTest.java` -- `POST_stock_transfers_employeeAllowed_shouldReturn201` (L174, uses `authenticateAs("EMPLOYEE")` without AuthDetails → will now 403); helper `authenticateAsEmployeeWithStore(UUID)` (L214).
- `keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/TransferSyncHandlerTest.java` -- `authenticateAsEmployee()` (L60, no AuthDetails), `handle_validTransfer_employee_delegates` (L91).
- `keevo/app/lib/features/inventory/presentation/widget/transfer_form_bottom_sheet.dart` -- self-load mode (FAB path, `destinationStores` empty) shows a free "Boutique source" dropdown over all stores (~L330).
- `keevo/app/lib/features/inventory/presentation/page/transfer_history_page.dart` -- FAB comment (~L198) claims "any store"; `_shouldShowReceiveButton` (~L456) is the 12.9 rule, untested.
- `keevo/app/lib/features/stores/presentation/provider/active_store_provider.dart` + `auth/presentation/provider/session_claims_applier.dart:70` -- for an EMPLOYEE, `activeStoreIdProvider` is set to the JWT storeId at each token apply; `currentUserRoleProvider` in `core/di/providers.dart:52`.
- `keevo/app/test/features/inventory/presentation/page/transfer_history_page_test.dart`, `.../widget/transfer_form_bottom_sheet_test.dart` -- existing widget test harnesses to extend.

## Tasks & Acceptance

**Execution:**
- `StockTransferController.java` -- in `transfer()`, call `extractAssignedStoreId()`; if non-null and `!equals(request.sourceStoreId())` throw `DomainException(FORBIDDEN, "EMPLOYEE can only transfer stock from their assigned store")`; rewrite the javadoc (FR36 / v1s-12-11, not "any store").
- `TransferSyncHandler.java` -- in `apply()`, same check against the payload `sourceStoreId` (parsed UUID) before building the command; replace the 4302eba comment.
- `StockTransferControllerTest.java` -- replace the "employeeAllowed any store" test with: employee own store → 201 + use case called; employee other store → 403 + `verify(executeTransferService, never())`; employee without store claim → 403; owner other store → 201.
- `TransferSyncHandlerTest.java` -- employee auth carries `AuthDetails` with storeId; cases: own store APPLIED, other store REJECTED `FORBIDDEN` + use case never invoked, employee without store REJECTED, owner APPLIED.
- `transfer_form_bottom_sheet.dart` -- self-load mode: when role is EMPLOYEE, the source options are only the store whose id == `activeStoreIdProvider` and it is preselected (dropdown disabled/locked); if none matches, show no source options so submit is impossible. OWNER unchanged.
- `transfer_history_page.dart` -- fix the FAB comment (employee: own store only, enforced by backend). FAB stays visible for both roles.
- `transfer_form_bottom_sheet_test.dart` -- widget tests: EMPLOYEE self-load shows only their store as source; OWNER self-load sees all stores.
- `transfer_history_page_test.dart` -- widget tests for "Réceptionner" visibility (item from CR v1s-12-9): EMPLOYEE active==destination visible, EMPLOYEE active≠destination hidden, EMPLOYEE active==null hidden, OWNER active==null visible.
- `keevo/app/pubspec.yaml` -- bump patch + build.

**Acceptance Criteria:**
- Given an EMPLOYEE assigned to store A, when they POST a transfer or push a STOCK_TRANSFER with `sourceStoreId` ≠ A, then REST returns 403 / sync returns REJECTED (`FORBIDDEN`) and no stock moves.
- Given an OWNER, when they transfer from any store of the tenant, then it succeeds on both channels (FR36, v1s-12-8 AC4 restored with the PO's employee-own-store refinement).
- Given an EMPLOYEE opening "Nouveau transfert" from the history FAB, then the source is locked to their store.
- Given the routed items, then D4 is resolved by the sync check (+ existing STORE_NOT_FOUND tenant check) and the `_shouldShowReceiveButton` tests exist.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 23 findings — high 0, medium 0, low 10, false 10, maybe-false 3
- findings:
  - `[low]` `[reject]` (edge) role null/other casing leaves EMPLOYEE picker unlocked — role is always written by SessionClaimsApplier at sign-in; the backend still returns 403; fail-closed would lock OWNERs with a missing pref.
  - `[low]` `[patch]` (edge) EMPLOYEE whose store is not loaded gets an empty, disabled source with no explanation — added `disabledHint: 'Votre boutique est introuvable'` + test assertion.
  - `[false]` `[reject]` (edge) activeStoreId changes while sheet open → destination equals source crash — the sheet is modal; an EMPLOYEE's active store only changes on token apply / Settings, unreachable while the sheet is open.
  - `[false]` `[reject]` (edge) non-self-load path unlocked for EMPLOYEE — its only caller (`cross_store_availability_bottom_sheet.dart:419`) is OWNER-only; EMPLOYEE gets the "Contacter le propriétaire" CTA.
  - `[low]` `[reject]` (edge) non-String sourceStoreId → ClassCastException → INTERNAL_ERROR — pre-existing (cast already in apply before this diff); still REJECTED; extra guard not worth it.
  - `[maybe-false]` `[reject]` (edge) replay of an already-applied op after employee reassignment is REJECTED — deliberate (Design Notes: fail closed, no foreign transfer read-back); needs reassignment + lost ack; if true only low (server row exists, next pull reconciles).
  - `[maybe-false]` `[reject]` (edge) null auth fails open in `extractAssignedStoreId` — pre-existing, explicitly owned by v1s-12-10 item D2 (already routed there); sync push always runs behind JwtAuthFilter.
  - `[false]` `[reject]` (blind) guard duplicated in controller + handler instead of use case — epic-12 Technical Decision: use cases carry no role awareness, scope lives in controller and is mirrored in the sync handler.
  - `[low]` `[reject]` (blind) idempotent replay rejected after reassignment — same as edge row above.
  - `[false]` `[reject]` (blind) role inferred from storeId presence; OWNER with storeId would be scoped — `JwtAuthFilter.java:236-240` sets `AuthDetails.owner()` (storeId null) for every non-EMPLOYEE token. Null-auth half → v1s-12-10 D2.
  - `[low]` `[reject]` (blind) `_selectedSource` assigned in build() — deterministic, idempotent derivation from watched providers; covered by tests; refactor adds complexity for no observed defect.
  - `[low]` `[patch]` (blind) EMPLOYEE with missing store gets no explanation — same root cause as edge row; patched with disabledHint.
  - `[low]` `[reject]` (blind) client scope from activeStoreIdProvider not JWT — documented design (12.9 precedent, no new provider; future roles module); backend is the perimeter.
  - `[false]` `[reject]` (blind) 403 shown in English — GlobalExceptionHandler maps FORBIDDEN to FR "Vous n'avez pas les droits nécessaires" in `error` (confirmed by 12.9). Sync REJECTED user feedback is owned by Epic 16.
  - `[false]` `[reject]` (blind) missing sourceStoreId → NPE → INTERNAL_ERROR — `validate()` rejects null sourceStoreId with IllegalArgumentException before apply.
  - `[low]` `[reject]` (blind) missing mobile tests (submit payload, FAB visibility, non-self-load) — non-self-load is OWNER-only; dropdown value test pins the submitted source (`_effectiveSourceId` = `_selectedSource.id`).
  - `[false]` `[reject]` (blind) OWNER storeId edge case untested — unreachable, see JwtAuthFilter row.
  - `[low]` `[reject]` (blind) OWNER can pick inactive destination / no source preselect — pre-existing, out of this story's rule; no change caused.
  - `[false]` `[reject]` (blind) receive-button tests unrelated to AC — they are the routed CR v1s-12-9 item this story owns.
  - `[maybe-false]` `[reject]` (intent) sync tested at handler level, not full push pipeline — handler is where the REJECTED mapping lives (AbstractSyncOperationHandler); 12.8 used the same level; if true only low.
  - `[false]` `[reject]` (intent) D4 tenant half not re-checked in handler — ExecuteTransferService looks the source up in the tenant schema (STORE_NOT_FOUND, covered by ExecuteTransferServiceTest); recorded in Design Notes.
  - `[false]` `[reject]` (intent) refonte "inventory/store" not addressed — refonte absorption is later-track work per epic context.
  - `[low]` `[reject]` (intent) receive-button mismatch fixture uses active == source only — plus the null case; a third unrelated-store fixture adds nothing to the strict-equality rule.

## Design Notes

- PO decision 2026-10-11 supersedes both 12.6/12.8 "OWNER-only initiation" and 4302eba "any store": the guard is a *scope* check, so the original 4302eba pain (employee transfers looping REJECTED in sync_queue) stays fixed for the legitimate case.
- Check placed before the use case's idempotent `findById(clientId)` so an employee cannot read back a foreign transfer via replay. Consequence: an op queued offline before a store reassignment is REJECTED after it — accepted (fail closed, FR36).
- Item D4 "validate store belongs to tenant/employee": employee half = this check; tenant half = `ExecuteTransferService` already looks the source up in the tenant schema (STORE_NOT_FOUND). No extra query added.
- Flutter source lock uses `activeStoreIdProvider` (kept equal to the JWT store for employees by `SessionClaimsApplier`), as 12.9 did; no new "assignedStoreId" provider (future roles module). Backend is the real perimeter.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='StockTransferControllerTest,TransferSyncHandlerTest,ExecuteTransferServiceTest,CompleteTransferServiceTest'` -- expected: all green
- `cd keevo/backend && mvn test` -- expected: no failures beyond the 10-class baseline
- `cd keevo/app && flutter analyze && flutter test` -- expected: no new issue; only known offline_gate_banner_test failure

## Auto Run Result

Status: done

**Summary:** EMPLOYEE transfer initiation is scoped again to their JWT-assigned source store on REST (`StockTransferController.transfer()`) and sync (`TransferSyncHandler.apply()`), FORBIDDEN before the use case runs; OWNER stays cross-store. Reverts the "any store" opening of 4302eba (its SyncErrorLog fix kept). Flutter form locks an EMPLOYEE's source to their store (self-load/FAB path).

**Files changed:**
- `keevo/backend/.../StockTransferController.java` -- source-store scope guard + javadoc.
- `keevo/backend/.../TransferSyncHandler.java` -- same guard on STOCK_TRANSFER, comment fixed.
- `keevo/backend/.../StockTransferControllerTest.java` -- employee own/other/no-claim/null-store + owner cross-store.
- `keevo/backend/.../TransferSyncHandlerTest.java` -- employee own/other/no-claim/malformed + owner.
- `keevo/app/.../transfer_form_bottom_sheet.dart` -- EMPLOYEE source locked, disabled hint when store missing.
- `keevo/app/.../transfer_history_page.dart` -- FAB comment corrected.
- `keevo/app/test/.../transfer_form_bottom_sheet_test.dart`, `transfer_history_page_test.dart` -- source-lock tests; "Réceptionner" visibility tests (routed CR v1s-12-9 item).
- `keevo/app/pubspec.yaml` -- 1.1.14+13 → 1.1.15+14.

**Review:** 23 findings; 1 patch (low: disabledHint for EMPLOYEE without loaded store, 2 rows); 0 deferred; 21 rejected with reasons in the triage log. Null-auth fail-open stays with v1s-12-10 (item D2 already routed there). Patched counts: high 0, medium 0, low 1 → followup_review_recommended: false.

**Routed items:** CR v1s-12-9 `_shouldShowReceiveButton` tests → resolved here (4 widget tests). CR v1s-12-8 D4 → resolved here (employee half: handler scope check; tenant half: existing STORE_NOT_FOUND lookup in tenant schema).

**Verification:** targeted backend tests green (StockTransferControllerTest 18, TransferSyncHandlerTest 8, ExecuteTransferServiceTest 11, CompleteTransferServiceTest 7). Full `mvn test`: 1883 tests, 7 failures + 29 errors in the 10 known baseline classes only. `flutter test`: 1293 pass, 1 known failure (offline_gate_banner_test); touched files 13/13 after patch. `flutter analyze`: no error/warning, info-level only (pre-existing style).

**Residual risks:** a STOCK_TRANSFER queued offline before an employee's reassignment is REJECTED after it (accepted, fail closed); client-side lock relies on `activeStoreIdProvider` mirroring the JWT store (backend is the perimeter).

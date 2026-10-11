---
title: 'v1s-12-10 — Residual sync RBAC (PROMOTE_PRODUCT, TOGGLE_CATEGORY, inventory sessions, null auth)'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: 'b1f55e10c9ec364f21a8005b591f92facc2a8afa'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** After v1s-12-8, three sync paths still let an EMPLOYEE bypass RBAC (audit I1 2026-10-11): `PROMOTE_PRODUCT`/`UPDATE_PRODUCT` call `UpdateProductUseCase` with the 10-arg DTO that defaults `actorRole` to `"OWNER"` (the Story 2.4 "only OWNER promotes a draft" guard never fires — same hole on REST `PATCH /products/{id}`); `TOGGLE_CATEGORY` (the offline delete since v1s-14-14) has no role check while REST `DELETE /categories/{id}` is OWNER-only; `CREATE_INVENTORY_SESSION` (and `SAVE_INVENTORY_COUNT`) ignore the employee's store scope enforced by `InventorySessionController.createSession`. Also `AbstractSyncOperationHandler.extractAssignedStoreId()` returns null (= OWNER) when there is no authentication (CR 12-8 D2).

**Approach:** Reuse the v1s-12-11 pattern: rule in REST controller + sync handler, sync rejection = `DomainException(FORBIDDEN)` → REJECTED `FORBIDDEN`; OWNER has no storeId in its JWT and stays unscoped. Pass the real actor role to the product use case on both channels, make category toggle OWNER-only on both channels, scope inventory session creation and counts to the employee's store, fail closed on null auth. On the app, an EMPLOYEE checkout no longer promotes draft products (locally or via `PROMOTE_PRODUCT`); the owner notification then says the drafts await validation.

## Boundaries & Constraints

**Always:** role and store come from the authenticated actor (authorities / `AuthDetails`), never from the payload. Checks run before any idempotency lookup or use-case call (no read-back of foreign data). Use `isOwnerRole()` / `extractAssignedStoreId()` from `AbstractSyncOperationHandler`; REST controllers keep their own private helpers (existing style) plus explicit defense-in-depth checks because tests use standalone MockMvc (no `@PreAuthorize` evaluation).

**Never:** no roles/privileges module, no new permission model, no change to `SyncOperation`/`SyncOperationHandler` signatures, no use-case role awareness beyond the existing `UpdateProductDto.actorRole`. Do not route `InventorySessionSyncHandler` through `CreateInventorySessionUseCase` (functional parity, routed to v1s-13-8). Do not touch RENAME/CREATE category rules or read endpoints.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Null auth (sync) | no Authentication, handler calls `extractAssignedStoreId()` | REJECTED `FORBIDDEN` | DomainException FORBIDDEN |
| Promote by employee (sync) | EMPLOYEE, `PROMOTE_PRODUCT` | REJECTED `FORBIDDEN`, use case never called | explicit OWNER check |
| Promote by owner (sync) | OWNER, `PROMOTE_PRODUCT` on DRAFT | APPLIED, product ACTIVE | — |
| Update draft by employee (sync/REST) | EMPLOYEE, `UPDATE_PRODUCT` / PATCH on DRAFT product | REJECTED `FORBIDDEN` / 403 | use-case guard with real role |
| Update active product by employee | EMPLOYEE, ACTIVE product | APPLIED / 200 (unchanged) | — |
| Toggle category by employee (sync/REST) | EMPLOYEE, `TOGGLE_CATEGORY` / PATCH `/toggle` | REJECTED `FORBIDDEN` / 403, no repository lookup | explicit OWNER check |
| Toggle by owner | OWNER | APPLIED / 200 (unchanged) | — |
| Session other store (sync) | EMPLOYEE store A, `CREATE_INVENTORY_SESSION` storeId C | REJECTED `FORBIDDEN`, nothing saved | — |
| Session own store / owner | EMPLOYEE storeId A / OWNER any | APPLIED | — |
| Count in foreign session (sync/REST) | EMPLOYEE store A, session of store C | REJECTED `FORBIDDEN` / 403, use case never called | — |
| Count, unknown session | EMPLOYEE, sessionId not found | delegated to use case (existing not-found error) | existing |
| Employee checkout with draft (app) | role EMPLOYEE, cart has draft | initial stock applied, product stays DRAFT locally, no `PROMOTE_PRODUCT` queued, CREATE_SALE still carries `originalDraftProductIds` | — |
| Owner checkout with draft (app) | role OWNER | unchanged (promote locally + `PROMOTE_PRODUCT`) | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/sync/sync/application/handler/AbstractSyncOperationHandler.java` -- `extractAssignedStoreId()` L50 (`if (auth == null) return null;` = D2), `isOwnerRole()` L65. Add `actorRole()` helper ("OWNER"/"EMPLOYEE"/null from authorities).
- `.../sync/sync/application/handler/ProductSyncHandler.java` -- `UPDATE_PRODUCT` L82 and `PROMOTE_PRODUCT` L131 use the 10-arg `UpdateProductDto`; `CREATE_DRAFT_PRODUCT` L126 hardcodes `"OWNER"`.
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/UpdateProductUseCase.java` -- read-only: 11-arg DTO, guard L88 FORBIDDEN on DRAFT when role ≠ OWNER.
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductController.java` -- `updateProduct()` L140 (10-arg DTO); private `extractRole()` L236 already exists.
- `.../sync/sync/application/handler/CategorySyncHandler.java` -- `TOGGLE_CATEGORY` branch L104-120.
- `keevo/backend/src/main/java/com/keevo/catalog/category/adapter/in/web/CategoryController.java` -- `toggleCategory()` L121 (open to EMPLOYEE), `deleteCategory()` L147 `@PreAuthorize("hasRole('OWNER')")`.
- `.../sync/sync/application/handler/InventorySessionSyncHandler.java` -- `apply()`: idempotency `findById` then `repository.save` without scope.
- `.../sync/sync/application/handler/InventoryCountSyncHandler.java` -- `SAVE_INVENTORY_COUNT`, no scope; needs `InventorySessionRepository` (port out, `findById` returns session with `getStoreId()`).
- `keevo/backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventoryCountController.java` -- `saveCount()` POST and `bulkSaveCounts()` PUT `/counts`, no scope. `InventorySessionController.extractAssignedStoreId()` (private) is the helper to mirror.
- `keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/SaleDraftValidatedNotificationListener.java` -- `onSaleDraftProductsUpgraded()` ~L241: "🆕 Nouveaux produits activés" wording; `employeeRepository.findByUserId(sale.getEmployeeId())` identifies an employee seller.
- Tests: `keevo/backend/src/test/java/com/keevo/sync/sync/application/handler/SyncTestAuth.java` (`owner()`, `employee(storeId)`, `clear()`), `ProductSyncHandlerTest`, `CategorySyncHandlerTest`, `InventoryCountSyncHandlerTest` (no `InventorySessionSyncHandlerTest` yet), `catalog/category/adapter/in/web/CategoryControllerTest`, `catalog/product/adapter/in/web/ProductControllerTest`, `inventory/counting/adapter/in/rest/InventoryCountControllerTest`.
- `keevo/app/lib/features/pos/presentation/provider/record_sale_notifier.dart` -- L116-123 `promoteToActive` for each draft inside the transaction.
- `keevo/app/lib/features/pos/data/repository/sale_repository_impl.dart` -- L65-82 queues `PROMOTE_PRODUCT` per `sale.originalDraftProductIds`.
- `keevo/app/lib/features/pos/domain/model/sale_model.dart`, `.../domain/usecase/record_sale_usecase.dart` -- plain classes carrying `originalDraftProductIds`.
- `keevo/app/lib/features/pos/presentation/widget/cart_bottom_sheet.dart` -- `_handleEncaisser()` ~L68 prompts "Stock initial" for every `isDraft` cart item; `LocalSaleDataSource.getAvailableStock()` (`local_sale_datasource.dart:118`) returns 0 when no `stock_levels` row exists, so it cannot tell "never stocked" from "sold out".
- `keevo/app/lib/features/catalog/presentation/widget/product_card.dart` -- card `onTap` L58 and "Modifier" menu option ~L346 open `/products/{id}/edit` for any role; `_confirmPromote` (~L460-520) owner dialog "Stock initial" is additive (`stockRepo.recordEntry`).
- `keevo/backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventoryCountController.java` `quickAddProduct()` ~L142 (POST `/quick-add`) writes a product + stock level + count into the session; test `QuickAddProductControllerTest`.
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/model/SaleDraftProductsUpgradedEvent.java` + `SaleSyncHandler.java` ~L95 (publisher) -- the event carries no seller role.
- `keevo/app/lib/core/di/providers.dart:52` -- `currentUserRoleProvider` ('OWNER'|'EMPLOYEE'|null).
- App tests: `keevo/app/test/features/pos/presentation/provider/record_sale_notifier_test.dart` (draft tests L196+), `keevo/app/test/features/pos/data/repository/sale_repository_offline_first_test.dart` (PROMOTE queueing).

## Tasks & Acceptance

**Execution:**
- `AbstractSyncOperationHandler.java` -- null auth → `DomainException(FORBIDDEN, "No authenticated actor")`; add `protected String actorRole()`; javadoc notes v1s-12-10 D2.
- `ProductSyncHandler.java` -- `PROMOTE_PRODUCT`: `if (!isOwnerRole())` FORBIDDEN first, then 11-arg DTO with `"OWNER"`; `UPDATE_PRODUCT`: 11-arg DTO with `actorRole()`; `CREATE_DRAFT_PRODUCT`: `actorRole()` instead of `"OWNER"`.
- `ProductController.java` -- `updateProduct()` passes `extractRole()` (11-arg DTO); keep the IAE→404 mapping (DomainException propagates to the global handler → 403).
- `CategorySyncHandler.java` -- `TOGGLE_CATEGORY`: OWNER check before any repository read.
- `CategoryController.java` -- `toggleCategory()`: `@PreAuthorize("hasRole('OWNER')")` + explicit OWNER check throwing FORBIDDEN.
- `InventorySessionSyncHandler.java` -- parse storeId and enforce employee scope before the idempotency lookup.
- `InventoryCountSyncHandler.java` + `InventoryCountController.java` -- for an EMPLOYEE, load the session; if present and its store ≠ assigned store → FORBIDDEN before the use case (REST: POST `/counts`, PUT `/counts` and POST `/quick-add`).
- `UpdateProductUseCase.java` -- delete the 10-arg backward-compatible `UpdateProductDto` constructor (defaults to "OWNER" = fail open); update remaining callers/tests to pass a role.
- `ProductControllerTest.java` -- clear the SecurityContext after each test.
- `SaleDraftProductsUpgradedEvent.java` + `SaleSyncHandler.java` -- add `boolean draftsPromoted` to the event, set from `isOwnerRole()` in `SaleSyncHandler`.
- `SaleDraftValidatedNotificationListener.java` -- `draftsPromoted == false`: title "🆕 Produits brouillons vendus", body ends "(brouillons à valider)"; `true`: unchanged wording. Do not infer the seller type from the Employee row.
- `sale_model.dart`, `record_sale_usecase.dart` -- new `promoteDraftProducts` flag (default `true`).
- `record_sale_notifier.dart` -- `promoteDraftProducts = role == 'OWNER'` (fail closed: unknown role does not promote; backend rejects non-owner promotion anyway); skip `promoteToActive` when false; pass flag.
- `local_sale_datasource.dart` + `cart_bottom_sheet.dart` -- add `Future<bool> hasStockRecord(productId, storeId)` (a `stock_levels` row exists); `_handleEncaisser` prompts "Stock initial" only for draft items with no stock record in the active store. A draft already stocked (e.g. sold earlier by an employee) is sold like any product (normal local stock check).
- `product_card.dart` -- for an EMPLOYEE, a DRAFT product's card tap and "Modifier" option do not open the edit form; show a SnackBar "Seul le propriétaire peut modifier un produit brouillon". Owner validation dialog: label "Stock à ajouter", helper "S'ajoute au stock déjà saisi en boutique".
- `sale_repository_impl.dart` -- queue `PROMOTE_PRODUCT` only when `sale.promoteDraftProducts`.
- Backend tests -- new/extended cases for every matrix row (handler tests with `SyncTestAuth`, new `InventorySessionSyncHandlerTest`, controller tests, a listener unit test for both wordings, an `AbstractSyncOperationHandler` null-auth case).
- App tests -- notifier: EMPLOYEE (and null role) draft checkout applies initial stock, never calls `promoteToActive`; repository: no `PROMOTE_PRODUCT` when flag false; cart sheet: a draft with a stock record is not prompted; product card: EMPLOYEE cannot open edit on a DRAFT. Backend: quick-add foreign-store session → 403, use case never called; listener uses the event flag.
- `keevo/app/pubspec.yaml` -- 1.1.15+14 → 1.1.16+15.

**Acceptance Criteria:**
- Given an EMPLOYEE, when they push `PROMOTE_PRODUCT`, `TOGGLE_CATEGORY`, or a `CREATE_INVENTORY_SESSION`/`SAVE_INVENTORY_COUNT` targeting another store, then the op is REJECTED `FORBIDDEN` and nothing is written; the matching REST call returns 403.
- Given an OWNER, when they push the same operations on any store, then behavior is unchanged (APPLIED / 2xx).
- Given a sync handler invoked without authentication, when it needs the actor's store, then it fails closed (REJECTED `FORBIDDEN`).
- Given an EMPLOYEE selling a draft product, when the sale is recorded, then no draft is promoted (no local promotion, no `PROMOTE_PRODUCT` op) and the owner notification says the drafts await validation.
- Given routed items D2 and TOGGLE_CATEGORY, then both are ticked in the epic brief with this story's decision.

## Spec Change Log

### 2026-10-11 — Review loop 1 (bad_spec)
- **Triggers:** (1) EMPLOYEE re-selling a draft that now stays DRAFT is re-prompted for "Stock initial" each sale → duplicate STOCK_ENTRY, stock inflated; (2) EMPLOYEE edits of a DRAFT product are now REJECTED server-side but the app still opens the edit form → local/server divergence; (3) POST `/quick-add` left unscoped next to the scoped `/counts`; (4) notification inferred the seller from the Employee row; (5) client role check failed open on null; (6) 10-arg `UpdateProductDto` still defaults to OWNER; (7) owner validation dialog adds stock without saying so; (8) ProductControllerTest leaks the security context.
- **Amended:** Code Map, Tasks (new tasks for cart sheet, product card, quick-add, event flag, DTO constructor removal, test hygiene), Design Notes.
- **Known-bad state avoided:** silent stock inflation on repeated employee draft sales; employee draft edits lost to REJECTED ops; cross-store count writes via quick-add; wrong owner notification wording.
- **KEEP:** everything from attempt 1 is correct and must survive: the attempt-1 patch at /tmp/claude-1000/-home-toor-Project-FreeLance-AI-Keevo/04db4194-544b-4c10-a501-504a3970eded/scratchpad/v1s-12-10-attempt1.patch — re-apply it (`git apply`) as the starting point, then change only what the amended tasks require (role check `== 'OWNER'`, listener wording/flag). Keep: fail-closed `extractAssignedStoreId`, `actorRole()` helper, OWNER checks placed before any repository read, scope checks before idempotency lookups, sale/day-closure tests authenticating explicitly, epic brief ticks, pubspec 1.1.16+15.

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 34 findings — high 2, medium 5, low 19, false 8, maybe-false 0
- findings:
  - `[low]` `[bad_spec]` (edge) client role null/stale → `!= 'EMPLOYEE'` promotes locally, server rejects — amended: `role == 'OWNER'` (fail closed).
  - `[high]` `[bad_spec]` (edge) EMPLOYEE re-sale of a still-DRAFT product re-prompts initial stock → duplicate STOCK_ENTRY, inflated stock — verified in `cart_bottom_sheet.dart:68-87`; amended: prompt only when no stock record.
  - `[low]` `[reject]` (edge) old app builds (≤1.1.15) still queue PROMOTE_PRODUCT for employees → REJECTED, local ACTIVE until next pull — documented in Design Notes (fail closed); pull upserts the server status.
  - `[low]` `[bad_spec]` (edge) owner with an Employee row gets the employee wording — amended: seller role carried on the event (`draftsPromoted`).
  - `[low]` `[bad_spec]` (edge) employee whose Employee row is missing gets "activés" wording — same root cause as above.
  - `[false]` `[reject]` (edge) `InventoryCountController.extractAssignedStoreId` NPE on null auth — class-level `@PreAuthorize("hasAnyRole(...)")` + JwtAuthFilter guarantee an Authentication; identical to the existing `InventorySessionController` helper.
  - `[medium]` `[bad_spec]` (edge) EMPLOYEE edits of a DRAFT now REJECTED but the app still opens the edit form (`product_card.dart:58,346`) — amended: app does not offer draft edit to EMPLOYEE.
  - `[low]` `[reject]` (edge) `actorRole()` null on unauthenticated UPDATE/CREATE_DRAFT → APPLIED — unreachable: /sync/push is behind JwtAuthFilter; null auth on scoped ops already fails closed; extra branch not worth it.
  - `[false]` `[reject]` (edge) `ProductController.extractRole()` findFirst with several authorities — JwtAuthFilter.java:234 grants exactly one `ROLE_<role>` authority.
  - `[low]` `[bad_spec]` (edge, claim) "owner seller unchanged" contradicted for owners with Employee row — same root cause as the event flag row.
  - `[low]` `[bad_spec]` (edge, claim) null role still promotes — same root cause as the first row.
  - `[high]` `[bad_spec]` (blind) employee re-sale adds initial stock again — same root cause as the re-sale row.
  - `[medium]` `[bad_spec]` (blind) owner validation after an employee sale adds stock again without saying so — amended: dialog labels "Stock à ajouter" + helper.
  - `[low]` `[bad_spec]` (blind) client role fails open — same as first row.
  - `[low]` `[reject]` (blind) clients ≤1.1.15 not reconciled — same as old-builds row.
  - `[medium]` `[bad_spec]` (blind) POST `/quick-add` not store-scoped (reads also unscoped) — write half amended (quick-add guarded); read endpoints are outside the intent (sync push writes) → reject that half.
  - `[false]` `[reject]` (blind) InventoryCountController NPE — same refutation as above.
  - `[low]` `[bad_spec]` (blind) 10-arg `UpdateProductDto` still defaults to OWNER — amended: constructor deleted (named harm: any new caller skips the draft guard).
  - `[low]` `[bad_spec]` (blind) listener infers employee from Employee row — same as event-flag row.
  - `[low]` `[bad_spec]` (blind) "à valider dans le catalogue" while deep link goes to sales history — amended wording "(brouillons à valider)"; event/type names kept (wire contract).
  - `[low]` `[bad_spec]` (blind) ProductControllerTest leaks SecurityContext — verified no `@AfterEach`; amended: clear context.
  - `[false]` `[reject]` (blind) CategoryController explicit check is test-only code — established defense-in-depth pattern (`InventorySessionController.validateInventory`); it also enforces the rule if method security is ever misconfigured.
  - `[low]` `[reject]` (blind) session read twice (handler + use case) — one PK read per employee count; epic decision keeps scope in controller/handler, not use cases.
  - `[low]` `[reject]` (blind) tracking files / commit ref missing — handled at finalization (sprint-status, epic note with commit), not a code defect.
  - `[medium]` `[bad_spec]` (verification-gap) quick-add gap — same as quick-add row; test `quickAdd_employeeForeignStoreSession_returns403` required.
  - `[medium]` `[bad_spec]` (verification-gap other) employee draft edits rejected, app unchanged — same as draft-edit row.
  - `[low]` `[bad_spec]` (verification-gap other) role fallback promotes on null — same as first row.
  - `[false]` `[reject]` (intent) REST toggle/product changes beyond sync — deliberate R3 reading (rule in REST controller and sync handler, context line); recorded in Design Notes.
  - `[false]` `[reject]` (intent) PATCH toggle tightened beyond DELETE — toggle(false) is the delete since v1s-14-14; Design Notes.
  - `[false]` `[reject]` (intent) ops beyond the three named (UPDATE/CREATE_DRAFT role, counts) — same bypass family (audit I1 a/c); Design Notes.
  - `[false]` `[reject]` (intent) fail-closed reaches every handler — exactly item D2's wording.
  - `[low]` `[reject]` (intent) client + notification changes outside AC — necessary consequence of OWNER-only promotion; documented.
  - `[low]` `[reject]` (intent) no end-to-end push test, `@PreAuthorize` unexercised — same test level as 12.8/12.11 (handler is where REJECTED mapping lives); standalone MockMvc is the project convention.
  - `[low]` `[reject]` (intent) UPDATE_PRODUCT sync guard tested with a mocked use case — the guard itself is covered by UpdateProductUseCaseTest; handler test pins the role passed.

### 2026-10-11 — Review pass (loop 1 re-review)
- verdicts: 32 findings — high 0, medium 0, low 20, false 12, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) sold-out draft with a stock record is not re-prompted → INSUFFICIENT_STOCK — same behaviour as any sold-out ACTIVE product (before this story the first sale promoted it to ACTIVE); restocking is the normal stock-entry flow, not the "Stock initial" prompt.
  - `[low]` `[reject]` (blind) owner re-notified on every later sale of an unvalidated draft — accepted in Design Notes (informative); trimming `originalDraftProductIds` would also stop owner promotion of stocked drafts.
  - `[low]` `[reject]` (blind) `draftsPromoted` from server role, not client flag — only diverges for an OWNER whose local role pref is missing (written at every sign-in); wording-only impact.
  - `[low]` `[reject]` (blind) older app versions stuck ACTIVE locally — carried: documented fail-closed, pull restores server status.
  - `[false]` `[reject]` (blind) InventoryCountController NPE on null auth — carried: class-level `@PreAuthorize` + JwtAuthFilter guarantee an Authentication.
  - `[low]` `[reject]` (blind) inventory GET endpoints / cancel not store-scoped — reads are outside the intent (sync push write bypass); cancel already checks actor + role in the use case.
  - `[false]` `[reject]` (blind) `ProductController.extractRole()` order-dependent — carried: JwtAuthFilter.java:234 grants a single `ROLE_<role>` authority.
  - `[low]` `[reject]` (blind) DELETE /categories lacks the explicit check — pre-existing, unchanged by this story; `@PreAuthorize` enforces it in the real filter chain.
  - `[low]` `[reject]` (blind) edit route reachable by deep link — no deep link targets `/products/:id/edit`; backend rejects the op (perimeter); UI gate covers the two in-app entry points.
  - `[low]` `[reject]` (blind) "Stock à ajouter" shown even for never-stocked drafts — wording is still correct (adding to zero); adding a branch is not worth it.
  - `[low]` `[reject]` (blind) notification deep link to sales history — carried: the sale is the context the owner needs; drafts badge leads to validation.
  - `[low]` `[reject]` (blind) `hasStockRecord` without try/catch; null active store re-prompts — Drift local read in the same handler style as the rest of the sheet; checkout refuses a null store ("Identifiant boutique/employé manquant").
  - `[low]` `[patch]` (blind) missing tests (employee without store, etc.) — covered by the verification-gap patch rows below; others (neither-role actor, null actorRole in CreateDraft) unreachable via JwtAuthFilter.
  - `[low]` `[reject]` (blind) `dart format` noise in record_sale_notifier_test.dart, FQCNs in ProductControllerTest — cosmetic, no behaviour impact.
  - `[low]` `[patch]` (blind) epic brief records only the two routed items — finalization adds a story note listing the other resolutions + commit.
  - `[false]` `[reject]` (edge) sold-out draft unrestockable — same refutation as the blind row.
  - `[low]` `[reject]` (edge) null active store re-prompts — same as blind row.
  - `[low]` `[reject]` (edge) repeated owner notifications — same as blind row.
  - `[low]` `[reject]` (edge) server-side `draftsPromoted` for owner with null local role — same as blind row.
  - `[low]` `[reject]` (edge) queued/older PROMOTE_PRODUCT ops — carried.
  - `[low]` `[patch]` (edge) product_card draft-edit gate fails open on null role — fixed: gate on `role != 'OWNER'` + null-role test.
  - `[false]` `[reject]` (edge) InventoryCountController NPE — carried.
  - `[low]` `[reject]` (edge) GET counts/products cross-store reads — same as blind row.
  - `[low]` `[patch]` (verification-gap) `hasStockRecord` never run against a real DB — added real-DB tests (row at qty 0 → true, none → false, other store → false).
  - `[low]` `[patch]` (verification-gap) EMPLOYEE without assigned store → 403 untested on counts/quick-add — added tests, use cases never called.
  - `[false]` `[reject]` (verification-gap other) NPE on null auth — carried.
  - `[false]` `[reject]` (intent) REST reference changed (toggle/PATCH product) — carried: deliberate R2/v1s-12-11 pattern.
  - `[false]` `[reject]` (intent) unnamed ops change (UPDATE/CREATE_DRAFT role, fail-closed reach) — carried: audit I1(a) + D2 wording.
  - `[false]` `[reject]` (intent) inventory widened to count writes — carried: Design Notes.
  - `[false]` `[reject]` (intent) client + notification surfaces — carried: necessary consequence, Design Notes.
  - `[false]` `[reject]` (intent) asymmetry unknown-session delegated / payload vs stored storeId — by design: session creation has only the payload store; counts use the stored session (authoritative); unknown session already fails in the use case.
  - `[false]` `[reject]` (intent) tests at handler/standalone-MockMvc level, no e2e push — carried: 12.8/12.11 precedent.

## Design Notes

- **Promotion is OWNER-only** (Story 2.4 AC6 code guard, catalog "Valider ce produit" menu shown to OWNER only, epic 2: the owner completes drafts). The 30bdd07 "draft sale flow" made every checkout promote drafts; it stays for OWNER only. An EMPLOYEE sale with a draft still records initial stock and still notifies the owner, who validates from the catalog. Old app builds that still queue `PROMOTE_PRODUCT` for an employee get REJECTED (product stays DRAFT) — accepted, fail closed.
- **TOGGLE both directions OWNER-only**: since v1s-14-14 deactivate = delete; categories management is in the OWNER-only Settings section. REST PATCH `/toggle` aligned (no app/dashboard caller).
- **Counts** are scoped too: REST counts were not scoped either; an employee writing counts into another store's session would bypass the session scope this story enforces. Unknown session → left to the use case.
- **Null auth**: sync push always runs behind JwtAuthFilter; fail closed only changes behavior for misconfigured calls/tests. `isOwnerRole()` already returns false on null.
- **Draft re-sale (loop 1)**: an employee-sold draft keeps its stock record, so later sales skip the initial-stock prompt; owner notification repeats per sale (informative, accepted).
- **Draft edits by EMPLOYEE (loop 1)**: Story 2.4 guard treats any EMPLOYEE update of a DRAFT as forbidden; the app stops offering it rather than letting it fail in sync. Backend stays the perimeter.
- **Routed**: `InventorySessionSyncHandler` writes via `repository.save` and skips `CreateInventorySessionUseCase` (store existence, single active session, created event) — functional parity, not RBAC → v1s-13-8-sync-integrity-residual.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='*SyncHandlerTest,CategoryControllerTest,ProductControllerTest,InventoryCountControllerTest,InventorySessionControllerTest,SaleDraftValidatedNotificationListenerTest'` -- expected: all green
- `cd keevo/backend && mvn test` -- expected: no failures beyond the 10-class baseline
- `cd keevo/app && flutter analyze && flutter test` -- expected: no new issue; only known offline_gate_banner_test failure

## Auto Run Result

Status: done

**Summary:** Residual sync RBAC closed on both channels (v1s-12-11 pattern). PROMOTE_PRODUCT and TOGGLE_CATEGORY are OWNER-only (sync + REST PATCH toggle); UPDATE_PRODUCT / CREATE_DRAFT_PRODUCT / PATCH product pass the real actor role (10-arg OWNER-defaulting DTO removed); CREATE_INVENTORY_SESSION and inventory count writes (sync SAVE_INVENTORY_COUNT, REST POST/PUT /counts, POST /quick-add) are scoped to the employee's store; `extractAssignedStoreId()` fails closed on null auth (D2). App: an EMPLOYEE checkout no longer promotes drafts, a stocked draft is not re-prompted for initial stock, a non-OWNER cannot open edit on a DRAFT, owner validation dialog says "Stock à ajouter"; owner notification wording driven by `SaleDraftProductsUpgradedEvent.draftsPromoted`.

**Files changed (main):**
- `keevo/backend/.../sync/sync/application/handler/AbstractSyncOperationHandler.java` -- null auth → FORBIDDEN; `actorRole()` helper.
- `.../ProductSyncHandler.java` -- PROMOTE OWNER-only; real role on UPDATE / CREATE_DRAFT.
- `.../CategorySyncHandler.java`, `catalog/category/adapter/in/web/CategoryController.java` -- toggle OWNER-only.
- `.../InventorySessionSyncHandler.java`, `.../InventoryCountSyncHandler.java`, `inventory/counting/adapter/in/rest/InventoryCountController.java` -- employee store scope.
- `catalog/product/adapter/in/web/ProductController.java`, `catalog/product/application/usecase/UpdateProductUseCase.java` -- real role; fail-open constructor removed.
- `commerce/sale/domain/model/SaleDraftProductsUpgradedEvent.java`, `SaleSyncHandler.java`, `SaleDraftValidatedNotificationListener.java` -- `draftsPromoted` flag + wording.
- `keevo/app/lib/features/pos/...` (`record_sale_notifier.dart`, `sale_repository_impl.dart`, `sale_model.dart`, `record_sale_usecase.dart`, `local_sale_datasource.dart`, `cart_bottom_sheet.dart`), `features/catalog/presentation/widget/product_card.dart` -- client side of OWNER-only promotion.
- Tests: new `AbstractSyncOperationHandlerTest`, `InventorySessionSyncHandlerTest`, `SaleDraftValidatedNotificationListenerTest`; extended handler/controller/use-case tests; Flutter notifier, repository, cart sheet, product card, datasource (real DB) tests.
- `keevo/app/pubspec.yaml` -- 1.1.15+14 → 1.1.16+15.

**Review:** pass 1 (34 findings: high 2, medium 5, low 19, false 8) → bad_spec loopback (initial-stock re-prompt on employee draft re-sale, employee draft edits rejected, quick-add unscoped, notification seller inference, fail-open role checks, OWNER-defaulting DTO, owner dialog wording, test context leak). Pass 2 (32 findings: low 20, false 12): 4 patches applied (product card gate `!= 'OWNER'`, real-DB `hasStockRecord` tests, EMPLOYEE-without-store 403 tests on counts/quick-add, epic note); 0 deferred; all other findings rejected with reasons in the triage log. Patched this pass: high 0, medium 0, low 4 → followup_review_recommended: false.

**Routed items:** InventorySessionSyncHandler bypasses CreateInventorySessionUseCase → v1s-13-8-sync-integrity-residual. Story items D2 and TOGGLE_CATEGORY → resolved here (ticked in epic brief).

**Verification:** targeted backend tests 211/211 green; full `mvn test` 1927 tests, 7 failures + 29 errors only in the 10 baseline classes. `flutter test` 1306 pass, 1 known failure (offline_gate_banner_test). `flutter analyze` 630 issues = unchanged count, no new error/warning.

**Residual risks:** employee devices on app ≤1.1.15 still queue PROMOTE_PRODUCT → REJECTED FORBIDDEN, product ACTIVE locally until the next pull; owner receives one "Produits brouillons vendus" notification per employee sale of an unvalidated draft; inventory read endpoints remain unscoped (out of this story's write-bypass intent).


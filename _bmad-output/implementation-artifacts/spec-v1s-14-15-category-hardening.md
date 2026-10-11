---
title: 'v1s-14-15 — Category hardening: 404 not-found, audit events, atomic offline repository'
type: 'bugfix'
created: '2026-10-12'
baseline_revision: '0837c5fc06781fa93a251c757c45cbb872cccb6f'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['multiple-goals', 'oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Category operations lag behind products: an unknown id on `DELETE /categories/{id}`, `PATCH /{id}/toggle`, `PATCH /{id}` (rename) or a create with an unknown `parentId` throws `IllegalArgumentException` → generic HTTP 500 (audit M6); create / toggle / delete / rename leave no audit-log entry; on the device, `CategoryRepositoryImpl` does read-check-write and the Drift write + `queueOperation` outside one transaction (a crash or concurrent tap leaves a local change without its queued op, or duplicate TOGGLE ops), and `getSingle()` throws a raw `StateError` when the row is gone.

**Approach:** Backend — category not-found becomes `DomainException(CATEGORY_NOT_FOUND)` (→ 404 REST, REJECTED `CATEGORY_NOT_FOUND` in sync); the category use cases publish domain events (`CategoryCreatedEvent`, `CategoryActiveChangedEvent`, `CategoryRenamedEvent`) recorded by `AuditEventListener`; REST and sync both go through those use cases. App — every category write (read, check, Drift write, `queueOperation`) runs in one `_database.transaction()`, push is triggered after commit, and a missing row raises a typed `CategoryNotFoundException` with a French message.

## Boundaries & Constraints

**Always:** OWNER-only toggle/delete (v1s-12-10) unchanged and still checked before any lookup; sync TOGGLE_CATEGORY on an unknown id stays an idempotent APPLIED no-op; CREATE_CATEGORY idempotency (same UUID) and name+parent dedup stay in the handler and emit no event; an event is emitted only when state actually changes (deactivating an already inactive category, toggling to the current state, renaming to the same name → no event); audit rows: entityType `Category`, actions `CATEGORY_CREATED`, `CATEGORY_ACTIVATED`, `CATEGORY_DEACTIVATED`, `CATEGORY_RENAMED`, actor + tenant from the authenticated request; `deleteCategory` on the device stays a no-op when the category is already inactive or no longer exists locally.

**Never:** no migration / schema change; no new REST endpoint or payload change; no change to `GET` endpoints; no sub-category cascade (decision D3); no UI redesign of the categories page beyond the error message it already shows via `appErrorMessage`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| REST delete unknown id | `DELETE /categories/{unknown}` as OWNER | 404, `domainCode=CATEGORY_NOT_FOUND`, no audit | none |
| REST toggle / rename unknown id | `PATCH /{unknown}/toggle` (OWNER), `PATCH /{unknown}` | 404 `CATEGORY_NOT_FOUND` | none |
| REST create, unknown parent | `POST` with `parentId` unknown | 404 `CATEGORY_NOT_FOUND`, nothing saved | none |
| REST delete active category | OWNER | 204, `CATEGORY_DEACTIVATED` audit | none |
| REST delete inactive category | OWNER | 204, no save, no audit | none |
| Toggle (REST or sync) | active → inactive / inactive → active | `CATEGORY_DEACTIVATED` / `CATEGORY_ACTIVATED` audit | none |
| Create (REST or sync new UUID) | valid name | `CATEGORY_CREATED` audit | none |
| Sync create, existing UUID or same name+parent | — | APPLIED, no save, no audit | none |
| Sync rename unknown id | RENAME_CATEGORY | REJECTED reason `CATEGORY_NOT_FOUND` | none |
| App toggle / rename missing row | id not in Drift | `CategoryNotFoundException` ("Catégorie introuvable ou supprimée."), nothing written, nothing queued | caller shows `appErrorMessage(e)` |
| App write, `queueOperation` throws | any write | local change rolled back, no push trigger | exception propagates |
| App delete missing / inactive row | — | no-op, nothing queued | none |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/catalog/category/adapter/out/persistence/JpaCategoryRepository.java:71-122` -- `toggleActive`/`rename`/`deactivate` throw IAE on missing id → switch to `DomainException(ErrorCode.CATEGORY_NOT_FOUND, "categoryId=" + id)`.
- `.../catalog/category/application/usecase/{Create,Toggle,Rename}CategoryUseCase.java` -- no events, IAE on missing parent/id. Pattern to copy: `catalog/contact/application/usecase/ArchiveClientUseCase.java` (findById → DomainException, then `eventPublisher.publishEvent`).
- `.../catalog/category/adapter/in/web/CategoryController.java:493-529` -- toggle calls use case; DELETE calls `categoryRepository.deactivate` directly (bypasses use case). Actor via `CurrentActor.actorId()`, tenant via `TenantContext.getCurrentTenant()` (cf. `ClientController.java:100`).
- `.../sync/sync/application/handler/CategorySyncHandler.java:79-140` -- CREATE saves via repository directly; TOGGLE/RENAME use the use cases; `apply(op, actorId, tenantId)` gives actor + tenant. `AbstractSyncOperationHandler.mapDomainException` → REJECTED with error-code name.
- `.../shared/infrastructure/web/AuditEventListener.java` -- add three `@EventListener on(...)` handlers, same shape as `on(ClientArchivedEvent)` (line ~364).
- `.../shared/infrastructure/web/GlobalExceptionHandler.java:202` -- `CATEGORY_NOT_FOUND` already → 404 (read-only).
- Tests: `catalog/category/adapter/in/web/CategoryControllerTest.java` (standalone MockMvc + GlobalExceptionHandler, mocks use cases + repository), `sync/sync/application/handler/CategorySyncHandlerTest.java`, `shared/infrastructure/web/AuditEventListenerContactTest.java` (listener test pattern).
- `keevo/app/lib/features/catalog/data/repository/category_repository_impl.dart:63-161` -- four writes; atomic pattern to copy: `features/contact/data/repository/client_repository_impl.dart:42-90` (`db.transaction`, queue inside, `triggerPushIfIdle` after). `RestSyncService.queueOperation` writes `syncQueue` on the same `AppDatabase`, so it joins the Drift transaction zone.
- `keevo/app/lib/core/widget/app_error_widget.dart:12-40` -- `appErrorMessage` returns `toString()` minus `Exception: ` for `Exception`s; `StateError` (an `Error`) falls to the generic message.
- `keevo/app/test/features/catalog/data/repository/category_repository_offline_first_test.dart` -- in-memory Drift + mocked `SyncService`; extend here.

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/java/com/keevo/catalog/category/domain/event/{CategoryCreatedEvent,CategoryActiveChangedEvent,CategoryRenamedEvent}.java` -- new framework-free records (`categoryId, name/oldName/newName, parentId|active, actorId, tenantId, occurredAt`).
- `JpaCategoryRepository.java` -- IAE → `DomainException(CATEGORY_NOT_FOUND)` in toggleActive/rename/deactivate.
- `CreateCategoryUseCase.java` -- `execute(dto, actorId, tenantId)`: unknown parent → `CATEGORY_NOT_FOUND`; publish `CategoryCreatedEvent`. Add `createWithId(UUID id, String name, UUID parentId, UUID actorId, String tenantId)` (save `new Category(id,…,true,true,now,now)` + event) for sync.
- `ToggleCategoryUseCase.java` -- `execute(id, actorId, tenantId)`: findById → 404, toggle, publish `CategoryActiveChangedEvent`; new `deactivate(id, actorId, tenantId)`: 404 when missing, no-op when inactive, else `repository.deactivate` + event(active=false). `@Transactional` on both.
- `RenameCategoryUseCase.java` -- `execute(id, dto, actorId, tenantId)`: 404, rename, publish `CategoryRenamedEvent` only when the name changed.
- `CategoryController.java` -- pass actor/tenant; DELETE → `toggleCategoryUseCase.deactivate`; drop the now-unused `CategoryRepository` dependency.
- `CategorySyncHandler.java` -- CREATE new UUID → `createCategory.createWithId(...)`; pass actor/tenant to toggle/rename.
- `AuditEventListener.java` -- record the three events (actions per Boundaries).
- Tests: `CategoryControllerTest` (404 for delete/toggle/rename/create-parent; delete delegates to use case), new `catalog/category/application/usecase/CategoryUseCasesTest.java` (events emitted / not emitted, 404s), `CategorySyncHandlerTest` (create via use case, idempotent paths emit nothing, rename unknown → REJECTED `CATEGORY_NOT_FOUND`), new `shared/infrastructure/web/AuditEventListenerCategoryTest.java`.
- `keevo/app/lib/features/catalog/domain/repository/category_repository.dart` -- add `CategoryNotFoundException implements Exception` (`toString()` = `Catégorie introuvable ou supprimée.`); document it on toggle/rename.
- `category_repository_impl.dart` -- wrap each write (read + check + write + queue + re-read) in `_database.transaction`; `getSingleOrNull` → throw `CategoryNotFoundException` (toggle/rename) or return (delete); `triggerPushIfIdle()` only after commit.
- `category_repository_offline_first_test.dart` -- missing-row toggle/rename throw typed exception and queue nothing; queue failure rolls back the local write (toggle, create, delete); delete missing row no-op.
- `keevo/app/pubspec.yaml` -- `1.1.22+21` → `1.1.23+22`.

**Acceptance Criteria:**
- Given an OWNER token, when `DELETE /api/v1/categories/{unknown}` is called, then the response is 404 with `domainCode` `CATEGORY_NOT_FOUND` (not 500).
- Given an authenticated actor, when a category is created, toggled, deactivated or renamed (REST or sync) and its state changes, then exactly one audit row with the matching `CATEGORY_*` action is recorded.
- Given the device, when any category write's `queueOperation` fails, then the Drift `categories` table is unchanged.
- Given the device, when toggling or renaming a category whose row no longer exists, then the UI receives `CategoryNotFoundException` and `appErrorMessage` yields "Catégorie introuvable ou supprimée.".

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 26 findings — high 0, medium 0, low 19, false 7, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) Sync TOGGLE read-then-flip race under concurrent REST toggle — pre-existing handler logic (unchanged compare-then-toggle); needs two concurrent writers on one category; fix needs a new set-based use-case method + row lock.
  - `[low]` `[reject]` (blind) REST toggle has no lock (double concurrent toggle) — pre-existing; two OWNER requests on the same category at the same instant; would need pessimistic locking.
  - `[low]` `[reject]` (blind) Audit listener synchronous inside the business tx; audit failure rolls back / audit row (REQUIRES_NEW) can survive a rollback — identical to every existing audit event (products, clients, suppliers, stores) in AuditEventListener; AFTER_COMMIT would be a cross-cutting change, not category-specific.
  - `[low]` `[reject]` (blind) Sync CREATE skips parent validation — pre-existing (old handler saved directly); app never sends parentId (no sub-category UI, D3); `categories.parent_id` has no FK.
  - `[low]` `[reject]` (blind) Sync payloads bypass @NotBlank/@Size — pre-existing; app trims and refuses empty names before queueing.
  - `[low]` `[reject]` (blind) CREATE dedup checks outside the use-case tx (two devices racing) — pre-existing, unchanged by this diff.
  - `[false]` `[reject]` (blind) Unknown-id inconsistency / RENAME on a G3-merged UUID now permanently REJECTED — before the diff RENAME on an unknown id already threw IAE → REJECTED; only the reason string changed (`CATEGORY_NOT_FOUND`).
  - `[false]` `[reject]` (blind) Mobile atomicity only proven with a mock SyncService — `syncServiceProvider` builds RestSyncService on `ref.watch(appDatabaseProvider)`, the same instance `categoryRepositoryProvider` uses (providers.dart:80-92, category_provider.dart:11-17); its `sync_queue` insert joins the Drift transaction zone, as for clients/products.
  - `[low]` `[reject]` (blind) CategoryNotFoundException message only via toString; `_domainMessage` lacks CATEGORY_NOT_FOUND — app category writes are offline-first (no REST call), so the domain-code branch is unreachable; message tested via appErrorMessage.
  - `[low]` `[reject]` (blind) Rename to the same name still writes (bumps updatedAt) — harmless; no audit row emitted; fix adds a branch.
  - `[low]` `[reject]` (blind) Controller tests use any() for tenant — AuditLogRepositoryAdapter ignores tenantId (schema from TenantContext), so a wrong tenant is unobservable.
  - `[low]` `[patch]` (blind) AuditEventListenerCategoryTest leaves before/after payloads and parentId unchecked — grouped with the verification-gap row; fixed (captors + parent case).
  - `[false]` `[reject]` (blind) Map.of NPE on null name/oldName — `categories.name` is NOT NULL and events carry a loaded entity's name.
  - `[low]` `[reject]` (blind) Use-case tests miss repository-throws paths / no JpaCategoryRepository unit test — plain exception propagation; adapter change is a one-line exception-type swap.
  - `[low]` `[reject]` (blind) Double findById per toggle/rename/delete — negligible; adapter guard kept for direct port callers.
  - `[low]` `[reject]` (edge) Audit row kept for a create failing at commit (oversized sync name) — same root as the audit-in-tx row.
  - `[low]` `[reject]` (edge) Sync CREATE unknown parent → orphan — same as the blind parent row.
  - `[low]` `[reject]` (edge) Sync RENAME blank/over-100 name — same as the blind validation row.
  - `[low]` `[reject]` (edge) Audit failure rolls back the user operation — same as the audit-in-tx row.
  - `[false]` `[reject]` (edge) Map.of NPE on null name — NOT NULL column (see blind row).
  - `[low]` `[reject]` (edge) Concurrent TOGGLE ops both pass the state check — same as the sync race row.
  - `[low]` `[reject]` (edge) Concurrent DELETE → two DEACTIVATED rows — same as the REST lock row.
  - `[low]` `[reject]` (edge, claim) "exactly one audit row" not guaranteed under concurrency/rollback — same roots; AC holds for sequential requests.
  - `[low]` `[patch]` (verification-gap) ACTIVATED/DEACTIVATED before/after and CREATED parentId unverified — patched: ArgumentCaptor assertions + `on_categoryCreated_withParent_recordsParentId` (5/5).
  - `[false]` `[reject]` (intent) StateError item: categories_page unchanged — the page already catches and shows `appErrorMessage(e)`; the typed exception now yields the French message (before, StateError fell to the generic message).
  - `[false]` `[reject]` (intent) Cascade decided without PO, dialog unchanged — AC allows explicit rejection with reason; D3 records the evidence.

## Design Notes

- **D1 — 404 everywhere a category id is resolved**, not only DELETE: toggle, rename and create-parent had the same IAE→500 path (epic-14 context, audit M6).
- **D2 — events from use cases, not the JPA adapter**: keeps the persistence adapter free of publishing and matches `ArchiveClientUseCase`. DELETE is routed through `ToggleCategoryUseCase.deactivate` so it is audited too (since v1s-14-14 delete = deactivate).
- **D3 — sub-category cascade: rejected (no change).** No V1 surface creates sub-categories: onboarding seeds roots only (no `parentId` in `identity/onboarding`), the app's `create(name)` never passes `parentId`, POS lists roots only. A cascade would be dead code with sync semantics to invent; revisit only if a sub-category UI is introduced (its story owns the cascade). Confirmation dialog wording stays (products only).
- **D4 — device delete of a missing row is a no-op** (the goal "category gone" already holds); toggle/rename on a missing row surface the typed exception.

## Verification

**Commands:**
- `MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=CategoryControllerTest,CategoryControllerSecurityTest,CategoryUseCasesTest,CategorySyncHandlerTest,AuditEventListenerCategoryTest -DforkCount=1 -DargLine=-Xmx1536m` (in keevo/backend) -- 0 failures.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend catalog sync shared` -- 0 failures / 0 errors.
- `flutter test --concurrency=4 test/features/catalog/data/repository/category_repository_offline_first_test.dart` then `run-tests-chunked.sh app test/core test/features/catalog` -- green (known baseline failure offline_gate_banner_test only).
- `flutter analyze lib/features/catalog test/features/catalog` -- no new error/warning.

## Auto Run Result

Status: done

**Summary:** Category not-found is now `CATEGORY_NOT_FOUND` (404 REST / REJECTED sync) on delete, toggle, rename and create-parent; create/toggle/delete/rename publish `CategoryCreatedEvent` / `CategoryActiveChangedEvent` / `CategoryRenamedEvent` from the use cases, recorded by `AuditEventListener` (`CATEGORY_CREATED|ACTIVATED|DEACTIVATED|RENAMED`); REST DELETE and sync CREATE now go through the use cases. App: every category write + `queueOperation` in one Drift transaction, push after commit, missing row → `CategoryNotFoundException` ("Catégorie introuvable ou supprimée."). Sub-category cascade rejected (D3). App 1.1.23+22.

**Files:** backend `catalog/category/domain/event/*` (3 new events), `JpaCategoryRepository` (DomainException), `Create/Toggle/RenameCategoryUseCase` (404 + events, `createWithId`, `deactivate`), `CategoryController` (actor/tenant, DELETE via use case), `CategorySyncHandler` (create via use case), `AuditEventListener` (3 handlers); tests `CategoryControllerTest`, `CategoryControllerSecurityTest`, `CategorySyncHandlerTest`, new `CategoryUseCasesTest`, new `AuditEventListenerCategoryTest`. App `category_repository.dart` (exception), `category_repository_impl.dart` (transactions), offline-first test (+8), `pubspec.yaml`.

**Review:** 26 findings — 1 patch group (audit listener test payloads, 2 rows), 0 deferred, 24 rejected (19 low pre-existing/negligible, 7 false incl. 2 intent notes) — reasons in the triage log. followup_review_recommended: false (only low patches).

**Verification:** targeted backend classes 53/53 green; chunks backend/catalog 394 OK, backend/sync 423 OK, backend/shared 391 OK (after patch), backend/architecture 6 OK; app test/features/catalog +117 all passed; app test/core +338 -1 (known baseline offline_gate_banner_test); `flutter analyze lib/features/catalog test/features/catalog` no new error/warning.

**Residual risks:** audit rows are written synchronously in REQUIRES_NEW like every other aggregate (an audit row can outlive a rolled-back change); concurrent toggles are not locked (pre-existing).

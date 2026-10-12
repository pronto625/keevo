---
title: 'v1s-15-10 — Flutter code hygiene & stale DayCloseButton docs'
type: 'refactor'
created: '2026-10-12'
status: 'done'
baseline_revision: 'e37f57327ce3d17a79a1ae8674c20ea28045e86d'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: ['multiple-goals', 'oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Ten deferred Flutter smells (Story 15.10 brief in `epics-remediation-audit.md`) remain: magic status strings in the POS cart/checkout, a secure-storage read on every navigation, duplicated debug HttpClient and role→home ternaries, a hardcoded `'OWNER'` role at registration, an undocumented SyncService/Drift contract and `employeeId == null` semantics, a dispatcher that can use a stale `Ref`, the misnamed `DayCloseButtonState`, and living docs still naming the deleted `DayCloseButton` widget. Plus `flutter analyze` still reports 6 warnings in `lib/`.

**Approach:** Small behaviour-preserving refactors, one per item, each covered by an existing or new unit test; doc-only items get dartdoc; living planning docs are updated, historical artifacts left untouched. Fix the 6 analyzer warnings.

## Boundaries & Constraints

**Always:** Behaviour preserved for every user flow (refactor only, except the registration role now coming from the token claim). Wire/DB strings stay identical (`'ACTIVE'`, `'DRAFT'`, `'COMPLETED'`, `'PENDING_VALIDATION'`, `'CANCELLED'`). Bump `keevo/app/pubspec.yaml` `1.1.31+30` → `1.1.32+31`. Tests only per chunk via `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh` or targeted `flutter test --concurrency=4 <files>`. No hardcoded colors (guard test). deferred-work.md stays empty.

**Never:** No sweep of every status string in SQL datasources (dashboard/profitability/sync) — enums are introduced at the item sites (POS domain/presentation) only. No change to `hasSession(storage)` semantics or its direct (uncached) callers in tests. No rewrite of historical artifacts (done stories, audits, archive, old specs) — they record what was true then. No build_runner unless a generated file's source changes (none planned).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Router guard, repeated navigation | session cached, no token write since | `globalAuthRedirect` answers without re-reading secure storage | storage error → existing `/splash` fallback |
| Token written / cleared | `SecureTokenStorage.saveToken/saveRefreshToken`, `clearSessionSecrets`, startup garbage-token delete | next guard check re-reads storage and sees the new state | — |
| Access token expires while cached | cached access token, wall clock passes `exp` | expiry evaluated on every check (refresh-token presence decides) | — |
| Registration | token claim `role` present | `kUserRoleKey` = claim role | no claim → `'OWNER'` fallback |
| Dispatcher after scope dispose | provider container disposed | `triggerPushIfIdle()` is a no-op, never touches the stale `Ref` | StateError catch kept as belt |

</intent-contract>

## Code Map

- `keevo/app/lib/features/catalog/domain/model/product_status.dart` -- existing `ProductStatus {active, draft}` with `.value` / `fromString`; reuse.
- `keevo/app/lib/features/pos/domain/model/cart_item.dart:14,25,32,34` -- `productStatus` String → `ProductStatus`.
- `keevo/app/lib/features/pos/presentation/page/pos_page.dart:289-300,322` -- `_addProductToCart` unused `productStatus` param (always 'ACTIVE'); draft creation passes `result.product.status`.
- `keevo/app/lib/features/pos/presentation/provider/record_sale_notifier.dart:122` -- `status: 'COMPLETED'`; also `:106` `== 'OWNER'`.
- `keevo/app/lib/features/pos/domain/model/sale_model.dart:65`, `domain/usecase/record_sale_usecase.dart:24`, `presentation/page/sale_success_page.dart:15,65`, `data/repository/day_closure_repository_impl.dart:66` -- POS sale-status literals to route through new `SaleStatus`.
- `keevo/app/lib/core/router/app_router.dart:122,270-300` -- splash + `redirect` + `/auth/login` redirect call `hasSession(storage)` every navigation.
- `keevo/app/lib/core/router/startup_route.dart:16-21,37,76` -- `hasSession`, garbage-token delete, role ternary.
- `keevo/app/lib/core/storage/secure_storage_provider.dart:36` -- `clearSessionSecrets` (all session clears funnel here, incl. `SecureTokenStorage.clearAll`, sign-out, claims applier).
- `keevo/app/lib/features/auth/data/repository/secure_token_storage.dart:22-27` -- only writers of `jwt_token`/`refresh_token` (used by `SessionRefresher:67-68`, login/tenant/change-password use cases).
- `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart:66-72,121-127` -- duplicated `badCertificateCallback`; `:272` hardcoded `'OWNER'` after `sessionClaimsApplierProvider.apply` (which already writes the claim role, `session_claims_applier.dart:55-56`).
- `keevo/app/lib/features/onboarding/presentation/page/shop_name_page.dart:79-80` -- role ternary duplicate.
- `keevo/app/lib/core/sync/sync_service.dart:1-8` -- interface doc (add Drift-connection contract).
- `keevo/app/lib/features/pos/domain/repository/day_closure_repository.dart:9-14`, `data/datasource/local_day_closure_datasource.dart:112-117` -- `employeeId` null semantics (null = all store sales; online path fetches server-wide, `day_closure_repository_impl.dart:41-43`; `close_day_usecase.dart:39` passes null).
- `keevo/app/lib/core/sync/riverpod_sync_trigger_dispatcher.dart:14-47` -- captures provider `Ref`; tests construct it directly (`test/core/sync/riverpod_sync_trigger_dispatcher_test.dart:73`, `sync_trigger_single_flight_test.dart:138`).
- `keevo/app/lib/features/pos/presentation/provider/day_closure_providers.dart:17-27,83-99`, `pos/presentation/widget/pos_speed_dial.dart:108`, `reports/presentation/page/reports_page.dart:132,137` + tests `day_closure_state_provider_test.dart`, `sale_detail_page_test.dart:457`, `pos_feedback_contrast_test.dart`, `reports_page_test.dart` -- `DayCloseButtonState` rename.
- Warnings: `core/di/providers.dart:4` unused import; `catalog/data/repository/stock_repository_impl.dart:25,316` unused `_connectivity` / `_refreshLocalLevels`; `contact/data/repository/client_repository_impl.dart:21`, `supplier_repository_impl.dart:18` unused `_connectivity`; `pos/presentation/widget/cart_bottom_sheet.dart:19` unused `_currencyFormat`. Constructor callers: `catalog/presentation/provider/stock_provider.dart`, `inventory/presentation/provider/global_stock_provider.dart`, `contact/presentation/provider/contact_provider.dart` (2), tests `stock_repository_atomicity_test`, `stock_repository_offline_first_test`, `client_/supplier_repository_offline_first_test` (beware `MultiStoreStockRepositoryImpl` is a different class).
- Living docs: `_bmad-output/planning-artifacts/ux-design-specification.md:326,739,774`, `epics/requirements-inventory.md:261`, `epics/epic-4-point-de-vente-pos.md:232,251`.

## Tasks & Acceptance

**Execution:**
- `lib/features/pos/domain/model/sale_status.dart` (new) -- `enum SaleStatus {completed, pendingValidation, cancelled}` with `value` (`'COMPLETED'|'PENDING_VALIDATION'|'CANCELLED'`) and `fromString` -- single source for POS sale-status wire values.
- `cart_item.dart`, `pos_page.dart`, `record_sale_notifier.dart`, `sale_model.dart`, `record_sale_usecase.dart`, `sale_success_page.dart`, `day_closure_repository_impl.dart` -- use `ProductStatus` (typed field on CartItem) and `SaleStatus.x.value`; drop the dead `productStatus` param of `_addProductToCart`; update tests passing `productStatus: 'DRAFT'` → `ProductStatus.draft`.
- `lib/core/router/startup_route.dart` -- add `String routeForRole(String? role)` (OWNER → `/dashboard`, else `/pos`); use it at `:76` and in `shop_name_page.dart:80`; extract the token→bool decision so `hasSession` and the cache share it.
- `lib/core/storage/secure_storage_provider.dart` -- add a monotonically increasing session-secrets generation + `markSessionSecretsChanged()`; call it at the end of `clearSessionSecrets`; `SecureTokenStorage.saveToken/saveRefreshToken` and the startup garbage-token delete call it after writing.
- `lib/core/router/session_presence_cache.dart` (new) -- `SessionPresenceCache` (per container, `sessionPresenceCacheProvider`) caching the access token + refresh-token presence tagged with the generation read before the storage read; reused only while the generation is unchanged; expiry evaluated per call. `app_router.dart` redirect, `/auth/login` redirect and splash startup-pull check use it.
- `auth_provider.dart` -- extract one `_createHttpClient()` used by both Dio providers; registration writes `JwtClaims.tryParse(result.token)?.role ?? 'OWNER'`.
- `sync_service.dart` -- document: every implementation must use the app's single `AppDatabase` (Drift) connection from `appDatabaseProvider`, so queue writes inside repository transactions are seen by push; swapping strategy must keep that; add an integration test when a second implementation appears.
- `day_closure_repository.dart`, `local_day_closure_datasource.dart` -- dartdoc: `employeeId == null` means all sales of the store (OWNER view, close-day), non-null scopes to that employee.
- `riverpod_sync_trigger_dispatcher.dart` -- add `dispose()` / `_disposed` flag registered with `ref.onDispose` in the provider; `triggerPushIfIdle` returns early (also re-checked inside the microtask) once disposed; keep the StateError catch.
- `day_closure_providers.dart` + 3 lib call sites + 4 tests -- rename `DayCloseButtonState` → `DayClosureState`; trim the "name retained" comment.
- Warning fixes -- remove unused import/fields/element; remove the unused `connectivity` constructor param of the three repos and update their callers and tests.
- Living docs (`ux-design-specification.md`, `requirements-inventory.md`, `epic-4-point-de-vente-pos.md`) -- replace the `DayCloseButton` widget name with the current implementation (`_ClosureButton` in `ReportsPage` + "Clôturer la journée" action of the POS speed dial, state `DayClosureState`); the UX component title may stay "Day Close Button" with an implementation note.
- Tests -- new: `test/core/router/session_presence_cache_test.dart` (cached read, invalidation on save/clear, expiry re-evaluated); `routeForRole` cases in `startup_route_test.dart`; dispatcher no-op after dispose in `riverpod_sync_trigger_dispatcher_test.dart`; registration role from claim + fallback (new provider test under `test/features/auth/presentation/provider/`); `SaleStatus` round-trip.
- `keevo/app/pubspec.yaml` -- version `1.1.32+31`.
- `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` Story 15.10 -- tick all 10 items with resolution notes; add the analyzer AC line.

**Acceptance Criteria:**
- Given the app sources, when `flutter analyze` runs, then it reports 0 errors and 0 warnings (infos tolerated).
- Given `lib/features/pos`, when grepping `cart_item.dart` and `record_sale_notifier.dart`, then no `'ACTIVE'`/`'DRAFT'`/`'COMPLETED'` literal remains.
- Given `lib/`, when grepping `'/dashboard' : '/pos'`, `badCertificateCallback` and `DayCloseButton`, then respectively 0 hits (only `routeForRole`), 1 hit, 0 hits.
- Given registration with a token whose role claim is present, when the session is applied, then `kUserRoleKey` equals the claim role.
- Given all app chunks, when `run-tests-chunked.sh app` runs them one by one, then every chunk is OK.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 23 findings — high 0, medium 2, low 14, false 7, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) SaleStatus migration stops partway (sale_detail_page, app_router:496) — intent item 1 names cart_item.dart / record_sale_notifier.dart; values identical, no behaviour impact; the overstated doc claim is patched (see SaleStatus doc row).
  - `[low]` `[reject]` (blind) `Sale.status` stays `String` — typing it cascades into Drift row/wire mapping across repositories; not required by the item; constants give one source for the touched sites.
  - `[low]` `[patch]` (blind) `SaleStatus.fromString` dead with a risky `completed` fallback — removed `fromString` and its tests.
  - `[medium]` `[patch]` (blind) `clearSessionSecrets` bumps the generation only after every delete succeeds — wrapped in try/finally (grouped with edge rows 1–2).
  - `[low]` `[reject]` (blind) cache relies on a writer convention / process-global counter — all 3 writers in lib bump it (verification layer confirmed); a structural owner of the keys adds an abstraction beyond a hygiene story; contract documented on `markSessionSecretsChanged`.
  - `[low]` `[reject]` (blind) cache holds the raw access token in memory — the same token is read from storage by the interceptor on every request and lives in Dio headers; no new exposure.
  - `[low]` `[reject]` (blind) session-cache test gaps (race, provider override, refresh-read error) — verification layer found no gap that would hide a visible regression; adding them is test gold-plating.
  - `[low]` `[reject]` (blind) third dispose check (after connectivity await) untested; `isDisposed` public — removing that check only reaches the kept StateError catch (no visible change); `isDisposed` patched to `@visibleForTesting`.
  - `[low]` `[reject]` (blind) registration role write duplicates the applier — same key, same value; the explicit write keeps the `'OWNER'` fallback and is what `registration_role_test` proves; dropping it would need the test to drive the real applier.
  - `[low]` `[patch]` (blind) dead connectivity mocks left in client/supplier offline-first tests, stray blank line in stock atomicity test — removed.
  - `[low]` `[patch]` (blind) new imports out of order (3 tests, 3 lib files, auth_provider) — reordered; analyzer infos 616 → 607.
  - `[false]` `[reject]` (blind) routeForRole case-sensitive / 'OWNER' literals elsewhere — roles are uppercase enum values issued by the backend; a UserRole type is outside the 10 items; the app_router ternary was verified absent before planning (grep of `'/dashboard' : '/pos'`).
  - `[low]` `[patch]` (blind) analyzer AC has no evidence — epic AC line now lists the 6 warnings fixed and the 0-warning analyze result.
  - `[medium]` `[patch]` (edge) clearSessionSecrets partial delete leaves cache stale — try/finally (same root cause as blind row 4).
  - `[low]` `[patch]` (edge) SecureTokenStorage write throws after partial write — try/finally around both writes (same root cause).
  - `[false]` `[reject]` (edge) unexpected role claim ('owner', '') stored verbatim — the applier already stores the claim verbatim before this line; backend only issues OWNER/EMPLOYEE; no new path.
  - `[low]` `[patch]` (edge) SaleStatus "single source" claim overstated — doc now names the adopting sites and says other screens/SQL still use raw strings.
  - `[low]` `[patch]` (verification-gap, other) SaleStatus doc overstated, `fromString` unused — same patch as above.
  - `[false]` `[reject]` (intent) item 1 sale status untyped / literals remain — descriptive; scope decision recorded in the epic note.
  - `[false]` `[reject]` (intent) item 2 synced from storage writers rather than login/logout/refresh; `readRole` still calls `SharedPreferences.getInstance()` — login/logout/refresh all persist through those writers, so they are covered; `getInstance()` returns the in-memory singleton after the first call (no disk read).
  - `[false]` `[reject]` (intent) item 3 named `_createHttpClient` not `_createDebugHttpClient` — the client serves production too (debug only relaxes localhost certs); the name matches its use.
  - `[false]` `[reject]` (intent) item 7 app_router occurrence closed without proof — grep at planning found no such ternary in app_router.dart (only `globalAuthRedirect` returning '/pos' for non-owners, a different rule).
  - `[false]` `[reject]` (intent) item 8 keeps the Ref capture, item 9 edits 3 docs — design decisions recorded in Design Notes (guarded capture; historical artifacts preserved).

## Design Notes

Session cache uses a generation counter instead of an Expando/static snapshot: tests reuse a `const FlutterSecureStorage()` and swap its backing with `setMockInitialValues`, so a storage-keyed global cache would leak between tests; a per-container cache plus a global "secrets changed" generation stays correct in-app (all writes go through `SecureTokenStorage` / `clearSessionSecrets` / startup delete) and fresh in each test container. Direct `hasSession(storage)` stays uncached.

Historical artifacts (`4-4-…`, `v1s-16-10-…`, `v1s-12-6-…`, `spec-hf2-…`, audits, `archive/epics.md`, epic contexts) keep `DayCloseButton`: they describe past states; rewriting them would falsify history. Recorded as the decision for item 9.

Grid-added products always enter the cart as `ProductStatus.active` (pre-existing behaviour, the draft badge shows but the item is not flagged draft); preserved as-is — out of scope for a hygiene refactor.

## Verification

**Commands:**
- `cd keevo/app && flutter analyze` -- expected: no `error`/`warning` lines.
- `flutter test --concurrency=4 <touched test files>` -- expected: all pass.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app` -- expected: every chunk OK.

## Auto Run Result

Status: done

**Summary:** All 10 Story 15.10 items resolved (none was already covered in code): POS status enums (`ProductStatus` typed on `CartItem`, new `SaleStatus` constants), router session cache (`SessionPresenceCache` + session-secrets generation), single `_createHttpClient()`, registration role from the token claim, `routeForRole()`, SyncService Drift-connection contract and `employeeId == null` dartdoc, dispatcher dispose guard, `DayCloseButtonState` → `DayClosureState`, living docs cleaned (historical artifacts kept). The 6 pre-existing analyzer warnings are fixed. App version 1.1.32+31.

**Files changed (keevo/app):**
- `lib/core/router/session_presence_cache.dart` (new) — cached session presence for the router.
- `lib/core/router/startup_route.dart` — `routeForRole`, `isSessionUsable`, public token keys, generation bump on garbage-token delete.
- `lib/core/router/app_router.dart` — redirects and splash use the cache.
- `lib/core/storage/secure_storage_provider.dart` — session-secrets generation; `clearSessionSecrets` bumps it (try/finally).
- `lib/features/auth/data/repository/secure_token_storage.dart` — token writes bump the generation (try/finally).
- `lib/features/auth/presentation/provider/auth_provider.dart` — `_createHttpClient()`, registration role from claim.
- `lib/core/sync/riverpod_sync_trigger_dispatcher.dart` — dispose guard.
- `lib/core/sync/sync_service.dart`, `pos/domain/repository/day_closure_repository.dart`, `pos/data/datasource/local_day_closure_datasource.dart` — contract docs.
- `lib/features/pos/domain/model/sale_status.dart` (new), `cart_item.dart`, `sale_model.dart`, `record_sale_usecase.dart`, `record_sale_notifier.dart`, `sale_success_page.dart`, `day_closure_repository_impl.dart`, `pos_page.dart` — status enums.
- `day_closure_providers.dart`, `pos_speed_dial.dart`, `reports_page.dart` — `DayClosureState` rename.
- `di/providers.dart`, `stock_repository_impl.dart`, `client_/supplier_repository_impl.dart`, `stock_provider.dart`, `contact_provider.dart`, `cart_bottom_sheet.dart`, `onboarding/.../shop_name_page.dart` — warning fixes / routeForRole.
- Tests: new `session_presence_cache_test`, `registration_role_test`, `sale_status_test`; updated startup_route, dispatcher, repository, POS and reports tests.
- `pubspec.yaml` — 1.1.32+31.
- Docs: `ux-design-specification.md`, `epics/requirements-inventory.md`, `epics/epic-4-point-de-vente-pos.md`, `epics/epics-remediation-audit.md` (10 items ticked + analyzer AC with evidence).

**Review:** 23 findings — 7 patched entries (generation bump in try/finally ×2 sites, SaleStatus doc + dead `fromString` removed, import ordering, dead test mocks, `@visibleForTesting isDisposed`, analyzer AC evidence), 0 deferred, 16 rejected with reasons (see triage log). Patched counts by entry verdict: high 0, medium 1, low 6.

**Follow-up review recommended:** false — no high patched, a single medium entry patched.

**Verification:** `flutter analyze` → 0 error, 0 warning (607 infos). Targeted tests of patched files: 97 passed. `run-tests-chunked.sh app`: all 20 chunks OK, 1677 tests (core 460, widget 2, account 20, audit 14, auth 99, catalog 148, contact 37, dashboard 94, feedback 14, identity 11, inventory 180, notifications 16, onboarding 26, pos 229, profitability 45, reports 42, settings 44, stores 45, sync_indicator 62, team 89).

**Residual risks:** the router cache stays correct only while every future writer of `jwt_token`/`refresh_token` calls `markSessionSecretsChanged()` (documented on the function); the router redirect wiring itself has no widget test (pre-existing manual-only gap, see `splash_sync_wiring_test.dart`).

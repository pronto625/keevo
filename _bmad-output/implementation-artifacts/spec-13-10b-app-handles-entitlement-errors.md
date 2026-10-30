---
title: '13.10b — App handles module entitlement refusals (403 MODULE_NOT_ENTITLED / MODULE_READ_ONLY) cleanly'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '0fa07b218bab515554bdc8be2c69aee4386141ad'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-10a-gating-all-endpoints.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-8b-held-operations-visible.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Since 13.10a the backend refuses premium features to Free tenants with `403 MODULE_NOT_ENTITLED` and writes during grace with `403 MODULE_READ_ONLY`. The app does not know these codes: screens show a generic error (or may treat 403 like an auth problem), the export upgrade sheet keys on the old `PLAN_FEATURE_REQUIRED`, and the dashboard « stock bas » tile / catalogue button open the now-premium multi-store overview. Hiding premium entry points from the module list is 15.1/15.8; this story makes every refusal clean and understandable.

**Approach:** Map the two codes once in the network/error layer to typed failures with French messages and an upgrade affordance; never log out or retry on them; fix the known entry points.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **Error mapping**: a `403` whose `domainCode` is `MODULE_NOT_ENTITLED` or `MODULE_READ_ONLY` becomes a dedicated failure type (not auth, not network); messages « Cette fonctionnalité fait partie de l'offre Premium » (+ « Passer à Premium » action opening the existing subscription/upgrade screen) and « Abonnement expiré : ce module est en lecture seule » (+ same action). Never triggers logout, token refresh or retries. Shown through the app's existing error presentation (snackbar/inline state) — no code, id or English text visible.
- **Exports**: the existing upgrade sheet (`isPlanGate`) also opens on `MODULE_NOT_ENTITLED` (both codes accepted).
- **Low-stock entry points**: for a tenant whose premium modules are off, the dashboard « stock bas » tile and the catalogue low-stock button open a free local low-stock list (products of the active store at/below their threshold, from Drift) instead of `/stock/overview?showLowOnly=true`; Premium tenants keep the multi-store overview. The module state comes from `GET /api/v1/tenants/me/modules` (owner) fetched once per session and cached (minimal provider; the full entitlements provider is 15.1); employees use the free list.
- **Version**: bump `pubspec.yaml` (2.0.4+75 → 2.0.5+76).

## Boundaries & Constraints

**Always:** offline behaviour unchanged; no technical data in the UI; Flutter tests by folder; live test on the Redmi with the test tenant temporarily Free.

**Never:** no backend change, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Free tenant opens a premium screen | 403 MODULE_NOT_ENTITLED | French message + « Passer à Premium », still logged in | — |
| Write during grace | 403 MODULE_READ_ONLY | French read-only message | — |
| XLSX export, Free | 403 MODULE_NOT_ENTITLED | upgrade sheet | — |
| Dashboard « stock bas », Free | tap | local low-stock list | — |
| Same, Premium | tap | multi-store overview (unchanged) | — |
| Other 403 (FORBIDDEN role) | — | unchanged behaviour | — |

</frozen-after-approval>

## Code Map

- `keevo/app/lib/core/network/` (interceptors, error mapping), shared failure/error presentation.
- `keevo/app/lib/features/export/.../export_exception.dart` (`isPlanGate`), dashboard page (low-stock tile), catalogue low-stock button, stock providers.

## Tasks & Acceptance

**Execution:**
- [x] Failure type + mapping + French messages + upgrade action; tests (no logout/refresh on these codes).
- [x] Export sheet on both codes; tests.
- [x] Minimal `/tenants/me/modules` provider + local low-stock list routing; widget tests.
- [x] pubspec bump; tick the 13.10b routed items in the epic.

**Acceptance Criteria:**
- Given a Free tenant opening a premium feature, then a French message with an upgrade action, no logout; given the low-stock tile, then a free local list.
- Given `flutter test` on the touched folders, then green; live Redmi scenario passes.

## Implementation Notes

**Task 1 done (2026-10-30).** `core/network/module_entitlement_exception.dart`: `ModuleEntitlementException` (reasons notEntitled / readOnly, the two French messages, `forDomainCode`, `fromResponse` = 403 only, `of(error)` reads the typed error, a `DioException`, an `ApiException` or a text state) + `ModuleEntitlementInterceptor` (tags `DioException.error`, keeps the response, `handler.next` — no logout / renewal / retry), added after `AuthInterceptor` in `dioProvider`. `appErrorMessage` maps it first (and the two codes in `_domainMessage`). `core/widget/entitlement_upgrade.dart`: `PremiumUpgradeButton`, `appErrorSnackBar` (message + « Passer à Premium » SnackBarAction), `canOfferPremiumUpgrade` (hidden for an employee — `/settings/subscription` is owner-only). `AppErrorWidget`: premium badge, title « Fonctionnalité Premium » / « Abonnement expiré », refusal message, « Passer à Premium » above « Réessayer »; `AppErrorInline`: compact action. Wired: inventory session cancel + transfer receive snackbars, report preferences (notifier stored `e.toString()` → `appErrorMessage(e)`, snackbar with action), test report / weekly preview rethrow the refusal instead of « échec », report resend (`ReportException` message), profitability product list / store performance no longer hide the refusal behind the local fallback. Tests: `test/core/network/module_entitlement_test.dart` (mapping, French messages, real Dio chain Retry+Auth+Module: typed error, 1 call, no session end, no refresh; FORBIDDEN untouched), `test/core/widget/entitlement_upgrade_test.dart` (full page / inline / snackbar, navigation to the subscription screen, employee without action), identity provider + profitability repository tests.

**Task 2 done (2026-10-30).** `ExportException`: `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY` French messages; `isPlanGate` accepts `PLAN_FEATURE_REQUIRED` and `MODULE_NOT_ENTITLED` (export page + Mes exports open the upgrade sheet). Export page: a CSV refused (premium dataset, 13.10a item 4) shows « Données réservées au plan Premium » instead of the Excel sheet. Tests: datasource code table + 2 export page widget tests; `test/features/export` 141 green.

**Task 3 done (2026-10-30).** `settings/presentation/provider/tenant_modules_provider.dart`: `tenantModuleStatesProvider` (FutureProvider, owner only — employee → `TenantModuleStates.none` without a call; parses `data[].moduleId/state`; cached per session, invalidated by `SignOutService` and a new sign-in in `SessionClaimsApplier`) + `isMultiStoreOverviewAvailable(ref)` (`multi-store` ENABLED or READ_ONLY → true; error → invalidated + false; > 3 s → false, answer cached for the next tap). `inventory/presentation/page/local_low_stock_page.dart`: `openLowStockView` (premium → overview, else `/stock/low`) and `LocalLowStockPage` (Drift, active store or every store with its name, « Rupture » / quantity, « Seuil : n », empty state, pull-to-refresh). `LocalDashboardDatasource.getLowStockProducts({storeId})` (same threshold rule as `getLowStockCount`), `LowStockProduct.storeName` (optional), `localLowStockProductsProvider`. Route `/stock/low` (not owner-only). Dashboard « stock bas » tile and catalogue « Vue stock multi-boutiques » button go through `openLowStockView` (tile keeps `showLowOnly=true` for Premium). Tests: datasource (store filter, all stores + name, archived excluded, order), `local_low_stock_page_test.dart` (DISABLED / ENABLED / READ_ONLY / employee / offline / empty), `tenant_modules_provider_test.dart` (parse, once per session, employee no call).

**Task 4 done (2026-10-30).** `pubspec.yaml` 2.0.4+75 → 2.0.5+76. Epic: 13.10b deferred item ticked with evidence; 13-5 gating item annotated (Flutter part done, `@RequiresPermission` still 14.1). Folder runs, one at a time, all green: `test/core` 693, `test/features/export` 141, `dashboard` 141, `identity` 13, `profitability` 91, `reports` 53, `settings` 57, `inventory` 232, `catalog` 191, `auth` 111. `flutter analyze` on touched files: infos only. Not done here: live Redmi scenario (orchestrator). Residual: the warm-up of the module states happens on the first tap (≤ 3 s wait), cross-store availability sheet keeps its local fallback, settings links to `/stock/overview` still open it (Premium message shown) — hiding entry points is 15.1 / 15.8.

**Review round 1 — item 1 done (2026-10-30): refusals swallowed.** Live bug root cause: `InventorySessionRepositoryImpl.create` already rethrew the 403, but the config sheet showed it in a SnackBar of the page *under* the modal sheet — invisible. The sheet now keeps the failure (`_startError`) and shows `AppErrorInline` (key `inventory-start-error`) above the start button, with « Passer à Premium »; `openPremiumUpgrade` closes an enclosing popup route (sheet/dialog) before pushing the subscription screen. Rethrow of a `ModuleEntitlementException` before every local fallback: inventory session `getActiveByStoreId` / `cancel` / `getHistory`, inventory count `getCountingProducts` / `saveCount`, multi-store `getOverview` / `getStoreStockDetail` (the 13.8b pending-ops guard of `getOverview` still answers locally first). Transfer history kept its local fallback on purpose (held transfers must stay visible, 13.8b). Tests: session repo (create/getActive/getHistory/cancel), count repo, multi-store repo, config sheet widget test (message in the sheet, upgrade closes it and opens the subscription screen).

**Review round 1 — item 2 done: feature exceptions.** `ModuleEntitlementException.of()` also reads the `domainCode` String getter of any feature exception (ReportException, ExportException…, no feature import in core); new `code` getter. `RemoteReportHistoryDataSource._mapError` takes `refusal.code` (cast removed). `ReportHistoryRepositoryImpl.getReportById` rethrows the refusal instead of showing the cached copy → the weekly report detail shows « Fonctionnalité Premium » + action. Tests: core mapping (`_CodedException`), repository, report detail page.

**Review round 1 — item 3 done: count vs list.** The tile opens `/stock/low?all=true` (`openLowStockView(allStores: true)` → `LocalLowStockPage(allStores: true)`, `storeId` null); the catalogue button keeps the active store. `getLowStockProducts`: store branch = `getStoreLowStockProducts`, all-stores branch filters `COALESCE(s.is_active, 1) = 1`; `getLowStockCount` gets the same filter; the header counts distinct products (as the tile). Test: datasource count == distinct products of the list (inactive store excluded), page tests (all stores, product low in 2 stores counted once).

**Review round 1 — item 4 done: modules cache.** `SubscriptionPage` invalidates `tenantModuleStatesProvider` whenever the subscription loads/refreshes (after a purchase / plan change); `SessionClaimsApplier` invalidates it on every token apply (not only a new session — a renewal follows a plan change), lazily re-read. `openLowStockView(context, …)`: no `WidgetRef` (reads through `ProviderScope.containerOf`, so no `ref.invalidate` on a disposed ref), in-flight guard (a second tap while loading is ignored). `isMultiStoreOverviewAvailable(ProviderContainer)`. Tests: error → refetch, slow answer → false then cached, double tap → one push, subscription page re-read.

**Review round 1 — item 5 done: snackbar context.** `appErrorSnackBarFor(context)` captures router + role before the await and returns a builder; used in the inventory session cancel (built before `Navigator.pop` / await) and transfer receive (both catch branches). Report preferences / report resend build theirs synchronously in a `ref.listen` callback (unchanged). Test: builder used after its widget is gone still shows the message and opens the subscription screen.

**Review round 1 — item 6 done: real wiring tests.** `dashboard_page_test.dart` pumps the real `DashboardPage` and taps « Stock Bas » (Free → `/stock/low?all=true`, Premium → `/stock/overview?showLowOnly=true`); `catalog_page_test.dart` pumps the real `CatalogPage` and taps « Vue stock multi-boutiques » (Free / employee → `/stock/low`, Premium → `/stock/overview`). Runs: `test/core` 695, `inventory` 241, `dashboard` 143, `catalog` 194, `reports` 55, `settings` 60, `auth` 111, `export` 141, `profitability` 91, `identity` 13 — all green; analyze: no error/warning.

**Live verification (orchestrator, 2026-10-30, Redmi, dev APK 2.0.5+76, test tenant temporarily FREE).** Run 1: catalogue « Vue stock multi-boutiques » → local « Stock bas » list ✅; « Démarrer l'inventaire » → server 403 but no message ❌ (fixed). Run 2: Inventaire screen shows « Fonctionnalité Premium — Cette fonctionnalité fait partie de l'offre Premium » + « Passer à Premium » + « Réessayer »; « Passer à Premium » opens « Abonnement ». Tenant restored to PREMIUM_TRIAL.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass (13-10b)
- verdicts: 1 combined layer (13 findings) + live test — medium 6, low 7
- findings:
  - `[medium]` `[patch]` live: « Démarrer l'inventaire » got 403 but showed nothing (snackbar under the sheet) — message + upgrade inside the sheet; repositories (sessions, counts, multi-store) rethrow the refusal instead of falling back to local data (transfer history kept local for held transfers)
  - `[medium]` `[patch]` feature exceptions (reports…) not recognised — `of()` reads the server code from any feature exception; weekly report detail shows the Premium title + action
  - `[medium]` `[patch]` low-stock tile count (all stores) vs list (active store); inactive stores — tile opens all stores, same rule both sides
  - `[medium]` `[patch]` modules cache not refreshed after a plan change; double push; invalidate on disposed ref — fixed
  - `[low]` `[patch]` snackbar context after await; real tile/button wiring tests
  - `[low]` `[defer]` hide premium entry points, upgrade action on Audit/Product/Contact exceptions, persisted entitlements offline, XLSX premium-dataset wording → routed → 15-8
  - `[low]` `[defer]` subscription screen shows the raw limit `2147483647` for unlimited plans (pre-existing) → routed → v1s-16-25

## Verification

**Commands:**
- `cd keevo/app && flutter test test/core` + touched `test/features/...` folders -- expected: green
- Live (Redmi, test tenant temporarily FREE): open stock overview / inventory / export XLSX → French messages; « stock bas » tile → local list; restore PREMIUM_TRIAL.

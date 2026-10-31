---
title: '15.8 — Map every Flutter feature to a module + frontend gating'
type: 'feature'
created: '2026-10-31'
status: 'done'
baseline_revision: 'd421821dbacd76a46f455d301c23d96c6762ba11'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-2-routing-dynamique-go-router.md'
  - '{project-root}/keevo/app/packages/README.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** 15.2 built the gating mechanism (route → module registry from the manifests, go_router guard, hidden entries) for the premium modules and CRM only. The app's 21 feature folders are not all attached to a module, a few entry points still lead to gated screens (`apply_adjustments_button.dart` → `/inventory`, the catalogue / low-stock « stock » overview, the product-form supplier card), the advanced-reports surfaces (leaderboard tab, weekly report trigger, `/dashboard/store/:storeId` if premium) are not gated, the `debug` feature is reachable in release builds, and on phones the app rotates to landscape where onboarding/terms screens overflow.

**Approach:** Produce and enforce a feature → module mapping covering all 21 `lib/features/*` folders (plus `keevo_invoicing`): a documented table (`docs/modules/flutter-feature-modules.md`) and a code table (`kFeatureModules` in the app shell) checked by a test against the folders on disk. Every premium surface left is gated with the 15.2 mechanism (manifest routes + `isRouteAvailable` for in-page entries), the `debug` feature is excluded from release builds, and phones are locked to portrait.

## Boundaries & Constraints

**Always:**
- Module ids are the backend manifest ids (`core`, `catalog`, `crm`, `inventory`, `pos`, `reporting`, `store`, `sync`, `messaging`, `invoicing`, `counting`, `multi-store`, `exports`, `profitability`, `report-config`, `advanced-reports`, `alerts`); a feature that spans modules lists its main module and the premium sub-surfaces with their module (e.g. `reports` → `reporting`; leaderboard → `advanced-reports`; boutiques → `multi-store`).
- Free modules are always on, so gating only applies to premium surfaces; new premium routes go into the manifests (+ contract file regeneration) exactly like 15.2.
- Every in-app entry pointing to a premium route uses `isRouteAvailable` (hidden when the module is not readable): the 15.2 routed items (`apply_adjustments_button.dart` `/inventory`, catalogue/low-stock overview link, product-form supplier card — no chevron/tap when CRM is off, with a widget test both ways) and the advanced-reports entries (leaderboard tab, weekly report action).
- A test fails if a `lib/features/*` folder (or module package) is missing from `kFeatureModules`, or if a mapped module id is not a manifest id.
- `debug`: its routes are only mounted when `kDebugMode` (or `!kReleaseMode`) and no release entry point links to them; a test asserts the release route table has no `/debug` path.
- Phones: portrait-only (`SystemChrome.setPreferredOrientations` portrait up/down at startup and/or `android:screenOrientation` in the manifest); tablets unaffected only if the codebase already distinguishes them — otherwise portrait everywhere.
- Document the Drift choice (G14): local tables of inactive modules stay on the device (harmless); gating is UI/route/sync (12.7), not schema.
- French labels only; no module ids on screen.
- Bump `keevo/app/pubspec.yaml` 2.0.16+88 → 2.0.17+89.

**Never:**
- No extraction of more features into packages (post-pilot).
- No backend entitlement change except adding manifest `routes` for newly gated client routes.
- No removal of local Drift tables.

</intent-contract>

## Code Map

- `keevo/app/lib/features/` (21 folders: account, audit, auth, catalog, contact, dashboard, debug, export, feedback, help, identity, inventory, notifications, onboarding, pos, profitability, reports, settings, stores, sync_indicator, team) and `keevo/app/packages/keevo_invoicing`.
- `keevo/app/lib/core/router/module_routes.dart` (15.2 registry, `isRouteAvailable`, `kFreeRouteShadows`), `app_router.dart` (`/debug/categories` at ~904, `/dashboard/store/:storeId` at ~522), `keevo/contracts/module-routes.json` + backend `ModuleRoutesContractFileTest` (`-Dmodule.routes.contract.write=true`), manifests `keevo/backend/src/main/resources/keevo/modules/*/module.json`.
- `keevo/backend/docs/entitlements/endpoints.md` -- which endpoints each module gates (e.g. `/api/v1/reporting/leaderboard` and `/api/v1/reports/trigger-weekly` → `advanced-reports`) — use it to find every premium surface.
- Routed entry points: `lib/features/inventory/presentation/widget/apply_adjustments_button.dart`, `lib/features/catalog/presentation/page/catalog_page.dart` (overview route), `lib/features/inventory/presentation/page/local_low_stock_page.dart`, `lib/features/catalog/presentation/page/product_form_page.dart` (`_SupplierCard`).
- `keevo/app/lib/main.dart` (startup), `keevo/app/android/app/src/main/AndroidManifest.xml`, `ios/Runner/Info.plist` (orientations; existing `ios_push_config_test` checks orientation keys — keep it consistent).

## Tasks & Acceptance

**Execution:**
- `docs/modules/flutter-feature-modules.md` + `lib/core/modules/feature_modules.dart` (`kFeatureModules`) + test.
- gate remaining premium surfaces (manifest routes + guard, `isRouteAvailable` entries) incl. the routed entry points; widget tests.
- `debug` excluded from release routing + test.
- portrait lock + test where feasible (config file assertion).
- pubspec 2.0.17+89.

**Acceptance Criteria:**
- Given the 21 feature folders, when the mapping test runs, then each is attached to a manifest module.
- Given a Free tenant, when they navigate, then premium surfaces (multi-store/transfers, counting, profitability, advanced reports, exports, invoicing, report config) are hidden or lead to « module non activé ».
- Given a release build, then no `/debug` route exists.
- Given a phone rotated to landscape, then the app stays in portrait.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 30 findings — high 0, medium 3, low 19, false 8, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind/edge/claim) checkout still sends a client chosen before CRM turned unavailable — clear the selection / send no `clientId` when CRM is not readable; test.
  - `[medium]` `[patch]` (blind/edge/claim) weekly report and trend settings stay editable without `advanced-reports` (the scheduler skips the tenant) — hide that section; test.
  - `[medium]` `[patch]` (vg) `ApplyAdjustmentsButton` caller path untested (dropping the flag passes) — widget test with `counting` DISABLED ending on Plus; read availability at completion, not before the async gap.
  - `[low]` `[patch]` (blind) weekly preview shown in READ_ONLY although the endpoint needs write access — write actions gated on `isActive`.
  - `[low]` `[patch]` (blind/edge/vg other) catalogue tooltip and tap disagree when states are unknown — same predicate for both.
  - `[low]` `[patch]` (blind/edge) `isMultiStoreOverviewAvailable` fails open for an unregistered route — require the route's module and readability.
  - `[low]` `[patch]` (vg) `/reports/equipe` owner-only redirect untested — tests for `/reports/equipe`, `/reports/rentabilite`, `/reports/boutiques`.
  - `[low]` `[patch]` (blind/edge) debug link test only matches single quotes — quote-insensitive.
  - `[low]` `[patch]` (blind/edge) `feature_modules_test`: unclear error on a manifest without id; paid modules without routes never checked — explicit failure; every paid module mapped.
  - `[low]` `[patch]` (blind) Android `sensorPortrait` allows upside-down while iPhone does not; unreachable test branch — Android `portrait`; test branch cleaned.
  - `[low]` `[patch]` (blind) `owner_reports_page_test` fixed pumps instead of settling; no fallback check for a hidden initial tab — settle + fallback assertion.
  - `[low]` `[patch]` (blind) `isModuleAvailable` / `isRouteAvailableNow` lack unit tests (unknown, READ_ONLY) — unit tests.
  - `[low]` `[reject]` (blind) `kFeatureModules` drives nothing at runtime / doc table unchecked — the table is the reviewed mapping the AC asks for; enforcement stays in the 15.2 registry; consistency test exists.
  - `[low]` `[reject]` (blind) `CategoryDebugPage` still compiled in release — the route is not mounted and nothing links to it; binary size impact negligible.
  - `[low]` `[reject]` (blind) `UIRequiresFullScreen` deprecated in iPadOS 26 — still honoured; revisit with a tablet layout.
  - `[low]` `[reject]` (blind) `returnToInventory` uses `go('/settings')` — Plus tab root, intended stack reset.
  - `[low]` `[reject]` (blind) `deep_link_navigation` checks the multi-store module id directly — equivalent today (single module for the route).
  - `[low]` `[reject]` (blind) manifests parsed twice in the test — test-only cost.
  - `[low]` `[reject]` (edge) entitlements changing mid-validation — covered by reading at completion (patch row).
  - `[false]` `[reject]` (intent) table not read at runtime — R1 is the defensible reading; per-feature runtime gating of free modules would be a no-op.
  - `[false]` `[reject]` (intent) hidden without CTA — the AC allows hidden or blocked; blocked routes keep the « module non activé » CTA.
  - `[false]` `[reject]` (intent) no Free-plan walkthrough test — per-module overrides exercise each surface; device check done.
  - `[false]` `[reject]` (intent) debug code still in the binary — the AC is about the release app exposing it; routes excluded and tested.
  - `[false]` `[reject]` (intent) supplier card tested alone — patched? no: the card is the gated widget; form wiring unchanged.
  - `[false]` `[reject]` (intent) portrait lock instead of layout fix — the routed item allows either; verified on the Redmi (ROTATION_0 with auto-rotate on).
  - `[false]` `[reject]` (intent) 21 folders vs 18 listed — "map what exists" (products is part of catalog).
  - `[false]` `[reject]` (intent) G14 doc only — the intent asks to document it.
  - `[false]` `[reject]` (intent) slots not tested here — slots are server-driven (15.3) and filtered server-side by module.

## Verification

**Commands:**
- `runpkg.sh shared` (contract test) then `architecture` -- expected: 0 failures.
- `cd keevo/app && flutter analyze lib test` and `bash tool/test_chunked.sh` -- expected: 0 errors/warnings, all chunks pass.
- `cd keevo/app && flutter build apk --debug` -- expected: success.

**Manual checks:**
- Redmi: rotate the phone → app stays portrait; a Free-like tenant (temporary DISABLED rows) sees no premium entries.

## Auto Run Result

Status: done

**Summary:** Every Flutter feature folder (21) plus `keevo_invoicing` is attached to a manifest module in `kFeatureModules` (`lib/core/modules/feature_modules.dart`, documented in `docs/modules/flutter-feature-modules.md` with the G14 Drift choice), checked by a test against folders and manifests (every paid module mapped). Remaining premium surfaces gated: « Équipe » leaderboard (`/reports/equipe`, `advanced-reports` manifest 1.1.0, contract regenerated, owner-only), weekly report + trend settings and preview (`advanced-reports`, write actions need ENABLED), « Alertes Stock Bas » channel (`alerts`), checkout client selector and `clientId` (CRM), supplier card (CRM), inventory return path (counting, read at completion), stock overview entry (single predicate for tooltip and tap, fail-closed for unregistered routes). `debug` routes only in debug builds (test). Portrait lock: Android `portrait`, iPhone portrait, iPad portrait/upside-down + `UIRequiresFullScreen`. Helpers `isModuleReadable/Writable`, `isModuleAvailable[Now]`, `isModuleActive`, `isRouteAvailableNow`. App 2.0.17+89.

**Review:** 30 findings — 3 medium patched (stale checkout client, weekly settings without module, untested inventory return path), 9 lows patched (+ alerts channel gating decided at review), rest rejected with reasons.

**Follow-up review recommended:** true — 3 medium patches.

**Verification:** backend shared 1054 / architecture 32 — 0 failures; analyze 0 errors/warnings; keevo_core, keevo_invoicing and app chunked tests all pass; `flutter build apk --debug` OK. Device (Redmi): app stays portrait with auto-rotate on (rotation 0); with `advanced-reports` temporarily DISABLED the « Équipe » tab disappears from Rapports (present when enabled); row removed (tenant_modules 331).

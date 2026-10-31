---
title: '15.2 — Dynamic go_router routing by active modules'
type: 'feature'
created: '2026-10-31'
status: 'done'
baseline_revision: '8ae8b18f8cee0c1743192bd9a58d14864459ad6c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-1-entitlements-provider-flutter.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** A tenant without a module can still reach that module's screens: every route is mounted, deep links open premium pages that then fail with a 403 message, and navigation entries for inactive modules stay visible (UX-DR4). There is also no single mapping "route → module" — the backend manifests have a `routes` field that only `invoicing` fills and that nothing validates.

**Approach:** The backend module manifests become the single source of the route → module mapping (validated at boot, exported as a contract file); the app holds the same mapping in a route registry checked against that contract, and a go_router guard sends any route of a module that is not readable to a French « module non activé » screen (upgrade CTA for the owner). Navigation entries (tabs, Plus/settings tiles, in-page buttons) of a non-readable module are hidden.

## Boundaries & Constraints

**Always:**
- Gate only on a known state: `entitlementsProvider` status `unknown` (nothing known yet) lets the route through — the backend still answers 403 and 13.10b's French message stays the fallback.
- READ_ONLY (grace) modules stay reachable (reads allowed); only a module that is not readable is blocked.
- Role guard (`_ownerOnlyPrefixes`, employee → `/pos`) keeps working and runs first; the module guard composes with it.
- Deep links (notifications, `deep_link_navigation.dart`) go through the same guard.
- The « module non activé » screen shows the module's French display name (from the entitlements states' `displayName`, persisted with them), never its id; owner CTA « Passer à Premium » → `/settings/subscription`; employee text « Demandez au propriétaire d'activer ce module ».
- Free modules that are always on (core, catalog, pos, inventory, store, sync, reporting) need no route entry.
- Bump `keevo/app/pubspec.yaml` 2.0.8+80 → 2.0.9+81.

**Never:**
- No mapping of every one of the 18 features (Story 15.8) — this story covers the premium modules and CRM listed in the Code Map.
- No ExtensionSlot (15.3), no « Mes modules » screen (15.6), no package extraction (15.4).
- No removal of the 13.10b 403 handling (`ModuleEntitlementException`, `entitlement_upgrade.dart`): it stays as the server-side fallback.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Inactive module, direct route | `multi-store` DISABLED (status server/cached), go `/stock/transfers` | redirected to « module non activé » for « Multi-boutiques et transferts » | — |
| Inactive module, deep link with params | `counting` DISABLED, notification opens `/inventory/counting/<id>` | same redirect | — |
| Active / grace module | `profitability` ENABLED or READ_ONLY, go `/reports/rentabilite` | page opens | — |
| Unknown state | status `unknown`, go `/exports` | page opens (backend decides) | 403 → existing French message |
| Employee on owner-only route | employee, go `/exports` | role guard → `/pos` (unchanged) | — |
| Navigation | `crm` DISABLED | « Clients » / « Fournisseurs » entries hidden; others unchanged | — |
| State changes | module re-enabled (new server answer) | entries reappear, route opens without restart | — |
| Manifest route invalid | a manifest route without leading `/` or with an invalid segment | boot fails with a message naming the module and route | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/keevo/modules/*/module.json` -- `routes` (only invoicing filled: `/invoicing`, `/invoicing/:id`). Fill for: `crm` (`/clients`, `/clients/new`, `/clients/:id`, `/suppliers`, `/suppliers/new`, `/suppliers/:id`), `multi-store` (`/stock/overview`, `/stock/transfers`, `/reports/boutiques`), `counting` (`/inventory/counting/:sessionId`, `/inventory/gap-report/:sessionId` — check `inventory_launch_page.dart` for the launch route ownership), `profitability` (`/reports/rentabilite`, `/reports/rentabilite/:productId`), `exports` (`/exports`, `/exports/new`, `/exports/:id`; NOT `/exports/local`, the free CSV path — verify in `export_page.dart`), `report-config` (`/settings/reports`). Use `docs/entitlements/endpoints.md` + 13.10b premium screen list (epics doc line ~1017) to confirm each module.
- `keevo/backend/src/main/java/com/keevo/shared/module/ModuleManifestLoader.java:98-100` -- parses `routes`; add validation (leading `/`, segments literal or `:name`, no duplicates across modules) → boot failure (routed item 13-1).
- `keevo/backend/src/test/java/com/keevo/reporting/export/ExportContractFileTest.java` + `keevo/contracts/export-datasets.json` -- pattern for a generated contract file; add `keevo/contracts/module-routes.json` (module id → routes) written/checked the same way.
- `keevo/app/test/features/export/domain/local_export_contract_test.dart` -- pattern for the app-side contract test.
- `keevo/app/lib/core/router/app_router.dart:186-230` -- `globalAuthRedirect` (role guard, `_ownerOnlyPrefixes`), top-level `redirect:` at ~294 reading `sessionPresenceCacheProvider`; ~138 `path:` entries.
- `keevo/app/lib/core/router/deep_link_navigation.dart` -- deep link entry.
- `keevo/app/lib/core/scaffold/main_shell.dart:41-56` -- owner/employee tab routes (Dashboard / Caisse / Catalogue / Rapports / Plus).
- Entry points pushing premium routes: `settings_page.dart`, `reports_page.dart`, `report_history_page.dart`, `catalog_page.dart`, `store_dashboard_page.dart`, `inventory_launch_page.dart`, `export_entry_button.dart`, `client_list_page.dart`, `supplier_list_page.dart`, `product_profitability_list_page.dart` (all under `keevo/app/lib/features/`).
- `keevo/app/lib/core/entitlements/` (15.1) -- `entitlementsProvider`, `Entitlements.isReadable`, status; add `displayName` per module if not already kept.
- `keevo/app/lib/core/widget/entitlement_upgrade.dart` -- existing French upgrade wording / CTA to reuse on the new screen.

## Tasks & Acceptance

**Execution:**
- backend manifests -- fill `routes` per the Code Map; bump each touched manifest's version (minor).
- `ModuleManifestLoader` + test -- route format validation and cross-module duplicate check.
- backend contract test + `keevo/contracts/module-routes.json` -- generated from manifests, fails when stale.
- `keevo/app/lib/core/router/module_routes.dart` (new) -- registry (module id → route patterns, same content as the contract) + `moduleOfPath(String path)` matching `:param` segments.
- `keevo/app/test/core/router/module_routes_contract_test.dart` (new) -- registry equals `keevo/contracts/module-routes.json`.
- `app_router.dart` -- module guard after the role guard (testable pure function like `globalAuthRedirect`), new route for the « module non activé » page, router refresh when `entitlementsProvider` changes (`refreshListenable`).
- `keevo/app/lib/core/router/module_unavailable_page.dart` (new) -- French screen, owner CTA / employee text, back to home.
- `main_shell.dart` + entry-point widgets above -- hide entries whose route's module is not readable (shared helper, e.g. `isRouteAvailable(ref, path)`).
- tests -- guard pure-function tests for every I/O row; widget tests for one hidden tile/button and the unavailable page (owner vs employee).
- `keevo/app/pubspec.yaml` -- 2.0.9+81.

**Acceptance Criteria:**
- Given an inactive module, when the user opens one of its routes (deep link included), then they land on the « module non activé » screen naming the module in French.
- Given the main navigation and settings, when they render, then entries of inactive modules are absent and those of active modules are present.
- Given a manifest route and the app registry, when either changes without the other, then a test fails.
- Given touched backend packages and Flutter folders, when tests run one at a time, then all pass.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 32 findings — high 0, medium 4, low 20, false 8, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) `ModulesAnswer` typedef steals the provider's doc comment — move typedef.
  - `[medium]` `[patch]` (blind) low-stock notification (`/stock/history` → `/stock/overview`) now lands on « module non activé » for a Free tenant instead of the free local list — normalizer falls back to `/stock/low` when `multi-store` is not readable.
  - `[low]` `[reject]` (blind) multi-store helper vs guard rules differ — the overview helper needs a server answer on purpose (the overview is a server page); documented in 15.1.
  - `[low]` `[reject]` (blind) « Passer à Premium » shown for a disabled free module (crm) — CRM is on by default and only a super-admin disables it; the CTA leads to the subscription screen where support is reachable. Revisit with 15.6 « Mes modules ».
  - `[low]` `[patch]` (blind) masculine agreement « « Rentabilité » n'est pas activé » — wording « Le module « X » n'est pas activé pour votre commerce ».
  - `[low]` `[reject]` (blind) screens lower in the back stack not re-checked — a mid-session disable is a rare admin action; the backend 403 + French message remains the fallback; a stack-wide re-check is new machinery.
  - `[low]` `[patch]` (blind) refresh ping on any `Entitlements` change (fetchedAt) — ping only when the readable set or known-ness changes (shared helper).
  - `[medium]` `[patch]` (blind) real `appRouter` redirect + `KeevoApp` listener untested (test copies the wiring) — same root cause as verification-gap #1.
  - `[low]` `[patch]` (blind) test routers never disposed with a global notifier — `addTearDown(router.dispose)`.
  - `[low]` `[patch]` (blind) `kFreeRouteShadows` invisible to the backend — contract test asserts no registry route is a free shadow.
  - `[low]` `[reject]` (blind) OwnerReportsPage tab reset on live module change / missing fallback test — needs a module toggle while the hub is open; rare, fix adds state plumbing.
  - `[low]` `[defer]` (blind) supplier card keeps its tappable look when CRM is off — routed to 15.8 (entry-point audit).
  - `[low]` `[defer]` (blind) entry-point audit incomplete (`apply_adjustments_button.dart` → `/inventory`, catalog overview route) — routed to 15.8 (map every feature / entry point).
  - `[low]` `[reject]` (blind) display names lost on a claim seed — claim emission is 16.3; fallback text « Cette fonctionnalité » is correct French.
  - `[low]` `[reject]` (blind) contract file paths depend on cwd — same convention as the existing export contract tests run from the package dir.
  - `[low]` `[reject]` (blind) backend `ROUTE_PATTERN` and Dart matcher not tied — the app registry is checked equal to the generated contract, which only contains backend-validated routes.
  - `[medium]` `[patch]` (verification-gap) production router/refresh wiring never exercised — extract the listener into a function used by `KeevoApp` and tests; add a test driving the real `appRouter`.
  - `[low]` `[patch]` (verification-gap) « Mes exports » icon gating untested — two widget tests (DISABLED hidden / ENABLED shown).
  - `[low]` `[defer]` (verification-gap) supplier-card unlink untested — routed to 15.8 with the supplier card item.
  - `[low]` `[reject]` (edge) pushed gated screen stays open after disable — same as blind #6.
  - `[low]` `[reject]` (edge) re-enable while on « module non activé » screen — the user goes back / home; the entry reappears.
  - `[low]` `[reject]` (edge) OwnerReportsPage tab reset — same as blind #11.
  - `[medium]` `[patch]` (edge) cross-module overlap `/x/new` vs `/x/:id` passes boot — `validateRoutes` also rejects cross-module pairs that match segment-wise with params as wildcards.
  - `[low]` `[patch]` (edge) free shadow could un-gate a declared literal route — same fix as blind #10.
  - `[low]` `[reject]` (edge) Dart contract test cwd — same as blind #15.
  - `[low]` `[reject]` (edge) backend contract test cwd — same as blind #15.
  - `[low]` `[reject]` (edge claim) contract test name says "same order" but map equality ignores order — no route matches two modules (boot overlap check), so order cannot change a result.
  - `[low]` `[reject]` (edge claim) refresh only re-evaluates the base location — same as blind #6.
  - `[false]` `[reject]` (intent) routes guarded, not unmounted — the intent allows "route absente/guardée" and names the redirect guard.
  - `[false]` `[reject]` (intent) bottom bar never changes — no tab belongs to a gated module today; filter in place for 15.8.
  - `[false]` `[reject]` (intent) manifests not consumed at runtime — the app mirrors them through a test-enforced contract; runtime manifests are not served to the app.
  - `[false]` `[reject]` (intent) unknown state lets routes through — deliberate spec boundary (backend 403 fallback).
  - `[false]` `[reject]` (intent) display names / reports hub restructuring beyond the story — needed by the « module non activé » screen and hidden tabs.
  - `[false]` `[reject]` (intent) "états vides cohérents" met by hiding — hiding is the UX-DR4 reading "la barre/menu ne montre que les modules activés".
  - `[false]` `[reject]` (intent) deep links tested on a test router — covered by the new real-`appRouter` test patch.
  - `[false]` `[reject]` (intent) module missing from known states treated as inactive — the server lists every module; 16.3 claim note recorded in epic 16.3 brief.

## Verification

**Commands:**
- `bash /tmp/claude-1000/-home-toor-Project-FreeLance-AI-Keevo/b3540a1e-019a-42f0-9dea-6ba6965d948e/scratchpad/runpkg.sh shared` then `reporting`, `architecture` -- expected: 0 failures.
- `cd keevo/app && flutter test test/core/router` then each touched feature test folder, one at a time -- expected: all pass.
- `cd keevo/app && flutter analyze lib` -- expected: no errors/warnings.

## Auto Run Result

Status: done

**Summary:** The backend manifests now declare the client routes of the gated modules (crm, multi-store, counting, profitability, exports, report-config, plus invoicing), validated at boot (format, cross-module overlap incl. literal-vs-`:param`) and exported to `keevo/contracts/module-routes.json`. The app mirrors them in `core/router/module_routes.dart` (test-enforced equal to the contract) and a go_router guard sends any route of a known non-readable module to the French « module non activé » screen (owner « Passer à Premium », employee « Demandez au propriétaire… »). Unknown states let routes through (backend 403 fallback); READ_ONLY passes. Navigation entries of non-readable modules are hidden (settings tiles, reports hub tabs, « Mes exports », supplier link). Low-stock notifications fall back to the free local list without multi-store.

**Deviations (verified):** `/exports/new` and `/exports/local` stay free (server CSV export is free) — listed as free shadows; `/inventory` belongs to `counting` (the launch page only calls counting endpoints).

**Files changed (main):** manifests (crm 1.3.0; multi-store/counting/profitability/exports/report-config 1.1.0), `ModuleManifestLoader` (+tests), `ModuleRoutesContractFileTest`, `keevo/contracts/module-routes.json`; app `core/router/{module_routes,module_unavailable_page,app_router,deep_link_navigation}.dart`, `main.dart` (`listenModuleGate`), `core/entitlements` (display names), `main_shell.dart`, `settings_page.dart`, `owner_reports_page.dart`, `export_page.dart`, `product_form_page.dart`; tests under `test/core/router/`, settings, reports, export; pubspec 2.0.9+81.

**Review:** 32 findings (+ intent notes) — 4 medium patched (low-stock deep link regression, real `appRouter`/listener wiring untested, cross-module overlap at boot, ping/test-copy root cause), lows patched (doc comment, French agreement, ping only on readable-set change, router dispose, free-shadow contract assertion, « Mes exports » tests); 3 lows routed to 15.8 (entry points / supplier card) and 1 note to 16.3 (claim must list free modules); the rest rejected with reasons in the triage log.

**Follow-up review recommended:** true — several medium patches; unverified risk: screens pushed below the top of the stack are not re-checked when a module is disabled mid-session (backend 403 remains the fallback), and the real device flow of deep links was not run on a phone (adb device unauthorized).

**Verification:** backend shared 1048 / reporting 404 / architecture 32 — 0 failures; dev backend boots with the new manifests (route validation passes) and `/tenants/me/modules` answers 200. Flutter one folder at a time: core/router 114, entitlements 28, scaffold 15, sync 347, settings 74, reports 56, export 143, contact 49, catalog 194, inventory 241, profitability 91, notifications 24, dashboard 143, auth 113 — all pass; `flutter analyze lib` 0 errors/warnings.

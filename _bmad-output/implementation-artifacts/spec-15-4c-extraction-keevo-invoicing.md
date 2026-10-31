---
title: '15.4c — Extract the invoicing module into the keevo_invoicing package'
type: 'refactor'
created: '2026-10-31'
status: 'done'
baseline_revision: '9d3d846a962161756550061ab6a447605fed9f97'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-4a-workspace-melos-keevo-core.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-3-extension-slot-lazy-loading.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Modules are still folders of the app: the invoicing feature (`lib/features/invoicing`, ~870 lines from 15.3) reaches into other features (`dioProvider` in `features/auth`, the file sharer and temp dir in `features/export`) and is wired by hand into the app shell (`/invoicing/:id` in `app_router.dart`, the « Facturer » handler in `core/extension/extension_actions.dart`). The "module = package" pattern is unproven.

**Approach:** Move invoicing into `keevo/app/packages/keevo_invoicing`, depending only on `keevo_core` (+ third-party packages). `keevo_core` gains the small module contract the package needs: injectable ports for the HTTP client and the file sharer/temp dir (providers that the app overrides), and a `KeevoModuleUi` descriptor (module id, routes as builders, extension action handlers). The package exposes `invoicingModuleUi`; the app shell composes registered module descriptors into the router and the action registry, and loads the package's screens/handlers through `deferred as`. Behaviour unchanged.

## Boundaries & Constraints

**Always:**
- `keevo_invoicing` imports only `package:keevo_core/...`, its own files and third-party packages — never `package:keevo/...`; the boundary test (15.4a) is extended to every package under `packages/` (core: no app/features; module packages: no app, no other module package).
- Ports in `keevo_core` are plain providers that throw a clear `UnimplementedError` until the app overrides them (`apiDioProvider` → app's `dioProvider`; `fileSharerProvider`/`exportTempDirProvider` equivalents → app's export implementations). Override wiring lives in `buildRootScope` (15.4b) so tests and production use the same composition.
- Routes: `/invoicing/:id` keeps its path and its 15.2 guard (module `invoicing`); the app router builds routes from the registered descriptors (one list of descriptors in the app shell, e.g. `lib/core/modules/registered_modules.dart`).
- Action « Facturer » (`invoicing.open-sale-invoice`) keeps its key and behaviour; the handler is resolved through the descriptor, loading the package library `deferred as`.
- Tests of the moved code move to `packages/keevo_invoicing/test/` and pass with `flutter test` from the package; app tests touching the slot/route keep passing.
- Melos scripts (`generate`, `analyze`, `test`) and CI include the new package; workspace `pubspec.yaml` lists it.
- Routed item (review 15.4b): audit `state = <equal value>` patterns in the moved providers (Riverpod 3 `==` notification) — fix any that relied on identity.
- Bump `keevo/app/pubspec.yaml` 2.0.12+84 → 2.0.13+85; `keevo_invoicing` 0.1.0, `publish_to: none`.
- Document the pattern (how to extract the next feature) in `keevo/app/packages/README.md` or `docs/modules/flutter-module-packages.md`.

**Never:**
- No behaviour/UI change, no new invoicing screens (15.5).
- No extraction of other features.
- No backend change.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Open invoice | `/invoicing/inv-7` | `InvoiceDetailPage(invoiceId: 'inv-7')` from the package, through the deferred loader | load failure → French error + retry (existing `DeferredWidget`) |
| « Facturer » | slot contribution `invoicing.open-sale-invoice`, `{'saleId': …}` | lookup sheet from the package | — |
| Unregistered module | descriptor list without invoicing | `/invoicing/:id` not mounted; action key unknown → not rendered | — |
| Port not overridden | package provider read in a container without the app overrides | clear `UnimplementedError` naming the port | — |

</intent-contract>

## Code Map

- `keevo/app/lib/features/invoicing/**` (data/remote_invoice_datasource.dart, domain/model/invoice.dart, presentation/{open_sale_invoice.dart, page/invoice_detail_page.dart, provider/invoicing_providers.dart}) and `keevo/app/test/features/invoicing/**` -- to move.
- External deps of the feature: `features/auth/presentation/provider/auth_provider.dart` (`dioProvider`), `features/export/presentation/provider/export_providers.dart` (`exportFileSharerProvider`, `exportTempDirProvider`).
- `keevo/app/lib/core/router/app_router.dart:~55,~610` -- invoicing import + `/invoicing/:id` route (`DeferredWidget`).
- `keevo/app/lib/core/extension/extension_actions.dart:5-27` -- `deferred as invoicing_actions`, `kOpenSaleInvoiceAction`, `kExtensionActions`, `extensionActionsProvider`.
- `keevo/app/lib/core/router/module_routes.dart` (15.2 registry/guard), `keevo/app/lib/root_scope.dart` (`buildRootScope`, 15.4b).
- `keevo/app/test/architecture/core_package_boundary_test.dart` -- extend to all packages.
- `keevo/app/pubspec.yaml` (`workspace:`, `melos:` scripts), `.github/workflows/flutter-ci.yml`, `release-flutter.yml`, `Dockerfile` (generate core-first already via melos).
- `keevo/app/packages/keevo_core/lib/widget/deferred_widget.dart` -- reuse.

## Tasks & Acceptance

**Execution:**
- `packages/keevo_core/lib/module/` (new) -- `KeevoModuleUi` (id, routes `List<RouteBase>` or builder list, `Map<String, ExtensionActionHandler>`), ports (`apiDioProvider`, file sharer/temp dir providers) with clear errors; tests.
- `packages/keevo_invoicing/` (new) -- pubspec (deps: keevo_core, flutter, flutter_riverpod, dio, go_router, intl…; `resolution: workspace`), moved lib/test (git mv), `invoicingModuleUi`.
- app shell -- `registered_modules.dart`, router composition, action registry from descriptors (deferred loading kept), `buildRootScope` overrides for the ports; delete `lib/features/invoicing`.
- boundary test, melos scripts, CI steps, workspace pubspec, docs, version bump.

**Acceptance Criteria:**
- Given the workspace, when the extraction is done, then `packages/keevo_invoicing` analyzes and tests on its own, depends only on `keevo_core`, registers its route and its « Facturer » handler through the descriptor, and is loaded `deferred`.
- Given the app, when every test folder runs one at a time and the debug APK is built, then all pass and behaviour is unchanged.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 30 findings — high 0, medium 0, low 22, false 8, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) `/invoicing` declared in the manifest but not mounted — reserved for the 15.5 list screen; the guard passes it to the app error builder (→ splash), never a crash; 15.5 mounts it.
  - `[low]` `[patch]` (blind/edge) `composeModuleRoutes` has no duplicate check — same `StateError` rule as actions (duplicate module id or path).
  - `[low]` `[patch]` (blind) module `id` not checked against manifest ids — registered-modules test asserts `kModuleRoutes.containsKey(id)`.
  - `[low]` `[patch]` (blind) app could import a module's internals eagerly — boundary rule: app `lib/` imports only `package:keevo_<m>/<m>_module.dart`.
  - `[low]` `[patch]` (blind) pubspec dependencies not checked — boundary test parses `packages/*/pubspec.yaml` (only `keevo_core` among keevo packages for modules, none for core).
  - `[low]` `[patch]` (blind/edge) a caller overriding a port through `buildRootScope` overrides it twice (Riverpod assertion) — caller overrides of a port replace the default port override.
  - `[low]` `[reject]` (blind) app widget tests with a plain `ProviderScope` get no ports — they override the module providers; the port error is explicit when not.
  - `[low]` `[patch]` (blind/edge) stale « Invoicing screens are loaded on first use » comment in `app_router.dart` — removed.
  - `[low]` `[reject]` (blind) "module not registered" test close to a tautology — composition functions are covered in keevo_core; app-level effect is the empty action map.
  - `[low]` `[reject]` (blind) per-package `analysis_options.yaml` copies — three small files; a shared include can come with the next extraction.
  - `[low]` `[patch]` (blind) deferred-route test may fail with an unclear message — explicit `findsOneWidget` with reason.
  - `[low]` `[patch]` (blind/edge) registration errors surface lazily — compose routes/actions eagerly in `main` (debug assert at startup).
  - `[low]` `[reject]` (blind) release workflow installs with `flutter pub get` — a root `pub get` resolves the workspace; release relies on the CI gate.
  - `[low]` `[patch]` (blind) README extraction step-1 grep misses `../../core/` imports — point to the boundary test as the authoritative check and widen the grep.
  - `[low]` `[reject]` (edge) module path colliding with an app route — covered by the duplicate-path check extended to the static app routes in the registered-modules test.
  - `[low]` `[reject]` (edge) conditional imports — none in the code base.
  - `[low]` `[reject]` (edge) path without `packages` segment — only called with scanned package paths.
  - `[low]` `[patch]` (edge) ShellRoute cast / unclear `No element` in registered-modules test — `whereType<GoRoute>()` and a reasoned `isNotEmpty` expectation.
  - `[low]` `[reject]` (edge) weaker file-count sanity threshold — the self-check cases prove the detector; the count only guards an empty scan.
  - `[low]` `[patch]` (edge) module path vs static app route collision assertion — added to the registered-modules test.
  - `[low]` `[patch]` (edge) orphaned comment — same as the stale comment row.
  - `[low]` `[reject]` (vg) no gaps — n/a.
  - `[false]` `[reject]` (intent) app imports the descriptor eagerly (not package-level deferral) — "loaded deferred" applies to the module code; the descriptor is a few lines of metadata the router needs at boot.
  - `[false]` `[reject]` (intent) no real code splitting on mobile — deferred components need build/Play config; documented as structural.
  - `[false]` `[reject]` (intent) app-side list instead of self-registration — Dart has no import-time side effects; an explicit list is the registry.
  - `[false]` `[reject]` (intent) slots reduced to action handlers — slot placement is server-driven (15.3 decision).
  - `[false]` `[reject]` (intent) pubspec purity not enforced — patched (pubspec check row).
  - `[false]` `[reject]` (intent) workspace-only standalone — `resolution: workspace` is the chosen model (15.4a).
  - `[false]` `[reject]` (intent) Riverpod `==` audit leaves no code trace — the moved code has no Notifier / `state =`; recorded here.
  - `[false]` `[reject]` (intent) tests do not prove app-to-package import edge — patched (app import rule row).

## Verification

**Commands:**
- `cd keevo/app && flutter pub get && dart run melos generate` -- expected: success.
- `cd keevo/app/packages/keevo_invoicing && flutter analyze && flutter test` -- expected: pass.
- `cd keevo/app && flutter analyze lib test` -- expected: 0 errors/warnings.
- `cd keevo/app && bash tool/test_chunked.sh` (+ keevo_core) -- expected: all chunks pass.
- `cd keevo/app && flutter build apk --debug` -- expected: success.

## Auto Run Result

Status: done

**Summary:** Invoicing now lives in `keevo/app/packages/keevo_invoicing` (0.1.0), depending only on `keevo_core` among Keevo packages. `keevo_core/lib/module/` adds the module contract (`KeevoModuleUi`, `ExtensionActionHandler`, `composeModuleRoutes`/`composeModuleActions` with duplicate checks) and ports (`apiDioProvider`, `fileSharerProvider`, `tempDirProvider`, clear `UnimplementedError` until wired). The package exposes `invoicingModuleUi` (route `/invoicing/:id` through `DeferredWidget`, « Facturer » handler loading its library `deferred as`). The app shell composes `kRegisteredModules` into the router and the action registry (eagerly checked at startup) and wires the ports in `buildRootScope` (caller overrides win). Boundary test covers every package (imports, pubspec deps, app imports only module entry files). CI analyzes/tests the package; extraction recipe in `packages/README.md`. Riverpod `==` audit: the moved code has no Notifier / `state =` — nothing to fix. App 2.0.13+85.

**Review:** 30 findings — 0 high/medium; 12 lows patched (duplicate route/module checks, manifest-id + static-route collision assertions, app→module entry-only imports, pubspec dependency check, port override conflict, stale comment, eager registration check, clearer deferred test, README grep); the rest rejected with reasons (`/invoicing` list route reserved for 15.5).

**Follow-up review recommended:** false — no medium/high patched.

**Verification:** `flutter pub get` + `dart run melos generate` OK; keevo_invoicing analyze clean, 20 tests pass; keevo_core chunked tests pass; app analyze 0 errors/warnings; app `tool/test_chunked.sh` all chunks pass; `flutter build apk --debug` OK. Not run: Docker, GitHub workflows, device.

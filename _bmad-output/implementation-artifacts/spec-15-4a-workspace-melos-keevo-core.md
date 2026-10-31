---
title: '15.4a — Melos workspace + keevo_core package'
type: 'refactor'
created: '2026-10-31'
status: 'done'
baseline_revision: 'a0c7576f895963a9824809c9855d92b12de96e56'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Flutter app is one package: its shared kernel (`lib/core`, 104 files) is mixed with app composition and imports features, so no module can be built as its own package (15.4c pilots `keevo_invoicing`) and nothing enforces that the kernel stays feature-free.

**Approach:** Turn `keevo/app` into a Melos 7 / Dart pub workspace root (the app stays at `keevo/app`, so CI, Dockerfile and release paths do not move) and extract the feature-independent kernel of `lib/core` into `keevo/app/packages/keevo_core`, which compiles, analyzes and tests on its own; the app consumes it via `package:keevo_core/...`. Purely structural: no behaviour change.

## Boundaries & Constraints

**Always:**
- Extraction rule (dependency closure): a `lib/core` file moves to `keevo_core` only if every file it imports (transitively) also moves and none of them imports `lib/features/**` or `package:keevo/...`. The 17 files that import features today (`core/router/app_router.dart`, `router/session_presence_cache.dart`, `di/providers.dart`, `scaffold/main_shell.dart`, `scaffold/shell_banners.dart`, `auth/session_refresher.dart`, `entitlements/entitlements_provider.dart`, `extension/extension_actions.dart`, `extension/extension_slot.dart`, `network/auth_interceptor.dart`, `notification/fcm_service.dart`, `photo/product_photo_providers.dart`, `sync/domain/sync_conflict_provider.dart`, `sync/rest_sync_service.dart`, `sync/sync_gate_guard.dart`, `sync/sync_monitoring_providers.dart`, `sync/sync_trigger_notifier.dart`) and whatever depends on them stay in the app as the "app shell" (`lib/core` keeps them). A small, behaviour-free split (e.g. moving a provider declaration that only needs Dio/prefs into the package) is allowed when it frees a whole folder; no logic rewrite.
- Expected to move (verify by the closure rule): `theme`, `utils`, `widget`, `storage` (Drift DB + generated code), `services`, most of `network`, `photo`, `sync` (all but the coupled files), `auth/jwt_claims.dart`, `entitlements/entitlements.dart`, `router` pure helpers that import nothing from features.
- Imports in the app (lib and test) are rewritten to `package:keevo_core/<path>.dart`; no re-export shim files left in `lib/core` for moved code.
- Tests of moved code move to `packages/keevo_core/test/` and pass there with `flutter test` run from the package; shared test helpers they need move or are duplicated minimally into the package's `test/helpers/`.
- Code generation (Drift `.g.dart`, `build.yaml` options) works in the package (`dart run build_runner build` from the package) and generated files stay committed as today.
- Melos: root `keevo/app/pubspec.yaml` gets `workspace: [packages/keevo_core]` and a `melos:` section with scripts `analyze` (each package) and `test` (each package, one at a time); packages declare `resolution: workspace`. `melos bootstrap` (= `flutter pub get` at the root) works.
- CI `.github/workflows/flutter-ci.yml`: also analyzes, generates code for and tests `packages/keevo_core`; release workflow and Dockerfile still build the APK (Docker build context `./app` contains `packages/`).
- `keevo/app/pubspec.yaml` version 2.0.10+82 → 2.0.11+83; `keevo_core` version 0.1.0, `publish_to: none`.
- An architecture test (app side, e.g. `test/architecture/core_package_boundary_test.dart`) fails if any file under `packages/keevo_core/lib` imports `package:keevo/` or a features path.

**Never:**
- No behaviour change, no renamed public symbols beyond import paths, no Riverpod migration (15.4b), no feature extraction (15.4c).
- No change to the Android/iOS application id, signing, flavors or `API_BASE_URL` handling.
- Never run the whole app test suite in one command: run test folders one at a time.

</intent-contract>

## Code Map

- `keevo/app/pubspec.yaml` -- app package `keevo` (version line 4); becomes workspace root.
- `keevo/app/lib/core/` -- 104 files; per-folder coupling: auth 1/2, di 1/1, entitlements 1/2, extension 2/2, network 1/5, notification 1/1, photo 1/5, router 2/7, scaffold 2/4, services 0/1, storage 0/28, sync 5/29, theme 0/1, utils 0/3, widget 0/13 (coupled/total, by `features/` imports — transitive imports through coupled core files also count).
- `keevo/app/build.yaml`, `analysis_options.yaml` -- copy relevant config into the package (Drift options, lints).
- `keevo/app/test/core/**`, `keevo/app/test/helpers/**` -- tests/helpers of moved code.
- `.github/workflows/flutter-ci.yml` -- `working-directory: keevo/app` steps (pub get, build_runner, analyze, test).
- `.github/workflows/release-flutter.yml`, `keevo/app/Dockerfile` -- APK build; check they still resolve the workspace (root `pub get`).
- Tooling: Melos is not installed globally; add `melos` as a root dev_dependency (Melos 7 reads the `melos:` key of the root pubspec) and run it with `dart run melos ...`.

## Tasks & Acceptance

**Execution:**
- `keevo/app/packages/keevo_core/` (new) -- `pubspec.yaml` (deps actually needed by moved files: flutter, riverpod, dio, drift, shared_preferences, flutter_secure_storage, intl, …; `resolution: workspace`), `analysis_options.yaml`, `build.yaml` if Drift needs it, `lib/` (moved files keeping their relative layout, e.g. `lib/storage/app_database.dart`), `test/`.
- `keevo/app/pubspec.yaml` -- `workspace:`, `keevo_core` dependency, `melos` dev dependency + `melos:` scripts, version 2.0.11+83.
- `keevo/app/lib/**`, `keevo/app/test/**` -- import rewrite to `package:keevo_core/...` (scripted, then `flutter analyze`).
- `keevo/app/test/architecture/core_package_boundary_test.dart` (new) -- boundary test.
- `.github/workflows/flutter-ci.yml` -- package steps.
- `keevo/app/README.md` -- short "Workspace" section: layout, `dart run melos bootstrap|analyze|test`, the closure rule and what stays in the app shell.

**Acceptance Criteria:**
- Given the Flutter repo, when `flutter pub get` runs at `keevo/app` (workspace bootstrap), then it resolves both packages, and `flutter analyze` + `flutter test` run inside `packages/keevo_core` pass on their own.
- Given the app, when it is analyzed and its test folders are run one at a time, then there are no new analyzer errors/warnings and every folder passes as before the move.
- Given a file under `packages/keevo_core/lib` importing the app or a feature, when tests run, then the boundary test fails.
- Given the release path, when `flutter build apk --debug` runs at `keevo/app`, then it succeeds.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 33 findings — high 0, medium 0, low 22, false 11, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) code generation runs app before core in CI/release/Docker/README — VG showed riverpod reads types from source so it works today; reordered core-first anyway behind one `melos run generate` script.
  - `[low]` `[patch]` (blind) two-step generation copy-pasted in 4+ places — single `generate` melos script used by CI/README; workflows/Docker call it or keep the same order.
  - `[low]` `[reject]` (blind) CI does not use chunked tests — CI already ran the whole suite before this story; chunking is a local-machine constraint.
  - `[low]` `[patch]` (blind/vg) coverage loses the moved kernel — core step runs with `--coverage` and its lcov is uploaded.
  - `[low]` `[reject]` (blind) package analyzed twice in CI — harmless duplicate.
  - `[low]` `[patch]` (blind) boundary guard ignores core `test/`; `depend_on_referenced_packages` — scan core `test/` too and enable the lint in the package.
  - `[low]` `[reject]` (blind) boundary regex loose / `> 50` sanity count — self-check of the detector exists; count adjusted only if files move.
  - `[low]` `[patch]` (blind) placeholder tests for app-shell classes moved into core — moved back to the app `test/core/sync`.
  - `[low]` `[patch]` (blind/vg) stale path comments (core files, backend `DeterministicIds.java:17`, dashboard `labels.ts:46,89`) — updated.
  - `[low]` `[patch]` (blind) SQLCipher requirement undeclared by core — documented in the package README / pubspec comment.
  - `[low]` `[reject]` (blind) duplicated dependency constraints — `flutter pub get` resolves one version for the whole workspace (single lockfile); no drift possible at resolution.
  - `[low]` `[patch]` (blind) app `build.yaml` still configures drift_dev — block removed if no Drift sources remain in the app.
  - `[low]` `[patch]` (blind/edge) `test_chunked.sh` unmatched glob / exit status / folders without tests — nullglob, if-then guards, explicit `exit 0`, skip folders without `*_test.dart`.
  - `[low]` `[patch]` (blind) import ordering infos in two moved tests — fixed.
  - `[low]` `[reject]` (edge) conditional imports bypass the boundary scan — none in the code base.
  - `[low]` `[reject]` (edge) depth computed from path prefix — `listSync` from the fixed relative root always yields that prefix.
  - `[low]` `[reject]` (edge) fonts dir without .ttf — repo layout fixed; error is explicit enough.
  - `[low]` `[patch]` (edge) asset mock handler never removed — `addTearDown` reset.
  - `[low]` `[patch]` (edge) Inter fonts are an app asset the core theme relies on — documented host requirement in the package README.
  - `[low]` `[patch]` (edge/vg) `.dockerignore` root-only patterns — `**/build/`, `**/.dart_tool/`.
  - `[false]` `[reject]` (edge claim) package cannot be tested on its own — it is tested from its own folder inside the workspace, the intended layout; fonts dependency documented.
  - `[low]` `[patch]` (intent) `melos bootstrap` not exercised by CI — CI installs dependencies with `dart run melos bootstrap`.
  - `[false]` `[reject]` (intent) partial move of `lib/core` (router, di, part of sync stay) — spec decision by dependency closure: those files import features; moving them would make the core depend on the app.
  - `[false]` `[reject]` (intent) no `melos.yaml` — Melos 7 reads the `melos:` key of the root pubspec.
  - `[false]` `[reject]` (intent) app not renamed `keevo_app` — renaming the package rewrites every `package:keevo/` import for no gain; "the app" reading.
  - `[false]` `[reject]` (intent) APK build not in CI — release workflow builds it; verified locally with `flutter build apk --debug`.
  - `[false]` `[reject]` (intent) SDK floor change untested — required by pub workspaces; resolution itself proves it.
  - `[false]` `[reject]` (intent) provider split runtime equivalence — bodies copied verbatim (vg checked against HEAD), single instance per provider.
  - `[false]` `[reject]` (intent) 15.4c cannot reach router/sync shell — the pilot package registers through the app shell (15.2/15.3 registries); recorded as 15.4c design input.
  - `[false]` `[reject]` (intent) `no_technical_text.dart` duplicated — minimal helper duplication allowed by the spec.
  - `[false]` `[reject]` (vg) no verification gaps — n/a.
  - `[false]` `[reject]` (vg other) Docker context size — covered by the dockerignore patch.
  - `[false]` `[reject]` (intent) bootstrap evidence only local `.iml` — covered by the CI bootstrap patch.

## Verification

**Commands:**
- `cd keevo/app && flutter pub get` -- expected: workspace resolves.
- `cd keevo/app/packages/keevo_core && flutter analyze && flutter test` -- expected: pass (package only).
- `cd keevo/app && flutter analyze lib test` -- expected: no errors/warnings (infos tolerated).
- `cd keevo/app && for d in test/*/ test/features/*/ ; do flutter test $d; done` (one folder per command) -- expected: all pass.
- `cd keevo/app && flutter build apk --debug` -- expected: success.

## Auto Run Result

Status: done

**Summary:** `keevo/app` is a Melos 7 / pub workspace root (config in the root pubspec: `workspace`, `melos:` scripts `generate` core-first, `analyze`, `test` via `tool/test_chunked.sh`). 79 of 104 `lib/core` files moved (git renames) to `packages/keevo_core` by the dependency-closure rule; `di/providers.dart` split verbatim into `keevo_core/lib/di/core_providers.dart` (kernel providers) and the app-shell remainder. The app shell keeps the feature-coupled files (router, DI wiring, REST sync service/triggers, auth interceptor, scaffold, extension, FCM, entitlements provider). Imports rewritten to `package:keevo_core/…` (no shims). Boundary test guards core `lib/` and `test/`. CI bootstraps with Melos, generates core-first, analyzes/tests both packages with coverage; release workflow + Dockerfile generate core-first. SDK floor 3.3 → 3.6 (pub workspaces).

**Deviations (by design):** router/DI/part of sync stay in the app (they import features); no `melos.yaml` (Melos 7 uses the root pubspec); app package keeps its name `keevo`; `.g.dart` files are gitignored (never were committed) and regenerated everywhere.

**Review:** 33 findings — 0 high/medium; 20 lows patched (generation order + single script, CI bootstrap + core coverage, boundary on core tests + lint, misplaced tests moved back, stale comments incl. backend/dashboard, host requirements README, app build.yaml drift block, chunk script robustness, import order, font mock teardown, dockerignore); the rest rejected with reasons.

**Follow-up review recommended:** false — no medium/high patched.

**Verification:** `flutter pub get` resolves the workspace; keevo_core `flutter analyze` (15 pre-existing infos) and `flutter test` 315 pass; app `flutter analyze lib test` 0 errors/warnings (infos 677 → 443); app tests one folder at a time: architecture 2, core 466, and every `test/features/*` folder pass (account 20 … team 128); `flutter build apk --debug` succeeds; `dart run melos generate` succeeds. Not run: Docker build, GitHub workflows, device.

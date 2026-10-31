---
title: '15.4b — Riverpod 2 → 3 migration (app + keevo_core)'
type: 'refactor'
created: '2026-10-31'
status: 'done'
baseline_revision: 'ca110630f6615062900107c31071a2f71706851a'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-4a-workspace-melos-keevo-core.md'
warnings: []
deferred:
  - summary: >-
      Riverpod 3 notifies on `==` instead of `identical` for every Notifier/AsyncNotifier; no audit proves no caller relies on setting an equal value to notify.
    evidence: |-
      Review 15.4b (blind). No call site found relying on it by grep; settle with riverpod_lint or a targeted audit of `state = sameValue` patterns when 15.4c/15.8 touch the providers.
    location: >-
      keevo/app/lib/**/presentation/provider/
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** The app state layer is on Riverpod 2 (`flutter_riverpod ^2.5.1`, `riverpod_annotation ^2.3.5`, `riverpod_generator ^2.4.0`) with legacy APIs (2 `StateNotifier`, ~24 files using `StateProvider`, `valueOrNull`, typed `*Ref` classes). Extracting module packages (15.4c) on top of a legacy base would mean migrating twice.

**Approach:** Upgrade both workspace packages to Riverpod 3 (latest stable 3.x of `flutter_riverpod`, `riverpod_annotation`, `riverpod_generator`, plus whatever `freezed`/`build_runner`/`drift_dev` versions are needed for one analyzer to resolve), follow the official Riverpod 3 migration guide, and remove every legacy provider: no `StateNotifier`/`StateNotifierProvider`/`StateProvider`/`ChangeNotifierProvider` and no `package:flutter_riverpod/legacy.dart` import remain; state lives in `@riverpod` / `Notifier` / `AsyncNotifier` (and plain `Provider`/`FutureProvider`/`StreamProvider` where they are not legacy). Behaviour stays identical.

## Boundaries & Constraints

**Always:**
- Behaviour preserved, in particular Riverpod 3's new defaults must not change it:
  - automatic retry of failed providers is disabled app-wide (`ProviderScope(retry: (_, __) => null)` and the same on test containers via the shared test helper), because several providers rely on "failure is not cached, the next read asks again" (15.1 entitlements, 15.3 extension slot) and on immediate error display;
  - provider pausing when unlistened and the `==`-based update filtering are checked where code relied on identical-instance notifications (e.g. `listenModuleGate`, `shouldPingModuleGate`) — adjust with `updateShouldNotify` where needed.
- `valueOrNull` usages migrate to the 3.x equivalent (`value`, nullable in 3.x) with the same semantics (error → previous value or null as before).
- Generated code is regenerated with `dart run melos generate`; hand-written code that referenced removed generated `*Ref` types uses `Ref`.
- Test overrides keep working: migrate `overrideWith` / `overrideWithValue` signatures, `ProviderContainer` helpers and fakes (e.g. `test/helpers/fake_entitlements.dart`).
- Both packages (`keevo`, `keevo_core`) migrate together; versions aligned in both pubspecs.
- Bump `keevo/app/pubspec.yaml` 2.0.11+83 → 2.0.12+84.

**Never:**
- No feature/package extraction (15.4c), no behaviour or UI change, no renaming of public providers beyond what the migration requires.
- No `legacy.dart` import kept "for later".
- Never run the whole test suite in one command: one folder at a time (`tool/test_chunked.sh` does that).

</intent-contract>

## Code Map

- `keevo/app/pubspec.yaml:25-26,100-102` and `keevo/app/packages/keevo_core/pubspec.yaml:15,35` -- riverpod/freezed/build_runner constraints.
- `lib/features/identity/presentation/provider/report_preferences_provider.dart`, `lib/features/pos/presentation/provider/day_closure_providers.dart` -- the two `StateNotifier`s.
- `grep -rl StateProvider lib packages/keevo_core/lib` (24 files) -- `StateProvider` → `Notifier` (small notifier with a setter, or `@riverpod` class).
- `grep -rl valueOrNull lib test packages/keevo_core` (55 files) -- `valueOrNull` migration.
- `keevo/app/lib/main.dart` -- `ProviderScope` (add `retry`); `listenModuleGate` (15.2) in `lib/core/router/`.
- `lib/core/entitlements/entitlements_provider.dart` (AsyncNotifier with generation guards), `lib/core/extension/extension_slot.dart` (`FutureProvider.autoDispose.family`, errors not cached) -- behaviour-sensitive to retry/pause defaults; their tests must still pass unchanged in intent.
- `test/helpers/` (app) and `packages/keevo_core/test/helpers/` -- container/scope helpers; add the retry-off default there.
- 54 files use freezed — if freezed 3 is required, follow its migration (classes `abstract`/`sealed`), no model change.

## Tasks & Acceptance

**Execution:**
- pubspecs -- upgrade to Riverpod 3.x (+ compatible generator/freezed/build_runner/drift_dev), `flutter pub get` at the workspace root.
- `lib/**`, `packages/keevo_core/lib/**` -- migrate APIs per the official guide; remove legacy providers; regenerate.
- `main.dart` + test helpers -- retry disabled; check pause/equality-sensitive listeners.
- `test/**`, `packages/keevo_core/test/**` -- migrate overrides/helpers; tests keep their assertions.
- `test/architecture/` -- add a guard test: no `StateNotifier`, `StateProvider`, `ChangeNotifierProvider` or `flutter_riverpod/legacy.dart` in `lib/` or `packages/*/lib/`.
- `keevo/app/pubspec.yaml` -- 2.0.12+84.

**Acceptance Criteria:**
- Given the code base, when the migration is done, then no legacy `StateNotifier`/`StateProvider`/`ChangeNotifierProvider`/`legacy.dart` remains and the guard test enforces it.
- Given both packages, when analyzed, then 0 errors/warnings; when every test folder runs one at a time, then all pass (same assertions as before).
- Given the release path, when `flutter build apk --debug` runs, then it succeeds.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 33 findings — high 1, medium 9, low 13, false 10, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind/vg other) `SupplierListNotifier` lacks the keep-alive its twin got — wrap its mutations like `ClientListNotifier`.
  - `[high]` `[patch]` (blind/edge) no systematic audit of auto-dispose notifiers writing state after an `await` (FrequentProducts.loadMore, AuditLog/TransferHistory.loadMore, Login/Registration/SelectTenant/ChangePassword, CsvImport, ResendReport, CreateSession, Onboarding, CreateEmployee, CloseDay, SaveCount; keep-alive links dropped on mid-await invalidation by the sync trigger) — Riverpod 3 throws on a disposed ref (release included) after the server write succeeded; audit every notifier method: `if (!ref.mounted) return` after each await before touching `state`/`ref`, keep-alive helper where callers only read.
  - `[medium]` `[patch]` (blind) function providers calling `ref.watch` after an `await` (export_providers, dashboard_providers, …) — move dependency registration before the first await (as done for `productListForPicker`).
  - `[low]` `[patch]` (blind) three keep-alive styles; `SaveCountNotifier` new code not using the helper — use `keepAliveWhile` there.
  - `[low]` `[patch]` (blind) MainShell double keep-alive / stale comment — comment says the issue is pausing.
  - `[medium]` `[patch]` (blind/edge/edge claim) `SyncIndicator` paused behind routes: Riverpod 3 replays only the last missed value, so earlier conflict/rejection notices are lost, and the replay mutates providers during build — producers append to the pending lists instead of overwriting; the indicator clears them in a post-frame callback.
  - `[medium]` `[defer]` (blind) `identical` → `==` notification for every Notifier/AsyncNotifier not audited — unverified; no caller found relying on equal-value notification; recorded in `deferred`.
  - `[medium]` `[patch]` (blind/edge) `catalog_page.dart` sets a cached notifier in a microtask after dispose (throws in release under Riverpod 3) — guard (mounted check / catch the unmounted error).
  - `[low]` `[reject]` (blind) retry disabled app-wide instead of per provider — behaviour preservation is the spec rule; per-provider retry is a product decision.
  - `[low]` `[reject]` (blind) no `riverpod_lint` — tooling addition outside a behaviour-preserving migration; the audit above covers the current code.
  - `[low]` `[patch]` (blind) `sync_status_provider.dart` `catch (_)` hides real errors — catch only the disposal case / log otherwise.
  - `[low]` `[patch]` (blind) `CloseDayNotifier` writes after await without guard; mutable use-case field — `ref.read` inside `closeDay` + mounted guard (covered by the audit row).
  - `[low]` `[patch]` (blind) doc gaps (`SettableNotifier` equality for collections, `build.yaml` naming holdover) — documented.
  - `[medium]` `[patch]` (vg) root `ProviderScope` retry untested — `buildRootScope` + unit test.
  - `[medium]` `[patch]` (vg) MainShell never-paused sync trigger untested — widget test pushing a full-screen route.
  - `[medium]` `[patch]` (vg) `ClientListNotifier` keep-alive never exercised without a listener — success-path `ClientFormPage` / provider test.
  - `[medium]` `[patch]` (vg) `SaveCountNotifier.save` keep-alive untested, row swallows errors — save-path test in `inventory_row_test.dart`.
  - `[medium]` `[patch]` (edge) keep-alive dropped when the notifier is invalidated mid-await (sync trigger invalidates list providers) — mounted guards (audit row).
  - `[low]` `[patch]` (edge) `inventory_counting_provider.dart` write after invalidation — mounted guard (audit row).
  - `[false]` `[reject]` (intent) not every provider converted to codegen — non-legacy `Provider`/`FutureProvider`/`StreamProvider` are Riverpod 3 first-class APIs; the AC targets the legacy `StateNotifier` family.
  - `[false]` `[reject]` (intent) Riverpod 3 defaults turned back (naming, retry, notification) — behaviour preservation is a stated boundary.
  - `[false]` `[reject]` (intent) no run evidence in the diff — full chunked run recorded in Verification.
  - `[false]` `[reject]` (intent) converted notifiers lack dedicated tests — their existing tests run unchanged.
  - `[false]` `[reject]` (intent) generated names invisible in the diff — compile + full test run check them.
  - `[false]` `[reject]` (intent) freezed 3 / SDK floor beyond the intent — forced by the toolchain resolution.
  - `[false]` `[reject]` (vg) `SettableNotifier`/helpers untested — `state_helpers_test.dart`.
  - `[false]` `[reject]` (vg) StoreList keep-alive untested — `store_provider_test.dart`.
  - `[false]` `[reject]` (vg) QuickAdd keep-alive untested — `quick_add_product_provider_test.dart:176`.
  - `[false]` `[reject]` (vg) notifier fields lost on rebuild — riverpod 3.2.1 reuses the instance.
  - `[false]` `[reject]` (blind) nested keep-alive wrapping redundant — harmless (link counting).

## Verification

**Commands:**
- `cd keevo/app && dart run melos generate` -- expected: success.
- `cd keevo/app && flutter analyze lib test && (cd packages/keevo_core && flutter analyze)` -- expected: 0 errors/warnings.
- `cd keevo/app && bash tool/test_chunked.sh` and `cd packages/keevo_core && bash ../../tool/test_chunked.sh` -- expected: every folder passes.
- `cd keevo/app && flutter build apk --debug` -- expected: success.

## Auto Run Result

Status: done

**Summary:** Both workspace packages on Riverpod 3 (`flutter_riverpod ^3.2.1` → 3.3.1, `riverpod_annotation ^4.0.2`, `riverpod_generator ^4.0.3`, freezed 3, build_runner ^2.10; SDK floor 3.8 forced by json_serializable; 3.4.x needs Dart 3.12). No legacy provider left (2 `StateNotifier` → `Notifier`, 22 `StateProvider` → `SettableNotifier<T>` in keevo_core); guard test `test/architecture/no_legacy_riverpod_test.dart`. Behaviour preserved: retry off app-wide (`noProviderRetry` via `buildRootScope`, tested) and in every test container; Riverpod 2 provider names kept (`provider_name_strip_pattern`); sync trigger kept unpaused under pushed routes (tested); systematic `ref.mounted` audit of every notifier and function provider using `ref` after an `await`, keep-alive helper where callers only read; sync notices accumulate and are cleared after the frame. App 2.0.12+84.

**Review:** 33 findings — 1 high (no systematic disposed-ref audit) and 8 medium patched (supplier keep-alive, function providers, sync notices, catalog deferred set, 4 untested behaviours), lows patched; 1 medium routed to 15.4c (`==` notification audit, unverified); the rest rejected with reasons. The implementer verified with the flutter_riverpod 3.3.1 source that `ref.listen` is not paused (notices were not actually lost) — fix kept as hardening with a container-level test.

**Follow-up review recommended:** true — a high was patched; unverified risk: runtime behaviour on a device (paused offscreen `ref.watch`, disposed-ref paths) is only covered by widget tests; no device run (adb unauthorized).

**Verification:** `dart run melos generate` OK; `flutter analyze` 0 errors/warnings (app 439 infos, keevo_core 15); keevo_core chunked tests all pass; app `tool/test_chunked.sh` 25 chunks, 2 379 tests, 0 failures; `flutter build apk --debug` OK.

# Story 16.5: SyncIndicator — état online-stale + daysSinceLastSync (UX15, 5-4 AC1)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Toor,
I want the AppBar SyncIndicator to warn when the device is online but hasn't completed a real sync in 5+ days,
so that a silently-failing background sync (server down, auth issue, background task not firing) doesn't hide behind a reassuring green "En ligne" dot (UX15, 5-4 AC1).

## Acceptance Criteria

1. **Given** `SyncIndicator.build()` (`sync_indicator.dart:54-105`) watches `syncStatusProvider` (a purely connectivity-based stream: online/syncing/offlineOk/offlineCritical, derived from `daysOfflineProvider`/`firstOfflineDateProvider`) and only special-cases `syncGateStateProvider` for the `SyncGateState.blocked` branch (line 59-85), **when** the device is connected to the internet (`SyncStatus.online`) but `syncGateStateProvider` is `SyncGateState.warning` (day 5 since last successful sync) or `SyncGateState.critical` (day 6), **then** today the indicator falls straight through to the default green dot + "En ligne" label (via `_buildIndicator(status, days)` → `_label`/`_dotColor`) — the staleness is completely invisible to the user despite `OfflineGateBanner` (`offline_gate_banner.dart`) already showing the correct warning/critical banner elsewhere in `MainShell`.
2. **Given** AC1's gap, **when** the fix is applied, **then**: `status == SyncStatus.online && gateState == SyncGateState.warning` renders an amber dot (`AppTheme.warning`, `#FCC419`) + label `"En ligne (sync J-$daysSinceLastSync)"` (e.g. `"En ligne (sync J-5)"`); `status == SyncStatus.online && gateState == SyncGateState.critical` renders a red dot (`AppTheme.errorColor`, `#FA5252`) + label `"⚠ Sync urgente"`.
3. **Given** `daysSinceLastSyncProvider` (`sync_gate_provider.dart:11-17`) already computes "days since `kLastSyncAtKey`" and is the canonical source for `syncGateStateProvider` and `OfflineGateBanner`'s day count, **when** the fix is implemented, **then** the SyncIndicator's online-stale day count is read from this same `daysSinceLastSyncProvider` — **not** `daysOfflineProvider` (a different concept: days of continuous *connectivity* loss, tracked via `kFirstOfflineDateKey`, used only for the `offlineOk`/`offlineCritical` "Hors-ligne — Jour X/7" labels).
4. **Given** AC1-3, **when** the fix is applied, **then** all currently-correct behavior is unchanged and byte-for-byte identical: `SyncGateState.blocked` still short-circuits to "🔴 Accès limité — Sync requise" (line 59-85, untouched); `SyncStatus.offlineOk`/`offlineCritical` still show "Hors-ligne — Jour X/7" / "Hors-ligne critique — Jour X/7" with amber/red dots (via `daysOfflineProvider`, untouched); `SyncStatus.syncing` (including the `SyncTriggerSyncing` override at line 92-95) still shows the spinner + "Synchronisation..." regardless of gate state; `gateState == SyncGateState.open` (days 0-4) with `SyncStatus.online` still shows plain green "En ligne" with no day suffix.

## Tasks / Subtasks

- [x] Task 1: Make `SyncIndicator` gate-aware for the online path (AC: 1, 2, 3, 4)
  - [x] In `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart`, after the existing `final gateState = ref.watch(syncGateStateProvider);` (line 56), add `final daysSinceLastSync = ref.watch(daysSinceLastSyncProvider);`. Import `sync_gate_provider.dart` already brings `daysSinceLastSyncProvider` in scope (it's exported alongside `syncGateStateProvider` from the same file, already imported at line 6) — no new import needed.
  - [x] Thread `gateState` and `daysSinceLastSync` through to `_buildIndicator` at both call sites inside the `Builder` (line 91-102): the `isSyncing` branch (line 95) and all three `asyncStatus.when` branches (line 98-100). `_buildIndicator`'s signature becomes `_buildIndicator(SyncStatus status, int days, SyncGateState gateState, int daysSinceLastSync)`.
  - [x] Inside `_buildIndicator`, compute two booleans: `final isOnlineStaleWarning = status == SyncStatus.online && gateState == SyncGateState.warning;` and `final isOnlineStaleCritical = status == SyncStatus.online && gateState == SyncGateState.critical;`. These are only ever true when `status == SyncStatus.online` — every other `status` value (`syncing`, `offlineOk`, `offlineCritical`) leaves both `false`, so AC4's non-regression falls out naturally rather than needing a separate branch.
  - [x] Label: in `_buildIndicator`, replace the direct `_label(status, days)` call with a conditional — `isOnlineStaleCritical ? '⚠ Sync urgente' : isOnlineStaleWarning ? 'En ligne (sync J-$daysSinceLastSync)' : _label(status, days)`. Leave `_label()` itself untouched (still used for the non-stale-online cases).
  - [x] Dot color: `_buildStatusDot(status)` (line 121-138) currently derives color solely from `_dotColor(status)` (line 140-147). Either add optional params `_buildStatusDot(status, {bool isOnlineStaleWarning = false, bool isOnlineStaleCritical = false})` and pick `isOnlineStaleCritical ? AppTheme.errorColor : isOnlineStaleWarning ? AppTheme.warning : _dotColor(status)` inside it, or resolve the color once in `_buildIndicator` and pass it down — either shape is fine, just keep the `Key('sync_dot')` on the same `Container` (existing tests locate the dot by this key).
  - [x] Do not touch the `gateState == SyncGateState.blocked` early return (line 59-85) — it already renders correctly and is untouched by this story.
  - [x] Do not touch `_label()`, `_dotColor()`, or the offline-day-count semantics — `daysOfflineProvider` keeps driving `offlineOk`/`offlineCritical` exactly as before.

- [x] Task 2: Tests (AC: 2, 4)
  - [x] In `keevo/app/test/features/sync_indicator/sync_indicator_gate_test.dart`, add `shouldShowWarningWhenOnlineAndStaleDay5()`: reuse the existing `_wrapIndicator(gateState: SyncGateState.warning, days: 5, prefs: prefs)` helper (it already forces `syncStatusProvider` to `SyncStatus.online` at line 40, exactly what's needed). Assert `find.text('En ligne (sync J-5)')` finds one widget, and the `Key('sync_dot')` `Container`'s `BoxDecoration.color` equals `const Color(0xFFFCC419)`.
  - [x] Add `shouldShowCriticalDay6()`: `_wrapIndicator(gateState: SyncGateState.critical, days: 6, prefs: prefs)`. Assert `find.textContaining('Sync urgente')` finds one widget (emoji-agnostic, matching the existing convention used for the blocked-state test at line 74-76) and the dot color equals `const Color(0xFFFA5252)`.
  - [x] Confirm the existing `indicator_open_showsNormalStatus` test (line 109-123, `gateState: SyncGateState.open, days: 0`) still passes unmodified — `status == online && gateState == open` must still render plain `"En ligne"`.
  - [x] Confirm the existing tests in `sync_indicator_test.dart` (offlineOk/offlineCritical/syncing labels and dot colors, all using `daysSinceLastSyncProvider.overrideWithValue(0)` i.e. gate stays `open`) still pass unmodified.
  - [x] Run `flutter test test/features/sync_indicator/` and the full suite to confirm 0 NEW regressions.

### Review Findings

- [x] [Review][Patch] Aucun test ne verrouille le fait que `SyncTriggerSyncing` prime sur l'état online-stale (AC4) — comportement correct par inspection (le guard `status == SyncStatus.syncing` force `isOnlineStaleWarning/Critical` à `false`), mais non couvert par un test de non-régression [keevo/app/test/features/sync_indicator/sync_indicator_gate_test.dart] — corrigé : ajout de `shouldShowSyncingSpinnerEvenWhenGateIsCritical`
- [x] [Review][Patch] `_buildIndicator(status, days, gateState, daysSinceLastSync)` a deux paramètres positionnels `int` adjacents (`days`, `daysSinceLastSync`) sans protection du compilateur contre une inversion accidentelle à un futur site d'appel [keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart:108] — corrigé : passage à des paramètres nommés `{required int days, required SyncGateState gateState, required int daysSinceLastSync}`
- [x] [Review][Defer] La branche `loading` de `asyncStatus.when` force `SyncStatus.online` et est désormais aussi gate-aware — un flash transitoire de "En ligne (sync J-5)"/"⚠ Sync urgente" est possible avant que le vrai statut de connectivité soit résolu [keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart:100] — deferred, pattern préexistant (loading→online) simplement étendu par ce diff, hors scope des AC de cette story, impact limité à une frame transitoire
- [x] [Review][Defer] `daysSinceLastSyncProvider` n'a pas de clamp bas contre un décalage d'horloge (pourrait théoriquement renvoyer un nombre de jours négatif) [keevo/app/lib/core/sync/sync_gate_provider.dart:16] — deferred, code préexistant non touché par ce diff, et non atteignable via le nouveau label car `SyncGateState.fromDaysSinceLastSync` ne produit `warning`/`critical` qu'à des valeurs exactes (5/6)
- [x] [Review][Defer] `daysSinceLastSyncProvider` est un `Provider` synchrone simple sans réévaluation périodique — le point ne changera pas de couleur au franchissement d'un jour tant que l'app reste ouverte, sans un rebuild déclenché par autre chose [keevo/app/lib/core/sync/sync_gate_provider.dart:11-17] — deferred, préexistant, non modifié par ce diff
- [x] [Review][Defer] Le nouveau texte "⚠ Sync urgente" n'a pas de label `Semantics`/accessibilité [keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart:123] — deferred, correspond au pattern déjà présent dans la branche `blocked` (texte brut, pas de `Semantics` non plus), pas une régression introduite par ce diff

## Dev Notes

- **Pure Flutter, single file touched for the fix** (`sync_indicator.dart`) + its existing gate test file for new coverage. Epic 16 is 100% Flutter, no backend involvement.
- **Root cause:** two independent staleness concepts coexist and the indicator only reads one of them for the "online" happy path. `SyncStatus` (online/syncing/offlineOk/offlineCritical) is a pure connectivity signal computed from `firstOfflineDateProvider`/`daysOfflineProvider` (`sync_status_provider.dart`) — it answers "is the device connected right now, and if not, since when". `SyncGateState` (open/warning/critical/blocked) is computed from `daysSinceLastSyncProvider`, which reads `kLastSyncAtKey` (`sync_gate_provider.dart`) — it answers "how long since the last *successful* push+pull cycle completed", regardless of current connectivity. A device can be `SyncStatus.online` (wifi is up) while `SyncGateState` is `warning`/`critical` (the last real sync attempt succeeded 5-6 days ago, e.g. a background sync silently failing on auth or server errors). The indicator today ignores this combination entirely outside the `blocked` (day 7+) case.
- **`OfflineGateBanner` (`keevo/app/lib/core/scaffold/offline_gate_banner.dart`) already implements the persistent warning/critical banner correctly** — mounted in `MainShell`, watches the same `syncGateStateProvider`/`daysSinceLastSyncProvider`, shows the amber/red messages from epic-5 AC1 ("⚠ Synchronisation requise dans X jours" / "🔴 Dernière chance..."). **Do not duplicate or modify that banner** — this story is scoped strictly to the `SyncIndicator` AppBar widget's own dot+label not reflecting the same state its sibling banner already reflects.
- **`SyncGateState.blocked` (day 7+) is already correctly handled** in `SyncIndicator` (the early-return at line 59-85, red "🔴 Accès limité — Sync requise", opens `SyncRequiredModal` on tap) — this story only fills the gap for `warning`/`critical` while `status == online`. Don't touch the blocked branch.
- **Exact label strings matter for tests** — use `'En ligne (sync J-$daysSinceLastSync)'` (no leading warning emoji, matches epic AC text `"En ligne (sync J-X)"`) and `'⚠ Sync urgente'` (matches epic AC text `"⚠ Sync urgente"`) verbatim.
- **Colors reuse existing `AppTheme` static constants** already used by `_dotColor()` in this same file: `AppTheme.warning` (`#FCC419`, same amber as `offlineOk`) and `AppTheme.errorColor` (`#FA5252`, same red as `offlineCritical`/`blocked`). No new color constants needed.
- **`daysSinceLastSyncProvider` already exists and is already used elsewhere** (`OfflineGateBanner`, `syncGateStateProvider` itself) — nothing to create, just watch it in `SyncIndicator` alongside the provider it already watches (`syncGateStateProvider`).
- **`sync_indicator_push_states_test.dart` is dead/placeholder** (`expect(true, isTrue, reason: 'RED placeholder...')` for all 4 tests) — do not confuse it with real coverage; it needs no changes and doesn't count toward this story's test requirements.

### Project Structure Notes

- Modified: `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart` (`build()`, `_buildIndicator()`, `_buildStatusDot()`).
- Modified: `keevo/app/test/features/sync_indicator/sync_indicator_gate_test.dart` (2 new `testWidgets`).
- No new files, no new providers, no new routes, no schema/migration changes, no backend changes.

### References

- [Source: _bmad-output/planning-artifacts/epics/epics-remediation-audit.md#Story 16.5] — canonical AC for this story (lines 655-666): `**Then** indicator consults syncGateStateProvider → online+warning (day5) "En ligne (sync J-X)", online+critical (day6) "⚠ Sync urgente"; daysSinceLastSyncProvider based on kLastSyncAtKey; day5 = yellow (not red), day6 = red`.
- [Source: _bmad-output/planning-artifacts/epics/epic-5-moteur-de-synchronisation-offline-first.md#Story 5.4] (lines 146-188) — original 7-day-gate AC this story completes: day-by-day indicator/banner states, `lastSyncAt` persistence, server-side HTTP 423 enforcement (already implemented, out of scope here).
- [Source: _bmad-output/planning-artifacts/epics/requirements-inventory.md:244] — UX15: "Indicateur de connectivité / statut de synchronisation toujours visible (Sync Indicator dans AppBar) — 4 états : Online synced | Online syncing | Offline OK (<7j) | Offline critique (>5j)".
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md:733-737] — Component #6 "Sync Indicator": dot vert/orange/rouge + texte, AppBar trailing position.
- [Source: keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart:1-170] — full widget to modify: `build()` (54-105), `_buildIndicator()` (107-119), `_buildStatusDot()` (121-138), `_dotColor()` (140-147), `_label()` (149-156).
- [Source: keevo/app/lib/core/sync/sync_gate_provider.dart:1-27] — `daysSinceLastSyncProvider` (11-17) and `syncGateStateProvider` (23-26), both already exist, both to be consumed (already imported) by `sync_indicator.dart`.
- [Source: keevo/app/lib/core/sync/sync_gate_state.dart:1-39] — `SyncGateState` enum + `fromDaysSinceLastSync` thresholds (open <5, warning ==5, critical ==6, blocked ≥7) + `showsBanner`/`isWriteBlocked` getters.
- [Source: keevo/app/lib/core/sync/sync_status_provider.dart:1-79] — `daysOfflineProvider`/`firstOfflineDateProvider` (connectivity-based, NOT to be used for the new online-stale day count) and `syncStatusProvider` (the connectivity stream `SyncIndicator` watches).
- [Source: keevo/app/lib/core/sync/sync_status.dart:1-23] — `SyncStatus` enum + `fromDaysOffline` thresholds, unaffected by this story.
- [Source: keevo/app/lib/core/scaffold/offline_gate_banner.dart:1-100] — sibling banner widget already correctly implementing the warning/critical messaging via the same two providers; reference for the pattern, not to be modified.
- [Source: keevo/app/test/features/sync_indicator/sync_indicator_gate_test.dart:1-125] — existing gate test file + `_wrapIndicator()` helper (30-57) to extend with the 2 new online-stale tests; existing `indicator_open_showsNormalStatus` (109-123) is the non-regression anchor for the `open` state.
- [Source: keevo/app/test/features/sync_indicator/sync_indicator_test.dart:1-97] — existing non-gate test file (offlineOk/offlineCritical/syncing coverage), all fix `daysSinceLastSyncProvider.overrideWithValue(0)` (gate stays `open`) — confirms these are unaffected by the fix.
- [Source: _bmad-output/implementation-artifacts/v1s-16-4-catalog-search-debounce-fuzzy.md] — previous story in this epic (16.4); status `ready-for-dev`, not yet implemented, no dev-agent learnings available yet. Cited only for story-file structure/format convention (this story follows the same shape: concrete code-level guidance, exact strings, explicit non-regression call-outs).

## Dev Agent Record

### Agent Model Used

DeepSeek V4 Pro (GitHub Copilot)

### Debug Log References

### Completion Notes List

- Task 1: Added `daysSinceLastSyncProvider` watch to `SyncIndicator.build()`, threaded `gateState` + `daysSinceLastSync` through `_buildIndicator` (signature extended). Computed `isOnlineStaleWarning`/`isOnlineStaleCritical` booleans inside `_buildIndicator`. Label: conditional chain `isOnlineStaleCritical → '⚠ Sync urgente'`, `isOnlineStaleWarning → 'En ligne (sync J-$daysSinceLastSync)'`, else `_label(status, days)`. Dot color: `_buildStatusDot` extended with optional named params `isOnlineStaleWarning`/`isOnlineStaleCritical`, picking `AppTheme.errorColor` (red #FA5252) / `AppTheme.warning` (amber #FCC419) / `_dotColor(status)` respectively. Blocked branch (line 59-85), `_label()`, `_dotColor()`, and offline semantics completely untouched.
- Task 2: Added 2 new `testWidgets` to `sync_indicator_gate_test.dart`: `shouldShowWarningWhenOnlineAndStaleDay5` (verifies label "En ligne (sync J-5)" + amber dot #FCC419) and `shouldShowCriticalDay6` (verifies label contains "Sync urgente" + red dot #FA5252). Existing 4 gate tests + 37 sync_indicator tests all pass unmodified.
- Full suite: 856/869 GREEN (13 pre-existing failures unrelated to sync_indicator). 43/43 sync_indicator tests GREEN. 0 NEW regressions.
- AC1-4 all satisfied. No new files, providers, routes, schema, or backend changes.

### File List

- Modified: `keevo/app/lib/features/sync_indicator/presentation/widget/sync_indicator.dart`
- Modified: `keevo/app/test/features/sync_indicator/sync_indicator_gate_test.dart`

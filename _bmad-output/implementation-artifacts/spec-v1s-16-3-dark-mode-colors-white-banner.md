---
title: 'v1s-16-3 — Dark mode: replace hard-coded colors with theme tokens + readable offline banner'
type: 'bugfix'
created: '2026-10-12'
status: 'done'
baseline_revision: 'fa52977885c40874a0662c666195d54f92806365'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Success SnackBars on the AppTheme.success fill pair a white icon (about 2:1) with text inheriting onInverseSurface (dark in dark mode).
    evidence: |-
      Pre-existing (icon was Colors.white before); _snackBarTheme sets no contentTextStyle. Routed to v1s-16-17-feedback-fill-contrast.
    location: >-
      keevo/app/lib/features/catalog/presentation/page/catalog_page.dart:170
    severity: medium
  - summary: >-
      POS speed-dial draws a white icon on AppTheme.warning and on cs.onSurfaceVariant when the day is closed.
    evidence: |-
      Pre-existing white icon; about 1.6:1 on #FCC419. Routed to v1s-16-17-feedback-fill-contrast.
    location: >-
      keevo/app/lib/features/pos/presentation/widget/pos_speed_dial.dart:257
    severity: medium
  - summary: >-
      Disabled checkout / cart CTA keeps a white label on cs.outlineVariant.
    evidence: |-
      Pre-existing white label (about 1.7:1). Routed to v1s-16-17-feedback-fill-contrast.
    location: >-
      keevo/app/lib/features/pos/presentation/page/checkout_page.dart:299
    severity: low
  - summary: >-
      Dark colorError stays #FA5252 while the UX spec table says #FF6B6B.
    evidence: |-
      Deliberate (Design Notes): errorColor is used as a fixed fill under white. Routed to v1s-16-17-feedback-fill-contrast.
    location: >-
      keevo/app/lib/core/theme/app_theme.dart
    severity: low
---

<intent-contract>

## Intent

**Problem:** After the UI-3 rebrand `keevo/app/lib` still holds 92 `Colors.white` (+2 `Colors.white70`), 46 `Color(0x…)` literals, 5 `Colors.teal`, 1 `Colors.black54` and 3 `Colors.primaries` outside `lib/core/theme/app_theme.dart` (recount 2026-10-12). They ignore brightness, so dark mode shows wrong foregrounds (e.g. white spinner on the sky-blue dark `primary`) and off-palette colors; `AppTheme.dark()` does not set the UX-spec dark tokens; the day-5/day-6 offline gate banner uses amber/red icons and red text whose contrast on their container is < 3:1, and `test/core/sync/offline_gate_banner_test.dart` fails (expects `#FFF3BF`, widget renders `warningContainer` `#FFF9DB`).

**Approach:** Every color outside the theme file resolves through `Theme.of(context).colorScheme`, the `KeevoColors` extension or a named `AppTheme` token; new tokens are added only in `app_theme.dart`. The banner uses container/on-container token pairs that pass WCAG AA in light and dark. A guard test forbids color literals outside the theme file.

## Boundaries & Constraints

**Always:**
- Foreground follows its background: on `cs.primary` → `cs.onPrimary`; on `cs.error` → `cs.onError`; on `cs.secondary` → `cs.onSecondary`; on a fixed brand fill that does not change with brightness (`AppTheme.primary`, `primaryGradient`, `primaryGradientDark`, `successGradient`, `AppTheme.success`/`errorColor`/`darkSurface` fills, avatar palette) → new constant `AppTheme.onBrand` (white, defined in the theme file); translucent variants use `.withValues(alpha: …)` on the token (never `withOpacity`).
- `AppTheme.dark()` sets the UX-spec dark tokens: `onPrimary #0D1B2A`, `primaryContainer #1B3A5C`, `onSurface #F0F4F8`, `onSurfaceVariant #ADB5BD`, `surfaceContainerHighest #1B2838` (M3 successor of `surfaceVariant`); `onError` is set explicitly to `AppTheme.onError` in both themes. `AppTheme.light()` sets `onPrimary` to `#FFFFFF` explicitly.
- Remaining `Color(0x…)` literals move into `app_theme.dart` as named tokens (constants, or `KeevoColors` getters when a dark counterpart exists — reuse the dark value already present in the widget's `isDark` ternary). Merge near-identical hues into one token (e.g. `#E67700/#E65100/#F08C00/#F59E0B/#F9A825` → one warning-strong token; `#2B8A3E/#37B24D/#40C057/#22C55E` → one success-strong token); reuse existing tokens where equal or same intent (`#339AF0` = `AppTheme.iconBlue`, `#D0EBFF` = `AppTheme.iconBlueBg`, `#F59F00` = `iconAmber`).
- `Colors.transparent` stays allowed everywhere.
- Every touched `keevo/app` commit bumps `pubspec.yaml` `1.1.26+25` → `1.1.27+26`.

**Never:**
- No literal colors (`Colors.<anything but transparent>`, `Color(0x…)`, `Color.fromARGB/fromRGBO`) in `lib/` outside `lib/core/theme/app_theme.dart`.
- Do not edit a test expectation to match a bug; test expectations derive from the UX spec token table.
- No layout/behavior change beyond colors; no new packages; no `flutter build/clean`; never run `flutter test` on the whole suite at once.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Warning banner, light | gate `warning`, day 5, light theme | bg `warningContainer` `#FFF9DB` (≈ spec "#FCC419 at 15%"); text + icon `onWarningContainer` = `AppTheme.onWarning` `#4A2C00`; contrast ≥ 4.5:1 | n/a |
| Warning banner, dark | gate `warning`, dark theme | bg `#462F00`; text + icon `#FFE8A3`; contrast ≥ 4.5:1 | n/a |
| Critical banner, light | gate `critical`, day 6 | bg `errorContainer_` `#FFE3E3`; text + icon `onErrorContainer_` `#8C1D1D`; ≥ 4.5:1; no dismiss | n/a |
| Critical banner, dark | gate `critical`, dark theme | bg `#5C1A1A`; text + icon `#FFC9C9`; ≥ 4.5:1 | n/a |
| Guard | any `lib/**.dart` except theme file contains a forbidden literal | guard test fails naming `file:line` | n/a |

</intent-contract>

## Code Map

- `keevo/app/lib/core/theme/app_theme.dart` -- only place for literals. Add `AppTheme.onBrand`, medal/avatar palette constants, consolidated strong success/warning tokens, `KeevoColors.onWarningContainer` (light `AppTheme.onWarning`, dark `#FFE8A3`) and `onErrorContainer_` (light `#8C1D1D`, dark `#FFC9C9`); set dark/light scheme tokens (see Always).
- `keevo/app/lib/core/scaffold/offline_gate_banner.dart:57-66` -- replace text/icon colors with the on-container tokens (drop `AppTheme.iconAmber`/`errorColor`/`isDark` branch).
- `keevo/app/test/core/sync/offline_gate_banner_test.dart` -- warning bg expectation `#FFF3BF` → `#FFF9DB` (theme token / spec 15 % amber; `#FFF3BF` is neither); add `shouldRenderReadableOfflineBannerInDarkMode` (+ light) computing WCAG contrast (text & icon vs bg ≥ 4.5) for warning and critical with `theme: AppTheme.light()/dark()` (keep `GoogleFonts.config.allowRuntimeFetching = false`).
- Occurrences (run `grep -rnE "Colors\.|Color\(0x" lib | grep -v core/theme`): `lib/core/widget/lang_toggle.dart`; features `inventory` (launch, validation overlay, global stock, transfer history, transfer form, apply adjustments, product_detail_bottom_sheet/gap_row_tile/inventory_row `Colors.primaries` → `AppTheme.avatarPalette`), `contact` (client/supplier form & list snackbars), `stores`, `notifications` (bell badge on `cs.error` → `onError`), `dashboard/sales_evolution_chart`, `pos` (pos_page, checkout, sales_history date-picker theme `onPrimary`, day_close overlay, product_card, product_initials_avatar palette, cart_pill on `darkSurface` → `onBrand`, cart_bottom_sheet, speed dial, sale_success, pending_sales), `catalog` (csv import, categories, stock entry, catalog_page incl. `Colors.teal`, product_form_page header on gradient, product_card snackbars, draft sheet), `auth` & `onboarding` (FilledButton spinners on `cs.primary` → `onPrimary`; check icons), `profitability/store_performance_row` (gold/silver/bronze → tokens), `sync_indicator`, `team` (employee_card, temp_password sheet), `settings` (settings, report_preferences, subscription, account), `onboarding/domain/model/sector_type.dart` (`#D0EBFF` → `AppTheme.iconBlueBg`), `reports/report_history_card`.
- SnackBars: background decides (`cs.error` → `cs.onError`; `AppTheme.primary`/`success` fixed fills → `AppTheme.onBrand`).
- `Colors.black54` barrier → `cs.scrim.withValues(alpha: 0.54)`.

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/theme/app_theme.dart` -- add tokens + scheme values -- single source of truth.
- `keevo/app/lib/core/scaffold/offline_gate_banner.dart` -- token pairs -- readable banner.
- every file listed in Code Map -- replace literals by rule -- dark-mode correctness.
- `keevo/app/test/core/theme/no_hardcoded_colors_test.dart` (new) -- walk `lib/` recursively, skip `lib/core/theme/app_theme.dart`, strip `//` comments, fail with `file:line` list on regex `Colors\.(?!transparent\b)[a-zA-Z]` or `Color\(0x` or `Color\.from(ARGB|RGBO)` -- regression guard.
- `keevo/app/test/core/theme/app_theme_test.dart` (new or extend existing) -- assert dark scheme tokens from the spec table and light `onPrimary` white.
- `keevo/app/test/core/sync/offline_gate_banner_test.dart` -- as Code Map.
- existing widget tests asserting `Colors.white` on a changed widget -- update only when the new token is the spec-correct value in that test's theme (light default ⇒ `onPrimary`/`onBrand` are still white, so most need no change).
- `keevo/app/pubspec.yaml` -- version `1.1.27+26`.

**Acceptance Criteria:**
- Given the repo, when the guard test runs, then it passes and the grep in Code Map returns only `Colors.transparent`.
- Given dark mode, when a `FilledButton`/`cs.primary` fill shows a spinner/label, then it uses `cs.onPrimary` (`#0D1B2A`).
- Given `test/core`, when run, then 0 failures.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 30 findings — high 0, medium 13, low 15, false 2, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) Loading spinners use navy onPrimary on the disabled FilledButton fill in dark mode — 14 spinners (13 + stock_entry) now `cs.onSurfaceVariant`; every site verified `onPressed: loading ? null : …`.
  - `[low]` `[patch]` (blind) successStrong #2B8A3E under onBrand is 4.37:1 — token darkened to #2B7A3B; test `onBrand is AA on the successStrong fill`.
  - `[medium]` `[patch]` (blind) warningStrong #E67700 is 3.0:1 as text — token darkened to #A85500; AA test on surface (light/dark).
  - `[medium]` `[patch]` (blind) Inventory avatar initials lost shade700 contrast — `AppTheme.avatarInitials(accent, brightness)` (lerp 35 % to black/white); AA test for every palette entry in both modes.
  - `[low]` `[patch]` (blind) Fixed AppTheme.warningStrong/successStrong used on theme surfaces — catalog product_card, employee_card ListTile icons, subscription Premium and checkout `_changeColor` now use `cs.warningStrong`/`cs.successStrong`.
  - `[medium]` `[defer]` (blind) Success SnackBar icon/text mismatch on AppTheme.success — pre-existing; routed → v1s-16-17-feedback-fill-contrast.
  - `[low]` `[patch]` (blind) Readability test pumps two scenarios in one tree — split into 4 testWidgets.
  - `[low]` `[reject]` (blind) Contrast test force-unwraps colors / nearest Container — fails loudly if structure changes; not a silent pass.
  - `[low]` `[patch]` (blind) Guard misses Color.from(, decimal Color(, CupertinoColors, MaterialColor/ColorSwatch, `//` in URLs, wrong cwd — regex widened, comment = `//` at line start or after whitespace, asserts >100 files scanned.
  - `[low]` `[patch]` (blind) Dark scheme partial: surfaceVariant still read — 4 reads moved to `surfaceContainerHighest.withValues`; onPrimaryContainer part false (seed-derived light tone stays readable on #1B3A5C).
  - `[medium]` `[patch]` (blind) No tests for KeevoColors getters / fixed pairs — `KeevoColors tokens` group added to app_theme_test.
  - `[low]` `[reject]` (blind) SectorType.selectedColor unused — renaming an unused field to a token is harmless; removal is unrelated cleanup.
  - `[low]` `[reject]` (blind) const-lint fallout and remaining withOpacity in touched files — analyze total went down (632 → 620), no new warnings; spec rule concerns translucent token variants only.
  - `[medium]` `[patch]` (verification-gap) Sibling banners keep the removed low-contrast pattern — DeletionPendingBanner icon → `onWarningContainer`, SuspensionBanner tones → on-container pairs; pairs pinned by AA token tests.
  - `[medium]` `[patch]` (verification-gap) successStrong/warningStrong brightness branch unpinned — `strong tokens follow brightness` test.
  - `[medium]` `[patch]` (verification-gap, other) Spinner on disabled fill — same as first row.
  - `[medium]` `[patch]` (verification-gap, other) Avatar initials — same as avatar row.
  - `[low]` `[patch]` (verification-gap, other) Guard holes — same as guard row.
  - `[medium]` `[patch]` (edge) Spinner on disabled fill — same as first row.
  - `[medium]` `[defer]` (edge) Speed-dial white icon on warning / onSurfaceVariant — pre-existing; routed → v1s-16-17.
  - `[low]` `[defer]` (edge) Checkout/cart disabled CTA white label — pre-existing; routed → v1s-16-17.
  - `[medium]` `[patch]` (edge) Avatar initials — same as avatar row.
  - `[low]` `[patch]` (edge) surfaceVariant reads miss #1B2838 — same as dark-scheme row.
  - `[medium]` `[defer]` (edge) Success SnackBar fill 2:1 — grouped with SnackBar row; routed → v1s-16-17.
  - `[low]` `[patch]` (edge) Guard `//` truncation — same as guard row.
  - `[low]` `[patch]` (edge) Guard misses Color.from( — same as guard row.
  - `[low]` `[reject]` (intent) No feature screen rendered under AppTheme.dark() — dark correctness is pinned at token level (AA pair tests) + guard; screen-by-screen golden tests are out of the intent's TDD list.
  - `[false]` `[reject]` (intent) New tokens are renames outside the spec table — the intent asks for theme tokens; the spec table defines no strong/medal/avatar tokens, and named tokens in the theme file are the agreed mechanism.
  - `[false]` `[reject]` (intent) Banner test aligned on widget instead of spec offline row — the gate banner is the in-page "Alerte attention" row (#FCC419 15 % ≈ #FFF6DD); the "Offline activé" row is the connectivity banner; #FFF9DB is the closest token (Design Notes).
  - `[low]` `[defer]` (intent) Dark colorError not #FF6B6B — deliberate (Design Notes); routed → v1s-16-17.

## Design Notes

Banner decision: UX spec "Alerte attention — fond #FCC419 15% opacity" ≈ `#FFF6DD`; the design-system token `warningContainer` `#FFF9DB` is the closest token and is already shared with `DeletionPendingBanner`; the old test value `#FFF3BF` (Open Color yellow-1, ≈ 25 %) matches neither, so the test is aligned to the token. The intent's `AppTheme.onWarning` text rule is kept for light; in dark `#4A2C00` on `#462F00` would be unreadable, hence the `onWarningContainer` pair. Dark `colorError` (`#FF6B6B` in the spec table) is left at `errorColor` because `errorColor` fills are used as fixed brand fills with white foreground; changing it is not needed for readability.

## Verification

**Commands (from `keevo/app`, one at a time, foreground):**
- `flutter test --concurrency=4 test/core/theme test/core/sync/offline_gate_banner_test.dart` -- all pass.
- `grep -rnE "Colors\.|Color\(0x" lib | grep -v lib/core/theme | grep -v "Colors.transparent"` -- empty.
- `flutter analyze` -- no new errors/warnings vs baseline (~630 infos).
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core` then each touched `test/features/<x>` dir -- test/core 0 failures; features no new failures.

## Auto Run Result

Status: done

**Summary:** every literal colour in `keevo/app/lib` outside `lib/core/theme/app_theme.dart` (92 `Colors.white`, 2 `Colors.white70`, 46 `Color(0x…)`, 5 `Colors.teal`, 3 `Colors.primaries`, 1 `Colors.black54`) now resolves through `ColorScheme`, `KeevoColors` or a named `AppTheme` token; `AppTheme.dark()` carries the UX-spec dark tokens; the offline gate banner (and its siblings DeletionPendingBanner / SuspensionBanner) use AA container/on-container pairs in light and dark; a guard test forbids literal colours outside the theme file; app 1.1.27+26.

**Files:** `lib/core/theme/app_theme.dart` (tokens: onBrand, successStrong #2B7A3B, warningStrong #A85500, violet/violetDeep, medals, avatarPalette + avatarInitials, dark scheme tokens, KeevoColors onWarningContainer/onErrorContainer_/successStrong/warningStrong); `lib/core/scaffold/offline_gate_banner.dart`; ~55 feature files (literal → token); `deletion_pending_banner.dart`, `suspension_banner.dart`; tests `test/core/theme/no_hardcoded_colors_test.dart` (new), `app_theme_test.dart`, `offline_gate_banner_test.dart`; `pubspec.yaml`.

**Review:** 1 pass, 30 findings — patched entries: 6 medium, 6 low (incl. duplicates); 4 deferred → `v1s-16-17-feedback-fill-contrast`; rejected: 3 low (test force-unwrap, unused selectedColor, const lint), 1 low (no per-screen dark render), 2 false (tokens outside spec table, banner row).

**Follow-up review recommended:** false — no high patched; the medium patches (spinner colour, token values, banner icons, avatar helper) are each pinned by token-level AA tests and re-verified by the full touched batches.

**Verification:** `flutter analyze` 620 issues (baseline 632), no error, no new warning; grep for literals outside theme → only `Colors.transparent`; `run-tests-chunked.sh app test/core` → 400 passed, 0 failed (offline_gate_banner_test now green); features inventory 180, pos 212, catalog 148, contact 37, stores 45, notifications 15, dashboard 85, sync_indicator 62, team 89, settings 43, auth 86, onboarding 26, profitability 45, reports 42, account 20 — all passed.

**Residual risks:** no on-device visual check of dark mode; darker successStrong/warningStrong shift a few light-mode hues (sale success page, badges); feedback-fill contrast items left to v1s-16-17.

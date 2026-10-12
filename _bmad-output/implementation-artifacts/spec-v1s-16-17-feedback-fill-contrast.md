---
title: 'v1s-16-17 — Feedback fill contrast: success SnackBars, POS speed-dial, disabled CTAs, dark error'
type: 'bugfix'
created: '2026-10-12'
status: 'done'
baseline_revision: '9fcd66f0d3b49fbc92a85c79cc6668e495e05d49'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 16.3 left fixed feedback fills whose foreground fails WCAG AA: success SnackBars on `AppTheme.success` #51CF66 (white icon 2.0:1; text from `onInverseSurface`, dark in dark mode, so icon and text disagree); error SnackBars / danger buttons on `AppTheme.errorColor` #FA5252 under white (3.28:1); the POS speed-dial draws a white icon on `AppTheme.warning` (1.61:1) and on `cs.onSurfaceVariant` (dark #ADB5BD, 2.0:1); the checkout / cart CTA keeps a white label on its disabled `cs.outlineVariant` fill; dark `cs.error` is #FA5252 while the UX spec table says #FF6B6B.

**Approach:** Every SnackBar becomes a fixed fill under `AppTheme.onBrand` (theme sets a fixed neutral fill + onBrand content/action/close colours; tone SnackBars use AA tone fills `successStrong` / new `errorStrong` / `primary` / `warningStrong`); danger and success `FilledButton`s use the same strong fills with `onBrand`; speed-dial options get a foreground per fill; disabled CTAs use `cs.surfaceContainerHighest` + `cs.onSurfaceVariant`; dark `cs.error` = #FF6B6B with a dark `onError`. Pairs are pinned by colour-level AA tests in light and dark.

## Boundaries & Constraints

**Always:**
- Thresholds: text ≥ 4.5:1, icons / large text ≥ 3:1, checked against `AppTheme.light()` and `AppTheme.dark()`.
- New colour literals only in `lib/core/theme/app_theme.dart`; `no_hardcoded_colors_test` stays green.
- Tokens: `AppTheme.errorStrong` #C92A2A (white 5.46:1); `AppTheme.snackBarNeutral` #343A40 (light) and `AppTheme.snackBarNeutralDark` #3B4D63 (dark) — white ≥ 8.6:1; `AppTheme.onErrorDark` = `AppTheme.darkSurface` #0D1B2A (on #FF6B6B 6.27:1); `AppTheme.errorDark` #FF6B6B.
- SnackBar tone mapping (inside `SnackBar(...)` only): `AppTheme.success` → `AppTheme.successStrong`; `AppTheme.errorColor` / `*.colorScheme.error` / `cs.error` / `AppTheme.secondary` (error message) → `AppTheme.errorStrong`; `AppTheme.warning` → `AppTheme.warningStrong`; `AppTheme.primary` unchanged. Every explicit icon/text/action colour inside a SnackBar → `AppTheme.onBrand`.
- Light `cs.error` stays #FA5252 (UX token table; indicator/text role, unchanged by this story).
- `pubspec.yaml` version `1.1.27+26` → `1.1.28+27`.

**Never:**
- No change to light `cs.error`, `AppTheme.success`, `AppTheme.errorColor`, `AppTheme.warning` values (indicators, dots, margins and their tests keep them).
- No behaviour/layout/duration change of any SnackBar or button; no new packages; never run the whole `flutter test` suite at once.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Success SnackBar | light / dark | fill `successStrong` #2B7A3B; icon + text `onBrand` (5.32:1) in both modes | n/a |
| Error SnackBar | light / dark | fill `errorStrong`; text/icon/action `onBrand` (5.46:1) | n/a |
| Neutral SnackBar (no backgroundColor) | light / dark | theme fill #343A40 / #3B4D63; text `onBrand` (≥ 8.6:1) | n/a |
| Speed-dial | open, day open / closed | Clôturer violetDeep + onBrand (7.1:1); Clôturée `cs.onSurfaceVariant` + `cs.surface` (≥ 4.5:1 both modes); Produit `warning` + `onWarning` (7.9:1) | n/a |
| Disabled checkout / cart CTA | no payment mode / empty cart / loading | fill `cs.surfaceContainerHighest`; label, icon, spinner `cs.onSurfaceVariant` (≥ 4.5:1 both modes); enabled stays `AppTheme.primary` + `onBrand` | n/a |
| Dark error pair | `AppTheme.dark()` | `cs.error` #FF6B6B, `cs.onError` #0D1B2A (6.27:1) | n/a |

</intent-contract>

## Code Map

- `keevo/app/lib/core/theme/app_theme.dart` -- add `errorStrong`, `errorDark`, `onErrorDark`, `snackBarNeutral(+Dark)`; `_snackBarTheme(cs, textTheme)`: `backgroundColor` neutral per `cs.brightness`, `contentTextStyle` = Inter `bodyMedium` with `onBrand` (must be built from the Inter text theme — a bare TextStyle would drop font/size), `actionTextColor` / `closeIconColor` `onBrand`; `dark()`: `error: errorDark, onError: onErrorDark`; update header comment.
- SnackBar sites (inventory found by parsing `SnackBar(` spans): `sync_indicator/.../sync_indicator.dart:38,87` (warning+onWarning, errorColor+onError, action `textColor`), `sync_required_modal.dart:48` (success+onSuccess), `inventory/.../transfer_history_page.dart:422,450,458`, `transfer_form_bottom_sheet.dart:166`, `apply_adjustments_button.dart:157`, `contact/.../client_form_page.dart:60,108` (icon `colorScheme.onError`), `client_list_page.dart:67`, `supplier_form_page.dart:113`, `supplier_list_page.dart:67`, `stores/.../stores_list_page.dart:73,158`, `settings/.../report_preferences_page.dart:46,57`, `pos/.../checkout_page.dart:70`, `reports/.../report_detail_page.dart:84,96`, `catalog/...` `product_form_page.dart:292,318,340`, `csv_import_page.dart:109`, `catalog_page.dart:167,708,718,755,794`, `categories_page.dart:41,51,93,133`, `stock_adjust_bottom_sheet.dart:96`, `stock_entry_bottom_sheet.dart:108,120`, `stock_threshold_bottom_sheet.dart:84`, `widget/product_card.dart:603,620,696,713,788,805`, `auth/.../user_password_change_page.dart:96`, `onboarding/.../shop_name_page.dart:128`.
- Danger / success FilledButtons: `pending_sale_detail_page.dart:244` (errorColor), `:398` (success, no fg), `sale_detail_page.dart:605`, `categories_page.dart:120`, `catalog_page.dart:781`, `employee_card.dart:380`, `account/.../delete_account_page.dart:222` (cs.error/cs.onError → fixed danger pair), `catalog/widget/product_card.dart:642,827` (success, no fg), `:735` (warning, no fg → `onWarning`), `stock_entry_bottom_sheet.dart:249` (success+onBrand → successStrong).
- `notifications/.../notification_bell_widget.dart:36` -- badge `errorColor` + `onError` text → `errorStrong` + `onBrand`.
- `catalog/.../product_form_page.dart:1345` -- icon `onBrand` on `AppTheme.warning` → `onWarning`.
- `pos/.../pos_speed_dial.dart:109-150,205-260` -- `_SpeedDialOption` gets required `foregroundColor` used by the icon.
- `pos/.../checkout_page.dart:266-317`, `pos/.../cart_bottom_sheet.dart:270-300` -- disabled fill/foreground as matrix.
- Read-only: `test/core/theme/app_theme_test.dart:37-58` (light error #FA5252 stays; dark `onError` expectation moves to `onErrorDark`), `test/features/sync_indicator/*` dot #FA5252 (not touched).

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/core/theme/app_theme.dart` -- tokens + snackBarTheme + dark error pair -- single source of truth.
- SnackBar / button / badge sites listed in Code Map -- apply the tone mapping -- AA fills under onBrand.
- `keevo/app/lib/features/pos/presentation/widget/pos_speed_dial.dart` -- foreground per option fill.
- `keevo/app/lib/features/pos/presentation/page/checkout_page.dart`, `.../widget/cart_bottom_sheet.dart` -- disabled pair.
- `keevo/app/test/core/theme/app_theme_test.dart` -- group `feedback fill pairs (v1s-16-17)`: AA for every matrix pair in light and dark (SnackBar theme fill vs content/action colour, tone fills vs onBrand, speed-dial pairs, disabled pair, dark error pair, onWarning on warning); snackBarTheme `contentTextStyle` keeps the Inter font family; update dark `onError` expectation.
- `keevo/app/test/core/theme/feedback_fill_guard_test.dart` (new) -- scan `lib/` (excluding theme file): fail with `file:line` on `backgroundColor:\s*AppTheme\.(success|errorColor|secondary)\s*[,)]` or `backgroundColor:\s*(cs|theme\.colorScheme|Theme\.of\(context\)\.colorScheme)\.error\s*[,)]` -- regression guard (asserts > 100 files scanned).
- `keevo/app/test/features/pos/...` -- widget test: speed-dial option icon colours and disabled cart CTA label colour under `AppTheme.dark()` match the pairs (add to existing pos widget tests if a fitting file exists, else new `pos_feedback_contrast_test.dart`).
- `keevo/app/pubspec.yaml` -- `1.1.28+27`.

**Acceptance Criteria:**
- Given the repo, when `test/core` runs, then 0 failures (guard tests included).
- Given any SnackBar in dark mode, when shown, then its text and icon colours are `onBrand` on an AA fill (no dark text on green).
- Given the four routed items, when the epic brief is read, then each is ticked with the decision.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 22 findings — high 0, medium 4, low 16, false 2, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) Neutral SnackBars change look in dark mode (slate + white instead of M3 light inverse) — required by the only mechanism that makes tone SnackBars' text agree with their icon (Design Notes); AA ≥ 8.6:1.
  - `[low]` `[reject]` (intent) Sync-conflict warning and report-preferences coral error pairs changed without an item — consistency with the theme-wide onBrand content/action colour (an amber fill would get white action text); coral was an error message on a 2.78:1 fill.
  - `[medium]` `[patch]` (intent) No SnackBar exercised at widget level — grouped with verification-gap rejection SnackBar row; assertions added in `sync_indicator_rejection_test.dart`.
  - `[medium]` `[patch]` (intent) Checkout disabled CTA untested — grouped with verification-gap checkout row.
  - `[low]` `[patch]` (intent) Light rendering of speed-dial / cart CTA untested — light variants added to `pos_feedback_contrast_test.dart`.
  - `[medium]` `[patch]` (verification-gap) Checkout disabled fill/label not asserted — `pos_feedback_contrast_test.dart` pumps `CheckoutPage` with no payment mode in light and dark, asserts `surfaceContainerHighest` fill + `onSurfaceVariant` label (≥ 4.5:1).
  - `[medium]` `[patch]` (verification-gap) Sync-indicator SnackBar colours unpinned — rejection test asserts `errorStrong` fill and `onBrand` text/icon; remaining one-off sites rejected (low): bounded by theme-level pair tests + guard, a widget test per page is out of proportion.
  - `[low]` `[patch]` (verification-gap, other) Guard weaker than its name — grouped with guard row below.
  - `[low]` `[patch]` (blind) Stale "texte foncé / fond ambre" comment in sync_indicator — rewritten.
  - `[low]` `[patch]` (blind) `AppTheme.onError` doc claims AA on errorColor; light error pair 3.28:1 — doc corrected (icons only, fills use errorStrong). Badge-default part `false`: every `Badge` in lib sets its own fill.
  - `[low]` `[patch]` (blind) Guard misses next-line / ternary values — guard rewritten: whole-file multiline scan, `Theme.of(context).colorScheme.error` caught, SnackBar spans also reject light fills (`AppTheme.warning`, `cs.success/warning/successStrong/warningStrong`); translucent tints (`.withOpacity/.withValues/.withAlpha`) skipped (catalog_page low-stock chip). `pos/product_card.dart:117` close icon on errorColor is 3.28:1 ≥ 3:1 icon threshold — kept.
  - `[low]` `[patch]` (blind) Theme white content would silently land on a light SnackBar fill — same guard row (SnackBar-span light-fill check).
  - `[low]` `[patch]` (blind) "Strong" means two things (KeevoColors getters are light tones in dark) — doc line on both getters: text/icon tones, never fills under onBrand.
  - `[low]` `[patch]` (blind) Checkout disabled CTA keeps the indigo glow while submitting; stale "indigo dans les deux thèmes" comments — shadow keyed on `!submitDisabled`, comments updated in checkout + cart.
  - `[low]` `[patch]` (blind) Missing widget coverage (checkout disabled, light mode, enabled cart CTA) — same as checkout/light rows; enabled cart CTA asserted (primary + onBrand).
  - `[low]` `[patch]` (blind) `cs.error` on `errorContainer` in dark 3.37:1 (create_draft_product_bottom_sheet) — icon/text moved to `onErrorContainer` (7.2:1).
  - `[false]` `[reject]` (blind) Already-AA pairs (sync_required_modal success+onSuccess, sync conflict warning+onWarning) needlessly changed — not a defect: the theme now sets onBrand action/close colours for every SnackBar, so these fills had to move to onBrand-compatible fills to keep action text readable.
  - `[low]` `[reject]` (blind) No shared SnackBar helper — refactor of ~100 call sites beyond the intent; theme + guard already enforce the rule.
  - `[low]` `[patch]` (edge) Light SnackBar fills unguarded — same guard row.
  - `[low]` `[patch]` (edge) Guard misses split-line / ternary / other receivers — same guard row.
  - `[low]` `[patch]` (edge) Guard throws PathNotFoundException outside keevo/app — `expect(Directory('lib').existsSync(), …)` added.
  - `[false]` `[reject]` (edge) Disabled CTA half-active glow — duplicate of blind glow row, already patched there (counted once as patch).

## Design Notes

UX spec conflict: the Feedback Patterns table asks for success SnackBars on `#51CF66` and errors / danger on `#FA5252` with white text, while the same spec requires every text ≥ 4.5:1 in light and dark (both pairs fail: 2.0 and 3.28). AA wins (NFR23-26); the hue families are kept with the darker strong tokens (`successStrong` from 16-3, new `errorStrong` = Open Color red-9). The light `cs.error` token stays the table value #FA5252 because it is used as indicator/text colour across ~40 sites and pinned by tests; no lib site uses it as a fill after this story (guard). Dark `cs.error` moves to the table's #FF6B6B with `onError` #0D1B2A (M3 dark pattern: light error tone, dark foreground) — this is what made 16.3 keep #FA5252 (white on #FF6B6B is 2.78), now settled since every fixed red fill uses `errorStrong`.

SnackBars: Flutter has no per-background foreground resolution, so the only way to make tone SnackBars' text agree with their icon in both modes without editing every `Text` is a theme-level onBrand content style over fixed fills. Consequence: neutral SnackBars in dark mode become slate #3B4D63 with white text instead of M3's light inverse surface (light mode visually unchanged: #343A40 ≈ M3 inverseSurface).

## Verification

**Commands (from `keevo/app`, one at a time, foreground):**
- `flutter test --concurrency=4 test/core/theme` -- all pass.
- `flutter analyze` -- no new errors/warnings vs ~620 baseline.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core` then each touched `test/features/<x>` -- 0 failures.

## Auto Run Result

Status: done

**Summary:** every SnackBar now sits on a fixed fill under `AppTheme.onBrand` (theme: neutral #343A40 / dark #3B4D63, Inter `bodyMedium` onBrand content, onBrand action/close); tone SnackBars and fixed danger/success buttons use AA strong fills (`successStrong` #2B7A3B, new `errorStrong` #C92A2A, `warningStrong`); the POS speed-dial passes a foreground per fill; disabled checkout/cart CTAs use `surfaceContainerHighest` + `onSurfaceVariant`; dark `cs.error` = #FF6B6B with `onError` #0D1B2A (UX table). Light `cs.error` stays #FA5252. App 1.1.28+27.

**Files:** `lib/core/theme/app_theme.dart` (tokens, snackBarTheme, dark error pair, docs); ~25 feature files (SnackBar fills, danger/success FilledButtons, notification badge, supplier icon, draft sheet error text); `pos_speed_dial.dart`, `checkout_page.dart`, `cart_bottom_sheet.dart`; tests `app_theme_test.dart` (feedback fill pairs group), `feedback_fill_guard_test.dart` (new), `pos_feedback_contrast_test.dart` (new, 8 widget tests light+dark), `sync_indicator_rejection_test.dart`; `pubspec.yaml`; epic brief items ticked.

**Review:** 1 pass, 22 findings — patched: 4 medium (checkout disabled test, sync SnackBar colour assertions and their 2 grouped intent rows), 13 low; deferred: none; rejected: 3 low (dark neutral SnackBar look, consistency pair changes, no helper refactor) and 2 false (already-AA pairs, duplicate glow).

**Follow-up review recommended:** false — the medium patches are test additions only (no production logic changed by them), now green.

**Verification:** `flutter analyze` 615 issues (baseline 620), no new warning/error; `run-tests-chunked.sh app`: test/core 411 passed; features pos 220, catalog 148, sync_indicator 62, contact 37, inventory 180, stores 45, settings 43, reports 42, team 89, auth 86, onboarding 26, notifications 15, account 20 — all passed, 0 failure.

**Residual risks:** no on-device visual check; dark-mode neutral SnackBars change appearance; error text/indicators are a lighter red in dark mode; the guard only inspects `backgroundColor:` (BoxDecoration fills are covered by review, not by the guard).

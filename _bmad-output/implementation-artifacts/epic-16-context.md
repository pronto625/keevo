# Epic 16 Context: Mobile Frontend Hardening & Parity

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Fix the mobile (Flutter) defects found by the V1 conformity and coherence audits so the V1 app on `deploy` is solid and shippable while the modular rewrite continues elsewhere. The epic covers push-notification hygiene, dark mode and colour contrast, catalog search and list quality, sync-state feedback, client behaviour when the server rejects queued offline operations, error-state recovery, and a sweep of residual UX bugs. It is a V1 release blocker. Most stories are tag A (patch V1 now); a few are tag C (patch V1 now, the rewrite re-applies the fix later).

## Stories

- Story 16.1: FCM token cleanup on logout (done)
- Story 16.2: iOS notifications (Darwin settings) and foreground display (backlog)
- Story 16.3: Dark mode — hard-coded colours to theme tokens, readable offline banner (done)
- Story 16.4: Catalog search debounce and wider matching (done)
- Story 16.5: SyncIndicator online-stale state (done)
- Story 16.6: `ADJUSTMENT` movement type and offline store sync_queue (done)
- Story 16.7–16.10: mark-all-read, startup sync pull, duplicate /reports route, dead DayCloseButton (done)
- Story 16.11: "Réessayer" on app error screens (done)
- Story 16.12: User feedback on sync rejections (done)
- Story 16.13: Residual app UX bugs (done)
- Story 16.14: Catalog list quality (done)
- Story 16.15: Offline sales stuck in the sync queue — quarantine (done)
- Story 16.16: Unicode case/accent-insensitive local search (done)
- Story 16.17: Contrast of feedback fills — success SnackBars, POS speed-dial, disabled CTAs, dark error colour (in progress)

## Requirements & Constraints

- **Contrast (WCAG 2.1 AA), light AND dark.** All text ≥ 4.5:1 against its background; large text / icons ≥ 3:1. Alerts are always colour + icon + text, never colour alone. Touch targets ≥ 48 dp; supports 200% font scaling. Dark mode follows the system.
- **Feedback must stay legible on every fill.** A foreground (icon *and* text) must match its own fill, not inherit a theme colour meant for another background (e.g. SnackBar text from `onInverseSurface` on a custom success fill). Icon and text on the same fill must agree.
- **Routed-items rule.** A story with an item list is done only when every item is resolved or explicitly rejected with the reason recorded next to it in the epics file. Nothing goes to `deferred-work.md`.
- **Zero lost sales / offline-first.** Sync never silently drops an operation; money/stock-ledger operations refused for good go to quarantine (16.15), never deleted. Offline gate: day 5 warning (amber), day 6 critical (red), day 7+ writes blocked.
- **Local search** goes through the shared fold (`core/storage/search_fold.dart`) and LIKE escaping (`core/storage/sql_like.dart`); < 500 ms on 500 products.
- **Error states** offer "Réessayer" (invalidates the failed provider). UI text is French, human, non-technical. Destructive actions need a confirmation dialog.
- **Process.** Flutter-first; a backend change needs a justification in the story. Every commit touching `keevo/app/` bumps `pubspec.yaml`.

## Technical Decisions

- **Colour tokens only.** Every colour outside `lib/core/theme/app_theme.dart` resolves through `Theme.of(context).colorScheme`, the `KeevoColors` theme extension, or a named `AppTheme` token. New tokens are added only in the theme file. A guard test (`test/core/theme/no_hardcoded_colors_test.dart`) forbids colour literals elsewhere; new token pairs get AA tests in `app_theme_test.dart`.
- **Foreground follows its fill (16.3 rule).** `cs.primary` → `cs.onPrimary`; `cs.error` → `cs.onError`; `cs.secondary` → `cs.onSecondary`; fixed brand fills that do not change with brightness (`AppTheme.primary`, gradients, `AppTheme.success`/`errorColor` fills, `darkSurface`) → `AppTheme.onBrand` (white). Translucent variants use `.withValues(alpha:)`, never `withOpacity`. On a theme surface use the brightness-aware `cs.successStrong`/`cs.warningStrong`; fixed `AppTheme.successStrong` (`#2B7A3B`, AA under `onBrand`) / `warningStrong` (`#A85500`) are for fixed fills. Container pairs: `warningContainer`/`onWarningContainer`, `errorContainer_`/`onErrorContainer_` (AA light and dark).
- **Disabled controls.** Disabled-fill foregrounds use the disabled/variant token, not white (16.3 already switched loading spinners on disabled FilledButtons to `cs.onSurfaceVariant`).
- **Dark `colorError`.** Spec says `#FF6B6B`; the theme keeps `#FA5252` because `errorColor` is also used as a fixed fill under white. Any change must be settled together with those fixed fills and their foreground (16.17 decides).
- **Write/sync strategy.** Online: backend first then local cache; offline: Drift + `sync_queue` in one transaction (repository layer). Rejection policy single source: `core/sync/sync_rejection_policy.dart`.
- **Stack/testing.** Flutter, Riverpod 3 (codegen), Drift (encrypted), go_router; feature-first layout; shared widgets in `core/widget/`. Widget tests pump both `AppTheme.light()` and `AppTheme.dark()` when asserting colours; `GoogleFonts.config.allowRuntimeFetching = false` in tests.

## UX & Interaction Patterns

- **Colour system "Indigo Sky" (light / dark):** primary `#3B5BDB`/`#4DABF7`; onPrimary `#FFFFFF`/`#0D1B2A`; primaryContainer `#D0EBFF`/`#1B3A5C`; secondary `#FF6B6B`/`#FF8787`; success `#51CF66`/`#69DB7C`; warning `#FCC419`/`#FFD43B`; error `#FA5252`/`#FF6B6B`; surface `#FFFFFF`/`#0D1B2A`; surfaceVariant `#F8F9FA`/`#1B2838`; onSurface `#212529`/`#F0F4F8`; onSurfaceVariant `#868E96`/`#ADB5BD`. Spec-verified ratios: body text 15.4:1 (light) / 14.8:1 (dark); primary on white 5.2:1; success `#51CF66` on white only 2.8:1 (acceptable solely as large icon + text, not as a fill under small white text).
- **Feedback patterns.** Transactional success: SnackBar on the success colour with ✅ icon + light haptic, auto-dismiss 3 s. System error: SnackBar on the error colour with ❌ and "Réessayer", never auto-dismissed. Attention: in-page banner on warning at ~15% (token `warningContainer`) with ⚠️, persistent. Contextual info: SnackBar on primary with ℹ️, 4 s. Offline: persistent top banner on warning. Messages French, positive tone. The *colour family* is fixed by the spec; the exact shade/foreground may be darkened (e.g. a strong success fill) to reach AA.
- **Buttons.** One primary (gradient, white text, 48 dp) per screen; danger = error fill + light text, always behind a dialog; FAB = gradient + icon. A disabled primary/CTA must keep a readable (disabled-token) label.
- **POS components.** Cart pill: item count, XAF total, "Encaisser". Speed-dial options sit on per-option fills (warning, primary, onSurfaceVariant when the day is closed) — each needs its own matching foreground.
- **Sync indicator:** green "Mis à jour", amber "En ligne (sync J-X)" day 5, red "⚠ Sync urgente" day 6, with Semantics labels.

## Cross-Story Dependencies

- 16.17 is the direct follow-up of 16.3: it inherits the token rules, `onBrand`, the strong success/warning tokens, the container pairs and the no-hardcoded-colours guard, and must not reintroduce literals. Its four items (success SnackBar fill/foreground, speed-dial foregrounds, disabled checkout/cart CTA label, dark `colorError`) were routed from the 16.3 review.
- 16.11's `AppErrorWidget`, 16.12's rejection SnackBars and 16.13's banner precedence / SnackBar throttling share the same feedback surfaces; colour changes must not alter their behaviour or precedence.
- 16.15 builds on 16.12; 16.16 on 16.14 and 16.4.
- The modular rewrite must re-apply every fix in this epic.

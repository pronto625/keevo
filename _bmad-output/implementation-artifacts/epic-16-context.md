# Epic 16 Context: Mobile Frontend Hardening & Parity

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the V1 Flutter app (pre-refactor line, shipped from `deploy`) production-solid while the modular refactor proceeds separately. The epic gathers mobile findings from the Flutter audits and later reviews: push-notification hygiene (FCM, iOS), dark mode and contrast, search quality, sync status and sync-rejection feedback, error-state recovery, residual UX bugs, iOS release identity (store-shippable via TestFlight/App Store), dashboard correctness, and the rule that end users never see technical data. Most stories are tag A (V1 patch shipped now); tag C stories are also re-applied by the refactor. Every fix must later be re-applied in Flutter by the refactor ("Refonte absorption").

## Stories

- Story 16.1: Cleanup du token FCM au logout
- Story 16.2: Notifications iOS (Darwin settings) + foreground display
- Story 16.3: Dark mode — remplacer les `Colors.white` hardcodés + contraste bannière offline
- Story 16.4: Recherche catalogue — debounce 300ms + fuzzy
- Story 16.5: SyncIndicator — état online-stale + daysSinceLastSync
- Story 16.6: Typo `STOCK_ADJUST` + store offline sync_queue
- Story 16.7: « Tout marquer comme lu » cassé après le premier usage
- Story 16.8: Câbler `onAppStartup()` (pull de sync au démarrage/login)
- Story 16.9: Route `/reports` dupliquée
- Story 16.10: Nettoyage du widget mort `DayCloseButton`
- Story 16.11: Boutons « Réessayer » sur les écrans d'erreur de l'app
- Story 16.12: Retour utilisateur sur les rejets de sync
- Story 16.13: Bugs UX résiduels de l'app (auth/router, bannières, listes, pickers)
- Story 16.14: Qualité de la liste catalogue (échappement recherche, dédup, pagination)
- Story 16.15: Ventes hors ligne bloquées dans la file de sync
- Story 16.16: Recherche locale insensible à la casse et aux accents (Unicode)
- Story 16.17: Contraste des aplats de feedback (SnackBars succès, speed-dial, CTA désactivés)
- Story 16.18: Identité de release iOS (bundle id, icônes, orientation, APNs production)
- Story 16.19: Seaux hebdomadaires du dashboard mobile (fin d'année, heure d'été)
- Story 16.20: Aucune donnée technique visible par l'utilisateur final (exports, historique des actions, écrans)

## Requirements & Constraints

- **Users and language:** tech literacy ~4/10. UI is French-only in V1 (i18n is V2). Critical actions in ≤ 3 taps. No accounting jargon; no technical or frightening errors — always a reassuring French message, with « Réessayer » where retry makes sense.
- **No technical data shown (permanent rule):** no UUIDs/ids, raw codes (audit actions, statuses, sync sources, movement types, error codes), table/class names, raw device ids or JSON on any screen, sync-rejection message, blocked-operations screen, notification, server CSV/XLSX/PDF export, offline local CSV, or action-history detail. Use readable French labels or omit the field. Ids remain fine internally. An automated guard fails on UUID/SNAKE_CASE in displayed strings of key screens and export headers/values.
- **Offline-first:** fully usable offline for 7 days; no sale is ever lost. Money/stock operations refused for good are quarantined (kept, not pushed, not counted pending) and shown under « Opérations bloquées » (Renvoyer/Exporter for all; Rattacher/Abandonner for OWNER after export).
- **Performance:** local search < 500 ms on a 500-product catalog; full catalog stays local in a lazy list. Smooth on 2 GB RAM devices.
- **Accessibility:** WCAG AA (≥ 4.5:1) in light and dark; adaptive font sizes / Dynamic Type; Semantics labels on status indicators.
- **Platforms & stores:** Android 8+ (API 26) and iOS 14+. The app must satisfy Google Play and Apple App Store guidelines (permissions, privacy policy). iOS is an MVP platform from the same codebase.
- **Export contract:** server exports, PDF templates and offline local CSV share one column contract (`keevo/contracts/export-datasets.json`) with backend golden/contract tests; change them together.
- **Item-list stories:** interim AC — every routed item is resolved or explicitly rejected with a reason recorded next to it (tick the box, note commit/decision). Deferred work is never orphaned; it is routed to its owning story.

## Technical Decisions

- **Flutter stack:** Riverpod 3 (`@riverpod`), go_router, Drift on SQLCipher, Material 3. Feature-first Clean Architecture (`core/` + `features/<x>/{data,domain,presentation}`); domain has no framework deps.
- **App identity:** the project was meant to be created with org `com.keevo` (project name `keevo`); the Android `applicationId` and iOS bundle id are still the template `com.example.keevo`, which Apple rejects. One production reverse-DNS id must be shared by Android and iOS, decided before registering the Firebase iOS app (`GoogleService-Info.plist`). The iOS Runner (generated in 16.2 from the Flutter template) still has default icons/launch image, iPhone landscape allowed, and `aps-environment=development` for all configurations.
- **Write path:** online → backend first, then local cache (`synced:true`); offline → Drift + `sync_queue`, pushed by `SyncTriggerNotifier` on reconnect. After a mutation, `refresh()`/`invalidate()` so UI updates from Drift.
- **Sync rejections:** shared policy classifies transient vs terminal. Terminal rejection of a create rolls back ghost local rows and dependent ops (SnackBar « … — annulé sur cet appareil »). Money/ledger ops are quarantined (`sync_queue.quarantined_at`), never dropped. `PLAN_LIMIT_EXCEEDED` is terminal.
- **Errors:** single error state `AppErrorWidget` with required `onRetry`, invalidating the exact failed provider.
- **Theming:** no literal colours outside `core/theme/app_theme.dart`; use `ColorScheme`, `KeevoColors`, `AppTheme` tokens, foreground following its fill (`onBrand`; `successStrong`/`errorStrong`/`warningStrong`). Guarded by `no_hardcoded_colors_test` and `feedback_fill_guard_test`. One shell banner at a time, fixed priority.
- **Search:** shared helpers `core/storage/sql_like.dart` (LIKE escaping) and `core/storage/search_fold.dart` (Unicode case/accent fold, SQL + Dart mirror). Dedup by sync id.
- **Dates/time:** inject clocks; weekly buckets keyed by local Monday date, built from calendar fields.
- **Testing:** `ProviderContainer` + `mocktail` for providers, widget tests for UI. Run tests one folder at a time, never the full suite. `flutter analyze` must add no new warnings. Device-only checks (iOS push, archives) go in a manual checklist.
- **Versioning:** every change under `keevo/app/` bumps `keevo/app/pubspec.yaml` (`1.1.x+n`).

## UX & Interaction Patterns

- Success = SnackBar + light haptic, auto-dismiss. Errors never auto-dismiss and offer « Réessayer ». Positive French tone.
- Offline is transparent (« tout va bien »); sync indicator amber at day 5 (« En ligne (sync J-X) »), red at day 6 (« ⚠ Sync urgente »).
- Layout is mobile-first; tablets use portrait medium layout (POS + cart side by side). Brand look: Indigo Sky header gradient, "Soft/Modern" rebrand direction.
- Traceability framed as protection, not surveillance; action history reads as plain French.
- Destructive actions confirm; non-destructive ones run immediately with a 5 s « Annuler ».

## Cross-Story Dependencies

- 16.2 → 16.18: the iOS Runner and push wiring from 16.2 are the base for release identity; 16.18 needs a product decision on the reverse-DNS id (both platforms, then Firebase iOS app), brand icons, orientation matching Android, and production APNs (confirm on first TestFlight archive, else per-configuration entitlements).
- 16.12 → 16.15: rejection handling feeds the quarantine; 16.20 cleaned that screen and the messages.
- 16.4 → 16.14 → 16.16: one search path — keep both escaping and folding.
- 16.3 → 16.17: dark-mode tokens precede feedback-fill contrast.
- 16.20 depends on Epic 18 (data export, action history) and touches the backend export contract.
- 16.6 ties to Story 13.4 (backend offline store sync handlers).

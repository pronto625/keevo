# Epic 16 Context: Mobile Frontend Hardening & Parity

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the V1 Flutter app (pre-refactor line, shipped from `deploy`) solid enough for production while the modular refactor runs on its own branch. The epic collects mobile findings with no backend counterpart from the Flutter audits and later reviews: push-notification hygiene (FCM, iOS), dark mode and contrast, search quality, sync status and sync-rejection feedback, error-state recovery, residual UX bugs, iOS release identity, dashboard correctness, and a rule that end users never see technical data. Most stories are tag A (V1 patch shipped now). Tag C stories are also re-applied by the refactor. Every fix must be re-applied in Flutter by the refactor ("Refonte absorption").

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

- **Users and language:** target users have tech literacy of about 4/10. The UI is French-only in V1 (English/i18n is V2, so English requests are rejected with that reason). Critical actions are reachable in 3 taps or fewer. No accounting jargon, and no technical or frightening error messages: always a reassuring French message, with « Réessayer » where a retry makes sense.
- **No technical data shown to users (16.20, permanent rule):** no UUIDs or ids, no raw codes (audit actions like `CATEGORY_RENAMED`, statuses like `PENDING_VALIDATION`, sources `ONLINE/SYNC/SYSTEM`, movement types, error codes), no table/column/class names, no raw device ids, no JSON. This applies to every screen, sync rejection message, the blocked-operations screen, notifications, server CSV/XLSX/PDF exports, the offline local CSV export, and the action history with its before/after detail. Replace each with a readable French label (product/store/employee name, « Vente du 12/10 à 14:32 », « Téléphone de Awa »), or omit the field when no label exists. Ids stay allowed internally (queries, navigation, file names that are not displayed). An automated guard must fail when a UUID or SNAKE_CASE code appears in the displayed strings of key screens or in test export headers/values. The rule must also be written into the PRD (as a non-functional requirement) and the UX spec.
- **Offline-first:** the app works fully offline for 7 days. No sale is ever lost. A money or stock operation that the server refuses for good is never auto-deleted: it is quarantined (kept, no longer pushed, not counted as pending) and shown under « Opérations bloquées » with Renvoyer/Exporter for all roles, and Rattacher/Abandonner for OWNER only, after a successful export.
- **Performance:** local search returns in under 500 ms on a 500-product catalog. The full catalog stays local (no pagination), shown in a lazy list.
- **Accessibility:** WCAG AA contrast (≥ 4.5:1) checked in both light and dark mode. Adaptive font sizes. Semantics labels on status indicators.
- **Export contract (16.20):** server exports, PDF templates and the offline local CSV share one column contract (`keevo/contracts/export-datasets.json`, with golden/contract tests on the backend). Any column change must update the contract and those tests together, so the local export stays aligned.
- **Item-list stories:** stories built from routed review items have an interim AC: every item must be resolved, or explicitly rejected with a reason recorded next to it (tick the box and note the commit/decision). Deferred work is never left orphaned. It is routed to the story that owns it.

## Technical Decisions

- **Flutter stack:** Riverpod 3 (`@riverpod`), go_router, Drift on SQLCipher, Material 3. Layout is feature-first Clean Architecture (`core/` plus `features/<x>/{data,domain,presentation}`). The domain layer has no framework dependencies.
- **Write path:** when online, writes go to the backend first, then to the local cache (`synced:true`, no queue). When offline, writes go to Drift plus a `sync_queue` entry, pushed by `SyncTriggerNotifier` on reconnect. After every mutation the notifier calls `refresh()`/`invalidate()` so the UI updates immediately from Drift.
- **Sync rejections:** a shared policy classifies each rejection as transient or terminal. A terminal rejection of a create rolls back the ghost local rows and their follow-up operations, and shows a SnackBar « … — annulé sur cet appareil ». Money/ledger operations (`CREATE_SALE`, `VALIDATE_SALE`, `CANCEL_SALE`, `CREATE_DAY_CLOSURE`) are quarantined (`sync_queue.quarantined_at`), never dropped. `PLAN_LIMIT_EXCEEDED` is terminal.
- **Errors:** the single error state is `AppErrorWidget`, with `onRetry` required. Retry invalidates the exact provider that failed.
- **Theming:** no literal colours outside `core/theme/app_theme.dart`. Use `ColorScheme`, `KeevoColors` or named `AppTheme` tokens, with the foreground following its fill (`onBrand` on fixed brand fills; `successStrong`/`errorStrong`/`warningStrong` fills for feedback). Guard tests (`no_hardcoded_colors_test`, `feedback_fill_guard_test`) enforce this. Show one shell banner at a time, in fixed priority order.
- **Search:** use the shared helpers `core/storage/sql_like.dart` (LIKE escaping) and `core/storage/search_fold.dart` (Unicode case/accent fold in SQL, with an exact Dart mirror) for catalog, POS, contacts and multi-store search. Dedup by sync id, not by name.
- **Dates/time:** inject clocks instead of using real time. Weekly buckets are keyed by the local Monday date and built from calendar fields, not `Duration` arithmetic.
- **Testing:** use `ProviderContainer` + `mocktail` for providers and widget tests for UI behaviour. Run tests one folder at a time, never the full suite at once. `flutter analyze` must report no new warnings.
- **Versioning:** each story that changes the app bumps `keevo/app/pubspec.yaml` (`1.1.x+n`).

## UX & Interaction Patterns

- Feedback: success = SnackBar with a light haptic that auto-dismisses. Errors never auto-dismiss and offer « Réessayer ». Messages are in French with a positive tone.
- Offline mode is transparent and the default message is « tout va bien ». The sync indicator shows day 5 in amber (« En ligne (sync J-X) ») and day 6 in red (« ⚠ Sync urgente »). The loading state is neutral.
- Traceability is framed as protection, never as surveillance. The action history and its detail must read as plain French (who did what, on what, when, where, before/after per field).
- Destructive actions ask for confirmation. Non-destructive ones run immediately and show an « Annuler » undo for 5 s.

## Cross-Story Dependencies

- 16.12 → 16.15: sync-rejection handling feeds the blocked-operations quarantine. 16.20 must clean that screen and the rejection messages.
- 16.4 → 16.14 → 16.16: these stack on one search path. Keep both the escaping and the folding.
- 16.3 → 16.17: dark-mode tokens come before the feedback-fill contrast work.
- 16.2 → 16.18: the iOS Runner was generated in 16.2. Its release identity (one reverse-DNS id shared by Android and iOS, Firebase iOS app, icons, `aps-environment`) needs a product decision.
- 16.20 depends on Epic 18 (data export 18-1/18-2/18-3/18-5 and action history 18-7). It touches the backend export contract and tests, not just Flutter.
- 16.6 is tied to Story 13.4 (offline store sync handlers on the backend).

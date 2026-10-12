# Epic 16 Context: Mobile Frontend Hardening & Parity

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Fix the mobile (Flutter) defects found by the V1 conformity and coherence audits so the V1 app on `deploy` is solid and shippable while the modular rewrite continues elsewhere. The epic covers push-notification hygiene, dark mode, catalog search and list quality, sync-state feedback, the client behaviour when the server rejects queued offline operations, error-state recovery, and a sweep of residual UX bugs. It is a V1 release blocker. Most stories are tag A (patch V1 now). A few are tag C (patch V1 now, and the rewrite re-applies the fix later).

## Stories

- Story 16.1: FCM token cleanup on logout (done)
- Story 16.2: iOS notifications (Darwin settings) and foreground display
- Story 16.3: Dark mode: replace hard-coded `Colors.white` (recount first: ~95 after UI-3) and fix offline banner contrast
- Story 16.4: Catalog search: 300 ms debounce and wider matching (done)
- Story 16.5: SyncIndicator online-stale state and daysSinceLastSync (done)
- Story 16.6: `ADJUSTMENT` movement type and offline store sync_queue (done)
- Story 16.7–16.10: mark-all-read fix, startup sync pull wiring, duplicate /reports route, dead DayCloseButton (done)
- Story 16.11: "Réessayer" buttons on app error screens (done)
- Story 16.12: User feedback on sync rejections (done)
- Story 16.13: Residual app UX bugs (auth/router, banners, lists, pickers)
- Story 16.14: Catalog list quality (search escaping, dedup, never-stocked vs out-of-stock, pagination) (in progress)
- Story 16.15: Offline sales stuck in the sync queue (done — quarantine)

## Requirements & Constraints

- **Zero data loss, zero lost sales.** Sync must never silently drop an operation. Every REJECTED or CONFLICT operation is logged server-side in `sync_error_log`, and the server keeps the full payload of each rejected operation for 30 days so the Super Admin can recover it by hand. Locally, the app keeps operations in `sync_queue` until the server explicitly confirms processing. PENDING queue items are never purged. Only SYNCED items are purged, 7 days after confirmation.
- **Offline-first.** The app stays 100% usable offline for 7 days. On reconnect it syncs fully in under 60 s and retries with exponential backoff. Sync failures must never block core offline operations. Gate thresholds: day 5 warning (amber), day 6 critical (red), day 7+ local writes blocked (reads still allowed) until a successful sync. Staleness counts from the last successful sync.
- **Rejection contract** (set by 16.12, amended by 16.15):
  - Transient reasons (`OPTIMISTIC_LOCK`, `INTERNAL_ERROR`, `TRANSACTION_ROLLED_BACK`, `DATA_INTEGRITY_VIOLATION`, `UNAUTHORIZED`, `SUBSCRIPTION_NOT_FOUND`, `TENANT_NOT_FOUND`, null) stay queued with `lastError`, are retried, and are surfaced once per reason.
  - Terminal rejections of non-money operations are reconciled: ghost creations are rolled back with their dependent operations, and other terminal operations are surfaced once and then dropped.
  - Money and stock-ledger operations (`CREATE_SALE`, `VALIDATE_SALE`, `CANCEL_SALE` except when already cancelled, `CREATE_DAY_CLOSURE`, plus stock ledger operations except the ghost product/store cascade) are never auto-deleted by a rejection. Since 16.15, a terminal refusal (or `DATA_INTEGRITY_VIOLATION`) puts them in **quarantine** (`sync_queue.quarantined_at`): kept, never pushed, not counted by `hasPendingOperations()`, surfaced once; a quarantined `CREATE_SALE` carries its `VALIDATE_SALE`/`CANCEL_SALE` along (chain). Transient refusals keep the retry behaviour.
  - Every notice is French and non-technical. It ends with "— annulé sur cet appareil" when something was undone.
- **The 16.15 decision (PRD FR73a).** A money operation refused for good is quarantined on the device and listed under "Bloquées" (Paramètres › Synchronisation › File for the OWNER, `/sync/blocked` for every role). Actions: *Renvoyer* and *Exporter* (all roles), *Rattacher* a sale to existing products/store and *Abandonner* after a non-dismissed export (OWNER, logged as an `ABANDON` sync event that is never pruned). Server-side ad-hoc sale lines were rejected (no stock movement, breaks FR27). The server refuses a sale or day closure for an unknown store with `STORE_NOT_FOUND`.
- **Catalog list and search.** Product search runs on local SQLite, returns in under 500 ms on a 500-product catalog, matches name, SKU and category with a plain substring match (no fuzzy library), is debounced 300 ms, and offers "Aucun résultat → Créer" (OWNER) when nothing matches. User-typed `%`/`_` must match literally (escape plus an `ESCAPE` clause). The catalog of each store lists every active/draft product of the tenant, not only products with a `stock_levels` row in that store; stock is computed per store (0 when there is no row), and "stock faible" is scoped to the active store. Distinct products must never be hidden: dedup by sync id/SKU, never by name. A never-stocked product and an out-of-stock product look the same today; any visual difference is a PO/UX decision to record in the story. The UX spec shows an out-of-stock product card greyed out with a ⚠️ badge, using the error color.
- **OWNER-only surfaces.** Governance actions are OWNER-only, and the navigation hides OWNER-only sections for `role: EMPLOYEE`. The router must not let a null role through. Sale cancel/correct, pending sales, rejected-payload recovery and data export are owner actions, and the backend enforces the same rule (HTTP 403 for EMPLOYEE). The audit log is immutable. Exports are owner-only, tenant-isolated and audited.
- **Error states.** Every full-page error state offers "Réessayer", which invalidates the failed provider. Messages are human French. Destructive actions need a confirmation dialog.
- **Accessibility and UI rules.** Tech literacy is 4/10. Critical actions take 3 taps or fewer. Touch targets are at least 48 dp. Contrast meets WCAG AA in light and dark mode. UI text is French.
- **Process rules.**
  - The work is Flutter-first. A backend change is allowed only when the mobile fix cannot work without it, and the story must justify it.
  - Stories with routed item lists are done only when every item is resolved or rejected, with the decision recorded next to it. Nothing goes to `deferred-work.md`.
  - Every commit touching `keevo/app/` bumps `pubspec.yaml`.

## Technical Decisions

- **Write strategy (all modules).**
  - Online: write to the backend first, then cache locally (`synced:true`, no queue entry). A business error (`DomainException`, below 500) is rethrown to the UI and nothing is saved locally. A network error or 5xx falls through to the offline path.
  - Offline: save to Drift (`synced:false`) and add a `sync_queue` entry (operation, entityId, payload JSON) in one transaction. The queue insert belongs in the repository layer, not in the datasource.
  - After every write, the UI rereads Drift through `ref.invalidate` or `refresh()`.
- **Sync protocol.**
  - `POST /api/v1/sync/push` replays the queue in batches. Each result is APPLIED, DUPLICATE, CONFLICT or REJECTED, with a `reason` that is a domainCode.
  - Operation ids and `sale.id` UUIDs are idempotent on the server. The server answers the same refusal when a REJECTED operationId is replayed.
  - `GET /sync/pull` delivers deltas. Stock conflicts resolve by summing deltas, and other conflicts use last-write-wins.
  - Operation labels must match the backend handler literals exactly.
  - `SyncTriggerNotifier` pushes on reconnect. `onAppStartup()` chains a full sync after any running cycle.
- **Rejection handling code.**
  - `core/sync/sync_rejection_policy.dart` is the single source for the transient and money-ledger sets.
  - `RestSyncService._reconcileRejection` runs one Drift transaction per rejection.
  - `sync_rejection_messages.dart` turns reasons and operation labels into French text.
  - Rejection notices reach the SnackBar in `sync_indicator.dart` through `_handlePushResults`.
- **Settings › Synchronisation.** The screen shows sync history, the conflict log and the pending queue, with type, entity, queued time and `lastError`. Rejected money operations show in red. A read-only payload view is available for rejected or conflicted operations. A forced full sync is available.
- **Local retention.** Sales, sale items and stock movements are kept 30 days on the device, and older data is online-only. Reference data is never purged.
- **Stack.** Flutter with Riverpod 3 (codegen, `AsyncValue`), Drift (encrypted), go_router. Code is organised feature-first (`domain/data/presentation`). Shared widgets live in `core/widget/`, including `AppErrorWidget` with a required `onRetry`.
- **Design tokens.** Use `Theme.of(context).colorScheme.*` and the `AppTheme` helpers. Never hard-code colors.
- **Testing.**
  - Sync tests use the in-memory `AppDatabase.forTesting()` with the MockDio harness (`test/core/sync/rest_sync_service_rejection_test.dart`).
  - Provider tests use `ProviderContainer` with mocktail.
  - Set `GoogleFonts.config.allowRuntimeFetching = false` in tests.
  - Known baseline failure: `offline_gate_banner_test` (amber color).

## UX & Interaction Patterns

- **Sync indicator** (AppBar dot and label): green "Mis à jour", amber "En ligne (sync J-X)" on day 5, red "⚠ Sync urgente" on day 6. Tapping it opens a detail sheet with the last sync time, the pending count and a "Synchroniser maintenant" button.
- **Unsynced operations.** If operations stay unsynced for more than 1 hour while online, a diagnostic runs and the user sees "Vérification requise : des données n'ont pas pu être synchronisées."
- **Errors.** Errors never auto-dismiss. A system-error SnackBar uses the error color and offers "Réessayer". Banners use a warning tint and stay visible at the top.

## Cross-Story Dependencies

- 16.15 builds directly on 16.12 (rejection policy, `_reconcileRejection`, ghost rollback that strips the ghost `clientId` from queued sales). It also builds on epic 5 (`sync_error_log` with 30-day payload retention, and the Synchronisation settings screen). Any "export then remove" action can reuse Epic 18's owner export conventions. Any server-side ad-hoc sale line touches the backend `CREATE_SALE` sync handler and needs a justified backend change.
- 16.12 depends on 16.6 (store queue), 16.8 (startup pull), 14.13 (`PLAN_LIMIT_EXCEEDED`) and 13.8 (inventory session rejection).
- 16.13 collects leftovers from 16.1, 16.5, 14.9, 14.12 and 12.7, and owns banner precedence and the SnackBar flood fix. The OTP requirement was amended: a single 6-digit field is enough.
- 16.14 extends 16.4 (debounce, category match, empty-state create) and the earlier fix that made products visible in every store's catalog; keep that visibility and the per-store low-stock scope intact.
- 16.11 and 16.3 touch the same screens, so coordinate to avoid reintroducing `Colors.white`.
- The modular rewrite must re-apply every fix in this epic, including the sync protocol behaviour.

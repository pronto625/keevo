---
title: 'v1s-18-4 — Écran « Exporter » + « Mes exports » (app)'
type: 'feature'
created: '2026-10-12'
status: 'done'
baseline_revision: '2ef3deb432b80c0a4c7706e86b25783a25dffc07'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-18-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The backend export module (18.1 endpoints, 18.2 sixteen datasets) has no app surface: an owner cannot choose what to export, in which format, nor get the file or follow a background export. The « Historique des actions » screen has no « Exporter » button (routed from 18.7), and the request-global criteria rule (routed from 18.2) is not honoured by any client.

**Approach:** New Flutter feature `features/export` (data / domain / presentation, plain Riverpod providers — no codegen): an « Exporter » screen (datasets → filters → format → launch) that streams a direct export to a temp file and opens the system share sheet, or confirms a background job; a « Mes exports » screen listing jobs (status, expiry, download + share, retry); OWNER-only routes `/exports/new`, `/exports`, `/exports/:id` (FCM deep link), a Settings entry, and pre-filled « Exporter » buttons on Historique des actions, Stock, Ventes and Rapports.

## Boundaries & Constraints

**Always:**
- OWNER only: `/exports` prefix added to `_ownerOnlyPrefixes`; Settings tile and every « Exporter » button rendered only when `currentUserRoleProvider == 'OWNER'`.
- Dataset list comes from `GET /api/v1/exports/datasets` (`key`, `label`, `storeFilter`, `periodFilter`, `criteria`) — never hard-coded labels; « Tout » selects every dataset. The `stores` dataset is listed like any other.
- `criteria` is sent **only** when the selection is exactly `{audit_log}`, restricted to the keys that dataset declares in `criteria`, blank values dropped. When another dataset is added the screen keeps the prefilled criteria in memory but shows the note « Les critères du journal (employé, action, objet, recherche) ne s'appliquent qu'au Journal d'audit seul » and does not send them.
- `from`/`to` sent as UTC ISO-8601 instants (`toUtc().toIso8601String()`), period presets Aujourd'hui / 7 jours / Mois (30 derniers jours, same as audit) / Personnalisé / Toutes les dates, same day-boundary logic as `audit_log_page.dart` `_pickPeriod` (custom end = end of day, `clampDateRange`). Store: « Toutes les boutiques » or one Drift store.
- Formats: CSV (all plans); Excel (`XLSX`) and PDF show a « Premium » badge. Plan is FREE when `subscriptionInfoProvider` says `planType == 'FREE'` or `status != 'ACTIVE'` (or is unavailable → treat as FREE for the badge, but still let the server decide on launch). On FREE, tapping Excel opens the export offer sheet instead of selecting it. PDF is rendered disabled with the label « Bientôt » for everyone (backend answers 400 `EXPORT_FORMAT_UNSUPPORTED` until 18.3) — no PDF request is ever sent.
- Direct export: `POST /api/v1/exports` with `responseType: ResponseType.stream`; HTTP 200 ⇒ stream body chunk-by-chunk to `<temp dir>/exports/<file name>` (name from `Content-Disposition` `filename`, sanitized to `[A-Za-z0-9._-]`, fallback `keevo-export.<csv|zip|xlsx>` from `Content-Type`), then share via the injectable sharer (`Share.shareXFiles`). HTTP 202 ⇒ decode the JSON `{data: job}` from the stream and show « Votre export se prépare, nous vous prévenons dès qu'il est prêt » with a « Voir Mes exports » action.
- Error bodies on a stream response must be decoded (bytes → UTF-8 → JSON) to read `domainCode` / `error`. Mapping to French messages in one place (`ExportException`): `PLAN_FEATURE_REQUIRED` 403 → offer sheet; `RATE_LIMIT_EXCEEDED` / `EXPORT_QUEUE_FULL` 429 → « Trop d'exports en cours, réessayez dans quelques minutes »; `EXPORT_EXPIRED` 410 → « Ce fichier a expiré (conservé 24 h). Relancez l'export. »; `EXPORT_NOT_READY` 409; `EXPORT_NOT_FOUND` 404; `EXPORT_FORMAT_UNSUPPORTED` / `VALIDATION_ERROR` / `EXPORT_DATASET_UNKNOWN` 400 → server message or generic; no response → `NETWORK_ERROR` « Impossible de joindre le serveur ». Every failure in the Exporter screen shows the message with a « Réessayer » action that replays the same request.
- « Mes exports » (`GET /api/v1/exports`): newest first; per job: dataset labels (from the datasets list, key as fallback), format, period/store summary, created time, status chip (En attente / En cours / Prêt / Échec / Expiré), row count and size when READY, « Expire le … » for READY. READY with `expiresAt` in the past is shown as Expiré. Actions: READY → « Ouvrir » = stream `GET /api/v1/exports/{id}/file` (`Dio.download` or stream GET with auth) to the temp file, then share; FAILED → « Relancer » (re-POST same datasets/format/store/period, no criteria — the job DTO does not carry them); PENDING/RUNNING → pull-to-refresh plus automatic refresh every 10 s while one is active (timer cancelled on dispose; interval injectable for tests). Load error → `AppErrorWidget` with retry; empty → explanatory empty state.
- `/exports/:id` (FCM `EXPORT_READY`/`EXPORT_FAILED` deep link, pushed by `fcm_service.dart` and the in-app notifications list) opens « Mes exports » with that job highlighted; if it is READY the file download + share starts once automatically.
- Offline (`connectivityServiceProvider.isOnline()` false): Exporter screen shows a banner « Hors ligne — l'export complet nécessite une connexion », the launch button is disabled, and a disabled tile « CSV — données de cet appareil » subtitled « Bientôt disponible » (key `export-local-csv-entry`) marks the 18.5 entry point. Mes exports offline → `AppErrorWidget` (no cache).
- UI-3 look: `cs.surface` scaffolds and app bars like `audit_log_page.dart`, `SoftIconBadge`, `AppTheme` radii/tokens; no colour literal outside `app_theme.dart` (`no_hardcoded_colors_test`). French UI copy; code/comments in English.
- `keevo/app/pubspec.yaml` version bumped `1.1.33+32` → `1.1.34+33`. No new dependency (dio, path_provider, share_plus already present).

**Never:**
- No offline/local CSV generation (18.5), no PDF template picker (18.3), no backend change.
- No codegen (freezed / riverpod_generator / drift) — no build_runner run needed.
- Never read the whole download into memory; never write outside the app temp directory; no storage permission request.
- Do not send `criteria` with a multi-dataset selection; do not send a PDF request.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Direct CSV | datasets [products], CSV, POST → 200 stream + `Content-Disposition: attachment; filename="keevo-products-20261012.csv"` | file written in temp/exports with that name, sharer called with it | — |
| Background | Premium, POST → 202 `{data:{id,status:PENDING}}` | confirmation « Votre export se prépare… » + action to Mes exports | — |
| Free + Excel | plan FREE, tap Excel | offer sheet opens, format stays CSV | — |
| Server plan gate | POST → 403 `PLAN_FEATURE_REQUIRED` | offer sheet opens | — |
| Audit sole | selection {audit_log}, prefilled criteria {userId, action, entityType, q} | body has `criteria` with those keys | — |
| Audit + other | selection {audit_log, sales} | body has no `criteria`, note shown | — |
| Error + retry | POST → 429 / network error | message shown with « Réessayer »; retry re-sends identical body | — |
| Expired download | GET file → 410 `EXPORT_EXPIRED` | « Ce fichier a expiré… » snackbar, list refreshed | — |
| READY past expiry | job READY, `expiresAt` < now | shown « Expiré », no Ouvrir action | — |
| Offline | isOnline false | banner, launch disabled, local CSV tile disabled | — |
| Employee | role EMPLOYEE navigates /exports | redirected to /pos; no Exporter buttons rendered | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/reporting/export/adapter/in/rest/ExportController.java` -- READ ONLY contract: `GET /datasets`, `POST` (200 file written to response, or 202 `ApiResponseWrapper` job), `GET /`, `GET /{id}`, `GET /{id}/file`. Body DTO `dto/ExportRequestDto.java` (`datasets`, `format` CSV|XLSX|PDF, `storeId`, `from`, `to`, `criteria` map). Job DTO `dto/ExportJobDto.java` (id, status, format, datasets, storeId, from, to, rowCount, fileName, fileSize, contentType, errorCode, createdAt, startedAt, completedAt, expiresAt, downloadUrl; nulls omitted). Dataset DTO `dto/ExportDatasetDto.java` (key, label, columns, storeFilter, periodFilter, criteria). Error envelope: `{success:false, error:<msg>, domainCode:<CODE>}`.
- Dataset keys (18.2): products, categories, stock_levels, stock_movements, stock_transfers, sales, sale_items, day_closures, inventory_sessions, inventory_counts, reports, clients, suppliers, employees, stores, audit_log; only `audit_log` declares criteria (`userId`, `action`, `entityType`, `entityId`, `q`).
- `keevo/app/lib/features/audit/data/datasource/remote_audit_datasource.dart` `_mapError` -- error-mapping pattern to mirror (domainCode / error / status null ⇒ NETWORK_ERROR).
- `keevo/app/lib/features/audit/domain/model/audit_filter.dart` -- `AuditFilter` (from, to, storeId, userId, action, entityType, query/`normalizedQuery`) → prefill: datasets {audit_log}, storeId, from, to, criteria {userId, action, entityType, q}.
- `keevo/app/lib/features/audit/presentation/page/audit_log_page.dart` -- AppBar (~:300) gets an `actions:` « Exporter » icon button (`Icons.ios_share_rounded`, tooltip « Exporter », key `audit-export`), reading `auditLogFilterNotifierProvider`; `_pickPeriod` (~:118) and `_pickOption` bottom sheet are the UI patterns to copy for the export filters.
- `keevo/app/lib/features/audit/presentation/provider/audit_log_provider.dart` `auditStoreOptionsProvider` -- Drift stores pattern for the store picker.
- `keevo/app/lib/features/inventory/presentation/page/global_stock_overview_page.dart` SliverAppBar `actions` (~:141, owner block) -- add « Exporter » prefilled {stock_levels}, storeId = page's selected store if any.
- `keevo/app/lib/features/pos/presentation/page/sales_history_page.dart` -- `PosAppBar(actions:)` (`pos/presentation/widget/pos_app_bar.dart`), owner only: prefill {sales, sale_items}, storeId = effective store, from/to from the current `SalesHistoryFilter` (`_buildFilter`, fields `from`/`to`).
- `keevo/app/lib/features/reports/presentation/page/owner_reports_page.dart` AppBar (~:64) -- add actions « Exporter » prefilled {sales, sale_items, day_closures, reports}, storeId = `activeStoreIdProvider`.
- `keevo/app/lib/features/settings/presentation/page/settings_page.dart` owner block (~:181) -- tiles « Exporter mes données » (`/exports/new`) and « Mes exports » (`/exports`).
- `keevo/app/lib/features/settings/presentation/widget/plan_limit_bottom_sheet.dart` -- offer sheet style; add `showPremiumFeatureSheet({context, title, message})` there whose CTA (key `premium_feature_cta`) pops then `context.push('/settings/subscription')`.
- `keevo/app/lib/features/settings/presentation/provider/subscription_info_provider.dart` -- `subscriptionInfoProvider` (planType, status).
- `keevo/app/lib/core/router/app_router.dart` -- `_ownerOnlyPrefixes` (:150) + routes next to `/audit` (:675): `/exports/new` (extra `ExportPrefill?`), `/exports`, `/exports/:id`. Declare `/exports/new` before `/exports/:id`.
- `keevo/app/lib/core/notification/fcm_service.dart` (:394 `_onTap` pushes `data['deepLink']`) and `features/notifications/presentation/page/notifications_page.dart:120` -- already push `/exports/{id}`; no change needed beyond the route.
- `keevo/app/lib/features/auth/presentation/provider/auth_provider.dart:119` `dioProvider`; `core/di/providers.dart` `connectivityServiceProvider`, `currentUserRoleProvider`; `features/stores/presentation/provider/active_store_provider.dart` `activeStoreIdProvider`.
- `keevo/app/lib/features/catalog/presentation/provider/csv_import_provider.dart:219-235` -- existing `getTemporaryDirectory` + `Share.shareXFiles` usage to mirror.
- `keevo/app/lib/core/widget/app_error_widget.dart` -- `AppErrorWidget(error:, onRetry:, title:, message:)`.
- Tests patterns: `test/features/audit/data/datasource/remote_audit_datasource_test.dart` (mocktail `MockDio`), `test/features/audit/presentation/page/audit_log_page_test.dart` + `audit_test_helpers.dart` (ProviderScope overrides + GoRouter), `test/core/router/global_auth_redirect_test.dart:63` (owner-only paths list), `test/core/theme/` no-hardcoded-colours guard.

## Tasks & Acceptance

**Execution:**
- `keevo/app/lib/features/export/domain/model/export_models.dart` -- `ExportDataset` (fromJson), `ExportJob` (fromJson; `isExpired(now)`), `ExportFormat {csv, xlsx, pdf}` (+ `apiValue`), `ExportPrefill` (datasets Set, storeId, from, to, criteria Map), `ExportRequest` (datasets, format, storeId, from, to, criteria; `toJson()`), `ExportOutcome` sealed: `ExportFileReady(File file)` / `ExportQueued(ExportJob job)`; `buildExportRequest(selection, format, storeId, from, to, criteria, datasets)` pure function applying the criteria rule.
- `keevo/app/lib/features/export/domain/exception/export_exception.dart` -- `ExportException(domainCode, message, statusCode)` + `isPlanGate`, `isExpired`.
- `keevo/app/lib/features/export/data/remote_export_datasource.dart` -- `getDatasets`, `listJobs`, `getJob`, `requestExport(ExportRequest, {required Directory dir})`, `downloadJobFile(ExportJob, {required Directory dir})`; streaming to `<dir>/exports/`, filename parsing/sanitizing, stream error-body decoding, error mapping; deletes partial file on failure.
- `keevo/app/lib/features/export/presentation/provider/export_providers.dart` -- `exportDataSourceProvider`, `exportTempDirProvider` (FutureProvider<Directory>, `getTemporaryDirectory`), `exportFileSharerProvider` (typedef `Future<void> Function(File)` default `Share.shareXFiles`), `exportDatasetsProvider`, `exportJobsProvider` (FutureProvider list, autoDispose), `exportIsPremiumProvider`, `exportStoreOptionsProvider`, `exportOnlineProvider` (FutureProvider<bool>), `exportJobsRefreshIntervalProvider` (Duration, default 10 s).
- `keevo/app/lib/features/export/presentation/page/export_page.dart` -- « Exporter » screen (sections Quoi / Filtres / Format / bottom FilledButton « Exporter », keys `export-dataset-<key>`, `export-all`, `export-format-<csv|xlsx|pdf>`, `export-submit`, `export-criteria-note`, `export-offline-banner`); disables store/period pickers when no selected dataset supports them; busy state while running; success (direct) → share then SnackBar « Fichier prêt »; queued → dialog/inline card; errors per Always.
- `keevo/app/lib/features/export/presentation/page/my_exports_page.dart` -- « Mes exports » (optional `highlightId`), per Always.
- `keevo/app/lib/features/export/presentation/widget/export_entry_button.dart` -- `ExportEntryButton(prefill:)` icon button, renders `SizedBox.shrink()` unless OWNER, pushes `/exports/new` with extra.
- `keevo/app/lib/features/audit/presentation/page/audit_log_page.dart`, `inventory/.../global_stock_overview_page.dart`, `pos/.../sales_history_page.dart`, `reports/.../owner_reports_page.dart` -- add the entry button per Code Map.
- `keevo/app/lib/features/settings/presentation/page/settings_page.dart`, `settings/presentation/widget/plan_limit_bottom_sheet.dart` -- tiles + `showPremiumFeatureSheet`.
- `keevo/app/lib/core/router/app_router.dart` -- prefix + 3 routes.
- `keevo/app/pubspec.yaml` -- version `1.1.34+33`.
- Tests (new `keevo/app/test/features/export/`): datasource (200 stream → file + name, fallback name, 202 → queued job, 403/410/429 stream error bodies decoded, network error, criteria present/absent in body, download file), `buildExportRequest` unit tests (criteria rule, UTC instants, PDF never built), `export_page_test` (datasets rendered, « Tout », free+Excel → offer sheet, PDF disabled « Bientôt », audit prefill + note when adding a dataset, error + Réessayer replays, offline banner + disabled launch + local tile, direct → sharer called, queued → confirmation), `my_exports_page_test` (statuses, expired READY, Ouvrir → sharer, 410 message, FAILED Relancer, load error AppErrorWidget retry, highlight + auto-open for `/exports/:id`), audit_log_page test for `audit-export` prefill (extend existing file), `global_auth_redirect_test` adds `/exports`, `/exports/new`, `/exports/x`.

**Acceptance Criteria:**
- Given a Free plan, when the owner taps Excel, then the offer sheet opens and no XLSX request is sent.
- Given a Premium owner launching a large export, when the server answers 202, then the confirmation is shown and the job is listed in Mes exports after reopening the app (server-side list).
- Given any export failure, when it happens, then a clear French message with « Réessayer » is shown and retry resends the same request.
- Given the « Historique des actions » with active filters, when the owner taps « Exporter », then the Exporter screen opens with only « Journal d'audit » selected, the same store and period, and the request carries the audit criteria.
- Given an `EXPORT_READY` notification, when tapped, then Mes exports opens with that export highlighted and its file is offered in the share sheet.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 38 findings — high 0, medium 13, low 15, false 9, maybe-false 1
- findings:
  - `[medium]` `[patch]` (blind) Retry replays stale `_lastRequest` after the form changed — `_error`/`_lastRequest` now cleared on dataset, « Tout », format, store and period changes.
  - `[low]` `[patch]` (blind) Prefilled keys missing from the server catalogue stay selected and are sent (400) — dropped once when the datasets load.
  - `[medium]` `[patch]` (blind) `_writeFile` ignores backpressure and reports disk errors as network errors — `sink.addStream`, dir creation inside try, `FileSystemException` → `STORAGE_ERROR`.
  - `[medium]` `[patch]` (blind) Temp exports never cleaned, same-name downloads overwrite each other — per-download folder `exports/<µs>/<name>`, best-effort purge of entries older than 24 h.
  - `[low]` `[reject]` (blind) No CancelToken on dispose — the download completes and is purged within 24 h; adding cancellation plumbing is complexity for a rare case.
  - `[medium]` `[patch]` (blind) Offline check is one-shot — banner gets « Réessayer » (`export-offline-retry`) re-checking connectivity and reloading datasets.
  - `[low]` `[patch]` (blind) `/exports/:id` with unknown id fails silently — snackbar « Export introuvable ou expiré » once.
  - `[low]` `[reject]` (blind) FAILED jobs show no reason — the server `errorCode` is technical (`EXPORT_QUEUE_FULL`, stale) and « Échec » + « Relancer » is the actionable state; cosmetic.
  - `[false]` `[reject]` (blind) Prefill gaps (stock store, reports period, sales employee scope) — the stock overview spans every store (no store selection on that page); the reports hub has no single period across tabs; `sales_history_page` `_buildContent` uses `employeeId = null` for OWNER, so the owner's list is the whole store, matching the export.
  - `[medium]` `[patch]` (blind) Missing tests (mid-stream failure, entry buttons, Settings tiles, unknown deep-link id) — added (grouped with the verification-gap rows).
  - `[low]` `[reject]` (blind) Period presets use device time zone, not WAT — same convention as `audit_log_page.dart` / sales history; the target devices run in WAT; changing it would diverge from the screens the prefill comes from.
  - `[low]` `[patch]` (blind) Hidden file name possible after truncation — dot check moved after truncation.
  - `[low]` `[patch]` (blind) Format tiles not announced as selected — `Semantics(container, selected, inMutuallyExclusiveGroup)`.
  - `[low]` `[reject]` (blind) `build()` side effects in Mes exports — timer/auto-open are guarded (one-shot flag, idempotent sync) and covered by tests; refactor without user-visible harm.
  - `[medium]` `[patch]` (edge) Unknown plan + Excel + 403 leaves `_format` on XLSX — reset to CSV on plan gate.
  - `[medium]` `[patch]` (edge) Offline banner never clears — same fix as the blind offline row.
  - `[low]` `[patch]` (edge) Prefill key not in catalogue — same as the blind row.
  - `[medium]` `[patch]` (edge) Disk error mapped as network, `create()` outside try — same as the blind writer row.
  - `[medium]` `[patch]` (edge) Concurrent same-name downloads — per-download folder.
  - `[low]` `[patch]` (edge) Truncated name starting with '.' — same as the blind row.
  - `[low]` `[reject]` (edge) Malformed 200 JSON raises TypeError — both pages catch generic errors (`ExportException.unexpected` card / `AppErrorWidget`); the backend contract is typed; guard not worth the branches.
  - `[false]` `[reject]` (edge) Non-string `domainCode` — `GlobalExceptionHandler` always serialises `domainCode` as a string.
  - `[false]` `[reject]` (edge) 2xx other than 200/202 — `ExportController.request` only returns 200 (file) or 202 (job).
  - `[false]` `[reject]` (edge) FAILED job with empty datasets — jobs are only created from a validated non-empty `datasets` list (`ExportController.parse`).
  - `[low]` `[patch]` (edge) 404/409 on « Ouvrir » leaves stale card — list invalidated for 404/409 as for 410.
  - `[low]` `[patch]` (edge) Unknown highlight id gives no feedback — same as the blind row.
  - `[low]` `[reject]` (edge) Highlighted job not scrolled into view — list is newest first (≤ 50 jobs) and a notification targets a fresh job near the top.
  - `[false]` `[reject]` (edge) Prefill with from after to crashes the picker — no entry point builds such a prefill (audit and sales pickers produce ordered ranges).
  - `[false]` `[reject]` (edge) Stock prefill ignores selected store — the overview has no store selection; `highlightedStoreIdProvider` is a navigation hint for another page.
  - `[medium]` `[patch]` (verification-gap) Real `appRouter` export routes unresolved in tests — `test/core/router/export_routes_test.dart` (`findMatch` for `/exports/new`, `/exports/abc`, `/exports`).
  - `[medium]` `[patch]` (verification-gap) Stock / reports / Settings entry points untested — stock button test, Settings tiles OWNER/EMPLOYEE, `ExportEntryButton` test (owner reports page too heavy to pump).
  - `[medium]` `[patch]` (verification-gap) `exportIsPremiumProvider` never exercised — `export_is_premium_provider_test.dart` (FREE/PREMIUM/EXPIRED/error).
  - `[medium]` `[patch]` (verification-gap) Partial-file deletion untested — mid-stream failure test + `STORAGE_ERROR` test.
  - `[false]` `[reject]` (intent) Free + PDF does not open the offer sheet — the intent itself says PDF is « bientôt »/disabled until 18-3; enabling PDF + Free gate routed to v1s-18-3.
  - `[false]` `[reject]` (intent) « Survives closing the app » not tested at lifecycle level — background jobs are server-side by design (intent: « 202 job », « GET /exports »); Mes exports lists them from the server.
  - `[false]` `[reject]` (intent) FCM tap path not exercised — `fcm_service.dart` / notifications page already push `deepLink` verbatim; the route resolution is now tested on the real router.
  - `[maybe-false]` `[reject]` (intent) Real `share_plus` / `path_provider` / connectivity never exercised — needs a device run; injected seams are the standard test approach here; would be low if real.
  - `[low]` `[patch]` (intent) Mes exports errors had no retry — `SnackBarAction` « Réessayer » repeats « Ouvrir » / « Relancer » (no retry for 404/409/410: list refreshed instead).

## Design Notes

- Background jobs survive app close because they live server-side (`GET /api/v1/exports`); the app keeps no local job store.
- « Relancer » on a failed job cannot restore audit criteria (not in `ExportJobDto`); acceptable — Mes exports only lists background (Premium) jobs and the user can re-run from the Exporter screen.
- Mes exports auto-refresh uses a `Timer.periodic` only while a PENDING/RUNNING job is shown; tests override the interval provider and use `tester.pump(duration)` — no real sleeps.

## Verification

**Commands:**
- `cd keevo/app && flutter analyze` -- expected: 0 errors, 0 warnings (infos tolerated).
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/features/export` -- expected: all pass.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core test/features/audit test/features/settings test/features/inventory test/features/pos test/features/reports` -- expected: 0 failures (one lot at a time; never a full `flutter test`).

## Auto Run Result

Status: done

**Summary:** New Flutter feature `features/export`: « Exporter » screen (datasets from `/exports/datasets`, « Tout », store + period filters disabled when no selected dataset honours them, CSV / Excel with Premium badge + offer sheet / PDF « Bientôt »), direct export streamed to a per-download temp folder then shared via `share_plus`, background export confirmation; « Mes exports » (status, size, expiry, Ouvrir → download + share, Relancer, auto-refresh while active, highlight + auto-open from `/exports/:id`); OWNER-only routes and Settings tiles; pre-filled « Exporter » on Historique des actions (Journal d'audit + AuditFilter, criteria only when alone), Stock, Ventes, Rapports; offline banner with retry and disabled 18-5 entry point.

**Files changed:**
- `keevo/app/lib/features/export/**` -- new feature (models + `buildExportRequest`, `ExportException`, `RemoteExportDataSource`, providers, `ExportPage`, `MyExportsPage`, `ExportEntryButton`, presenter).
- `keevo/app/lib/core/router/app_router.dart` -- `/exports` owner-only prefix, routes `/exports/new`, `/exports`, `/exports/:id`.
- `keevo/app/lib/features/audit/presentation/page/audit_log_page.dart` -- `audit-export` button + `auditExportPrefill`.
- `keevo/app/lib/features/{inventory,pos,reports}/presentation/page/*` -- pre-filled Exporter buttons.
- `keevo/app/lib/features/settings/presentation/page/settings_page.dart`, `widget/plan_limit_bottom_sheet.dart` -- tiles + `showPremiumFeatureSheet`.
- `keevo/app/pubspec.yaml` -- 1.1.34+33.
- Tests: `test/features/export/**` (58), `test/core/router/export_routes_test.dart`, `global_auth_redirect_test.dart`, `audit_log_page_test.dart`, `global_stock_overview_page_test.dart`, `settings_page_test.dart`.

**Review:** 38 findings — 22 patch rows (13 medium, 9 low; several grouped by root cause), 0 deferred, 16 rejected with reasons in the triage log. Routed: enable PDF (+ Free gate, template choice) → v1s-18-3; wire offline local-CSV entry → v1s-18-5.

**Follow-up review recommendation:** true — two or more medium entries were patched on a first pass; unverified risk: the patched temp-folder purge / per-download layout and the snackbar retries were reviewed only by their own tests, not by a second review pass.

**Verification:** `flutter analyze` 0 errors / 0 warnings (613 infos). Chunked app lots: export 58, core 468, audit 79, settings 48, inventory 181, pos 229, reports 42 — all pass.

**Residual risks:** no device run (share sheet, real temp dir, real server streaming); owner reports page button only covered through `ExportEntryButton`; Relancer cannot restore audit criteria (not in the job DTO).

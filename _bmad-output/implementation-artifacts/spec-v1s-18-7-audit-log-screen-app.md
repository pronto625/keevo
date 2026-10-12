---
title: 'v1s-18-7 — Historique des actions (app): filterable audit list + clickable detail'
type: 'feature'
created: '2026-10-12'
status: 'done'
baseline_revision: '08283096f23a209f38223ec139111246459bded1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-18-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-v1s-18-6-audit-enrichment-backend.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The app's `/audit` screen ("Journal d'audit") is a flat, non-clickable list built for stock history: no filters, raw phone numbers as actors, no store/device/source, no detail, no before/after table. Meanwhile 18-6 shipped an enriched, OWNER-only REST API (filters, `nextCursor`, `GET /audit/{id}`) but the sync pull still ships the full audit log (with justification metadata) to EMPLOYEE devices, and employee events have no readable object label (FR84, FR98).

**Approach:** Replace `/audit` with an owner-only « Historique des actions » screen (readable sentences, day groups, filter chips, cursor infinite scroll, offline fallback on the synced Drift copy) and a detail page (Qui / Quoi / Sur quoi / Quand et où / Changements / Contexte). Keep the old screen as « Mouvements de stock » filtered on stock movements. Backend: role-aware pull (audit delta OWNER-only), employee name as read-time label fallback, and an object search parameter `q`.

## Boundaries & Constraints

**Always:**
- Screen, detail and their routes stay OWNER-only (router prefix `/audit` already guarded; settings tiles inside the `isOwner` section).
- REST first; on network failure/offline, read the Drift `audit_entries` copy and show an « Hors ligne — dernier état connu » banner. Detail falls back to the local row.
- Unknown actions/fields render generically (humanized code, raw key/value) — never crash, never hide the entry.
- Amount keys (`totalAmount`, `discountAmount`, `appliedPrice`, `cataloguePrice`, `totalRevenue`, prices) render as XAF (`15 000 XAF`); quantity keys as `n unité(s)`; dates in local time `dd/MM/yyyy HH:mm`.
- Colors only via `ColorScheme`/`AppTheme` tokens (guard `no_hardcoded_colors_test`); errors via `AppErrorWidget` with Réessayer.
- Old-format rows (null enrichment fields, context still in `value_after`) list and open without error.
- Backend changes are additive and backward compatible; the pull contract for OWNER is unchanged.
- TDD, failing test first per behaviour. Bump `keevo/app/pubspec.yaml` 1.1.32+31 → 1.1.33+32.

**Never:**
- No Exporter button in this story (export flow does not exist yet — routed to v1s-18-4).
- No write path on audit rows; no change to `stock_history_widget.dart` value_after parsing.
- No new backend endpoint; no dashboard change; never write `deferred-work.md`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner online | open `/audit` | first 20 entries newest first, grouped by day (« Aujourd'hui », « Hier », `dd MMMM yyyy`), each: icon, sentence « **Awa** a créé le produit **Pagne wax** », store, time | — |
| Scroll end | `nextCursor` non-null | next slice appended via `cursor=`, no duplicates; footer spinner | load-more failure → SnackBar, list kept |
| Filters | period/store/employee/action/object chip or search text | list reloads from first page with `from,to,storeId,userId,action,entityType,q` | invalid → never sent (UI constrained) |
| Offline / network error | no connectivity or Dio connection error | Drift rows matching the same filters (offset paging), banner shown, actor/store names resolved from local `employees`/`stores` | empty local → empty state with offline wording |
| Server error online | 500 on first page | — | `AppErrorWidget` + Réessayer |
| Detail | tap a line | detail page renders immediately from the tapped entry, then refreshes from `GET /audit/{id}` | 404 `AUDIT_ENTRY_NOT_FOUND` → keep tapped data; network error → keep tapped/local data |
| Unknown action | `action = FOO_BAR` | sentence « … a effectué « Foo bar » », generic icon; changes table from raw keys | — |
| Sale entry | `entityType=Sale` | « Sur quoi » shows « Vente de 15 000 XAF » when total known + link « Voir la vente » → `/pos/sales-history/:id` | — |
| Employee pull | EMPLOYEE token on `/sync/pull` | `auditEntries: []`, count 0; other entities unchanged | — |
| Owner pull | OWNER token | `auditEntries` as before + enrichment keys stored in Drift | — |
| Employee label | `entity_type='Employee'`, `entity_label` null | REST returns `entityLabel` = employee « Prénom Nom » (read-time) | employee gone → null → app « un employé » |
| Object search | `q=pagne` | rows whose `entity_label` contains `pagne` (case-insensitive, `%`/`_` escaped) | `q` > 100 chars → 400 VALIDATION_ERROR |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/sync/sync/domain/port/in/DeltaEntityProvider.java` -- strategy port; add `default boolean ownerOnly()`.
- `.../sync/sync/application/provider/AuditEntryDeltaProvider.java` -- audit delta; override `ownerOnly()` → true.
- `.../sync/sync/domain/port/in/SyncUseCase.java:32` -- `PullCommand(actorId, tenantId, since, cursor)` + 3-arg ctor; add `boolean owner`.
- `.../sync/sync/application/service/SyncPushService.java:350-405` `pull()` -- provider loop; skip owner-only providers for non-owner (empty rows, state DONE).
- `.../sync/sync/adapter/in/rest/SyncController.java:189-218` -- builds PullCommand; pass `CurrentActor.isOwner()` (`com.keevo.shared.infrastructure.security.CurrentActor`, already used in sync handlers).
- `.../shared/infrastructure/persistence/impl/AuditLogRepositoryAdapter.java:52-72,169,199` -- `SELECT_ENRICHED` + dynamic WHERE; add read-time label fallback join on target employee and `q` filter.
- `.../shared/application/port/AuditPort.java` -- `AuditQuery` record: add `String q`.
- `.../shared/infrastructure/web/AuditController.java` -- list params; add `q` (trim, blank→null, max 100).
- `keevo/app/lib/core/storage/audit_entries_table.dart`, `app_database.dart:93,276` -- Drift table + schemaVersion 28 / migration pattern `migrator.addColumn`.
- `keevo/app/lib/core/sync/rest_sync_service.dart:1409` `_upsertAuditEntries` -- raw INSERT OR IGNORE; extend columns.
- `keevo/app/lib/features/audit/` -- data (remote/local datasource, repository impl), domain (`AuditEntryDto` freezed, repository), presentation (`audit_provider.dart` riverpod codegen, `audit_page.dart`, `stock_history_widget.dart` — keep, used by stock screen).
- `keevo/app/lib/core/router/app_router.dart:156,672-675` -- `/audit` owner-only prefix and route.
- `keevo/app/lib/features/settings/presentation/page/settings_page.dart:180-187` -- « Journal d'audit » tile inside `if (isOwner)`.
- Reuse: `AppErrorWidget(error:, onRetry:, title:)` (`core/widget/app_error_widget.dart`), `SoftIconBadge(icon:, color:)`, `currentUserIdProvider`, `connectivityServiceProvider`, `appDatabaseProvider`, `dioProvider`, `AppTheme.radius*`, `intl` (fr), `SaleDetailPage(saleId:)` loads by id via `saleByIdProvider`.
- No product/employee detail-by-id route exists (`/products/:id/edit` and `/settings/team/:id/edit` need the model in `extra`) → no link for those.

## Tasks & Acceptance

**Execution:**
- `DeltaEntityProvider.java` -- `default boolean ownerOnly() { return false; }` with Javadoc.
- `AuditEntryDeltaProvider.java` -- `ownerOnly()` true.
- `SyncUseCase.java` -- `PullCommand(UUID actorId, String tenantId, Instant since, String cursor, boolean owner)`; legacy 4/3-arg ctors default `owner=false` (fail-closed); update tests that assert `auditEntries` to pass `true`.
- `SyncPushService.pull` -- if `provider.ownerOnly() && !command.owner()`: `entities.put(key, List.of())`, count 0, position DONE, provider not queried.
- `SyncController.pull` -- pass `CurrentActor.isOwner()`.
- `AuditLogRepositoryAdapter` + `AuditPort.AuditQuery` + `AuditController` -- `LEFT JOIN employees te ON a.entity_type = 'Employee' AND te.id = a.entity_id`, select `COALESCE(a.entity_label, NULLIF(trim(concat_ws(' ', te.first_name, te.last_name)), ''))`; `q` → `a.entity_label ILIKE :q ESCAPE '\'` (escaped wildcards); controller validates length.
- Backend tests: `SyncPushServicePullOwnerOnlyTest` (or extend existing pull test: employee gets empty `auditEntries`, provider never called; owner unchanged); `SyncController` pull test asserts `owner` flag from role; `AuditControllerTest` (`q` bound, >100 → 400); `AuditLogEnrichmentPostgresIntegrationTest` (employee label fallback, `q` filter with `%` literal).
- `audit_entries_table.dart` + `app_database.dart` -- nullable `storeId, entityLabel, deviceId, source, metadata`; schemaVersion 29, `if (from < 29)` addColumn ×5; run build_runner.
- `rest_sync_service.dart` `_upsertAuditEntries` -- insert the 5 new keys (absent → null).
- `audit_entry_dto.dart` -- add nullable `storeId, storeName, entityLabel, deviceId, source, actorName`, `metadata` as JSON string (converter accepts Map or String from REST/Drift).
- `domain/model/audit_filter.dart` (new) -- immutable `AuditFilter{from,to,storeId,userId,action,entityType,query}` + `copyWith`/clear, `isEmpty`; `AuditSlice{entries, nextCursor, isOffline}`.
- `domain/repository/audit_repository.dart` -- add `getAuditSlice(AuditFilter, {String? cursor, int size})` and `getAuditEntry(String id)` (keep `getAuditHistoryPage` for the stock screen).
- `remote_audit_datasource.dart` -- `GET /api/v1/audit` with filters (`from/to` ISO UTC) + `cursor`; parse `nextCursor`; `GET /api/v1/audit/{id}`; map 404 to `AuditException(domainCode: AUDIT_ENTRY_NOT_FOUND)`.
- `local_audit_datasource.dart` -- same filters in SQL (`occurred_at` text compare via Drift-stored format, `entity_label LIKE ? ESCAPE '\'`), offset cursor `local:<n>`, `LEFT JOIN stores` / `employees` (by `user_id`; by `id` for Employee label) for names; `getById`.
- `audit_repository_impl.dart` -- online → remote; `DioException`/connection failure or offline → local with `isOffline=true`; HTTP 4xx/5xx other than connectivity rethrown (shown by `AppErrorWidget`); detail: remote, on failure local, else rethrow.
- `presentation/audit_presenter.dart` (new, pure Dart) -- action catalogue (label, verb phrase, icon, category) for every listener action (`USER_*`, `PRODUCT_*`, `PRICE_OVERRIDDEN`, `STOCK_*`, `CLIENT_*`, `SUPPLIER_*`, `CATEGORY_*`, `STORE_*`, `EMPLOYEE_*`, `ACCOUNT_DELETION_*`, `SALE_*`, `DAY_CLOSED`, `INVENTORY_*`, `PASSWORD_RESET*`, `ONBOARDING_COMPLETED`), generic fallback; `sentence(entry, selfUserId)`; `objectLabel(entry)` (entityLabel, else sale total from value_after, else type noun); `changes(entry)` → rows `{field label, before, after}` with XAF/units/status translation, lists summarized « n article(s) »; `context(entry)` from `metadata` (justification, motif, scope, isAutomatic → « Automatique »), keys `actorLabel`/`storeId`/`occurredAt` hidden; `sourceLabel` (« En ligne », « Synchronisé depuis l'appareil … », « Automatique (système) », null → « Non renseigné »).
- `presentation/provider/audit_log_provider.dart` (new, riverpod codegen) -- `AuditLogFilterNotifier`, `AuditLogNotifier` (first slice, `loadMore` with cursor, `isOffline`), `auditEntryDetailProvider(id)`; filter option providers (stores, employees from Drift).
- `presentation/page/audit_log_page.dart` (new, route `/audit`) -- title « Historique des actions », filter chips row (Période: Aujourd'hui/7 jours/30 jours/Personnalisé via date range picker; Boutique; Employé; Action; Objet + search field), offline banner, day-grouped list, tile tap → `/audit/entry/:id` with `extra: entry`, pull-to-refresh, empty state « Aucune action pour ces filtres ».
- `presentation/page/audit_entry_detail_page.dart` (new, route `/audit/entry/:id`) -- sections Qui (name, role, appareil), Quoi, Sur quoi (+ « Voir la vente » for Sale), Quand et où (date, boutique, source), Changements (Avant → Après table; « Aucun changement détaillé » when empty), Contexte (hidden when empty).
- `presentation/page/stock_movements_page.dart` (renamed from `audit_page.dart`, route `/audit/stock-movements`) -- title « Mouvements de stock », `StockHistoryWidget(entityType: 'StockMovement')`.
- `app_router.dart` -- routes `/audit`, `/audit/entry/:id`, `/audit/stock-movements`.
- `settings_page.dart` -- tile « Historique des actions » (`/audit`, subtitle « Qui a fait quoi, où et quand ») + tile « Mouvements de stock » (`/audit/stock-movements`).
- `pubspec.yaml` -- version 1.1.33+32.
- App tests (`test/features/audit/...`): presenter (known/unknown actions, XAF, legacy rows, metadata context), local datasource filters/paging/name joins (in-memory Drift), repository online/offline/fallback/404, remote datasource query params + nextCursor, list page widget (groups, filters reload, offline banner, error+retry, infinite scroll), detail page widget (sections, sale link, generic action); `test/core` sync upsert of enriched keys and Drift migration if a migration test exists.

**Acceptance Criteria:**
- Given an owner, when tapping an audit line, then the detail shows actor, action, object, time, store, source and the field-by-field changes.
- Given no connectivity, when the owner opens « Historique des actions », then the last synced entries are listed with the offline banner, and unknown action types use a generic rendering.
- Given an employee, when navigating to `/audit` or opening Réglages, then the screen and its tiles are not reachable/visible, and their pull returns no audit entries.
- Given the settings screen, when the owner opens it, then « Historique des actions » and « Mouvements de stock » are both available, the latter showing only stock movements.

## Spec Change Log

## Review Triage Log

### 2026-10-12 — Review pass
- verdicts: 38 findings — high 0, medium 7, low 24, false 7, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) `q` misses labels shown from the employee-name fallback — search runs on the displayed COALESCE expression, backend + local, tests added.
  - `[low]` `[reject]` (blind) offline LIKE is ASCII-only case-insensitive for accents — offline-only edge on the first accented letter; fix needs a folded column; online path correct.
  - `[medium]` `[patch]` (blind) load-more connectivity failure restarts the local copy at offset 0 and appends it to the server list — server-cursor failures now rethrow (footer « Réessayer »); local fallback kept for the first page only.
  - `[medium]` `[patch]` (blind) a refresh can be overwritten by an in-flight loadMore — result discarded when a rebuild happened (generation guard), test added.
  - `[medium]` `[patch]` (blind) employee devices keep audit rows pulled before the owner-only gate — non-OWNER pull deletes local `audit_entries` and skips the upsert, test added.
  - `[low]` `[reject]` (blind) pre-v29 local rows never get enrichment fields (INSERT OR IGNORE) — offline-only, rows age out of the 30-day window, online path shows full data; a re-pull would cost a full audit download.
  - `[low]` `[patch]` (blind) migration upgrade untested — same root cause as the verification-gap row; v28→v29 upgrade test added.
  - `[low]` `[reject]` (blind) offline date filter compares text with the device offset — rows and bounds share the device offset (WAT, no DST); breaks only after a device timezone change.
  - `[low]` `[patch]` (blind) detail shows the raw device id — uses `AuditPresenter.deviceLabel`.
  - `[low]` `[reject]` (blind) « Rôle » is inferred — the Drift `employees` table has no role column and a tenant has one owner; showing « Non renseigné » for unknown actors is honest.
  - `[low]` `[reject]` (blind) detail falls back to the local row on 403/500 — matches the spec matrix (keep tapped/local data); route is OWNER-only so 403 is not reached; showing cached immutable data on a 500 is harmless.
  - `[low]` `[reject]` (blind) « Mouvements de stock » drops STOCK_FORCED_ZERO / STOCK_THRESHOLD_BREACHED — alerts are not movements; both remain in « Historique des actions » with the Objet filter (now selectable); multi-type filter would add API surface.
  - `[low]` `[reject]` (blind) relative periods resolved when chosen go stale past midnight — rare (screen kept open across midnight); re-picking the chip refreshes; fix adds a period model.
  - `[low]` `[patch]` (blind) code translation applied to any string value (product named "SALE") — limited to code-typed keys, test added.
  - `[low]` `[reject]` (blind) decimal float noise — quantities and amounts are integers in Keevo (XAF, units).
  - `[false]` `[reject]` (blind) pagination stops when hasMore without nextCursor — the adapter always sets `nextCursor` when `hasMore` (`AuditLogRepositoryAdapter.java:196-201`), legacy page mode included.
  - `[low]` `[reject]` (blind) no trigram index for `q` ILIKE — tenant-scale table, combined with indexed filters; documented in Design Notes.
  - `[low]` `[patch]` (edge) backend `q` on fallback label — grouped with the blind row, fixed once.
  - `[low]` `[patch]` (edge) local `q` on fallback label — grouped, fixed once.
  - `[medium]` `[patch]` (edge) loadMore vs refresh race — grouped with the blind row.
  - `[medium]` `[patch]` (edge) build() resets the loading flag so two loadMore overlap — same root cause (no generation guard), fixed once.
  - `[medium]` `[patch]` (edge) server list mixed with local copy — grouped with the blind row.
  - `[low]` `[reject]` (edge) footer spinner forever when the page does not fill the viewport — 20 rows fill a phone viewport; after the mixed-source fix a page always appends rows or ends.
  - `[low]` `[patch]` (edge) StockLevel/StockAlert/InventoryCount/Tenant/User not selectable in « Objet » — added to the object types.
  - `[low]` `[reject]` (edge) STOCK_FORCED_ZERO missing from « Mouvements de stock » — same as the blind row.
  - `[low]` `[reject]` (edge) existing local rows never enriched — same as the blind row.
  - `[medium]` `[patch]` (edge) employee devices keep audit rows — grouped with the blind row.
  - `[low]` `[reject]` (edge) text date compare across offsets — same as the blind row.
  - `[false]` `[reject]` (edge) empty id on `/audit/entry/:id` — only reachable from a list tile which always carries an id; go_router does not match an empty segment.
  - `[low]` `[reject]` (edge) detail masks 401/403/5xx with local data — same as the blind row.
  - `[false]` `[reject]` (edge) Sale objectLabel prefers amount over entityLabel — by design: the stored sale label is the generic « Vente » (Design Notes), the amount is the readable label.
  - `[low]` `[patch]` (verification-gap) v28→v29 Drift upgrade never exercised — upgrade test added.
  - `[low]` `[patch]` (verification-gap other) `_mapError` indexes a non-Map body → TypeError — guarded, test added.
  - `[false]` `[reject]` (intent) Exporter button absent — excluded by the spec boundary because the export flow does not exist; routed as an item to v1s-18-4 (owner of the export buttons, epic dependency « 18.4's Exporter button on the audit screen depends on 18.7 »).
  - `[low]` `[reject]` (intent) « Sur quoi » links only to sales — no product/employee detail-by-id route exists (`/products/:id/edit`, `/settings/team/:id/edit` need the model in `extra`); spec Code Map records it.
  - `[false]` `[reject]` (intent) item 2 has no recorded decision — recorded in the spec Design Notes and epic item ticked.
  - `[false]` `[reject]` (intent) rename also scopes the screen to stock movements — the UX spec defines that screen as the stock movement history; title-only rename would mislabel the full log.
  - `[false]` `[reject]` (intent) backend `q` added — required by the UX « recherche d'objet » filter; additive and validated.

## Design Notes

- Routed item 1 (pull to employees) — resolved: owner-only flag on the provider, role from the authenticated principal (FR98: log is owner-only, so employees get nothing; not « own actions »). Rows already on an employee device stay until `clearAllData` on account switch, and are unreachable (route guard) — accepted.
- Routed item 2 (labels) — resolved partly at read time: employee name via join (also fixes legacy rows); sales have no receipt number in the domain, so the app composes « Vente de X XAF » from `value_after.totalAmount` plus store and time; closures/inventories stay « Clôture de journée » / « Inventaire » with store and date shown beside them — the detail screen needs nothing more. Events are not changed.
- Object search = label text search (`q`) + object type chip, since there is no free-text index; ILIKE on a filtered, indexed range is acceptable at tenant scale.
- Sentence example: `Awa` + `a modifié le prix de` + `Pagne wax 6 yards`; self → « Vous avez … ».

## Verification

**Machine limits (mandatory, 15 GB RAM machine):** never run `mvn test` or `flutter test` on the whole project, never two suites in parallel or in background. Targeted: `MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=A,B -DforkCount=1 -DargLine=-Xmx1536m`; `flutter test --concurrency=4 <files>`. Batches only through `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh`. No `flutter build`/`flutter clean`. Do not touch Docker containers/volumes. Do not commit.

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest=<new/changed sync + audit tests> -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend shared sync` -- expected: 0 failures / 0 errors
- `cd keevo/app && dart run build_runner build --delete-conflicting-outputs` then `flutter analyze` -- expected: 0 errors, 0 warnings
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core test/features/audit test/features/settings test/features/sync_indicator` -- expected: 0 failures

## Auto Run Result

Status: done

**Summary:** `/audit` is now the owner-only « Historique des actions »: readable sentences (`AuditPresenter`, every listener action + generic fallback), day groups, filter chips (period, store, employee, action, object type + label search `q`), keyset infinite scroll on `nextCursor`, pull-to-refresh, `AppErrorWidget` with Réessayer, offline banner reading the synced Drift copy (schema 29, 5 enrichment columns). Tapping a line opens `/audit/entry/:id` (Qui / Quoi / Sur quoi with « Voir la vente » / Quand et où with source and device / Changements Avant → Après in XAF and units / Contexte), refreshed from `GET /api/v1/audit/{id}`. The old screen is kept as « Mouvements de stock » (`/audit/stock-movements`, `StockMovement` only). Backend: owner-only sync delta (`DeltaEntityProvider.ownerOnly`, `PullCommand.owner`), Employee read-time label fallback, `q` filter on the displayed label. App wipes local audit rows on non-OWNER devices. Version 1.1.33+32.

**Files (main):** backend `DeltaEntityProvider`, `AuditEntryDeltaProvider`, `SyncUseCase`, `SyncPushService`, `SyncController`, `AuditPort`, `AuditLogRepositoryAdapter`, `AuditController` (+ tests, new `SyncPushServicePullOwnerOnlyTest`); app `audit_entries_table.dart`, `app_database.dart` (v29, `forTestingWith`), `rest_sync_service.dart`, `features/audit/**` (dto, `audit_filter.dart`, datasources, repository, `audit_presenter.dart`, `audit_log_provider.dart`, `audit_log_page.dart`, `audit_entry_detail_page.dart`, `stock_movements_page.dart`), `app_router.dart`, `settings_page.dart`, `pubspec.yaml` (+ tests).

**Review:** 38 findings (medium 7, low 24, false 7). Patched entries: 3 medium (mixed online/local paging, loadMore/refresh race, employee devices keeping audit rows) and 6 low (search on displayed label, migration upgrade test, device label, code translation by key, object types, non-Map error body). Rejected with reasons in the triage log. Routed: Exporter button → v1s-18-4.

**Follow-up review recommended:** true — 3 medium entries patched (counts: high 0, medium 3, low 6). Unverified risk: the non-OWNER wipe in `RestSyncService._upsertAuditEntries` keys on the stored `user_role` pref; if a first owner pull ever ran before the role is written, that window's audit rows would be dropped locally and never re-sent (online view unaffected).

**Verification:** `flutter analyze` 0 errors / 0 warnings; app batches test/core 464, test/features/audit 77, settings 46, sync_indicator 62, dashboard 94 — all passing; backend batches shared 442, sync 431 — 0 failures / 0 errors; `AuditLogEnrichmentPostgresIntegrationTest` 12/12.

**Residual risks:** backend deploy is manual — employees keep receiving audit deltas until deployed (the app wipe covers it meanwhile); « Rôle » in the detail is inferred locally (no role in the API / Drift employees); offline date filter compares stored text dates (correct for WAT, no DST); pre-v29 local rows stay unenriched offline.


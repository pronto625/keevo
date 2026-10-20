---
title: 'v1s-16-23 Transfers: full history on mobile + origin/destination in the audit trail'
type: 'bugfix'
created: '2026-10-20'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_revision: 'a0bb1a2e92ca57b6e192e3f331921e2149aa08bc'
baseline_commit: 'a0bb1a2e92ca57b6e192e3f331921e2149aa08bc'
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** (1) The mobile transfer list loads only the 20 most recent transfers of the whole company (`transferHistoryProvider()` = page 0, size 20, no paging) and applies the active-store and status filters on the client *after* that limit, so older transfers — and often all of a store's transfers — never show. (2) A transfer reaches the audit trail as `STOCK_ADJUSTED` (movementType `TRANSFER_OUT` / `TRANSFER_IN`) on one store only; neither the event nor the audit metadata names the other store, so « qui a fait quoi » never says where the goods went or came from.

**Approach:** (1) Push the store filter (source OR destination) and the status filter into the query (backend, remote, local) and page the list with progressive loading. (2) Carry the counterpart store's *name* from the transfer services through `StockAdjustedEvent` into the audit metadata (`destinationStoreName` on a sending, `sourceStoreName` on a receipt), labelled « Vers » / « Depuis » in the export and in the app, and complete the app sentence (« … vers Boutique B » / « … depuis Boutique A »).

## Boundaries & Constraints

**Always:** offline-first stays — the list is always read back from local storage, the remote page only refreshes the cache; names only in audit metadata and UI (never a UUID); French labels; bump `keevo/app/pubspec.yaml`; existing `recordOperation` callers (sales, entries, inventory, import…) unchanged; new query params optional (old app versions keep working).

**Decisions (2026-10-20):** legacy transfer audit entries are left as they are — no lookup, no backfill; only transfers made after this fix show « Vers » / « Depuis ». Full spec kept (both goals, ~2 000 tokens) by user choice.

**Never:** no DB migration, no rewrite of existing `audit_log` rows; no change to transfer creation/receipt rules; no change to who may read transfers (employee scope on GET is a separate issue).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Store active | 60 transfers, store X involved in 5 old ones | the 5 appear (sent and received), paged | N/A |
| Scroll end | more rows than one page | next page appended, no duplicate | load failure → « Réessayer » footer |
| Status chip | « Effectués » selected | only COMPLETED, paged from the query | N/A |
| Offline | no network | local pages only, pending transfers included | N/A |
| New sending | A → B | audit: « … transféré du stock de P vers B », Contexte « Vers : B » | counterpart store missing → no row, sentence unchanged |
| New receipt | received in B | « … reçu par transfert du stock de P depuis A », « Depuis : A » | idem |
| Legacy entry | transfer audited before this fix | shown as today (no counterpart) | N/A |

</frozen-after-approval>

## Code Map

Backend (`keevo/backend/src/main/java/com/keevo/`):
- `catalog/stock/adapter/in/web/StockTransferController.java:124` -- GET history; add optional `store`, `status` params.
- `catalog/stock/domain/port/in/GetTransferHistoryQuery.java` -- record (source, destination, from, to, page, size); extend.
- `catalog/stock/application/usecase/GetTransferHistoryService.java` -- forwards to `findByFilters`.
- `catalog/stock/domain/port/out/StockTransferRepository.java:24`, `adapter/out/persistence/StockTransferRepositoryAdapter.java:53,68-88` -- JPA Specification `buildSpec` (AND of equals); add `cb.or(source=store, destination=store)` and status.
- `catalog/stock/domain/service/StockOperationService.java:83-174` -- publishes `StockAdjustedEvent`; resolves storeName via `storeRepository`. Add an overload taking a counterpart store id; the existing signatures delegate with null.
- `catalog/stock/domain/event/StockAdjustedEvent.java` -- add nullable `counterpartStoreName`; keep a constructor with the old arity (3 call sites incl. tests).
- `catalog/stock/application/usecase/ExecuteTransferService.java:117`, `CompleteTransferService.java:86` -- pass destination / source store id.
- `shared/infrastructure/web/AuditEventListener.java:291` -- add `destinationStoreName` (TRANSFER_OUT) or `sourceStoreName` (TRANSFER_IN) to the context map (null dropped by `writeRaw`).
- `shared/infrastructure/export/AuditLabels.java:237` (CONTEXT) -- « Vers » / « Depuis ».
- Tests: `StockTransferControllerTest`, `GetTransferHistoryServiceTest`, `StockTransferRepositoryAdapterTest`, `AuditEventListenerTest` (l.463, 626), `StockModificationNotificationListenerTest`, transfer service tests.

App (`keevo/app/`):
- `lib/features/inventory/data/datasource/local_stock_transfer_datasource.dart:164`, `remote_stock_transfer_datasource.dart:61`, `data/repository/stock_transfer_repository_impl.dart:149`, `domain/repository/stock_transfer_repository.dart:20` -- add `storeId` (OR) + `status` to `getHistory`.
- `lib/features/inventory/presentation/provider/stock_transfer_provider.dart:44` -- replace the family with a paged notifier (state: items, hasMore; `loadMore`, generation guard, dedup by id) keyed by storeId + status; keep name enrichment. Copy `lib/features/audit/presentation/provider/audit_log_provider.dart` (offset instead of cursor).
- `lib/features/inventory/presentation/page/transfer_history_page.dart` -- drop the client filters (l.172-186); ScrollController + footer spinner/« Réessayer » as in `audit_log_page.dart:96-110,532-560`; refresh + `_TransferTile` invalidations (l.414, 443).
- Other invalidations: `widget/transfer_form_bottom_sheet.dart:156`, `lib/core/sync/sync_trigger_notifier.dart:447`.
- `lib/features/audit/domain/audit_labels.dart:217` -- context labels « Vers » / « Depuis ».
- `lib/features/audit/presentation/audit_presenter.dart:412-462` -- append the counterpart (from metadata) to the STOCK_ADJUSTED transfer sentence (`AuditSentence` has actor/verb/object; extend with an optional trailing part, keep `toString`).
- `lib/features/audit/presentation/widget/stock_history_widget.dart:303-311,371` -- add « vers B » / « depuis A » to the transfer line.
- Tests under `test/features/inventory/...` (page, provider, datasources, repository) and `test/features/audit/presentation/` (presenter, detail page, stock history widget).

## Tasks & Acceptance

**Execution:**
- [x] Backend history filters (controller → query → service → port → adapter) + tests -- store OR + status in SQL.
- [x] Backend counterpart store (StockOperationService overload, event field, both transfer services, listener, AuditLabels) + tests -- names in metadata.
- [x] App data layer (`getHistory` storeId/status in local, remote, repo) + tests.
- [x] App paged notifier + page (infinite scroll, footer, invalidation sites) + tests covering the matrix rows 1-4.
- [x] App audit (labels, presenter sentence, stock history widget) + tests covering rows 5-7.
- [x] `keevo/app/pubspec.yaml` -- bump to 2.0.1+71.

**Acceptance Criteria:**
- Given a store is active, when the transfer list opens and the user scrolls, then every transfer where that store is source or destination is reachable, newest first, without duplicates.
- Given an old app version calling GET history without `store`/`status`, then the response is unchanged.
- Given a new transfer A → B sent then received, when the owner opens each audit entry, then the sending reads « vers B » and the receipt « depuis A », with matching « Vers » / « Depuis » Contexte rows and the same labels in the audit export.

## Implementation Notes

- 2026-10-20 — Backend: `store`/`status` optional params on GET history (old 6-arg `GetTransferHistoryQuery` constructor kept); sort `occurredAt DESC, id DESC`. `StockOperationService.recordOperation(..., notes, counterpartStoreId)` overload; `StockAdjustedEvent.counterpartStoreName` (old arity constructor kept); `AuditEventListener` writes `destinationStoreName` / `sourceStoreName`; `AuditLabels.CONTEXT` « Vers » / « Depuis ». Offline transfers go through `TransferSyncHandler` → `ExecuteTransferService`, so they get the counterpart too.
- App: `getHistory(storeId, status)`; paged `TransferHistory` notifier (name `transferHistoryProvider` kept, so existing invalidation sites are unchanged); page renders a `SliverList` with spinner / « Réessayer » footer; `AuditSentence.tailLead/tail`; stock history line reuses `AuditPresenter.transferCounterpart`. App 2.0.1+71.
- Review patch 1: the remote refresh is sent by store only (no `status`) so status changes on the server refresh cached rows; the status filter applies to the local read. Chip lists stay complete because the sync pull (`StockTransferDeltaProvider`, cursor `GREATEST(occurred_at, updated_at)`) brings every transfer and every status change into the local cache.
- Verification: backend targeted set 147 tests green; Flutter `test/features/inventory` 225, `test/features/audit` 97, `test/core/sync` 316 green; `flutter analyze` on the touched folders: 0 error / 0 warning.
- Routed: GET history employee store scope → v1s-12-9 (reopened).

## Spec Change Log

## Review Triage Log

Pass 1 (2026-10-20) — blind-hunter (BH), edge-case-hunter (EC), verification-gap (VG).

| # | Finding | Verdict | Evidence | Route |
|---|---------|---------|----------|-------|
| 1 | VG/EC/BH: status chip sends `status` to the remote, so a cached IN_TRANSIT row received meanwhile is never refreshed and stays under « En transit » | medium | remote page only returns rows still IN_TRANSIT; sync pull (`rest_sync_service.dart:945`) heals it only at next pull | patch — remote refresh by store only, status applied to the local read |
| 2 | VG: `AuditLogTile` tail spans untested | medium | `audit_log_page_test.dart` has no transfer case | patch |
| 3 | VG: backend `id` tie-break not asserted | low | `GetTransferHistoryServiceTest` checks page/size only | patch |
| 4 | VG: local `id` tie-break not exercised | low | paging test uses distinct `occurredAt` | patch |
| 5 | EC/BH: `_loadMoreFailed` survives store change / provider rebuild → new list starts on « Réessayer », auto-load off | low | flag reset only in `_selectStatus` and refresh | patch |
| 6 | EC: `loadMore` after a failed rebuild re-requests page 0 and replaces the error with the old list | low | `valueOrNull` keeps previous data on AsyncError, `_nextPage` reset to 0 | patch — return when `state.hasError` |
| 7 | BH: shrink-wrapped `ListView` inside the `CustomScrollView` lays out every loaded row on each page | medium | `ListView.builder(shrinkWrap: true, NeverScrollable…)` in a `SliverToBoxAdapter` | patch — `SliverList` |
| 8 | BH: `never()` checks in Execute/CompleteTransferServiceTest only match the 8-arg overload with non-null counterpart | medium | `any(java.util.UUID.class)` rejects null; 7-arg overload unchecked | patch |
| 9 | BH: counterpart logic duplicated in `stock_history_widget.dart` | low | reimplements `AuditPresenter.transferCounterpart` | patch — reuse the presenter |
| 10 | BH: GET history has no employee store scope | medium | pre-existing (POST/complete only use `CurrentActor.assignedStoreId()`) | defer → v1s-12-9 |
| 11 | BH: network failure ends the list silently (repo falls back to local) | low | sync pull fills the local cache; pull-to-refresh retries; fix needs new surface | reject |
| 12 | BH: `hasMore` from page length, `totalElements` dropped | low | one extra empty request at most | reject |
| 13 | BH: invalid `status` value untested | false | `GlobalExceptionHandler:154` maps `MethodArgumentTypeMismatchException`; all four enum values exist server-side | reject |
| 14 | BH: PENDING_SYNC transfers drop out of every chip | false | `_FilterBar` has an « En attente » (PENDING_SYNC) chip | reject |
| 15 | BH: counterpart not persisted on stock movements / movements export | false | intent covers the audit trail; legacy rows left as-is by user decision | reject |
| 16 | BH: adapter test uses mocked CriteriaBuilder | low | existing test style; behaviour covered by local datasource tests | reject |
| 17 | BH: other `findByFilters` callers keep unstable order | false | only caller is `GetTransferHistoryService` | reject |
| 18 | EC: offset paging can skip a row leaving the filter between loads | low | rare; keyset paging is a redesign | reject |
| 19 | EC: counterpart `findById` throwing could mark the tx rollback-only | maybe-false | same pattern as the existing storeName lookup just above; would need a throwing store adapter to show | reject (low if true) |

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='StockTransferControllerTest,GetTransferHistoryServiceTest,StockTransferRepositoryAdapterTest,AuditEventListener*Test,StockModificationNotificationListenerTest,*TransferService*Test,StockOperationService*Test'` -- expected: green.
- `cd keevo/app && flutter test test/features/inventory` then `flutter test test/features/audit` -- expected: green (one folder at a time).
- `cd keevo/app && flutter analyze lib/features/inventory lib/features/audit lib/core/sync` -- expected: no new issue.

---
title: '13.8b — App: held operations stay visible (sales, stock) + French labels for module audit entries'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: 'f2832f72e02c04663de059d2e0dd3fdb033000e1'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-7-sync-module-aware.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-5-entitlement-service.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-8a-module-lifecycle-api.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Since 12.7/13.5 an operation refused with `MODULE_INACTIVE` or `MODULE_READ_ONLY` is **held** on the device (kept, retried). Live test 12.7 showed that while a sale is held, pulls overwrite the local views: the held sale disappeared from « Mes Ventes » and the stock showed the server value (19 instead of 18) until the module came back — the cashier may think the sale is lost and re-enter it (double sale, wrong stock). Now that 13.8a lets the super-admin disable a module (and 13.5 lets a module expire on its own), this is the blocking item before any module is disabled or expires in production. Separately, the owner's history (`/api/v1/audit`) and exports now receive `MODULE_ACTIVATED` / `MODULE_DEACTIVATED` entries with no French labels.

**Approach:** Keep local-only data of held operations through pulls and show held operations explicitly in the lists; add the French audit labels in the three places kept equal by contract tests.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- **Root cause first**: the implementer identifies exactly how the held sale vanished from « Mes Ventes » and how the stock was overwritten (pull reconciliation deleting/replacing local-only rows? list reading server data? stock level replaced by the server value?) and records it in Implementation Notes before fixing.
- **Data rules while an operation is held** (`MODULE_INACTIVE` / `MODULE_READ_ONLY`, and their held follow-ups):
  - the local entity it created or changed (sale, client, product, store, stock movement…) is never deleted or replaced by a pull;
  - local stock deltas of held stock-affecting operations (sales, adjustments, entries, transfers) stay applied on top of the server stock: displayed stock = server stock + pending/held local deltas (same rule the app already applies to pending operations, extended to held ones);
  - when the held operation finally applies, the next pull converges without double counting (deterministic movement ids already guarantee it — verify).
- **Visibility**: « Mes Ventes » lists held sales with a badge « En attente — module non activé » (or « En attente — abonnement expiré » for `MODULE_READ_ONLY`); same badge on a held client/product/supplier/store in its list; the sync queue screen already lists them (keep). No technical code, id or reason string shown; French only.
- **Audit labels**: add `MODULE_ACTIVATED` « Module activé », `MODULE_DEACTIVATED` « Module désactivé », entity type `Module` « Module », and every field key written by 13.8a (`module`, `statut`, `finAbonnement`, `entreprise`, `donnees`) with French labels, in the server `AuditLabels` (`ACTIONS`, `ENTITY_TYPES`, `FIELDS`), `keevo/contracts/export-datasets.json`, and the app `AuditPresenter` maps; contract tests green.
- **App version**: bump `pubspec.yaml` 2.0.3+74 → 2.0.4+75.

## Boundaries & Constraints

**Always:** offline-first rules unchanged (Drift + `sync_queue` in one transaction); money operations never lost; no technical data in the UI; Flutter tests by folder (`test/core/sync`, the touched `test/features/...` folders); backend contract tests for audit labels green; live test on the Redmi.

**Never:** no change to backend sync semantics, no new endpoint, no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Held sale + pull | sale held `MODULE_INACTIVE`, pull runs | sale still listed with badge; stock = server − 1 | — |
| Held client + pull | client held | client still listed with badge | — |
| Held then applied | module re-enabled, retry applies | badge gone; stock = server value; no duplicate sale/movement | — |
| Read-only hold | `MODULE_READ_ONLY` | badge « abonnement expiré » | — |
| Normal pending sale | online, not held | unchanged behaviour | — |
| Audit entry | `MODULE_ACTIVATED` in history/export | « Module activé », French field labels | — |

</frozen-after-approval>

## Code Map

- `keevo/app/lib/core/sync/rest_sync_service.dart` (pull reconciliation, held handling), `sync_rejection_policy.dart`, `sync_monitoring_providers.dart`.
- Sales list: `keevo/app/lib/features/pos/...` (« Mes Ventes »), stock display (catalog/inventory providers).
- Audit: `keevo/app/lib/features/audit/presentation/audit_presenter.dart`; backend `keevo/backend/src/main/java/com/keevo/shared/infrastructure/export/AuditLabels.java`; `keevo/contracts/export-datasets.json` + their contract tests.

## Tasks & Acceptance

**Execution:**
- [x] Root-cause note (how pulls hid the sale / reset the stock).
- [x] Pull/data rules for held operations + tests (held sale survives a pull, stock delta kept, convergence after apply).
- [x] Badges in « Mes Ventes » and entity lists + widget tests.
- [x] Audit labels in the three places + contract tests; pubspec bump.
- [x] Tick the Story 13.8 Flutter routed item and the 13.8a audit-labels item with evidence.

**Acceptance Criteria:**
- Given a held sale, when pulls run, then the sale stays visible with its badge and the stock keeps its local decrement; when the module comes back, the sale applies once and the stock converges.
- Given `flutter test` on the touched folders and the backend contract tests, then all pass; live Redmi scenario passes.

## Implementation Notes

**Root cause (2026-10-30).** The pull itself was not the culprit: a held operation is still `synced = 0` / `quarantined_at IS NULL`, so `RestSyncService._getPendingEntityIds` / `_getPendingStockKeys` already keep the held sale row and its `(product, store)` stock row out of `_mergePulledEntities`. Two read paths bypassed that protection:
- *Sale hidden* — `SaleRepositoryImpl.getSalesHistory` (« Mes Ventes ») is online-first and returns **only** the server list (`_remote.getSalesHistory`) when online; the local Drift rows are read only as an offline fallback. A held sale is not on the server, so it vanished from the list whenever the device was online, and came back once applied.
- *Stock overwritten* — since 12.7, `hasPendingOperations()` excludes held operations (so held ops do not stop the sync cycle/pulls). But the same method is the guard of the remote-first stock reads: `MultiStoreStockRepositoryImpl.getOverview/getStoreStockDetail` and `StockRepositoryImpl.getLevels/getLevelByStore/getMovementHistory`. With only held ops queued, they read the server (19), **displayed it and wrote it into the local `stock_levels`** (`upsertStockLevels` / `upsertLevel`), erasing the local decrement (18). After the module came back, the applied sale + the next pull brought the server's 18, hence the convergence seen live. `ProductRepositoryImpl.syncFromRemote` (archives local products absent from the server) had the same guard and would have archived a held product creation; the client/supplier/store `syncFromRemote` (manual refresh) upsert server rows without any queue check, so a held edit would be replaced.

**Progress — task 2 done (2026-10-30).** `SyncService` gains `hasUnsyncedLocalChanges()` (pending **or held**, not quarantined) and `unsyncedEntityIds()` (= the pull's `_getPendingEntityIds`: pending + held, photos excluded); `hasPendingOperations()` keeps its 12.7 meaning (held not counted, so the sync cycle and pulls keep running). Every "do not overwrite local data with a server read" guard now uses `hasUnsyncedLocalChanges()`: `MultiStoreStockRepositoryImpl.getOverview/getStoreStockDetail`, `StockRepositoryImpl.getLevels/getLevelByStore/getMovementHistory`, `ProductRepositoryImpl.syncFromRemote`; client/supplier/store `syncFromRemote` skip the rows in `unsyncedEntityIds()`; `SignOutService.flushPendingOperations` also returns false while held operations exist (the existing warning: « … seront perdues si un autre compte se connecte »). « Mes Ventes » online: `SaleRepositoryImpl.getSalesHistory` appends the local sales of the filter whose `CREATE_SALE` is still queued (pending or held, not quarantined) and absent from the server list, newest first (a pending, not yet pushed sale is now listed too, without badge — no other change for normal sales). Displayed stock = local `stock_levels` = server stock + pending/held deltas (the pull already skips protected `(product, store)` rows; the remote stock reads no longer write over them). Convergence verified: the SALE movement id is the sale item id and the sale id is shared, so after the held sale applies the pull upserts the same rows (1 sale, 1 item, 1 movement, server stock). Tests: `test/core/sync/rest_sync_service_held_pull_test.dart` (6: held sale survives a pull with stock 18 for both reasons, `hasPendingOperations` false / `hasUnsyncedLocalChanges` true; held client edit not replaced; held → applied → pull converges without duplicates; `heldReasonsByEntity`; labels), `test/features/pos/data/repository/sale_repository_held_history_test.dart` (4), client/store `syncFromRemote` skip tests; mock stubs renamed in the multi-store stock, product, sign-out and settings tests.

**Progress — task 3 done (2026-10-30).** `core/sync/held_operations.dart`: `heldReasonsByEntity` (entity id → dominant held reason of its queued, unsynced, non-quarantined held operations), `heldEntityReasonsProvider` (reads `sync_queue`; invalidated by the sync trigger after every cycle and as soon as a push returns a newly held result), `heldOperationBadgeLabel` (« En attente — module non activé » / « En attente — abonnement expiré »). `core/widget/held_operation_badge.dart`: `HeldOperationBadge(entityId)` — nothing when the entity has no held operation. Shown in « Mes Ventes » (`_SaleHistoryCard`, next to the status), client and supplier cards, `StoreCard` (under the address) and the catalogue `ProductCard` (under the reference). Sync queue screen unchanged (still lists held operations with the French reason). Widget tests: sales history (both reasons, no `MODULE_` text), client + supplier list pages, `ProductCard` (held / not held), new `store_card_test.dart`.

**Progress — task 4 done (2026-10-30).** Server `AuditLabels`: `ACTIONS` + `MODULE_ACTIVATED` « Module activé », `MODULE_DEACTIVATED` « Module désactivé »; `ENTITY_TYPES` + `Module` « Module »; `FIELDS` + `module` « Module », `statut` « Statut », `finAbonnement` « Fin de l'abonnement », `entreprise` « Entreprise », `donnees` « Données » (the values written by 13.8a are already French text, nothing to add to `VALUES`). `keevo/contracts/export-datasets.json` regenerated by `ExportContractFileTest -Dexport.contract.write=true` (only these 10 entries added). App: `auditActionLabels`, `auditEntityTypeLabels`, `auditFieldLabels` (`audit_labels.dart`), `AuditPresenter.catalogue` (`MODULE_ACTIVATED` « a activé le module X », `MODULE_DEACTIVATED` « a désactivé le module X », category Compte) and `objectTypes` `Module` « Modules ». Tests: `AuditChangesFormatterTest.moduleLifecycle` (French « Changements » for both payloads), `ExportContractFileTest` 2/2, app `local_export_contract_test.dart` and new `audit_presenter_test.dart` group « module lifecycle entries » (titles, sentence with the « Administrateur Keevo » actor label, object type, payload text identical to the server). `pubspec.yaml` 2.0.3+74 → 2.0.4+75.

**Progress — task 5 done (2026-10-30).** `epics-refonte-modulaire.md` Story 13.8: the routed held-operations item (live test 12-7) and the 13.8a audit-labels item ticked with evidence.

**Verification (2026-10-30).** Backend `com.keevo.shared.**`: 933 tests, 0 failures/errors (`ExportContractFileTest` 2/2 run separately with the same command, package `com.keevo.reporting.export`). Flutter, one folder at a time, all green: `test/core/sync` 343, `test/features/pos` 281, `test/features/catalog` 184, `test/features/inventory` 225, `test/features/contact` 46+2, `test/features/stores` 59, `test/features/audit` 99, `test/features/export` 138, `test/features/auth` 110, `test/features/settings` 54. `flutter analyze` on every touched Dart file: no error/warning (only pre-existing `directives_ordering` infos). Not done here: live Redmi scenario (orchestrator). Nothing committed.

**Notes for review.** (1) Scope of « held »: an entity gets the badge when one of its own queued operations is held (`entityId`); a sale held as a follow-up of a held client carries the held reason on its own `CREATE_SALE`, so it shows the badge too. (2) A pending (not yet pushed, not held) sale is now also listed in « Mes Ventes » when online — previously it was missing until pushed; no badge for it. (3) The owner's dashboard / day summary online (`computeTodaySummary`, `DashboardRepositoryImpl`) still read server totals only, so a held sale is not counted there until it applies — out of this spec's lists, left as is. (4) Quarantined sales are still not added to the online « Mes Ventes » (they have their own « Bloquées » screen).

**Review patches (orchestrator review 2026-10-30, after the passing Redmi run).**
- *R1 tenant-wide freeze — done.* The guards are now scoped. Stock reads keep the local value only for the `(product, store)` keys of unsynced stock-affecting operations (`SyncService.unsyncedStockKeys()` = the pull's `_getPendingStockKeys`). `StockRepositoryImpl.getLevels` refreshes every other row. `getLevelByStore` reads locally only for a protected key. `getMovementHistory` skips the server only while the product has a protected key. `MultiStoreStockRepositoryImpl.getStoreStockDetail` upserts the unprotected rows and returns the local view when this store has a protected row. `getOverview` stays local while any stock key is protected. `ProductRepositoryImpl.syncFromRemote` skips (no upsert, no archive) only the products in `unsyncedEntityIds()`. Tests: `test/features/catalog/data/repository/stock_repository_held_test.dart` (real `RestSyncService` over Drift; with a held sale, s1 stays 18 and s2 refreshes to 7; a held client edit freezes nothing, so s1 goes to 19), multi-store detail/overview tests (protected row vs another store's row), product `syncFromRemote` (unrelated held → refreshed and archived as before; related → kept and not archived).
- *R2 one definition — done.* `RestSyncService._unsyncedOperations()` is the one selection: not synced, not quarantined (so pending and held), product photo uploads excluded. It feeds `_getPendingEntityIds`, `_getPendingStockKeys`, `unsyncedEntityIds` and `unsyncedStockKeys`. `hasUnsyncedLocalChanges()` is removed (no caller left once the guards were scoped). `heldOperationCount()` is new (held only, for the sign-out wording).
- *R3 referenced ids — done.* `unsyncedEntityIds()` = the entity ids plus every id their payloads reference (same scan as the 12.7 follow-up rule: map keys and values, lists), so a held `CREATE_SALE` protects its client, products and store, and a transfer protects its product and stores. The pull's own `pendingIds` is unchanged. Test in `rest_sync_service_held_pull_test.dart`: references included, photo upload and quarantined operation excluded, stock keys and held count from the same queue.
- *R4 cross-store availability — done.* `getCrossStoreAvailability` still returns the remote figures but no longer writes them over protected keys (test in `stock_repository_held_test.dart`).
- *R5 « Mes Ventes » merge — done.* `_unsentSaleIds()` is read **before** the server call: a sale applied in between comes back from the server and is deduplicated, never lost. It now reads only `entity_id` (`selectOnly`, no payload). Local rows are limited to the filter's period and employee. The remote call is a single page (page 0, size 100), so the merge applies to that page only. `isIn` is chunked by 500 ids. Ids dedupe safely: `SaleSyncHandler` uses the payload `saleId` as the sale's primary key, so server id = device id (`serverEntityId` = saleId); this is documented in the code. The card shows time, item count, client name (provider), amount and badges, never an id. End-to-end page test `test/features/pos/presentation/page/sales_history_held_page_test.dart`: real repository + in-memory DB + mocked remote. It lists 3 cards (server, held, pending), with the badge only on the held one and no UUID or code on screen.
- *R6 badges — done.* `heldEntityReasonsProvider` is invalidated after **every** push (`_handlePushResults`: newly held, applied, quarantined or dropped), not only after a full cycle, and by the « Bloquées » actions (`QuarantinedOperationsSection._refresh`: release, re-attach, abandon). `StoreCard` (now a `ConsumerWidget`) keeps `subtitle: null` (one-line tile) when the store has no address and no hold; the badge alone is the subtitle when it is held without an address. Tests added in `store_card_test.dart`.
- *R7 sign-out wording — done.* `flushPendingOperations` returns false when operations are pending or `heldOperationCount() > 0` (or quarantined). When held operations exist (and none are blocked), the dialog says « … elles seront envoyées automatiquement dès que le module sera réactivé, si vous restez connecté avec ce compte sur ce téléphone. Elles seront perdues si un autre compte s’y connecte. » Tests: `sign_out_service_test` « held operations remain → false », `settings_page_test` « held operations: sent once the module is active again ».
- *R8 backend audit detail — done.* `ModuleLifecycleService` no longer writes `entreprise` (the raw tenant code) into the tenant's own history. The owner knows its company, and the server log line keeps the code. The `entreprise` « Entreprise » label stays in `AuditLabels`/contract/app maps (identical on both sides) so that entries already written on dev still read in French. Tests: `ModuleLifecycleServiceTest` (activate/deactivate: no code, no `entreprise`), `ModuleLifecyclePostgresIntegrationTest.paidModule`, formatter and app presenter payloads without it.
- *R9 tests — done.* Supplier `syncFromRemote` skip test; held-only queue tests for `getLevels` / `getLevelByStore` (`stock_repository_held_test.dart`); a normal pending sale is listed online without badge (`sales_history_held_page_test.dart`). See the Change Log.
- *Verification of the patches.* Flutter, one folder at a time: `test/core/sync` 344, `test/features/pos` 282, `test/features/catalog` 191, `test/features/inventory` 226, `test/features/contact` 49, `test/features/stores` 61, `test/features/auth` 111, `test/features/settings` 55, `test/features/audit` 99, `test/features/sync_indicator` 80, all green. `flutter analyze` on touched files: no error/warning. Backend `com.keevo.shared.**` 933 / 0 failures / 0 errors; `com.keevo.admin.module.**` 12 / 0 (full-context audit check included). Not committed.

**Live verification (orchestrator, 2026-10-30, Redmi, dev APK 2.0.4+75).** Run 1 (before review patches) and run 2 (after): `crm` DISABLED row → airplane mode → new client + sale for that client → reconnect → server `CREATE_CLIENT REJECTED MODULE_INACTIVE`, `CREATE_SALE REJECTED CLIENT_NOT_FOUND`; after ≥ 60 s of pulls « Mes Ventes » shows the sale with « En attente — module non activé » and the stock stays at the local value (16) while the server is at 17 → `crm` back + app restart → applied once (server 4 sales / 3 with client, 4 SALE movements, stock 16), badge gone, device stock 16. During run 1 a carrier USSD popup captured keystrokes; dismissed with Cancel, nothing sent.

## Spec Change Log

- 2026-10-30 (review, accepted by the orchestrator): « Normal pending sale — unchanged behaviour » is read as "no badge, nothing else changes for it". Online, « Mes Ventes » now also lists a sale whose `CREATE_SALE` is not pushed yet (previously missing until the push); it carries no badge. Pinned by `sales_history_held_page_test.dart`.

## Review Triage Log

### 2026-10-30 — Review pass (13-8b)
- verdicts: 2 layers (Blind Hunter 13, Edge+Gap+Intent 10; overlap) — high 4, medium 6, low 6 after dedup
- findings:
  - `[high]` `[patch]` one held op froze every server stock/product read tenant-wide for the whole hold — guards scoped to protected (product, store) keys / entity ids
  - `[high]` `[patch]` cross-store availability wrote server quantities over protected keys (the path that can reset the held decrement) — guarded
  - `[high]` `[patch]` count vs ids used different selections — single `_unsyncedOperations()` query
  - `[high]` `[patch]` held credit sale did not protect its client/products/store from `syncFromRemote` — payload-referenced ids included
  - `[medium]` `[patch]` « Mes Ventes » merge: race, date range, paging, dedupe (server id = device sale id, verified), light query, chunked `isIn`; end-to-end page test
  - `[medium]` `[patch]` badges stale after quarantine/discard — provider refreshed after each push and blocked-ops actions; StoreCard layout kept
  - `[medium]` `[patch]` sign-out wording wrong for held ops — « envoyées automatiquement dès que le module sera réactivé »
  - `[medium]` `[patch]` raw tenant code shown as « Entreprise » in the owner's history — key no longer written
  - `[medium]` `[defer]` held inventory session server-first reads, stock-op badges, owner dashboard/day summary and day closure while a sale is held → routed → 15-8 (blocking before disabling pos/crm in prod)
  - `[low]` `[patch]` supplier skip test, held-only stock repository tests
  - `[low]` `[reject]` stale held `UPDATE_CLIENT` over a newer server edit when the module returns — server LWW decides
  - `[low]` `[reject]` store overview stays local while any stock key is protected — per-store total, accepted
  - `[low]` `[reject]` pending (non-held) sale now listed online without badge — accepted interpretation (recorded in Spec Change Log)

## Verification

**Commands:**
- `cd keevo/app && flutter test test/core/sync` + touched `test/features/...` folders -- expected: green
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` (contract tests) -- expected: green
- Live (Redmi): crm/pos DISABLED row → offline sale (with client) → reconnect → pulls → sale visible with badge, stock −1 → module back → applied once, stock converged.

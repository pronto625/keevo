---
title: '16-13 Employee store scope on every store-specific sync dataset, scope through the pull command, server-side re-seed on store change'
type: 'bugfix'
created: '2026-11-01'
status: 'done'
baseline_revision: 'b3cc5cb377697c66ccd2a027a6c7a47e021aa775'
reprise_baseline_revision: 'cf9402ba20aa80e6cbf096de59e3d01b2d71810c'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 16.13 (`epics-refonte-modulaire.md`, routed from the 16-10 review): the sync pull still sends an EMPLOYEE of store A the stock movements, sales, day closures, inventory sessions/counts, colleagues and reports of every store; the store scope (16-10 transfers) is read from the static security context inside the provider instead of the pull command; after a store reassignment the device keeps the old store's rows and never receives the new store's history (incremental `since`).

**Approach:** Resolve the employee scope once in `SyncController.pull` and carry it in `PullCommand`; every store-specific provider applies it as a bound-parameter filter; the pull response gains an additive `scope` object so the client can detect a change and purge; the server remembers the last pulled scope per device and, when it changes, answers that pull from EPOCH so the new store's history is re-seeded. Device purge itself is the mobile part (React Native, other repo) — documented in the spec's Mobile section; this repo delivers the API only.

## Boundaries & Constraints

**Always:**
- Scope source: `CurrentActor` read **only** in `SyncController.pull` → `PullCommand.scope` (store id + actor user id + `restricted` flag). OWNER / super-admin: unrestricted. EMPLOYEE with a store S: restricted to S. EMPLOYEE without a store: restricted with no store → every store-scoped dataset returns an empty page, never an exception (an exception freezes the cursor). Legacy `PullCommand` constructors fail closed (restricted, no store).
- Employee scope per dataset (OWNER unchanged): `stockMovements` `store_id = S`; `sales` (+ items) `store_id = S` (all sellers of S); `dayClosures` `store_id = S`; `inventorySessions` `store_id = S`; `inventoryCounts` via `session_id IN (SELECT id FROM inventory_sessions WHERE store_id = S)`; `employees` `store_id = S`; `reports` `actor_id = <actor user id>` (REST parity, `ReportController`); `stockTransfers` unchanged predicate (source or destination = S) but scope from the command. Unchanged for everyone: `stores`, `stockLevels` (inter-store availability is an employee feature, REST `MultiStoreStockController` allows it), `products`, `categories`, `clients`, `suppliers`, `invoices`; `auditEntries` stays ownerOnly.
- Filters go through `DeltaPageQuery.fetch(..., filter, params)` with bound parameters; keyset shape (`FROM t WHERE <CURSOR> >= :since ... ORDER BY <CURSOR>, CAST(id AS text)`) kept so `SyncKeysetIndexesMatchProvidersTest` holds; providers read no security context.
- Response (additive, V1-safe): `scope: {"storeId": "<uuid>|null", "restricted": true|false}` on every pull page, plus `"reseed": true` on the page(s) of a pull answered from EPOCH because the scope changed.
- Re-seed: V37 adds `last_scope_store_id` (+ a marker for "known") to `public.user_sync_state`; on the first page of a pull (no cursor), if the device's stored scope differs from the command's (known and different), the pull ignores `since` (EPOCH) and sets `reseed`; the stored scope is updated when that pull completes successfully. First pull of a device (unknown stored scope) is not a change.
- Spec ends with the « Mobile (React Native — dépôt AI/bref) » section and the `m16-13-scope-employe-donnees-synchro` key in the MOBILE block of sprint-status.

**Never:**
- No change to REST endpoints (multi-store stock, reports, sales history), to push handlers, or to product/client/supplier exposure (product decision, out of scope).
- No deliverable change in `keevo/app` (Flutter test client) — the device purge is specified for the RN app only.
- No response shape change other than the additive fields; V1 2.0.1 apps must keep pulling (they ignore unknown keys; they cannot purge — documented limit).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Employee of A pulls | movements/sales/closures/sessions/counts/employees in A and B, reports by owner and employee | only A rows; reports only the employee's own; stores, stock levels, products, clients all stores | — |
| Owner pulls | same data | everything (unchanged) | — |
| Employee without store | — | store-scoped datasets empty; others normal; `scope.restricted=true, storeId=null` | no failure, cursor advances |
| Reassigned A→B, next pull with `since=T` | device last pulled with scope A | pull from EPOCH, `reseed: true`, only B rows; stored scope becomes B after the last page | partial/failed pull → stored scope unchanged, next pull re-seeds again |
| Same scope, normal pull | stored scope A, command A | incremental from `since`, no `reseed` | — |
| First pull of a new device | no stored scope | normal pull (from client `since`), stored scope set | — |
| Paging | > 1000 rows, mixed stores | keyset continues correctly over filtered rows | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/sync/sync/adapter/in/web/SyncController.java:194-224` -- builds `PullCommand` with `CurrentActor.isOwner()`; resolve the scope here (`CurrentActor.assignedStoreIdOrNull()`, `isEmployee()`), set `scope` on the response DTO.
- `keevo/backend/src/main/java/com/keevo/sync/sync/domain/port/in/SyncUseCase.java:34` -- `PullCommand(actorId, tenantId, since, cursor, boolean owner)`; legacy constructors default owner=false (keep fail-closed for the scope too).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/service/SyncPushService.java:493-632` -- pull loop (ownerOnly skip `:514-516`, module skip `:517-521`, failure keeps `since` `:530-534,547-549`), `serverTimestamp`, opaque `PullCursor` (`:581-632`; carry the reseed decision in it so later pages stay from EPOCH); `UserSyncState` update.
- `keevo/backend/src/main/java/com/keevo/shared/application/port/sync/DeltaEntityProvider.java:24-57` -- add `queryDeltaPage(since, after, limit, PullScope)` default delegating to the current method (InvoiceDeltaProvider + ~47 test call sites keep compiling).
- `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/` -- `StockMovementDeltaProvider`, `SaleDeltaProvider` (sale_items follow their sales), `DayClosureDeltaProvider`, `InventorySessionDeltaProvider`, `InventoryCountDeltaProvider`, `EmployeeDeltaProvider`, `ReportsDeltaProvider` (`actor_id` = user id, see `ReportHistoryService.reportActorId:63-68`), `StockTransferDeltaProvider:134,153-166` (drop the `CurrentActor` read, keep `STORE_SCOPE`); `DeltaPageQuery.java:26,50-99` (filter + params, reserved names).
- `UserSyncState` entity/repository (sync module) + `public.user_sync_state` (`V1__baseline_public.sql:372-379`, keyed by device_id/user_id) -- new columns via `V37__sync_scope.sql`.
- `SyncPullResult` / `SyncPullResponseDto` -- add `scope`, `reseed`.
- Tests: `*DeltaProviderTest` (none yet for InventorySession/InventoryCount), `StockTransferDeltaProviderTest`, `StockTransferStoreScopePostgresIntegrationTest` (moves from SecurityContext to command scope), `SyncKeysetIndexesMatchProvidersTest:58-102`, `SyncPullServiceTest`, `SyncPushServicePullOwnerOnlyTest`, `SyncPushServiceModuleGuardTest`, `SyncControllerPullGateTest`, `DeltaPageQueryTest`.
- Mobile reference (behaviour only): `keevo/app/lib/core/sync/rest_sync_service.dart:908-920` (per-store local purge pattern), `:1133-1139` (`kFullPullRequestedKey` full pull), `session_claims_applier.dart:91-97` (store switch from profile); sync protocol and conventions in the Mobile sections of `spec-16-10-*` and `spec-16-11-*`.

## Tasks & Acceptance

**Execution:**
- `SyncUseCase.PullCommand` + new `PullScope` record (sync domain) -- scope field, legacy constructors fail closed -- single source of the scope.
- `SyncController.pull` -- resolve scope from `CurrentActor`, pass it, expose `scope`/`reseed` in the DTO -- only place reading the actor.
- `DeltaEntityProvider` -- scoped overload with default -- compatibility.
- Providers listed in the Code Map -- bound-parameter filters per the Always table; `StockTransferDeltaProvider` takes the scope from the argument -- confidentiality.
- `SyncPushService.pull` -- pass scope to providers; re-seed decision on the first page (stored scope vs command), carried in the cursor; update stored scope only after a successful last page -- re-seed.
- `V37__sync_scope.sql` + `UserSyncState` -- `last_scope_store_id uuid`, `last_scope_known boolean default false` -- per-device scope memory.
- Tests -- unit test per provider (owner unscoped, employee scoped with bound param, employee without store empty), new tests for InventorySession/InventoryCount providers, Postgres integration of a full employee pull over A/B data incl. paging, reassignment re-seed (reseed flag, EPOCH, only B rows, stored scope updated; failed pull keeps the old scope), first-device pull, keyset shape test updated.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the 16.13 items -- tracking.
- This spec -- final section « ## Mobile (React Native — dépôt AI/bref) » (purge on scope change using `scope`/`reseed` and the profile `storeId`, keep rows referenced by unsynced queued operations, full re-pull, V1 limit) + `m16-13-scope-employe-donnees-synchro` in the MOBILE block of `sprint-status.yaml` -- handover.

**Acceptance Criteria:**
- Given an employee of A and data in A and B, when the employee pulls through `POST /api/v1/sync/pull` (real HTTP, local backend), then no movement, sale, closure, inventory session/count, colleague of B nor other users' report is in the response, while stores, stock levels and the catalogue still cover all stores.
- Given the same employee reassigned to B through the owner API, when the device pulls again with its previous `since`, then the response has `reseed: true`, `scope.storeId` = B and B's full history, and the next pull is incremental again.
- Given the Flutter test client on the Redmi signed in as an employee, when it syncs, then sync completes without error (basic check; no purge expected from the Flutter client).

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 26 findings — high 0, medium 5, low 10, false 7, maybe-false 4
- findings:
  - `[medium]` `[patch]` (edge) Owner ↔ storeless employee both stored as null store, never a reseed — `last_scope_restricted` remembered, whole scope compared; tests both directions.
  - `[medium]` `[patch]` (edge) Reassignment between pages of one pull mixes A and B rows and remembers B without reseed — scope pinned in the cursor, a differing later page restarts the pull with `reseed`; test.
  - `[low]` `[reject]` (edge) Client ignoring `hasMore` or a provider failing forever reseeds on every pull — Flutter and V1 clients follow `hasMore` (V1 `rest_sync_service.dart` ~870-894); a permanently failing provider already blocks the pull; cost only.
  - `[low]` `[reject]` (edge) Rolling deploy: old instance misreads the new two-character flags line — single backend instance in production; deploys are stop/start.
  - `[low]` `[reject]` (edge) Legacy 5-arg `SyncPullResult` reports `restricted:true` — the only production construction (`SyncPushService.java:572`) passes the real scope; legacy constructor used by tests only.
  - `[low]` `[reject]` (edge) Concurrent pulls of one device with different scopes race on `rememberScope` — one device runs one pull at a time (client single-flight); worst case one extra reseed.
  - `[false]` `[reject]` (edge) `InvoiceDeltaProvider` not scoped — invoices are tenant-wide and explicitly unchanged by the intent.
  - `[maybe-false]` `[reject]` (intent) Tests split controller / service / serialisation, reassignment simulated by scope — the implementation agent ran the full HTTP flow on a local backend (real login, `PATCH /employees/{id}/store`, reseed observed); if-true low.
  - `[medium]` `[patch]` (intent) R3 vs R3' scope comparison — same root as the first edge row.
  - `[false]` `[reject]` (intent) Reseed returns only 30 days of stock movements — the same 30-day window applies to every first install (`StockMovementDeltaProvider` design); a reseed equals a fresh install.
  - `[low]` `[patch]` (intent) Scoped providers' 3-arg overloads fail open — delegate to `PullScope.NONE`; tests pass `UNRESTRICTED` explicitly (no production caller, `DeltaEntityProvider.java:58` only reaches unscoped providers).
  - `[maybe-false]` `[reject]` (intent) Reseed needs `X-Device-Id` — every client sends it (V1 included); documented in the Mobile section; if-true low.
  - `[false]` `[reject]` (intent) Spec / sprint-status / epics not in the patch — excluded from the review diff on purpose; present in the working tree.
  - `[false]` `[reject]` (intent) TOML rule edits unrelated — committed separately in 4509ed51 (inside the baseline range of this diff).
  - `[low]` `[patch]` (blind) 3-arg overloads fail open — same root as the intent row above.
  - `[medium]` `[patch]` (blind) `restricted` not remembered — same root as the first edge row.
  - `[medium]` `[patch]` (blind) Mid-pull scope change — same root as the second edge row.
  - `[false]` `[reject]` (blind) Devices installed before the release keep cross-store data — the RN app starts from an empty device; the Flutter refonte app is not shipped; V1 cannot purge whatever the server says (documented limit).
  - `[low]` `[reject]` (blind) Reseed re-downloads tenant-wide datasets too — reassignment is rare; per-dataset EPOCH would add state to the single-`since` cursor.
  - `[maybe-false]` `[reject]` (blind) No indexes for the new filters — residual filter on cursor-leading keyset indexes; tenant volumes small; would need EXPLAIN on a large tenant; if-true low.
  - `[false]` `[reject]` (blind) Fallback scope NONE could make an owner app purge — no production path builds the legacy result (see edge row).
  - `[low]` `[patch]` (blind) `rememberScope` writes on every pull — only when unknown or different.
  - `[maybe-false]` `[reject]` (blind) Missing tests list — covered by the patches above (mid-pull, null↔null, row mapper); super-admin branch unreachable (`@PreAuthorize OWNER/EMPLOYEE`).
  - `[false]` `[reject]` (blind) Patch mixes TOML edits and omits handover files — see intent rows.
  - `[low]` `[patch]` (blind) WARN noise for storeless employees, dead null checks, fully qualified UUID — log lowered/reworded, checks removed, import.
  - `[low]` `[patch]` (verification-gap) `findAllByTenantId` with the V37 columns never run on PostgreSQL — integration assertion added.

### 2026-11-10 — Review pass (reprise: stock scope, purchase prices, SQL)
- verdicts: 36 findings — high 0, medium 4, low 27, false 5, maybe-false 0
- findings:
  - `[medium]` `[patch]` (design) New `(store_id, product_id)` index does not serve the store-scoped keyset pull of `stockLevels` — V2 now creates `idx_stock_levels_store_sync (store_id, updated_at, CAST(id AS text))` (shape of `idx_invoices_store_sync`), also leading the store-filtered overview.
  - `[medium]` `[patch]` (design) Gap report computed before the scope check — `InventoryReportController` checks the session's store (`CountingReadScope.requireSessionReadable`, one PK read) before `generateGapReportUseCase`; unit test verifies the use case is never called on a 403.
  - `[low]` `[reject]` (design) Counting reads check scope in the controller with a second session PK read — one indexed PK read per request; moving the scope into three query records adds surface for no measurable gain.
  - `[low]` `[patch]` (design) `COUNT(DISTINCT sl.product_id)` useless under `uq_stock_level (product_id, store_id)` — `COUNT(*)`.
  - `[low]` `[reject]` (design) `ProductMcpTools.quantities` restates the owner / member / storeless rule — catalog may not depend on inventory (`StockReadScopes`); 6 lines, covered by `AiToolsFullContextTest.productSearchStockScope` (owner, two members, storeless member).
  - `[low]` `[reject]` (design) Two UPDATEs (scope then visibility) at the end of a pull — only when the scope or marker changed (once per device), same row, best effort like the existing write; merging changes a port used by 20.2.
  - `[low]` `[patch]` (design) Variable-length `IN (?, …)` list in `CatalogStockQuantitiesAdapter` — one `ANY(CAST(? AS uuid[]))` parameter.
  - `[low]` `[reject]` (intent) V1 tolerance argued, not tested on the V1 client — this repo ships the API only; V1 code read on `release/2.0.x` (Drift `buy_price` NOT NULL → sync sends 0; REST DTO `@Default(0)` → null read as 0).
  - `[low]` `[reject]` (intent) `products.stockQuantity` still sent to members (REST, sync) — decision DD3: legacy creation-time column never maintained by movements (17-11), not another store's stock; `search_products` no longer reads it.
  - `[false]` `[reject]` (intent) "One policy" split at sync — one permission and one role mapping; `PullScope.holds` reads the same `ModulePermissions` mapping resolved in `SyncController`, providers read no security context (16-13 rule); documented hook for 19-14.
  - `[low]` `[reject]` (intent) D3 tested by results, OFFSET kept — the detail must order the whole catalogue (rowless products are « rupture »), so a keyset would need a computed sort key; one statement + top-N sort + index-driven join, total from the window.
  - `[low]` `[patch]` (intent) Tenant isolation asserted on only some paths — `otherTenantReadsNothing` covers product stock, availability, product, counting counts and session list from another company.
  - `[false]` `[reject]` (intent) Spec section absent from the diff — excluded from the review diff on purpose; present in the working tree.
  - `[false]` `[reject]` (blind) Counting write guard lets a storeless member write anywhere — `CurrentActor.assignedStoreId()` throws FORBIDDEN for a store member without a store (CurrentActor.java:157-170).
  - `[medium]` `[patch]` (blind) Gap report before authorization — same root as the design row.
  - `[low]` `[patch]` (blind) Sync `UPDATE_PRODUCT` with the masked 0 untested — `syncPushNeverErasesThePrice` (employee push: price applied, buy price and transport cost kept).
  - `[low]` `[reject]` (blind) Fallback constructors without role mapping hide prices from MANAGER — used only by hand-built unit tests; fail closed (OWNER only); Spring uses the `@Autowired` constructor.
  - `[low]` `[patch]` (blind) DTO masking opt-in, `ProductThresholdResponseDto.from(p)` defaults to visible — one-arg overload removed (visibility now required); `ProductResponseDto` is only built through `ProductController.respond` / `forReader` (all endpoints covered by `lookupAndWriteResponsesMasked` and `restPurchasePrices`).
  - `[low]` `[reject]` (blind) Store-scope rule copied in `ProductMcpTools` — same root as the design row.
  - `[low]` `[patch]` (blind) No end-to-end test for a member without a store — over HTTP a member always has a store (`employees.store_id` NOT NULL, `JwtAuthFilter` reads it from the employee row), so the fail-closed branch is proven by `StockReadScopeTest`, the service tests (no query) and a storeless `search_products` call (stock null).
  - `[medium]` `[patch]` (blind) Index useless for the scoped sync pull — same root as the design row.
  - `[low]` `[reject]` (blind) Index migration not CONCURRENT — module migrations run per tenant inside Flyway transactions like every other module index (invoicing V12/V14/V17); `stock_levels` is small per tenant.
  - `[low]` `[reject]` (blind) V71 committed while V69/V70 are not — V71 is the number reserved for this story by the orchestrator, which cherry-picks the parallel stories in order.
  - `[low]` `[reject]` (blind) Two UPDATEs and a reseed of every member device after deploy — DD6 (deliberate: flush pulled purchase prices and other stores' levels); V1 devices get the overwrite of prices, purge is RN only (documented).
  - `[low]` `[reject]` (blind) `stockQuantity` still exposed — same as the intent row (DD3).
  - `[low]` `[reject]` (blind) An employee's real price edit is dropped silently — rule D2 (only admins write purchase prices); documented in the Mobile section (the app must not send them).
  - `[low]` `[patch]` (blind) Per-size SQL in `CatalogStockQuantitiesAdapter` — same root as the design row.
  - `[low]` `[patch]` (verification-gap) Masking on lookup / create / merge / archive responses untested — `lookupAndWriteResponsesMasked` (barcode lookup as EMPLOYEE / VIEWER / MANAGER, archive response).
  - `[low]` `[patch]` (verification-gap) `search_products` storeless member and untracked product untested — `productSearchStockScope` extended (stock null in both cases).
  - `[low]` `[reject]` (verification-gap other) `stockQuantity` — same as the intent row (DD3).
  - `[false]` `[reject]` (verification-gap other) Stripping prices turns a request into tracking-only — a tracking change is OWNER-only (17.12), so the remaining request is exactly what the actor may do.
  - `[low]` `[reject]` (edge) A device whose pulls keep failing re-seeds forever — same behaviour as the 16-13 scope re-seed (rejected 2026-11-01): a failing provider already blocks the pull.
  - `[low]` `[patch]` (edge) Owner's `search_products` total counted deactivated stores — join on active stores (same as the overview).
  - `[low]` `[patch]` (edge) A stripped edit with nothing left still saved and re-synced the product — `isEmptyEdit()` → no save, no event (unit test).
  - `[false]` `[reject]` (edge) SUPER_ADMIN purchase prices masked — the product endpoints are `@PreAuthorize` OWNER / MANAGER / EMPLOYEE / VIEWER; a super-admin never reaches them.
  - `[low]` `[patch]` (edge) Fail-open one-arg overloads `GetCurrentStockUseCase.execute(id)` / `ProductThresholdResponseDto.from(p)` — removed.

## Design Notes

Why server-side re-seed in addition to the `scope` field: the RN client purges on a scope change, but nothing tells any client to fetch the new store's older rows (the cursor is a single client-held `since`). Answering that one pull from EPOCH fixes it for every client, V1 included; merges are idempotent upserts, so re-sending the tenant-wide datasets is safe. The decision must survive paging (stored in the cursor) and must not be committed until the pull's last page succeeds, otherwise an interrupted pull would lose the re-seed.

Implementation decisions (2026-11-01): `PullScope` lives in `shared.application.port.sync` (next to the `DeltaEntityProvider` SPI it parameterises — module providers such as `InvoiceDeltaProvider` cannot depend on `sync`); `PullCommand` also carries the `deviceId` (re-seed memory); scoped providers keep the 3-arg `queryDeltaPage` as the unrestricted (owner) view for direct callers/tests — the pull always calls the scoped overload. The pull endpoint is `GET /api/v1/sync/pull` (the AC's « POST » is a typo). The pull cursor's flag line carries reseed, remember-at-end and the pinned scope (restricted + store); a later page whose scope differs restarts the pull from the beginning as a re-seed; one-character cursors from before still decode (no pinned scope). The device scope (restricted + store, V37 `last_scope_restricted`) is written only when unknown or different. The scoped providers' 3-arg `queryDeltaPage` fails closed (`PullScope.NONE`).

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.sync.**'` then `'com.keevo.shared.**'`, `'com.keevo.inventory.**'`, `'com.keevo.commerce.**'`, `'com.keevo.reporting.**'`, `'com.keevo.identity.**'`, `'com.keevo.architecture.**'` -- expected: green, one package at a time, never two Maven builds in parallel

**Manual checks (if no CLI):**
- Local backend with `--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`; owner + employee via `/auth/login` + `/auth/select-tenant` (no forged tokens); employee pull before/after reassignment as in the AC; Redmi basic sync as employee (`adb reverse tcp:4500 tcp:4500`, never share sheets / wa.me); stop with `fuser -k 4500/tcp`.

## Reprise 10/11/2026 — store-scoped stock reads and purchase-price visibility

### Intent (reprise)

**Problem:** Items routed to 16-13 by the 21.8 and 21-4-2 reviews: (1) REST multi-store stock reads (`/stock/overview`, `/stock/stores/{id}/products`, `/stock/products/{id}/availability`), the product stock reads (`/products/{id}/stock`, `/stock/history`), the inventory-counting reads and the sync `stockLevels` give a store member (MANAGER, EMPLOYEE, VIEWER) the stock of every store; `search_products` shows a "total stock" that is the frozen legacy `products.stock_quantity`; (2) the purchase price (`buyPrice`, `transportCost`, margins derived from them) reaches every role through `GET /products*`, `/products/{id}/pricing`, the threshold PATCH response and the sync `products` rows; (3) the multi-store overview SQL is O(stores × products) (stores × products cross join before `stock_levels`), the store detail runs the page query plus a second full `COUNT(*)`, and `SUM(quantity * price)` is computed in 32-bit integers.

**User decisions (10/11/2026):** D1 a store member only sees the stock of their own store, everywhere (REST, AI/MCP tools, low stock, sync); the OWNER sees every store. D2 purchase prices are visible only to OWNER and MANAGER (Bref « admin »), hidden from EMPLOYEE and VIEWER in REST, exports, sync and AI tools, additively for V1 2.0.1, behind ONE policy that story 19-14 can replace with a per-role permission. D3 the stock_by_store / low-stock SQL becomes set-based with proper indexes.

**Approach:** D1 — a `StockReadScope` value object (inventory domain) resolved once per request by the inbound adapter (`StockReadScopes.current()` from `CurrentActor`) and carried by the queries to the services/repository; the sync `stockLevels` provider uses the existing `PullScope` store filter. D2 — a module permission `PURCHASE_PRICE_VIEW` declared by the `catalog` manifest (OWNER implicitly, MANAGER via `rolePermissions`), evaluated by one component `PurchasePriceVisibility` (REST, tools, write guard) and by `PullScope.holds(...)` (sync, same `ModulePermissions` mapping); writes from an actor without it never change the stored purchase price. D3 — overview aggregated from `stock_levels` per store then joined to `stores`; detail in one query with `COUNT(*) OVER ()`; new index `stock_levels(store_id, updated_at, id::text)` (serves the store-scoped keyset pull and the store filters).

### Boundaries & Constraints (reprise)

**Always:**
- Scope rule (D1): OWNER → every store; store member with store S → S only; store member without store → nothing (empty lists, 403 on a store-specific read), never an exception that breaks a sync pull. An explicit other store → 403 `FORBIDDEN` « Vous ne pouvez consulter que le stock de votre boutique. » (same message as the 21.8 tool). Tenant isolation unchanged (schema per tenant): another tenant's store id is unknown → empty / 403.
- Surfaces under D1: `GET /stock/overview` (own store line only), `GET /stock/stores/{storeId}/products` (403 outside), `GET /stock/products/{id}/availability` (own store entry only), `GET /products/{id}/stock` (own store level only), `GET /products/{id}/stock/history` (forced to own store, 403 for another), counting reads `GET /inventory/sessions` (forced to own store, 403 other), `/inventory/sessions/active?storeId` (403 other), `/inventory/sessions/{id}/products|counts|gap-report|gap-report/text` (403 when the session is in another store), tools `stock_by_store`, `low_stock_products`, `search_products` (stock = own store; owner = sum of every store), sync `stockLevels` (`store_id = :scopeStoreId`).
- Purchase price rule (D2): hidden = `buyPrice` and `transportCost` set to `null` in REST (`ProductResponseDto` on every products endpoint incl. `POST /products/draft`, `ProductThresholdResponseDto`); `GET /products/{id}/pricing` keeps `productId`/`sellingPrice` and nulls every cost-derived field (`buyPrice`, `transportCostValue`, `totalCost`, `grossMarginXaf`, `marginPercentage`, `isLoss`, `marginThreshold`); `search_products.purchasePrice` null. Sync `products` rows: `buyPrice: 0`, `transportCost: 0` plus additive `purchasePriceHidden: true` (V1 2.0.1 stores pulled products in a NOT NULL column and would fail on null; its REST DTO maps null → 0). Exports, profitability, finance, revenue tools, audit stay OWNER-only (already; MANAGER not widened).
- Writes (D2): `PATCH /products/{id}` and sync `UPDATE_PRODUCT` / `PROMOTE_PRODUCT` from an actor without `PURCHASE_PRICE_VIEW` ignore `buyPrice`/`transportCost` (stored value kept — a device holding the masked 0 must never erase the real price); creation keeps the typed value.
- One policy: the permission constant `PurchasePriceVisibility.PERMISSION` = `PURCHASE_PRICE_VIEW`; every check goes through `PurchasePriceVisibility` (REST / tools / write guard) or `PullScope.holds(PurchasePriceVisibility.PERMISSION)` (sync). 19-14 replaces the role → permission mapping, not the call sites.
- Re-seed (sync): the device memory gains `last_scope_visibility` (V71); a restricted pull whose visibility marker (store-scoped stock + purchase-price visibility) differs from the device's stored one — including unknown (devices from before this change) — is answered from EPOCH with `reseed: true` when the client sent a `since`, so devices drop other stores' levels (RN purge) and overwrite pulled purchase prices. Unrestricted (owner) pulls never re-seed for it.
- SQL (D3): overview = one statement, `stock_levels` aggregated per store (joined to non-archived tracked products) then LEFT JOINed to active stores; optional bound store filter; `SUM(CAST(quantity AS bigint) * price)`. Detail = one statement with `COUNT(*) OVER ()` (count query only for an empty page past the end). Index `idx_stock_levels_store_sync (store_id, updated_at, CAST(id AS text))` in `db/modules/inventory/V2` (review: same shape as `idx_invoices_store_sync`).

**Never:**
- No response shape change except additive (`purchasePriceHidden`) and null/0 values; no endpoint removed; V1 2.0.1 employee pulls must keep working.
- No change to `products.stock_quantity` semantics in REST/sync (legacy display column, documented); no owner-only surface widened to MANAGER.
- No per-row / per-store query loops; no security-context read inside services, repositories or sync providers.

### I/O & Edge-Case Matrix (reprise)

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| Owner overview | stores A, B | both lines | — |
| Member of A overview / availability | A, B stocked | only A's line / entry | — |
| Member of A detail of B | `GET /stock/stores/B/products` | — | 403 FORBIDDEN French message |
| Member without store | any stock read | empty list / 403 on a store read; sync stockLevels empty | no 500 |
| Member of A, `/products/{id}/stock/history?storeId=B` | — | — | 403 |
| Member of A, gap report of a B session | — | — | 403 |
| EMPLOYEE / VIEWER `GET /products` | product buy 600, transport 50 | `buyPrice: null`, `transportCost: null` | — |
| MANAGER / OWNER `GET /products` | same | real values | — |
| EMPLOYEE `PATCH /products/{id}` with `buyPrice: 0` | stored 600 | stored stays 600, response null | — |
| EMPLOYEE sync pull | products | `buyPrice: 0`, `transportCost: 0`, `purchasePriceHidden: true`; stockLevels of A only | — |
| Restricted device pulled before this change (`since` sent) | no stored visibility | `reseed: true`, from EPOCH; next pull incremental | — |
| Other tenant's store id | owner of T2 asks a T1 store | empty overview / empty page | — |

### Code Map (reprise)

- `inventory/stock/domain/model/StockReadScope.java` (new) -- value object; `inventory/stock/adapter/in/StockReadScopes.java` (new) -- resolution from `CurrentActor` (only place).
- `inventory/stock/domain/port/in/Get{MultiStoreOverview,StoreStockDetail,CrossStoreAvailability}Query.java` -- carry the scope; `application/usecase/Get*Service.java` -- apply it (403 via `DomainException.userFacing(FORBIDDEN, …)`).
- `inventory/stock/domain/port/out/MultiStoreStockRepository.java` + `adapter/out/persistence/MultiStoreStockRepositoryAdapter.java` -- store-filtered, set-based SQL (D3).
- `inventory/stock/adapter/in/web/MultiStoreStockController.java`, `adapter/in/mcp/StockMcpTools.java` -- pass the scope.
- `inventory/stock/adapter/in/web/StockController.java:84-97,165-174,210-229` -- product stock reads scoped; threshold response masked.
- `inventory/counting/adapter/in/rest/Inventory{Session,Count,Report}Controller.java` -- session read guard (one PK read).
- `catalog/api/ProductStockQuantities.java` (new, required port like `ProductStockPresence`) implemented in inventory (one grouped query) -- real stock for `search_products`.
- `catalog/product/adapter/in/mcp/ProductMcpTools.java` -- policy + real stock.
- `shared/infrastructure/security/PurchasePriceVisibility.java` (new) -- the single policy; `resources/keevo/modules/catalog/module.json` -- permission + MANAGER mapping.
- `catalog/product/adapter/in/web/ProductController.java`, `ProductImportController.java:163`, `dto/ProductResponseDto.java`, `application/usecase/GetProductPricingUseCase.java` -- masking; `application/usecase/UpdateProductUseCase.java:178-179` + `sync/sync/application/handler/ProductSyncHandler.java:98-99,157-158` -- write guard.
- `sync/sync/application/provider/{StockLevel,Product}DeltaProvider.java` -- store filter, masking; `sync/sync/application/service/SyncPushService.java` (`ScopeKey`, `PullCursor`, `scopeChanged`, `rememberScope`), `UserSyncState`, `UserSyncStateRepository(+Adapter)`, `db/migration/V71__sync_scope_visibility.sql` -- re-seed on visibility change.
- `db/modules/inventory/V2__stock_levels_store_index.sql` -- index.

### Tasks & Acceptance (reprise)

**Execution:**
- Scope plumbing, SQL, services, controllers and tools for D1 (files above) -- confidentiality.
- Policy, manifest, masking, write guard for D2 -- confidentiality and data safety.
- Sync: `stockLevels` store filter, product masking, visibility marker + V71 + re-seed -- devices.
- Tests: unit (scope, services, SQL shape, policy, masking, write guard, sync providers, pull re-seed), Postgres (store filter, bigint value, window total), full-context `StoreMemberStockAndPurchasePriceFullContextTest` proving tenant + store isolation for every read path (REST, tools, sync) and the role matrix for purchase prices.
- Docs: `docs/entitlements/endpoints.md`, `docs/ai/tools.md` regenerated; epics 16.13 routed items ticked; 19.14 brief gets the hook; Mobile section updated; sprint-status.

**Acceptance Criteria:**
- Given owner, manager, employee and viewer of tenant T1 (stores A, B) and a tenant T2, when each calls every D1 surface, then a member only ever gets A's stock (or 403 for B), the owner gets A and B, and nothing of T2 appears in T1 nor the reverse.
- Given a product with buy 600 / transport 50, when EMPLOYEE and VIEWER read it through REST, pricing, threshold PATCH, `search_products` and sync, then no real purchase price appears; MANAGER and OWNER see it; an EMPLOYEE update sending `buyPrice: 0` leaves 600 stored.
- Given a restricted device that pulled before this change, when it pulls with its `since`, then the pull is a re-seed and the following one is incremental.

### Design Notes (reprise)

- **Patterns.** Value Object + Query Object for the stock scope (resolved at the edge, pure below — same shape as `PullScope`); Policy (one component) behind a module permission for the purchase price. **19-14 hook:** when owner-defined roles land, the role → permission mapping (`ModulePermissions.permissionsOf`, or its per-tenant replacement) returns the role's chosen permissions and `PURCHASE_PRICE_VIEW` becomes a checkbox « Voir les prix d'achat »; `PurchasePriceVisibility` and `PullScope.holds` call sites do not change. Required port (Dependency Inversion) `ProductStockQuantities` so catalog keeps no dependency on inventory.
- **Complexity.** Overview: one statement, O(L) over `stock_levels` + hash join products, O(S) stores; with a store filter the new `(store_id, updated_at, id)` index limits it to that store's rows. Detail: one statement, O(P) scan of the catalogue joined by index to the store's levels, top-N sort O(P log k), total from the window (no second scan). Availability: O(S). `search_products` stock: one grouped query, one array parameter, over the ≤ 51 returned ids (index `(product_id, store_id)`), active stores only. Counting guard: one PK read. Sync: stockLevels walks `(store_id, updated_at, id)` — only the member's store rows. Re-seed: once per restricted device.
- **Why 0 + flag in sync and null in REST:** V1 2.0.1 inserts pulled products into `buy_price INTEGER NOT NULL` (Drift `withDefault(0)` does not apply to an explicit NULL) → a null would fail the employee's pull; its REST DTO maps null to 0 (`@Default(0)`). The RN app reads `purchasePriceHidden`.
- **Decisions taken by default:** DD1 MANAGER (« admin ») holds the permission; profitability, exports, finance, revenue tools and audit stay OWNER-only (not widened). DD2 creation by a member keeps the typed purchase price; updates and promotions ignore it. DD3 `products.stock_quantity` (legacy column, never maintained by movements) unchanged in REST/sync; `search_products` uses real `stock_levels`. DD4 counting and movement-history reads count as stock under D1. DD5 the inter-store availability that 16-13 (2026-11-01) kept for employees is now limited to their store (D1 overrides it). DD6 re-seed once per restricted device to flush stored purchase prices and other stores' levels.

### Verification (reprise)

**Commands:** `scratchpad/wt-pkgs.sh <backend> t1613 <pkg>` for `catalog`, `inventory`, `sync`, `shared`, `architecture` (one package at a time; `EndpointEntitlementCoverageTest` / `AiToolCoverageTest` regenerated with `-Dkeevo.entitlements.regenerate=true` / `-Dkeevo.aitools.regenerate=true`) -- expected: green.

**Manual checks:** local backend on port 4513 with its own database `keevo_dev_t1613` (WhatsApp noop, FCM off): owner, manager, employee of store A; `GET /stock/overview`, `/stock/stores/{B}/products` (403), `/products/{id}` and `/pricing` (masked for the employee), `PATCH /products/{id}` by the employee with `buyPrice: 0` (stored price kept), sync pull (`stockLevels` of A only, `purchasePriceHidden`), then a second pull with `since` (re-seed once, then incremental).

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Lire d'abord la section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` (conventions d'API, profil) puis celle de `spec-16-10-scope-employe-historique-transferts.md` (**protocole de synchro**) : cette section les complète.

**Statut** : API livrée (commit `2339512d`) — mobile à faire.

**Clé de suivi mobile** : `m16-13-scope-employe-donnees-synchro`

### Ce qui est fait côté API

- Le pull d'un **employé** ne contient plus que les données de **sa boutique** pour : `stockMovements`, `sales` (toutes les ventes de la boutique, tous vendeurs ; `items` suivent leur vente), `dayClosures`, `inventorySessions`, `inventoryCounts` (comptages des sessions de sa boutique), `employees` (collègues de sa boutique), `stockTransfers` (source **ou** destination = sa boutique, 16-10). `reports` : seulement **ses propres** rapports (même règle que `GET /api/v1/reports`). Restent **pour toutes les boutiques** : `stores`, `stockLevels` (disponibilité inter-boutiques), `products`, `categories`, `clients`, `suppliers`, `invoices`. `auditEntries` : owner seulement (inchangé). L'**owner** reçoit tout.
- Employé **sans boutique** : toutes les entités propres à une boutique reviennent vides (jamais d'erreur ; le curseur avance).
- Chaque page de pull porte en plus (ajouts, ignorés par les apps V1) :
  - `scope` : `{"storeId": "<uuid>" | null, "restricted": true | false}` — la portée pour laquelle la page a été calculée (`restricted=false` = owner ; `restricted=true, storeId=null` = employé sans boutique).
  - `reseed` : `true` sur **toutes les pages** d'un pull que le serveur a recommencé depuis le début parce que la portée de l'appareil a changé depuis son dernier pull terminé (employé réaffecté). Sinon `false`.
- Le serveur mémorise par appareil (`X-Device-Id`) la portée (`restricted` + `storeId`) du dernier pull **terminé** (dernière page, sans échec). Au pull suivant, si elle diffère (changement de boutique, employé sans boutique devenu owner ou l'inverse), il ignore le `since` du client (pull depuis le début, `reseed: true`) : l'historique complet de la nouvelle portée est renvoyé. Un pull interrompu ou en échec ne mémorise rien → le suivant recommence le re-seed. Premier pull d'un appareil : pas de re-seed. **Sans `X-Device-Id`, aucun re-seed** : toujours l'envoyer.
- **Re-seed en cours de pull** : le curseur `nextCursor` fige la portée du pull. Si la portée change entre deux pages (réaffectation pendant un pull paginé), la page suivante **recommence le pull depuis le début** : `reseed: true`, `scope` = nouvelle portée, les lignes repartent de zéro pour toutes les entités. Le client doit donc vérifier `scope`/`reseed` **à chaque page**, pas seulement à la première.
- Limite V1 2.0.1 : les apps installées reçoivent le re-seed (nouvelles données) mais ne purgent pas les anciennes lignes ; seule l'app RN purge.

### À utiliser

`GET /api/v1/sync/pull?since=<ISO>&cursor=<opaque>` + `X-Device-Id` (protocole complet : spec 16-10) → `{"data": {"serverTimestamp", "entities", "counts", "hasMore", "nextCursor", "scope": {"storeId", "restricted"}, "reseed"}}`.
Profil : `GET /api/v1/auth/profile` (16-11) → rôle et boutique de l'employé (source d'identité de l'app).

### À implémenter côté mobile

1. **Mémoriser la portée locale** (`storeId` + `restricted`) avec le curseur de synchro, mise à jour à la fin d'un pull complet (dernière page).
2. **Changement de portée détecté** — par le profil (`storeId` ou rôle différent de la portée mémorisée, au démarrage / au renouvellement de session) **ou** par n'importe quelle page de pull, y compris une page suivante (`scope` différent de la portée mémorisée ou de celle de la page précédente, ou `reseed: true` alors que la page précédente ne l'avait pas) : avant d'appliquer les lignes de la page, **purger** localement les données propres à une boutique qui ne sont plus dans la portée : `stock_movements`, `sales` + lignes de vente, `day_closures`, `inventory_sessions` + `inventory_counts`, collègues (`employees`), `stock_transfers` dont ni la source ni la destination n'est la nouvelle boutique, `reports` d'autres auteurs. Employé sans boutique : purger toutes ces tables. Owner : rien à purger.
3. **Ne jamais purger** une ligne référencée par une opération encore en file (non poussée) ni en quarantaine : la garder jusqu'au push, puis elle sera purgée au pull suivant.
4. Après la purge : **pull complet** (sans `since`), pages enchaînées comme d'habitude ; enregistrer la nouvelle portée et le `serverTimestamp` seulement à la dernière page. Le serveur renvoie de toute façon l'historique (`reseed`) si l'app garde son `since`.
5. **Mise à jour de l'app** (première ouverture de la version RN qui embarque 16-13) : faire une fois la purge selon la portée du profil puis un pull complet (les données d'autres boutiques reçues avant 16-13 restent sinon sur l'appareil).
6. L'écran de l'employé n'affiche que ce que le serveur envoie ; aucun identifiant brut ni libellé technique (`reseed`, `scope`) à l'écran. Pendant le re-téléchargement : « Mise à jour des données de votre boutique… ».

### Implémentation de référence (Flutter, `keevo/app` — comportement seulement)

- `lib/core/sync/rest_sync_service.dart` : suppression des lignes d'une boutique (`CREATE_STORE` annulé, ~l. 908-920) ; pull complet forcé via `kFullPullRequestedKey` (~l. 1133-1139).
- `lib/features/auth/presentation/provider/session_claims_applier.dart` (~l. 91-97) : changement de boutique de l'employé lu au renouvellement.
- Le client Flutter ne purge pas (pas de livraison Flutter) : il reçoit seulement le re-seed.

### Critères d'acceptation mobile / scénarios de test

Préparation : compte avec boutiques A et B (module `multi-store`), ventes, mouvements, clôtures, inventaires et transferts dans A et B, un employé de A connecté sur l'appareil.

```bash
curl -s "$BASE/api/v1/sync/pull" -H "Authorization: Bearer $AT_EMPLOYE" -H "X-Device-Id: curl-poste" | jq '.data | {scope, reseed, counts}'
# owner : réaffecter l'employé vers B (API employés), puis le même appareil avec son since précédent :
curl -s "$BASE/api/v1/sync/pull?since=$SINCE" -H "Authorization: Bearer $AT_EMPLOYE" -H "X-Device-Id: curl-poste" | jq '.data | {scope, reseed}'   # reseed true, scope.storeId = B
```

1. **Pull employé** — Given une installation neuve de l'employé de A, When le pull complet se termine, Then la base locale ne contient aucune vente, mouvement, clôture, inventaire, transfert sans A, ni collègue de B, ni rapport d'un autre ; les boutiques, niveaux de stock et le catalogue couvrent A et B.
2. **Mise à jour de l'app** — Given un appareil d'employé contenant des données de B reçues avant 16-13, When la nouvelle version synchronise, Then ces lignes disparaissent.
3. **Réaffectation A → B** — Given l'employé réaffecté par l'owner, When l'app synchronise (profil ou `scope`/`reseed`), Then les données de A disparaissent et l'historique de B est téléchargé ; la synchro suivante est incrémentale (`reseed: false`).
4. **Opération en file** — Given une vente de A pas encore poussée, When la réaffectation est détectée, Then la vente reste en file et localement jusqu'à son push, puis est purgée.
5. **Pull interrompu** — Given un re-seed coupé après la première page, When l'app relance, Then le serveur recommence le re-seed (`reseed: true`) et l'app termine sans doublon.
6. **Employé sans boutique** — Then aucune donnée propre à une boutique sur l'appareil, pas d'erreur de synchro.
7. **Réaffectation pendant un pull paginé** — Given plus de 1 000 lignes et l'employé réaffecté entre deux pages, When l'app demande la page suivante avec `nextCursor`, Then la réponse a `reseed: true` et la nouvelle `scope` ; l'app purge et continue jusqu'à `hasMore: false` sans garder de ligne de l'ancienne boutique.

### Reprise 10/11/2026 — stock de sa boutique seulement, prix d'achat réservés au propriétaire et au gérant

**Statut** : API livrée (commit `3c5059bc`) — mobile à faire. **Clé de suivi mobile** : `m16-13-scope-employe-donnees-synchro` (même clé, complétée).

#### Ce qui est fait côté API

- **Stock** (décision utilisateur D1) : un membre de boutique (gérant `MANAGER`, employé `EMPLOYEE`, lecteur `VIEWER`) ne voit plus que le stock de **sa** boutique, partout ; le propriétaire voit toutes les boutiques.
  - Synchro : `stockLevels` ne contient plus que les niveaux de sa boutique (fin de la « disponibilité inter-boutiques » gardée le 01/11).
  - REST : `GET /api/v1/stock/overview` → une seule ligne (sa boutique) ; `GET /api/v1/stock/products/{productId}/availability` → une seule entrée ; `GET /api/v1/products/{productId}/stock` → le niveau de sa boutique ; `GET /api/v1/stock/stores/{storeId}/products`, `GET /api/v1/products/{productId}/stock/history?storeId=`, `GET /api/v1/inventory/sessions?storeId=`, `/inventory/sessions/active?storeId=` et `/inventory/sessions/{id}/products|counts|gap-report|gap-report/text` d'une autre boutique → **403** `FORBIDDEN`, message « Vous ne pouvez consulter que le stock de votre boutique. » (sans `storeId`, l'historique et la liste des sessions prennent sa boutique).
  - Membre sans boutique : listes vides, 403 sur une boutique, synchro sans niveaux de stock (jamais d'erreur de synchro).
- **Prix d'achat** (D2) : `buyPrice` et `transportCost` (et tout coût / marge qui en dérive) ne sont visibles que pour le **propriétaire** et le **gérant**.
  - REST pour un employé ou un lecteur : `buyPrice: null`, `transportCost: null` sur tous les produits (`GET /api/v1/products`, `/products/{id}`, `/products/lookup`, création, modification, fusion, archivage, `POST /products/draft`, `PATCH /products/{id}/threshold`) ; `GET /products/{id}/pricing` garde `productId` et `sellingPrice`, tout le reste à `null`.
  - Synchro (`products`) pour un employé ou un lecteur : `buyPrice: 0`, `transportCost: 0` et le nouveau champ `purchasePriceHidden: true` (`false` pour le propriétaire et le gérant). Le 0 n'est **pas** un prix : il n'existe que parce que l'app V1 2.0.1 range ces champs dans une colonne non nulle.
  - Écriture : une modification (`PATCH /products/{id}`, `UPDATE_PRODUCT`, `PROMOTE_PRODUCT`) envoyée par un employé ou un lecteur ne change **jamais** le prix d'achat ni les frais de transport enregistrés (les autres champs s'appliquent) ; une création garde le prix saisi.
- **Re-téléchargement unique** : la première synchro d'un appareil de membre (avec `since`) après cette version, ou après un changement de droit sur les prix d'achat, revient avec `reseed: true` (pull depuis le début) ; la suivante est incrémentale.

#### À implémenter côté mobile

1. Sur `reseed: true` (déjà géré par la purge 16-13) : purger aussi les `stock_levels` des autres boutiques, puis appliquer les pages.
2. Produits : si `purchasePriceHidden` est `true`, ne **jamais** afficher ni utiliser `buyPrice` / `transportCost` (pas de « 0 FCFA », pas de marge, pas de rentabilité calculée localement) ; masquer les champs « Prix d'achat » / « Frais de transport » des fiches et des exports locaux. Si l'utilisateur modifie un produit, ne pas envoyer `buyPrice` / `transportCost` (le serveur les ignore de toute façon).
3. Écrans de stock d'un membre : uniquement sa boutique (plus de disponibilité dans les autres boutiques) ; sur un 403 avec le message ci-dessus, afficher ce message tel quel.
4. Aucun libellé technique à l'écran (`purchasePriceHidden`, `reseed`).

#### Critères d'acceptation mobile

1. Given un employé de A, When il synchronise, Then l'appareil ne contient que les niveaux de stock de A et aucun prix d'achat ; les fiches produits n'affichent ni prix d'achat ni marge.
2. Given un gérant de A, When il ouvre une fiche produit, Then le prix d'achat réel s'affiche ; le stock reste celui de A.
3. Given un employé qui modifie le prix de vente d'un produit hors ligne, When l'opération est poussée, Then le prix de vente change et le prix d'achat enregistré par le propriétaire reste intact.

```bash
curl -s "$BASE/api/v1/sync/pull" -H "Authorization: Bearer $AT_EMPLOYE" -H "X-Device-Id: poste-1" | jq '.data.entities.products[0] | {buyPrice, purchasePriceHidden}'
curl -s "$BASE/api/v1/stock/stores/$STORE_B/products" -H "Authorization: Bearer $AT_EMPLOYE_A" | jq '.message'   # 403
```

## Auto Run Result

Status: done

**Summary:** the employee pull scope is resolved once in `SyncController.pull` (`PullScope`: restricted, store, actor) and carried by `PullCommand` to every provider; an EMPLOYEE of S only receives S's stock movements, sales (+ items), day closures, inventory sessions/counts, colleagues, transfers involving S, and their own reports; stores, stock levels, catalogue, clients, suppliers and invoices stay tenant-wide; an employee without a store gets empty store datasets without failing the pull. The response gains `scope {storeId, restricted}` and `reseed`. V37 remembers each device's last completed scope (`last_scope_known`, `last_scope_restricted`, `last_scope_store_id`); a different scope makes that pull restart from EPOCH (`reseed: true`), including when the scope changes between two pages (scope pinned in the cursor); the scope is saved only after the last successful page and only when it changed.

**Files:** `shared/application/port/sync/PullScope.java`, `DeltaEntityProvider` (scoped overload), `DeltaPageQuery` (`fetchForStore`, `fetchForActor`), 8 providers in `sync/sync/application/provider/`, `SyncUseCase`, `SyncController`, `SyncPushService` (scope comparison, cursor flags + pinned scope, remember), `SyncPullResult` / `SyncPullResponseDto`, `UserSyncState` + adapter, `V37__sync_scope.sql`; tests (`StoreScopedDeltaProvidersTest`, `InventorySession/InventoryCountDeltaProviderTest`, `EmployeeScopePullPostgresIntegrationTest`, `SyncPullServiceTest`, `SyncControllerPullGateTest`, updated transfer/keyset tests); epics doc (16.13 ticked); Mobile section of this spec; `m16-13` key.

**Review:** 26 findings — patched entries: medium 2 (owner ↔ storeless employee scope change, scope change between pages), low 4 (3-arg overloads fail closed, scope written only on change, log/cleanup, PostgreSQL row-mapper assertion); 0 deferred; rejected with reasons in the triage log (invoices tenant-wide, 30-day movement window same as a fresh install, single-instance deploys, unreachable legacy constructors, pre-release devices: RN starts empty and V1 cannot purge).

**Follow-up review recommended: true** — two medium patches reworked the pull cursor (pinned scope, mid-pull restart, remember flag); a second look at `SyncPushService.pull` / `PullCursor` restart paths is the unverified risk.

**Verification:** backend, one package at a time: sync, shared, inventory, commerce, reporting, identity, invoicing, catalog, store, subscription, crm, admin, messaging, feedback, acceptance, architecture — 4748 tests, 0 failures. Live HTTP check by the implementation agent (real login, employee/owner pulls over two stores, reassignment through `PATCH /employees/{id}/store`, reseed then incremental). Redmi (Flutter test client) as employee: sync completes, « En ligne », device scope recorded (restricted, store) after the corrected V37 applied on boot.

**Residual risks:** V1 2.0.1 devices cannot purge rows already pulled (documented); store filters are residual on cursor-leading keyset indexes (fine at current volumes); test tenant `KV-1D0VLM` left in keevo_dev.

### Deferred items routed 2026-11-10
- [x] [Deferred-from: 21.8 review, 2026-11-10] Portée boutique des lectures de stock multi-boutiques [keevo/backend/src/main/java/com/keevo/inventory/stock/adapter/in/web/MultiStoreStockController.java:55,67] — `GET /api/v1/stock/overview` et `GET /api/v1/stock/stores/{storeId}/products` n'appliquent aucune portée : un EMPLOYEE / MANAGER / VIEWER lit le stock de n'importe quelle boutique (l'outil IA de 21.8 est, lui, limité à la boutique du membre). Trancher : besoin métier (disponibilité inter-boutiques) ou écart à la règle 16-13 ; dans le second cas, même limitation qu'en 21.8 (404/403 français hors de sa boutique). Réglé par un test full-context employé boutique A → boutique B. — **réglé par la reprise du 10/11/2026** (décision D1 : écart à la règle, plus de disponibilité inter-boutiques pour un membre ; 403 « Vous ne pouvez consulter que le stock de votre boutique. » ; `StoreMemberStockAndPurchasePriceFullContextTest`).

### Reprise 10/11/2026 — Auto Run Result

Status: done

**Summary:** D1 — `StockReadScope` (value object, inventory domain) resolved once per request by `StockReadScopes.current()` and carried by the multi-store queries to the services and the SQL: a store member (MANAGER, EMPLOYEE, VIEWER) only reads its store (overview, store detail → 403 French message, availability, product stock and history, inventory-counting reads, `stock_by_store` / `low_stock_products`, sync `stockLevels`), the owner every store; `search_products` reads the real `stock_levels` (port `ProductStockQuantities`, one grouped query). D2 — permission `PURCHASE_PRICE_VIEW` (catalog manifest, OWNER + MANAGER) behind `PurchasePriceVisibility` (REST, AI tool, write guard) and `PullScope.holds` (sync): `buyPrice` / `transportCost` null in REST (products, lookup, draft, threshold), pricing costs and margins null, sync `0` + `purchasePriceHidden: true`; updates from EMPLOYEE / VIEWER never change them. Devices re-seed once when the visibility marker changes (V71 `last_scope_visibility`). D3 — overview aggregated from `stock_levels` per store (64-bit value), detail in one statement (`COUNT(*) OVER ()`), index `stock_levels(store_id, updated_at, id::text)` (inventory V2).

**Files:** `inventory/stock/domain/model/StockReadScope`, `inventory/stock/adapter/in/StockReadScopes`, the three multi-store queries / services / port / adapter, `MultiStoreStockController`, `StockMcpTools`, `StockController`, `GetCurrentStockUseCase`, `ProductThresholdResponseDto`, counting controllers + `CountingReadScope`, `catalog/api/ProductStockQuantities` + `CatalogStockQuantitiesAdapter`, `ProductMcpTools`, `shared/infrastructure/security/PurchasePriceVisibility`, `catalog/module.json`, `ProductController`, `ProductImportController`, `ProductResponseDto`, `GetProductPricingUseCase`, `UpdateProductUseCase`, `StockLevelDeltaProvider`, `ProductDeltaProvider`, `SyncPushService` (visibility marker), `UserSyncState` + repository, `V71__sync_scope_visibility.sql`, `db/modules/inventory/V2__stock_levels_store_index.sql`; tests (new `StoreMemberStockAndPurchasePriceFullContextTest`, `StockReadScopeTest`, `PurchasePriceVisibilityTest`; extended service, adapter, Postgres, controller, provider, pull, AI tools tests); `docs/entitlements/endpoints.md`, `docs/ai/tools.md`.

**Review:** 5 layers, 36 findings — patched: medium 4 (2 roots: store-scoped sync index, gap report computed before the scope check), low 14 (sync push / lookup / storeless / other-tenant tests, `COUNT(*)`, array parameter, active stores only in the AI stock total, empty edit no-op, fail-open overloads removed); 0 deferred; rejected with reasons in the triage log (legacy `stockQuantity` DD3, V71 number reserved, non-concurrent module index, reseed spike DD6, owner-only surfaces not widened).

**Follow-up review recommended: false** — the two medium roots are an index shape and a check moved before the computation, both covered by tests (keyset shape test, report test asserting the use case is never called).

**Verification:** chunked Maven runs, one package at a time: catalog 373, inventory 441, sync 691, architecture 146, shared 1358 — 0 failure; `docs/ai/tools.md` regenerated (`endpoints.md` unchanged). Live (port 4513, DB `keevo_dev_t1613`, WhatsApp noop, FCM off, real register / employee creation / login / select-tenant): owner overview A + B, employee A only; employee detail of B → 403 « Vous ne pouvez consulter que le stock de votre boutique. »; availability A only; `buyPrice` null for the employee, 600 for manager and owner; pricing costs null; employee `PATCH buyPrice 0` → stored 600 kept, price 1100 applied; employee pull: `stockLevels` A only, `buyPrice 0`, `purchasePriceHidden true`; manager pull: real price, A only; owner pull A + B; a device with an unknown marker pulling with `since` → `reseed: true`, next pull incremental.

**Residual risks:** V1 2.0.1 devices keep other stores' stock levels already pulled (no purge; prices are overwritten by the re-seed); one re-seed per member device after deploy; inventory module index built non-concurrently at boot (small table per tenant).

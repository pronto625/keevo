# Story 11.5: Correctifs performance & justesse (N+1, bug d'index)

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> 2026-10-11 — Story file copied from branch `refractoring` to `deploy` (index bug already fixed: key totalRevenue; N+1 remains).

## Story

As a **platform developer**,
I want **the N+1 query patterns and the day-closure column-mapping ("index") bug flagged by the code audits fixed**,
so that **performance and data correctness hold at scale (MOD-NFR4, Performance).**

## Context & Why This Story

The audits (`review_claude_opus_8.md`, `review_qwen_6.md`) flagged three concrete, consensual defects: two **N+1 query patterns** and one **wrong-column mapping** in the sync delta. Each is small, real in `main` today, and **fully independent** — they live in three different modules (`admin`, `commerce`, `sync`) and share no code. Implement and test them separately; only the regression run (Task 4) is shared.

1. **N+1 on tenant-list schema lookup.** `AdminTenantService.execute(ListTenantsQuery)` loads the page of tenants in one SQL query, then `.map(this::enrichWithCrossSchemaMetrics)`. Inside, `enrichWithCrossSchemaMetrics` calls `safeLoadSchemaName(item.id())` which fires a **separate** `SELECT schema_name FROM public.tenants WHERE id = ?` **per row** — even though the list query already SELECTs `t.schema_name` (it just isn't carried into the immutable `AdminTenantListItem` record). For a 25-row page that is 25 redundant round-trips. Fix: preload the schema names for the whole page in **one** batched query into a `Map<UUID,String>`.

2. **N+1 on pending-sales items.** `SaleSpringRepository.findByStoreIdAndStatus(storeId, status)` is a Spring-Data **derived** query (no `JOIN FETCH`). Its caller path (`ValidateSaleService.getPendingSalesByStore` → `SaleRepositoryAdapter.findByStoreIdAndStatus` → `.map(toDomain)`) reads `entity.getItems()`, which is `FetchType.LAZY` → one extra `SELECT … FROM sale_items WHERE sale_id = ?` **per sale**. The codebase already has the correct pattern next door: `findByIdWithItems` uses `SELECT DISTINCT s … LEFT JOIN FETCH s.items`. Apply the same to `findByStoreIdAndStatus`.

3. **Day-closure delta maps the wrong column (the "bug d'index").** `DayClosureDeltaProvider.mapRow` builds the pull-sync payload from a positional `Object[]` row. The SELECT is `id, store_id, actor_id, closed_at, total_sales, total_revenue, cash_amount, momo_amount, is_automatic` → indices `0..8`. The mapping does:
   ```java
   map.put("totalSales",        num(row[4]));   // total_sales  (COUNT of completed sales) ✓
   map.put("totalTransactions", num(row[4]));   // BUG: also row[4] — should be row[5] (total_revenue)
   map.put("totalRevenue",      num(row[5]));   // total_revenue
   ```
   `total_sales` is a **count** (see `DayClosureSummary` javadoc: *"Count of COMPLETED sales"*); `total_revenue` is the money amount. The Flutter client consumes the payload **positionally** in `rest_sync_service.dart#_upsertDayClosures`: payload `totalSales` → local `total_sales` column, **payload `totalTransactions` → local `total_revenue` column**. So `totalTransactions` must carry the **revenue** (`total_revenue`, `row[5]`), not the count again. Today every synced (non-authoring) device records its day-closure **revenue equal to the number of sales** — a silent data-correctness bug. Fix: `map.put("totalTransactions", num(row[5]))`.

**These three fixes are independent.** Do **not** fold any other 11.x work (RBAC 11.4, session revocation 11.6) into this story; keep the diff minimal and orthogonal.

## Acceptance Criteria

1. **Given** the admin tenant list is loaded for a page of *N* tenants **When** `AdminTenantService.execute(ListTenantsQuery)` runs **Then** the per-tenant `schema_name` is resolved via **a single batched query** for the whole page (e.g. a `Map<UUID,String>` keyed by tenant id), **not** one `SELECT schema_name … WHERE id = ?` per row — the N+1 is eliminated. The returned `Page<AdminTenantListItem>` content (ids, plan, status, storeCount, lastActivity, employeeCount) is **byte-identical** to today for the same data.
2. **Given** `AdminTenantService.execute(GetTenantDetailQuery)` (detail path) **When** it enriches the single summary item **Then** it reuses the schema name it already loads (`loadSchemaName`) instead of re-querying it inside `enrichWithCrossSchemaMetrics` — no behavioural change to the detail response, and no duplicate schema lookup.
3. **Given** `ValidateSaleService.getPendingSalesByStore(storeId)` (EMPLOYEE pending-sales list) returns sales that each have multiple items **When** the result is mapped to domain **Then** the sale items are fetched **in the same query** via `LEFT JOIN FETCH` (no per-sale lazy `SELECT`), no `LazyInitializationException`, and each returned `Sale` carries its full item list exactly as today.
4. **Given** a sale with *k > 1* items **When** `findByStoreIdAndStatus` returns it **Then** it appears **once** (not *k* duplicated rows) — `DISTINCT` collapses the JOIN-FETCH fan-out, matching the proven `findByIdWithItems` behaviour. No `IncorrectResultSizeDataAccessException`.
5. **Given** a `day_closures` row with `total_sales = c` (completed-sales count) and `total_revenue = r` (XAF) **When** `DayClosureDeltaProvider.queryDelta` builds the payload **Then** `totalSales == c` (`row[4]`) **and** `totalTransactions == r` (`row[5]`, i.e. the revenue the client stores into its `total_revenue` column) — `totalTransactions` is **no longer** the duplicate of `totalSales`. `totalRevenue` continues to map `row[5]`.
6. **Given** the existing test suite **When** the build runs **Then** all previously-green tests stay green (regression baseline ~1463 backend tests with **1 pre-existing failure + 33 pre-existing errors**, unrelated, documented in sprint-status — do **not** increase these counts), and **new/updated** tests prove: AC1 (schema query issued once per page, not per row), AC3/AC4 (items eager-fetched, single row per sale), AC5 (`totalSales`/`totalTransactions` map the right indices).

## Tasks / Subtasks

- [ ] **Task 1 — Eliminate the tenant-list schema-name N+1** (AC: #1, #2)
  - [ ] In `keevo/backend/src/main/java/com/keevo/admin/tenant/application/service/AdminTenantService.java`, in `execute(ListTenantsQuery)`, **after** the list rows are fetched (`items`) and **before** the enrichment `.map(...)`, build a single `Map<UUID,String>` of tenant-id → schema_name for the page:
    ```java
    Map<UUID, String> schemaByTenant = loadSchemaNames(items.stream().map(AdminTenantListItem::id).toList());
    List<AdminTenantListItem> enriched = items.stream()
            .map(it -> enrichWithCrossSchemaMetrics(it, schemaByTenant.get(it.id())))
            .toList();
    ```
    Add a private `Map<UUID,String> loadSchemaNames(List<UUID> ids)` that, for a non-empty id list, runs **one** query — e.g.
    ```java
    if (ids.isEmpty()) return Map.of();
    String inClause = ids.stream().map(x -> "?").collect(Collectors.joining(","));
    Map<UUID, String> out = new HashMap<>();
    jdbc.query("SELECT id, schema_name FROM public.tenants WHERE id IN (" + inClause + ")",
            rs -> { out.put(UUID.fromString(rs.getString("id")), rs.getString("schema_name")); },
            ids.toArray());
    return out;
    ```
    (The `?` placeholders are parameterized — no interpolation of values; the count of `?` equals `ids.size()`. This keeps the existing SQL-injection-safe posture.)
  - [ ] Change `enrichWithCrossSchemaMetrics(AdminTenantListItem item)` → `enrichWithCrossSchemaMetrics(AdminTenantListItem item, String schemaName)`. Drop the internal `safeLoadSchemaName(item.id())` call; use the passed `schemaName` (keep the `if (schemaName == null) return item;` guard). The two cross-schema metric queries inside (`safeCount` for `storeCount`, `safeMaxInstant` for `lastActivity`) are **legitimately per-tenant** (they read different tenant schemas) and are **out of scope** — leave them exactly as-is. The N+1 to remove is only the redundant `schema_name` re-lookup.
  - [ ] In `execute(GetTenantDetailQuery)` (detail path), reorder so `schemaName` is loaded **before** enrichment and reused: move `String schemaName = loadSchemaName(query.tenantId());` above the `enrichWithCrossSchemaMetrics` call and pass it in — `AdminTenantListItem summary = enrichWithCrossSchemaMetrics(summaryRows.get(0), schemaName);`. This removes the duplicate lookup (today the detail path queries schema_name twice: once in `safeLoadSchemaName` inside enrich, once in `loadSchemaName`). `loadSchemaName` throws `TENANT_NOT_FOUND` on null, which is correct for the detail path (the tenant was already found in `summaryRows`).
  - [ ] Remove the now-unused `safeLoadSchemaName` private method (confirm no other caller via `grep`). Update the stale class/`enrichWithCrossSchemaMetrics` javadoc comments (lines ~24-43, ~214-219) that describe the old per-item lookup so they reflect the preloaded-map approach.
  - [ ] **Schema-name regex note:** this story does **not** add `TenantSchema.validate(...)` — that is Story 11.4's job (RBAC/exposition). If 11.4 has already landed on this branch and `AdminTenantService` now routes schema through `TenantSchema.validate`, keep that call (apply it to the map values before interpolation). If 11.4 has not landed, do **not** introduce it here. Check `git log`/the file's current state and note which case applied in completion notes.
  - [ ] Update `AdminTenantServiceTest` (`src/test/.../admin/tenant/application/service/AdminTenantServiceTest.java`): the existing `@BeforeEach` stub `lenient().when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn("kv_abc123")` modelled the **per-row** lookup — replace it with a stub of the **batched** `jdbc.query(... RowCallbackHandler/ResultSetExtractor ...)` used by `loadSchemaNames`. Add a new test `listTenants_multipleTenants_loadsSchemaNamesInOneQuery`: return a 3-row page from the list query and `verify(jdbcTemplate, times(1)).query(contains("WHERE id IN"), any(<the handler type>.class), any(Object[].class))` (and assert no per-id `queryForObject(String.class)` schema lookup is made). Keep the three existing tests green (adjust their stubs to the new shape). See Dev Notes "Mocking the batched schema query".

- [ ] **Task 2 — JOIN FETCH the pending-sales items (kill the lazy N+1)** (AC: #3, #4)
  - [ ] In `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java`, replace the **derived** declaration
    ```java
    List<SaleJpaEntity> findByStoreIdAndStatus(UUID storeId, String status);
    ```
    with an explicit fetch-join query mirroring `findByIdWithItems`:
    ```java
    @Query("SELECT DISTINCT s FROM SaleJpaEntity s LEFT JOIN FETCH s.items "
         + "WHERE s.storeId = :storeId AND s.status = :status")
    List<SaleJpaEntity> findByStoreIdAndStatus(@Param("storeId") UUID storeId, @Param("status") String status);
    ```
    Use `LEFT JOIN FETCH` (not inner) + `DISTINCT` exactly like `findByIdWithItems` — `LEFT` keeps a (hypothetical) item-less sale in the result; `DISTINCT` collapses the per-item row fan-out to one entity. Field names confirmed on `SaleJpaEntity`: `storeId` (`store_id`), `status`, `items` (`@OneToMany(mappedBy="sale", fetch=LAZY)`).
  - [ ] Do **not** touch the other repository methods (`findByStatus`, `findPendingByProductId`, `findByIdWithItems`, the Story-4.4 history queries). The adapter (`SaleRepositoryAdapter.findByStoreIdAndStatus`) and the caller (`ValidateSaleService.getPendingSalesByStore`) need **no** change — same method signature, same returned `List<Sale>`; only the fetch strategy improves.
  - [ ] Add/extend a `@DataJpaTest` (or the existing `SaleRepositoryAdapterTest` if it already uses a real EM/Testcontainers) proving AC3/AC4: persist a `PENDING_VALIDATION` sale with ≥2 items in a store, call `findByStoreIdAndStatus(storeId, "PENDING_VALIDATION")`, assert exactly **one** entity is returned and `entity.getItems()` is populated (size == number of items) **without** a `LazyInitializationException` when accessed outside an open session/transaction. If the existing test infra for this repository is Testcontainers-backed, follow that pattern; if there is none, a `@DataJpaTest` with the in-suite Postgres/Testcontainers config used elsewhere is acceptable — match the project's existing repository-test convention rather than inventing a new one. See Dev Notes "Proving the fetch join".

- [ ] **Task 3 — Fix the day-closure column index** (AC: #5)
  - [ ] In `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/DayClosureDeltaProvider.java`, change the single line in `mapRow`:
    ```java
    map.put("totalTransactions", num(row[4]));   // before — duplicate of totalSales
    map.put("totalTransactions", num(row[5]));   // after  — total_revenue (what the client stores as total_revenue)
    ```
    Leave `map.put("totalSales", num(row[4]))` and `map.put("totalRevenue", num(row[5]))` unchanged. Do **not** rename the payload keys — the Flutter client (`rest_sync_service.dart#_upsertDayClosures`) reads them **by name** and maps `totalTransactions` **positionally** into its local `total_revenue` column; renaming would break sync. Add a short comment above the put explaining the (admittedly confusing) key→column contract so a future reader doesn't "helpfully" revert it. See Dev Notes "Why `totalTransactions` carries revenue".
  - [ ] **Do not** change the SQL SELECT, `setMaxResults`, or any other mapping. `day_closures` has **no** `total_transactions` column (confirmed in `V1__baseline_public.sql` and `TenantSchemaProvisioner`), so the "extend the query" alternative in the epic note is not applicable — the index correction (`row[4]`→`row[5]`) is the fix.
  - [ ] Update `DayClosureDeltaProviderTest` (`src/test/.../sync/sync/application/provider/DayClosureDeltaProviderTest.java`): make the test row semantically unambiguous and assert both keys. Set the row to realistic values — `total_sales` = a small count, `total_revenue` = a money amount, e.g.:
    ```java
    Object[] row = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now,
            12, 50000, 40000, 10000};   // row[4]=total_sales (count), row[5]=total_revenue
    ...
    assertThat(result.get(0)).containsEntry("totalSales", 12);          // count
    assertThat(result.get(0)).containsEntry("totalTransactions", 50000); // revenue (row[5])
    assertThat(result.get(0)).containsEntry("totalRevenue", 50000);
    ```
    (The current test only asserts `totalSales == 50000` against a row whose `row[4]=50000`; updating the data to count-vs-revenue makes the assertions express the real contract. Keep it a pure Mockito unit test — no Spring context.)

- [ ] **Task 4 — Regression + completion notes** (AC: #6)
  - [ ] From `keevo/backend`: `./mvnw -q -DskipTests compile` then `./mvnw test`. Confirm no NEW failures beyond the documented baseline (~1463 tests; **1 pre-existing failure + 33 pre-existing errors** — do not increase these counts). Your three touched test classes must be green.
  - [ ] In completion notes document: (1) the batched schema-name approach (one `IN (…)` query) and that the per-tenant cross-schema metric queries were intentionally left in scope-out; (2) confirmation `getTenantDetail` no longer double-loads schema_name; whether `TenantSchema.validate` was present (11.4 landed) or not; (3) the `findByStoreIdAndStatus` fetch-join (LEFT JOIN FETCH + DISTINCT) and how the item-eager-load test proves no N+1/`LazyInitializationException`; (4) the `totalTransactions` → `row[5]` correction and the client-contract reasoning (so a reviewer doesn't flag it as "now totalTransactions == totalRevenue").

## Dev Notes

### The three sites are independent — keep diffs orthogonal

`admin/tenant/application/service/AdminTenantService.java` (admin module), `commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java` (commerce module), `sync/sync/application/provider/DayClosureDeltaProvider.java` (sync module). No shared code; three small commits or one cohesive `feat(story-11.5)` commit are both fine. Do not pull in 11.4 (`TenantSchema`) or 11.6 (session revocation) work.

### Tenant-list N+1: what exactly is redundant (AC1/AC2)

The list query (`listSql`) **already** selects `t.schema_name`, but the immutable `AdminTenantListItem` record has no schema field, so the value is dropped and then re-fetched once per row by `safeLoadSchemaName`. Two valid fixes per the epic ("`Map<UUID,String>` **ou** colonne dans la requête principale"): (a) **preload a `Map` in one batched `IN (…)` query** — recommended, minimal, leaves the record untouched; (b) carry `schema_name` through the RowMapper into an intermediate pair/record — zero extra queries but more refactoring of the record/mapper. **Choose (a)** unless you have a reason otherwise; it converts *N* queries → 1 and is the smallest safe diff. The remaining 2 cross-schema metric queries per tenant (`COUNT stores`, `MAX(occurred_at)`) are inherent to reading each tenant's own schema and are **not** the flagged N+1 — do not try to "optimize" them in this story (they'd need a different, riskier cross-schema aggregation design).

### Mocking the batched schema query (AC1 test)

The current test stubs the **per-row** `queryForObject(sql, String.class, id)`. After the fix, `loadSchemaNames` uses a `jdbc.query(sql, <RowCallbackHandler|ResultSetExtractor>, ids...)`. Stub/verify that overload instead. The cleanest verification of "no N+1" is `verify(jdbcTemplate, times(1)).query(contains("WHERE id IN"), any(RowCallbackHandler.class), any(Object[].class))` for a multi-row page, plus asserting the page content is unchanged. Because `enrichWithCrossSchemaMetrics` still issues the (in-scope-out) `safeCount`/`safeMaxInstant` calls, keep `@MockitoSettings(strictness = LENIENT)` as today. If you pick a `ResultSetExtractor<Map<…>>` shape instead of `RowCallbackHandler`, verify that type — match whichever overload you actually call.

### Proving the fetch join (AC3/AC4)

`SaleRepositoryAdapter.toDomain` reads `entity.getItems()`; with the derived (lazy) query that access triggers a second `SELECT` per sale (N+1) and, if it happens after the session closes, a `LazyInitializationException`. `LEFT JOIN FETCH` + `DISTINCT` loads items in the same statement and collapses the fan-out (this is exactly why `findByIdWithItems` already does it — see its AC4 comment about `IncorrectResultSizeDataAccessException`). The strongest test persists a multi-item pending sale, evicts/clears the persistence context (or asserts outside the tx), then calls the finder and touches `getItems()` — it must not throw and must return one entity. Use the same repository-test harness the project already uses for `SaleRepositoryAdapterTest`; do not invent a new DB-test style.

### Why `totalTransactions` carries revenue (AC5 — read before "fixing" the name)

This looks wrong at first glance (`totalTransactions == totalRevenue`?), but it is dictated by the **existing client contract**, which must not break. In `keevo/app/lib/core/sync/rest_sync_service.dart#_upsertDayClosures` the INSERT column order is `(… total_sales, total_revenue, cash_amount, momo_amount …)` and the bound values are `(… map['totalSales'], map['totalTransactions'], map['cashTotal'], map['mobileMoneyTotal'] …)`. So the payload key **`totalTransactions` is positionally written into the client's `total_revenue` column** — it must therefore carry the backend `total_revenue` value (`row[5]`). The backend's `total_sales` column is a **count** (`DayClosureSummary` javadoc + `DayClosureSummaryBuilder`: `totalSales++`), which correctly flows through payload `totalSales` (`row[4]`) into the client's `total_sales` column. The pre-fix code put the count into the revenue slot → every synced device showed revenue == number-of-sales. **Do not rename the keys** to "fix" the smell; that would desynchronise the Flutter mapping. Just correct the index and leave a comment. No Flutter change is required (no client test asserts the closure `totalTransactions` value; the dashboard `totalTransactionsMonth` field is unrelated).

### Files being modified

| File | NEW/UPDATE | What |
| --- | --- | --- |
| `admin/tenant/application/service/AdminTenantService.java` | UPDATE | Batched `loadSchemaNames` (`IN (…)`), `enrichWithCrossSchemaMetrics(item, schema)`, reuse schema in detail path, drop `safeLoadSchemaName`, refresh javadoc. |
| `admin/tenant/application/service/AdminTenantServiceTest.java` | UPDATE | Stub/verify the batched schema query; add multi-tenant "one query" test; keep existing 3 green. |
| `commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java` | UPDATE | `findByStoreIdAndStatus` → `@Query` `SELECT DISTINCT s … LEFT JOIN FETCH s.items …`. |
| `commerce/sale/adapter/out/persistence/SaleRepositoryAdapterTest.java` (or a `@DataJpaTest`) | UPDATE/NEW | Multi-item pending sale → one entity, items eager-loaded, no `LazyInitializationException`. |
| `sync/sync/application/provider/DayClosureDeltaProvider.java` | UPDATE | `totalTransactions` → `num(row[5])`; clarifying comment. |
| `sync/sync/application/provider/DayClosureDeltaProviderTest.java` | UPDATE | Count-vs-revenue row data; assert `totalSales`(row[4]) & `totalTransactions`(row[5]). |

### Out of scope

- The 2 per-tenant cross-schema metric queries in `enrichWithCrossSchemaMetrics` (storeCount, lastActivity) — inherent, not the flagged N+1.
- Any `TenantSchema.validate` introduction (Story 11.4); RBAC (11.4); session revocation (11.6); observability (11.1–11.3).
- Any Flutter/client code change — the client mapping is correct given the contract; only the backend value was wrong.

### Project Structure Notes

- All three edits stay within their existing module and layer: `AdminTenantService` is an application service (admin), `SaleSpringRepository` an outbound JPA adapter (commerce), `DayClosureDeltaProvider` a sync application provider — hexagonal boundaries unchanged, so Spring Modulith `verify()` / ArchUnit (Story 10.6, warning mode) stay clean.
- No new classes, no new dependencies, no migration — pure in-place correctness/perf fixes.

### References

- [Source: _bmad-output/planning-artifacts/epics-refonte-modulaire.md#Story 11.5 : Correctifs performance & justesse (N+1, bug d'index)] — user story, AC, implementation notes (preload `Map<UUID,String>`; `JOIN FETCH DISTINCT`; `DayClosureDeltaProvider` index fix).
- [Source: keevo/backend/src/main/java/com/keevo/admin/tenant/application/service/AdminTenantService.java#enrichWithCrossSchemaMetrics] — current per-row `safeLoadSchemaName` N+1; list query already selects `t.schema_name`; detail path double-loads schema.
- [Source: keevo/backend/src/main/java/com/keevo/admin/tenant/domain/model/AdminTenantListItem.java] — immutable record (no schema field) → why the list-query schema_name is dropped and re-queried.
- [Source: keevo/backend/src/test/java/com/keevo/admin/tenant/application/service/AdminTenantServiceTest.java] — Mockito `JdbcTemplate` harness; existing per-row schema stub to replace.
- [Source: keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java#findByIdWithItems] — proven `SELECT DISTINCT s … LEFT JOIN FETCH s.items` pattern to mirror.
- [Source: keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/impl/SaleRepositoryAdapter.java#findByStoreIdAndStatus] + [.../application/service/ValidateSaleService.java#getPendingSalesByStore] — caller path that triggers the lazy items N+1 via `toDomain`.
- [Source: keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/entity/SaleJpaEntity.java] — `storeId`/`status`/`items` (`@OneToMany … LAZY`) field names for the `@Query`.
- [Source: keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/DayClosureDeltaProvider.java#mapRow] — `total_sales`(row[4]) vs `total_revenue`(row[5]); the `totalTransactions = row[4]` bug.
- [Source: keevo/backend/src/main/java/com/keevo/commerce/sale/domain/model/DayClosureSummary.java] — `total_sales` = "Count of COMPLETED sales"; `total_revenue` = money (semantics behind the index fix).
- [Source: keevo/app/lib/core/sync/rest_sync_service.dart#_upsertDayClosures (~line 588-608)] — client positional mapping: payload `totalTransactions` → local `total_revenue` column (proves `totalTransactions` must carry revenue).
- [Source: keevo/backend/src/main/resources/db/migration/V1__baseline_public.sql (day_closures, ~line 57-74)] — confirms there is **no** `total_transactions` column → the fix is the index correction, not a query extension.
- [Source: review_qwen_6.md] + [review_claude_opus_8.md] — original audit findings (N+1s + day-closure mapping defect).
- [Source: _bmad-output/implementation-artifacts/11-4-correctifs-rbac-exposition.md] — regression baseline (~1463 tests, 1 pre-existing failure + 33 errors); module/layer placement precedent; relation to `TenantSchema` (11.4).

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Deferred items routed 2026-10-11

- [ ] [Deferred-from: legacy CR 9-1, 2026-05-04] N+1 schema_name in enrichWithCrossSchemaMetrics [AdminTenantService.java#enrichWithCrossSchemaMetrics] — already in 11-5 scope (preload Map<UUID,String>)
- [ ] [Deferred-from: CR UI-3 lot 1 backend, 2026-10-11] Keyset pull ordering has no supporting index [DeltaPageQuery.java] — EXPLAIN on large tenant; index (cursor, id::text) per table

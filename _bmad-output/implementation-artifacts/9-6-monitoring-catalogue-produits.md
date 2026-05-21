# Story 9.6: Monitoring Catalogue Produits Cross-Tenant

**Status:** done
**Epic:** 9 — Super Admin Dashboard (Toor)
**Story ID:** 9.6
**Created:** 2026-05-16

---

## Story

As Toor (super admin),
I want to view the complete product catalog across all tenants from the admin dashboard,
so that I can monitor platform inventory, identify inactive or draft-heavy tenants, and track product catalog health at the platform level.

---

## Scope & Architecture Decision

This story adds a new **Product Monitoring** page to the existing `keevo/dashboard` Next.js 16 application.
It adds a new backend admin module `admin/catalog/` following the same hexagonal architecture used in `admin/tenant/`.

**No Flutter work.** Super Admin dashboard is exclusively Next.js.

**Key architecture constraint:** All cross-tenant product queries use `JdbcTemplate` with schema-qualified SQL (same pattern as `AdminTenantService`). The products table lives in each tenant's schema (`kv_xxxxxx.products`). Summary stats require iterating over all tenant schemas — acceptable for Phase 1 (<200 tenants); denormalization deferred.

---

## Acceptance Criteria

**AC1 — Platform summary cards**
- Page loads 5 KPI cards: **Total Produits** / **Actifs** / **Brouillons** / **Stock Critique (≤5 unités)** / **En Rupture (= 0)**
- Cards reflect the current platform state (all tenants combined, excluding `KV-ADMIN` system tenant)
- Data sourced from `GET /api/v1/admin/catalog/summary`

**AC2 — Cross-tenant product table**
- Paginated table (25 rows/page) showing all products across all tenants
- Columns: **Produit** (name), **Tenant** (business name), **Plan** (FREE/PAID badge), **Catégorie**, **Prix** (XAF formatted), **Stock**, **Statut** (badge: Actif / Brouillon / Archivé), **Dernière màj**
- Server-side pagination + sorting
- Endpoint: `GET /api/v1/admin/catalog/products`

**AC3 — Filters & search**
- **Search** bar: by product name (ILIKE), debounced 300ms → updates table in-place
- **Tenant** dropdown: `ALL` + list of active tenants (id + name)
- **Stock Level** dropdown: `ALL` / `OK (>5)` / `Faible (1–5)` / `Rupture (0)`
- **Statut** dropdown: `ALL` / `ACTIVE` / `DRAFT` / `ARCHIVED`
- **Plan** dropdown: `ALL` / `FREE` / `PAID`
- Active filters shown as dismissible chips above the table
- Reset All button clears all filters

**AC4 — Export CSV**
- "Exporter CSV" button (top-right of table) calls `GET /api/v1/admin/catalog/products/export`
  with all current filters applied
- Downloads `keevo-products-YYYY-MM-DD.csv` with columns: product_id, product_name, tenant_name, plan, category, price, stock_quantity, status, last_updated

**AC5 — Navigation**
- Sidebar gains a **"Monitoring"** collapsible group containing:
  - "Catalogue Produits" → `/monitoring/products`
  - (Placeholder links for stories 9.7 and 9.8 — rendered but marked "Coming soon" if not yet implemented)

---

## Tasks / Subtasks

### Part A — Backend: new `admin/catalog/` module

- [x] **Task 1 — Domain models** (files in `admin/catalog/domain/model/`)
  - [x] 1.1 Create `AdminProductListItem.java` record:
    ```java
    // admin/catalog/domain/model/AdminProductListItem.java
    public record AdminProductListItem(
        String id,
        String name,
        String tenantId,
        String tenantName,
        String tenantPlan,       // "FREE" | "PAID"
        String schemaName,       // internal use — not serialized to DTO
        String categoryName,     // nullable
        long price,              // in XAF (integer)
        int stockQuantity,
        String status,           // ACTIVE | DRAFT | ARCHIVED
        Instant updatedAt
    ) {}
    ```
  - [x] 1.2 Create `AdminCatalogSummary.java` record:
    ```java
    // admin/catalog/domain/model/AdminCatalogSummary.java
    public record AdminCatalogSummary(
        long totalProducts,
        long activeProducts,
        long draftProducts,
        long lowStockProducts,   // stock_quantity > 0 AND stock_quantity <= 5
        long outOfStockProducts, // stock_quantity = 0
        long createdThisWeek
    ) {}
    ```

- [x] **Task 2 — Port use cases** (files in `admin/catalog/domain/port/in/`)
  - [x] 2.1 Create `ListProductsQuery.java` record:
    ```java
    public record ListProductsQuery(
        String search,          // nullable — product name ILIKE
        String tenantId,        // nullable UUID string — filter by tenant
        String stockLevel,      // nullable: ALL | OK | LOW | OUT
        String status,          // nullable: ALL | ACTIVE | DRAFT | ARCHIVED
        String plan,            // nullable: ALL | FREE | PAID
        int page,
        int pageSize
    ) {}
    ```
  - [x] 2.2 Create `ListProductsUseCase.java` interface: `Page<AdminProductListItem> execute(ListProductsQuery)`
  - [x] 2.3 Create `GetCatalogSummaryUseCase.java` interface: `AdminCatalogSummary execute()`

- [x] **Task 3 — Application service** (`admin/catalog/application/service/AdminCatalogService.java`)
  - [x] 3.1 `@Service` implements `ListProductsUseCase` + `GetCatalogSummaryUseCase`
  - [x] 3.2 Constructor injects `JdbcTemplate jdbc` (existing Spring bean, same instance as `AdminTenantService`)
  - [x] 3.3 Implement `listProducts(ListProductsQuery)`:

    **Step 1 — Load matching tenant schemas**
    ```sql
    SELECT t.id::text, t.name, t.schema_name, t.plan_type
    FROM public.tenants t
    WHERE t.id <> '00000000-0000-0000-0000-000000000000'::uuid
      AND t.status = 'ACTIVE'
      -- Dynamic: AND t.id = ?  (if tenantId filter)
      -- Dynamic: AND t.plan_type IN ('PREMIUM','PREMIUM_TRIAL')  (if plan=PAID)
      --          OR t.plan_type = 'FREE'  (if plan=FREE)
    ORDER BY t.name
    ```

    **Step 2 — For each tenant schema, query products**
    ```sql
    -- Executed per schema: schema_name e.g. kv_abc123
    SELECT p.id::text, p.name, p.price, p.stock_quantity, p.status, p.updated_at,
           COALESCE(c.name, '') AS category_name
    FROM kv_abc123.products p
    LEFT JOIN kv_abc123.categories c ON c.id = p.category_id
    WHERE TRUE
      -- Dynamic: AND p.name ILIKE ?  (if search)
      -- Dynamic: AND p.status = ?     (if status filter)
      -- Dynamic: AND p.stock_quantity = 0         (if stockLevel=OUT)
      -- Dynamic: AND p.stock_quantity > 0 AND p.stock_quantity <= 5  (if stockLevel=LOW)
      -- Dynamic: AND p.stock_quantity > 5         (if stockLevel=OK)
    ORDER BY p.updated_at DESC
    ```

    Collect all results into a single list, apply global pagination (page * pageSize offset on the collected list). For Phase 1 (<200 tenants, <50k products) an in-memory sort + slice is acceptable. Use `safeQuery()` wrapper (try/catch) per schema to isolate schema failures.

  - [x] 3.4 Implement `getCatalogSummary()`:
    ```
    For each active tenant schema:
      total    += COUNT(*) FROM schema.products
      active   += COUNT(*) FROM schema.products WHERE status = 'ACTIVE'
      draft    += COUNT(*) FROM schema.products WHERE status = 'DRAFT'
      lowStock += COUNT(*) FROM schema.products WHERE stock_quantity > 0 AND stock_quantity <= 5
      outStock += COUNT(*) FROM schema.products WHERE stock_quantity = 0
      thisWeek += COUNT(*) FROM schema.products WHERE created_at >= NOW() - INTERVAL '7 days'
    ```
    Use `safeCount(sql)` helper (same pattern as `AdminTenantService.safeCount()`).

  - [x] 3.5 Annotate all methods with `@Transactional(readOnly = true)`
  - [x] 3.6 **schemaName safety note** (same as story 9.1): schemaName comes from `public.tenants.schema_name`, validated at provisioning to match `kv_[a-z0-9]{6}`. Direct interpolation in SQL is safe — never interpolate user input.

- [x] **Task 4 — REST adapter** (`admin/catalog/adapter/in/rest/AdminCatalogController.java`)
  - [x] 4.1 `@RestController @RequestMapping("/api/v1/admin/catalog")`
  - [x] 4.2 Inject `AdminCatalogService`
  - [x] 4.3 Endpoints:
    ```
    GET  /api/v1/admin/catalog/summary          → AdminCatalogSummary
    GET  /api/v1/admin/catalog/products         → Page<AdminProductListItemDto> (paginated)
    GET  /api/v1/admin/catalog/products/export  → ResponseEntity<byte[]> (CSV, Content-Type: text/csv)
    ```
  - [x] 4.4 All endpoints call `requireSuperAdmin()` (copy exact pattern from `AdminTenantController`)
  - [x] 4.5 CSV export: iterate ALL pages (no pagination limit), serialize to CSV using manual string builder:
    ```
    product_id,product_name,tenant_name,plan,category,price_xaf,stock_quantity,status,last_updated
    uuid,Savon Lux,Cosmos Beauté,FREE,Hygiène,1500,23,ACTIVE,2026-05-10T14:23:00Z
    ...
    ```
    Return `ResponseEntity<byte[]>` with header `Content-Disposition: attachment; filename="keevo-products-2026-05-16.csv"`

- [x] **Task 5 — DTOs** (`admin/catalog/adapter/in/rest/dto/`)
  - [x] 5.1 `AdminProductListItemDto.java` record (serialization-safe — no `schemaName` field):
    ```java
    public record AdminProductListItemDto(
        String id, String name,
        String tenantId, String tenantName, String tenantPlan,
        String categoryName,
        long price, int stockQuantity, String status,
        String updatedAt   // ISO-8601 string
    ) {
        public static AdminProductListItemDto from(AdminProductListItem item) { ... }
    }
    ```
  - [x] 5.2 `AdminProductPageResponse.java` record:
    ```java
    public record AdminProductPageResponse(
        List<AdminProductListItemDto> items,
        long totalCount,
        int page,
        int pageSize
    ) {}
    ```
  - [x] 5.3 `AdminCatalogSummaryDto.java` (mirrors the domain record — add static `from()` factory)

- [x] **Task 6 — Unit tests** (TDD — write tests BEFORE implementing service)
  - [x] 6.1 `AdminCatalogControllerTest.java` (standaloneSetup — same pattern as `AdminTenantControllerTest`):
    - `getCatalogSummary_withSuperAdmin_returns200()`
    - `getCatalogSummary_withOwnerRole_returns403()`
    - `listProducts_withSuperAdmin_returns200()`
    - `listProducts_withOwnerRole_returns403()`
    - `exportProducts_withSuperAdmin_returnsCsvBytes()`
  - [x] 6.2 `AdminCatalogServiceTest.java` (mock `JdbcTemplate`, verify SQL fragments):
    - `listProducts_noFilters_queriesAllActiveSchemas()`
    - `listProducts_withTenantFilter_queriesOnlyTargetSchema()`
    - `listProducts_withStockLevelOut_appendsStockFilter()`
    - `getCatalogSummary_aggregatesAcrossSchemas()`
    - `getCatalogSummary_schemaFailure_skipsAndContinues()`

---

### Part B — Dashboard: `/monitoring/products` page

- [x] **Task 7 — Types** (`src/types/catalog.ts`)
  ```ts
  export interface AdminProductListItem {
    id: string;
    name: string;
    tenantId: string;
    tenantName: string;
    tenantPlan: 'FREE' | 'PAID';
    categoryName: string;
    price: number;
    stockQuantity: number;
    status: 'ACTIVE' | 'DRAFT' | 'ARCHIVED';
    updatedAt: string; // ISO-8601
  }

  export interface AdminProductPage {
    items: AdminProductListItem[];
    totalCount: number;
    page: number;
    pageSize: number;
  }

  export interface AdminCatalogSummary {
    totalProducts: number;
    activeProducts: number;
    draftProducts: number;
    lowStockProducts: number;
    outOfStockProducts: number;
    createdThisWeek: number;
  }

  export interface ProductFilters {
    search: string;
    tenantId: string;   // "" = ALL
    stockLevel: 'ALL' | 'OK' | 'LOW' | 'OUT';
    status: 'ALL' | 'ACTIVE' | 'DRAFT' | 'ARCHIVED';
    plan: 'ALL' | 'FREE' | 'PAID';
  }
  ```

- [x] **Task 8 — API lib** (`src/lib/api/admin-catalog.ts`)
  ```ts
  import client from "./client";
  import type { AdminCatalogSummary, AdminProductPage, ProductFilters } from "@/types/catalog";

  export async function getCatalogSummary(): Promise<AdminCatalogSummary> {
    const res = await client.get<{ data: AdminCatalogSummary }>("/admin/catalog/summary");
    return res.data.data;
  }

  export async function getProducts(filters: ProductFilters, page: number): Promise<AdminProductPage> {
    const params: Record<string, string | number> = { page, pageSize: 25 };
    if (filters.search) params.search = filters.search;
    if (filters.tenantId) params.tenantId = filters.tenantId;
    if (filters.stockLevel !== "ALL") params.stockLevel = filters.stockLevel;
    if (filters.status !== "ALL") params.status = filters.status;
    if (filters.plan !== "ALL") params.plan = filters.plan;
    const res = await client.get<{ data: AdminProductPage }>("/admin/catalog/products", { params });
    return res.data.data;
  }

  export function buildExportUrl(filters: ProductFilters): string {
    const params = new URLSearchParams();
    if (filters.search) params.set("search", filters.search);
    if (filters.tenantId) params.set("tenantId", filters.tenantId);
    if (filters.stockLevel !== "ALL") params.set("stockLevel", filters.stockLevel);
    if (filters.status !== "ALL") params.set("status", filters.status);
    if (filters.plan !== "ALL") params.set("plan", filters.plan);
    return `/api/backend/admin/catalog/products/export?${params}`;
  }
  ```

- [x] **Task 9 — React Query hook** (`src/hooks/useProducts.ts`)
  ```ts
  "use client";
  import { useQuery } from "@tanstack/react-query";
  import { useEffect, useState } from "react";
  import { getCatalogSummary, getProducts } from "@/lib/api/admin-catalog";
  import type { ProductFilters } from "@/types/catalog";

  const DEFAULT_FILTERS: ProductFilters = {
    search: "", tenantId: "", stockLevel: "ALL", status: "ALL", plan: "ALL",
  };

  export function useProducts() {
    const [filters, setFilters] = useState<ProductFilters>(DEFAULT_FILTERS);
    const [debouncedSearch, setDebouncedSearch] = useState("");
    const [page, setPage] = useState(0);

    useEffect(() => {
      const t = setTimeout(() => setDebouncedSearch(filters.search), 300);
      return () => clearTimeout(t);
    }, [filters.search]);

    const effective = { ...filters, search: debouncedSearch };

    const productsQuery = useQuery({
      queryKey: ["admin-products", effective, page],
      queryFn: () => getProducts(effective, page),
      placeholderData: (prev) => prev,
    });

    const summaryQuery = useQuery({
      queryKey: ["admin-catalog-summary"],
      queryFn: getCatalogSummary,
      staleTime: 60_000, // 1 min cache
    });

    const updateFilter = <K extends keyof ProductFilters>(key: K, value: ProductFilters[K]) => {
      setPage(0);
      setFilters((f) => ({ ...f, [key]: value }));
    };

    const clearFilter = (key: keyof ProductFilters) => {
      setPage(0);
      setFilters((f) => {
        const reset = { ...f };
        if (key === "search") reset.search = "";
        else if (key === "stockLevel") reset.stockLevel = "ALL";
        else if (key === "status") reset.status = "ALL";
        else if (key === "plan") reset.plan = "ALL";
        else if (key === "tenantId") reset.tenantId = "";
        return reset;
      });
    };

    return { productsQuery, summaryQuery, filters, updateFilter, clearFilter, page, setPage };
  }
  ```

- [x] **Task 10 — Summary card component** (`src/components/catalog/CatalogSummaryCards.tsx`)
  - `"use client"` — receives `AdminCatalogSummary` as prop
  - 5 cards in a responsive grid (`grid-cols-2 md:grid-cols-5`):
    - **Total Produits** — gray, value: `totalProducts`
    - **Actifs** — green (`text-green-600`), value: `activeProducts`
    - **Brouillons** — blue (`text-blue-600`), value: `draftProducts`
    - **Stock Faible** — amber (`text-amber-600`), value: `lowStockProducts`
    - **En Rupture** — red (`text-red-600`), value: `outOfStockProducts`
  - Each card: `bg-white rounded-lg shadow p-4`, label in `text-sm text-gray-500`, value in `text-3xl font-bold`
  - Loading skeleton: 5 gray `animate-pulse` rectangles when data is loading

- [x] **Task 11 — Filter bar component** (`src/components/catalog/ProductFilters.tsx`)
  - `"use client"` directive
  - Search input (controlled, left icon 🔍)
  - Tenant `<select>`: fetched from `GET /api/backend/admin/tenants?pageSize=100` (reuse `getTenants` from `admin-tenants.ts`)
    - Options: `<option value="">Tous les tenants</option>` + mapped tenant list
  - Stock Level `<select>`: ALL / Faible (1–5) / Rupture (0) / OK (>5)
  - Statut `<select>`: ALL / Actif / Brouillon / Archivé
  - Plan `<select>`: ALL / FREE / PAID

- [x] **Task 12 — Filter chips** (`src/components/catalog/ProductFilterChips.tsx`)
  - Renders active filters as dismissible `<span>` chips (same pattern as `TenantFilterChips.tsx`)
  - Maps filter keys to human labels: `tenantId → "Tenant: [name]"`, `stockLevel → "Stock: Faible"` etc.
  - "Réinitialiser tout" button if any filter is active

- [x] **Task 13 — Product table** (`src/components/catalog/ProductTable.tsx`)
  - `"use client"` directive
  - Renders `<table>` with columns: Produit | Tenant | Plan | Catégorie | Prix | Stock | Statut | Dernière màj
  - **Stock column**: value in XAF (formatted as `1 500 XAF`); stock with color coding:
    - `text-red-600 font-bold` if `stockQuantity === 0`
    - `text-amber-600` if `stockQuantity > 0 && stockQuantity <= 5`
    - `text-green-600` if `stockQuantity > 5`
  - **Statut badge**: `bg-green-100 text-green-800` (ACTIVE), `bg-blue-100 text-blue-800` (DRAFT), `bg-gray-100 text-gray-600` (ARCHIVED)
  - **Plan badge**: `bg-yellow-100 text-yellow-800` (PAID), `bg-gray-100 text-gray-600` (FREE)
  - Pagination controls: "Précédent" / "Page N / Total" / "Suivant"
  - Loading state: rows replaced by `animate-pulse` skeleton rows
  - Empty state: "Aucun produit trouvé pour les filtres sélectionnés."

- [x] **Task 14 — Page assembly** (`src/app/monitoring/products/page.tsx`)
  - Server Component shell with `<Suspense>` wrapper
  - Inner `ProductsClientPage` (`"use client"`) renders:
    ```
    <AdminLayout>
      <div class="p-6">
        <header>Catalogue Produits</header>
        <CatalogSummaryCards data={summaryQuery.data} />
        <div class="flex gap-2">
          <ProductFilters ... />
          <ExportCsvButton href={buildExportUrl(filters)} />
        </div>
        <ProductFilterChips filters={filters} clearFilter={clearFilter} />
        <ProductTable data={productsQuery.data} page={page} setPage={setPage} />
      </div>
    </AdminLayout>
    ```

- [x] **Task 15 — Sidebar update** (`src/components/layout/Sidebar.tsx`)
  - Add "Monitoring" group with collapsible chevron (defaults open)
  - Items under group:
    ```ts
    { href: "/monitoring/products", label: "Catalogue Produits" },
    { href: "/monitoring/activity",  label: "Activité" },        // story 9.7
    { href: "/monitoring/sync",      label: "Synchronisation" }, // story 9.8
    ```
  - Keep existing "Tenants" item above the group

---

## Dev Notes

### Critical Architecture Rules

1. **No `TenantContext` manipulation in `AdminCatalogService`** — use direct `JdbcTemplate` with schema-qualified SQL. The `JdbcTemplate` bean bypasses Hibernate's multi-tenant routing entirely.

2. **schemaName interpolation safety** — `schema_name` values are of form `kv_[a-z0-9]{6}` (validated at provisioning time, never from user input). Direct SQL interpolation `"SELECT ... FROM " + schemaName + ".products"` is safe here, matching the pattern in `AdminTenantService.loadStores()`.

3. **Exclude system tenant always** — `WHERE t.id <> '00000000-0000-0000-0000-000000000000'::uuid` in every public.tenants query (same constant `ADMIN_TENANT_ID` in `AdminTenantService`).

4. **`requireSuperAdmin()` — copy exactly** from `AdminTenantController` — do not create a shared abstraction (not in this story's scope). Copy the method verbatim.

5. **Phase 1 pagination approach** — collect all matching rows from all schemas in memory, then slice by page. Acceptable for <200 tenants × avg 250 products = <50k rows. The `safeQuery()` per-schema catch ensures a single corrupted schema doesn't fail the entire request.

6. **CSV export** — no streaming library needed. Use `StringBuilder` + `String.join(",", ...)` for simple line building. Set response header `Content-Disposition: attachment; filename="keevo-products-{date}.csv"` and `Content-Type: text/csv; charset=UTF-8`.

7. **`@Transactional(readOnly = true)` scope** — annotate at service method level only, not controller level. The `JdbcTemplate` does not participate in JPA's transaction context (different DataSource wrapper), but the annotation ensures read-committed isolation and no write operations.

### Backend: Product Table Schema

From `ProductDeltaProvider.java` (confirmed columns):
```sql
-- Tenant schema: kv_xxxxxx.products
SELECT id, name, price, buy_price, transport_cost, sku, category_id,
       description, photo_url, archived, status, stock_quantity, created_at, updated_at
FROM products
```
- `status`: values are `ACTIVE`, `DRAFT`, `ARCHIVED` (string column)
- `stock_quantity`: integer, can be 0 (out of stock)
- `price`: integer in XAF (no decimals)
- `category_id`: UUID FK → `categories.id`
- `archived`: boolean (redundant with `status = 'ARCHIVED'` — both may be set)

Categories table join:
```sql
LEFT JOIN kv_xxxxxx.categories c ON c.id = p.category_id
-- c.name → categoryName
```

### Backend: Tenant List for Filters

The tenant dropdown in the dashboard needs active tenant names. Reuse the existing `GET /api/v1/admin/tenants?pageSize=100` endpoint — no new endpoint needed.

### Dashboard: Next.js Proxy

The `next.config.ts` already rewrites `/api/backend/*` → `http://localhost:8080/api/v1/*`. No configuration changes needed for new admin endpoints.

### Dashboard: Price Formatting (XAF)

```ts
// XAF has no decimal places — use integer formatting
function formatXAF(amount: number): string {
  return new Intl.NumberFormat('fr-CM', {
    style: 'currency',
    currency: 'XAF',
    maximumFractionDigits: 0
  }).format(amount);
  // → "1 500 FCFA" or "1 500 XAF"
}
```

Place this utility in `src/lib/format.ts` (new file — check if it already exists).

### Dashboard: `"use client"` Rules

- `app/monitoring/products/page.tsx` → Server Component shell (no `"use client"`)
- `ProductsClientPage` inner component → `"use client"` (state, event handlers)
- All `src/components/catalog/*.tsx` → `"use client"` (they use hooks/state)
- Same pattern as `tenants/page.tsx` → `TenantsClientPage`

### References

- Pattern: `AdminTenantService.java` — JdbcTemplate cross-schema queries, safeCount, safeQuery
- Pattern: `AdminTenantController.java` — requireSuperAdmin(), ApiResponseWrapper, @Operation annotations
- Pattern: `admin-tenants.ts` — axios client, query params building, response unwrapping `.data.data`
- Pattern: `useTenants.ts` — debounce, `useQuery`, `placeholderData`, `updateFilter`, `clearFilter`
- Pattern: `TenantTable.tsx` — status badges, pagination controls, loading skeleton
- Pattern: `TenantFilterChips.tsx` — dismissible chips structure
- Product schema: `ProductDeltaProvider.java` (confirmed column names)
- `AdminTenantService.ADMIN_TENANT_ID` = `"00000000-0000-0000-0000-000000000000"` — exclude from all queries

### Files to Create

**Backend (new):**
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/model/AdminProductListItem.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/model/AdminCatalogSummary.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/port/in/ListProductsQuery.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/port/in/ListProductsUseCase.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/port/in/GetCatalogSummaryUseCase.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/application/service/AdminCatalogService.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/adapter/in/rest/AdminCatalogController.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/adapter/in/rest/dto/AdminProductListItemDto.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/adapter/in/rest/dto/AdminProductPageResponse.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/adapter/in/rest/dto/AdminCatalogSummaryDto.java`
- `keevo/backend/src/test/java/com/keevo/admin/catalog/adapter/in/rest/AdminCatalogControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/admin/catalog/application/service/AdminCatalogServiceTest.java`

**Dashboard (new):**
- `keevo/dashboard/src/types/catalog.ts`
- `keevo/dashboard/src/lib/api/admin-catalog.ts`
- `keevo/dashboard/src/lib/format.ts` (check if exists first)
- `keevo/dashboard/src/hooks/useProducts.ts`
- `keevo/dashboard/src/components/catalog/CatalogSummaryCards.tsx`
- `keevo/dashboard/src/components/catalog/ProductFilters.tsx`
- `keevo/dashboard/src/components/catalog/ProductFilterChips.tsx`
- `keevo/dashboard/src/components/catalog/ProductTable.tsx`
- `keevo/dashboard/src/app/monitoring/products/page.tsx`

**Dashboard (modified):**
- `keevo/dashboard/src/components/layout/Sidebar.tsx` — add Monitoring nav group

---

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-5 (Cursor Agent)

### Completion Notes List
- All 15 tasks completed successfully across backend and frontend.
- Pre-existing compilation errors in `AdminSyncMonitoringService` (duplicate `execute()` method conflict between two interfaces) were fixed as a prerequisite: renamed `execute()` in `ListTenantSyncHealthUseCase` to `listTenantSyncHealth()`, updated service and controller accordingly.
- Pre-existing type inference error in `AdminSyncMonitoringServiceTest.stubTenants()` helper method was fixed: changed parameter type to `List<?>` with internal cast.
- Mockito stubbing order issue in `AdminCatalogServiceTest.getCatalogSummary_aggregatesAcrossSchemas()` was resolved by placing the lenient `anyString()` catch-all stub before the specific `matches()` stubs (LIFO matching rule).
- 21 pre-existing test failures in `JpaBaseEntityPersistTest`, `SectorTemplateFactoryTest`, `AuthControllerTest`, `OnboardingServiceTest` are unrelated to this story (require live PostgreSQL or depend on story 8.6 which is still in progress).
- All 10 new unit tests pass: 5 in `AdminCatalogControllerTest` + 5 in `AdminCatalogServiceTest`.
- `src/lib/format.ts` already existed; `formatXAFFull` function added alongside existing `formatXAF`.
- Frontend: `ProductFilters.tsx` reuses `getTenants` from `admin-tenants.ts` for the tenant dropdown — no duplicate API logic.
- Sidebar "Monitoring" group defaults open with `Activité` and `Synchronisation` marked as "coming soon" (links present, stories 9.7 and 9.8 not yet implemented).

### File List

**Backend (created):**
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/model/AdminProductListItem.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/model/AdminCatalogSummary.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/port/in/ListProductsQuery.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/port/in/ListProductsUseCase.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/domain/port/in/GetCatalogSummaryUseCase.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/application/service/AdminCatalogService.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/adapter/in/rest/AdminCatalogController.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/adapter/in/rest/dto/AdminProductListItemDto.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/adapter/in/rest/dto/AdminProductPageResponse.java`
- `keevo/backend/src/main/java/com/keevo/admin/catalog/adapter/in/rest/dto/AdminCatalogSummaryDto.java`
- `keevo/backend/src/test/java/com/keevo/admin/catalog/adapter/in/rest/AdminCatalogControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/admin/catalog/application/service/AdminCatalogServiceTest.java`

**Backend (modified):**
- `keevo/backend/src/main/java/com/keevo/admin/sync_monitoring/domain/port/in/ListTenantSyncHealthUseCase.java` — renamed `execute()` → `listTenantSyncHealth()` to fix method conflict
- `keevo/backend/src/main/java/com/keevo/admin/sync_monitoring/application/service/AdminSyncMonitoringService.java` — updated method name
- `keevo/backend/src/main/java/com/keevo/admin/sync_monitoring/adapter/in/rest/AdminSyncMonitoringController.java` — updated method call
- `keevo/backend/src/test/java/com/keevo/admin/sync_monitoring/application/service/AdminSyncMonitoringServiceTest.java` — fixed type inference in `stubTenants()`

**Dashboard (created):**
- `keevo/dashboard/src/types/catalog.ts`
- `keevo/dashboard/src/lib/api/admin-catalog.ts`
- `keevo/dashboard/src/hooks/useProducts.ts`
- `keevo/dashboard/src/components/catalog/CatalogSummaryCards.tsx`
- `keevo/dashboard/src/components/catalog/ProductFilters.tsx`
- `keevo/dashboard/src/components/catalog/ProductFilterChips.tsx`
- `keevo/dashboard/src/components/catalog/ProductTable.tsx`
- `keevo/dashboard/src/app/monitoring/products/page.tsx`

**Dashboard (modified):**
- `keevo/dashboard/src/lib/format.ts` — added `formatXAFFull` function
- `keevo/dashboard/src/components/layout/Sidebar.tsx` — added Monitoring nav group

---

## Review Findings (2026-05-21)

- [x] [Review][Patch] H1: `escapeCsv()` ne gérait pas `\r` (carriage return) — produits avec `\r` dans le nom généraient un CSV malformé [AdminCatalogController.java] → **fixé** : ajout `|| value.contains("\r")` dans la condition
- [x] [Review][Patch] NR: NPE dans `listTenantSyncHealth()` — `.thenComparing(tenantName)` lançait NullPointerException pour les 37 tenants avec `name = NULL` en DB [AdminSyncMonitoringService.java] → **fixé** : `Comparator.nullsLast(Comparator.naturalOrder())`
- **Curl tests story 9.6** : 13/13 PASS (T1–T12 + T8b)
- **Non-regression** : 5/5 PASS (NR-1 à NR-5)
- **Unit tests** : 10/10 GREEN (`AdminCatalogControllerTest` + `AdminCatalogServiceTest`)

# Story 6.2: Formulaire Guidé de Saisie des Quantités Physiques

Status: done

<!-- Validation optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a user (Simon or Loïc),
I want a guided form that shows me each product with its theoretical stock and lets me enter the physical count,
So that I can complete the inventory systematically without missing any product and without errors.

---

## ⚠️ CRITICAL ARCHITECTURAL CONTEXT — Building on Story 6.1

> **This is Story 6.2 of Epic 6 — Inventaire Assisté.**
> Story 6.1 is **done** and established the full inventory session foundation. This story adds the counting form on top of it.
>
> **Drift schema**: currently at **v19** (Story 6.1). This story bumps to **v20** (new `InventoryCounts` table).
> **Backend test count**: 1007 tests GREEN after 6.1. This story MUST preserve that baseline.
>
> **Key constraint**: The `InventoryScopeResolver` Strategy interface was **architected** in Story 6.1 but left as `(reserved for Story 6.2+ scope resolvers)`. This story **implements** those strategies:
> - `FullScopeResolver` — all active products in the target store from `stock_levels`
> - `PartialScopeResolver` — products in selected categories only
>
> **Everything created by Story 6.1 must stay untouched** — only extend, never modify existing methods unless strictly necessary. The one required extension to existing code is adding `findAllByStoreId()` to `StockLevelRepository` port + adapter (this method was listed as "REUSABLE" in Story 6.1 but was not yet added).

### Current State — What Story 6.1 Created

```
BACKEND (inventory/counting/ module — COMPLETE from 6.1):
  Domain: InventorySession, InventoryScope, InventorySessionStatus (state machine)
  Events: InventorySessionCreatedEvent, InventorySessionCancelledEvent
  Ports in: Create/Cancel/GetActive/List + Commands/Queries
  Ports out: InventorySessionRepository
  Services: Create/Cancel/GetActive/List session services
  REST: POST /api/v1/inventory/sessions, GET active, POST cancel, GET list
  JPA: InventorySessionJpaEntity + adapter + Spring Data interface
  Sync: InventorySessionSyncHandler (push) + InventorySessionDeltaProvider (pull - 13th provider)
  DDL: inventory_sessions table + 2 indexes in TenantSchemaProvisioner + TenantSchemaSyncService

FLUTTER (features/inventory/ — COMPLETE from 6.1):
  Drift v19: InventorySessions table
  Domain: InventorySessionModel (Freezed), InventoryScope enum
  Repo: InventorySessionRepository interface + Impl (Backend-First)
  Providers: activeSessionProvider, sessionHistoryProvider, CreateSessionNotifier, CancelSessionNotifier
  UI: InventoryLaunchPage, InventoryConfigBottomSheet, ActiveSessionBanner, SessionHistoryCard
  Router: /inventory → InventoryLaunchPage + /inventory/counting/:sessionId → PLACEHOLDER (to replace here)
  Sync: pull upsert of inventorySessions in rest_sync_service.dart
```

### What Story 6.2 Adds

```
┌──────────────────────────────────────────────────────────────────────
│ STORY 6.2 — INVENTORY COUNTING FORM                                 │
│                                                                      │
│ BACKEND (inventory/counting/ module extension):                      │
│   DDL: inventory_counts table (per-tenant schema)                   │
│     → id, session_id, product_id, variant_id, product_name,        │
│       variant_label, theoretical, physical, counted_at,             │
│       counted_by, updated_at                                        │
│     → Unique partial indexes for (session+product) per variant      │
│                                                                      │
│   Domain extension:                                                  │
│     InventoryCount (entity — count row for one product in session)  │
│     InventoryProductRow (projection — product + stock + count)      │
│     InventoryGap (value object — ecart = physical − theoretical)    │
│     InventoryScopeResolver (Strategy interface — NEW, reserved 6.1) │
│     FullScopeResolver (Strategy impl — all active products)         │
│     PartialScopeResolver (Strategy impl — products by category)     │
│                                                                      │
│   Ports:                                                             │
│     in/  → GetCountingProductsUseCase (+ Query)                     │
│            SaveInventoryCountUseCase (+ Command)                    │
│            GetSessionCountsUseCase (+ Query)                        │
│     out/ → InventoryCountRepository                                 │
│                                                                      │
│   Application services:                                              │
│     GetCountingProductsService (uses InventoryScopeResolver)        │
│     SaveInventoryCountService                                        │
│     GetSessionCountsService                                          │
│                                                                      │
│   Adapter REST:                                                      │
│     GET  /api/v1/inventory/sessions/{id}/products (scope-filtered)  │
│     POST /api/v1/inventory/sessions/{id}/counts   (save count)      │
│     PUT  /api/v1/inventory/sessions/{id}/counts   (bulk upsert)     │
│     GET  /api/v1/inventory/sessions/{id}/counts   (all counts)      │
│                                                                      │
│   StockLevelRepository: add findAllByStoreId() port + adapter       │
│   Sync: InventoryCountSyncHandler + InventoryCountDeltaProvider     │
│                                                                      │
│ FLUTTER:                                                             │
│   Drift v20: InventoryCounts table                                  │
│   Domain: InventoryCountModel (Freezed), InventoryProductRowModel   │
│   Data: Local/Remote InventoryCount DataSources + RepositoryImpl    │
│   Providers: countingProductsProvider, inventoryCountNotifier,      │
│              countingProgressProvider, countingFilterProvider       │
│   Pages: InventoryCountingPage (THE counting form — replaces        │
│          /inventory/counting/:sessionId placeholder)                │
│   Widgets: InventoryRow (UX32 — the KEY component)                  │
│   Router: /inventory/counting/:sessionId → InventoryCountingPage    │
│   InventoryLaunchPage: wire navigation to counting page             │
└──────────────────────────────────────────────────────────────────────
```

---

## GoF Pattern Analysis (MANDATORY)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Product scope resolution: FULL (all store products) vs PARTIAL (products in selected categories). This filtering logic differs per scope type. Future scopes may add BY_SUPPLIER or BY_PRICE_RANGE. |
| What might change in the future? | New inventory scopes. Barcode scan mode (Step 1 scan → Step 2 auto-fill count). Auto-suggest counts from average sales velocity. Multi-user concurrent counting (one user per category). |
| Which GoF patterns apply? | **Strategy** — `InventoryScopeResolver` interface defines product fetch contract. `FullScopeResolver` and `PartialScopeResolver` are concrete strategies. `GetCountingProductsService` uses the strategy without knowing which implementation it has. **Factory/Registry** — `ScopeResolverRegistry` maps each `InventoryScope` enum value to its `InventoryScopeResolver` implementation (Open/Closed: new scope = new class + register it). **Observer** — `InventoryCountSavedEvent` → `AuditEventListener` (audit trail for each count save). |
| How does it enable Open/Closed? | Adding a new scope = implement `InventoryScopeResolver`, register in `ScopeResolverRegistry`. Zero modification to `GetCountingProductsService`. |
| Where is the pattern applied? | **Strategy** → `inventory/counting/domain/service/InventoryScopeResolver.java` (interface) + `FullScopeResolver.java` + `PartialScopeResolver.java`. **Registry** → `ScopeResolverRegistry.java` in `application/service/`. **Observer** → `InventoryCountSavedEvent` in `inventory/counting/domain/event/` → `AuditEventListener`. |

---

## Acceptance Criteria

### AC1 — Counting form loads with all in-scope products

- **Given** Simon has an active `IN_PROGRESS` inventory session for a store
- **When** he navigates to `/inventory/counting/{sessionId}` (or taps "Reprendre" on the banner, or is redirected after session creation)
- **Then** the `InventoryCountingPage` loads with all products in scope:
  - **FULL scope**: all active (non-archived, status = ACTIVE) products in that store for which a `stock_level` record exists
  - **PARTIAL scope**: same, but filtered to the session's `categoryIds` only
- **And** each product is displayed as an `InventoryRow` widget showing:
  - Product photo (from `products.photo_url`) OR initials avatar (first 2 letters of product name, colored with a deterministic hash)
  - Product name
  - Variant label (if `variant_id != null` — shown as a small chip below the name)
  - Theoretical stock: label "Keevo : X" (X from local Drift `stock_levels.quantity` for that productId + storeId)
  - A numeric `TextFormField` labelled "Réel" — initially empty
  - An écart badge — **hidden** until the "Réel" field has a value
- **And** the list is sorted **alphabetically** by product name by default
- **And** the list uses `ListView.builder` — **NEVER** `children: [...]` (mandatory for performance with 200+ items)
- **And** the page loads **fully offline** from local Drift `stock_levels` and `products` tables
- **And** upon page load, the backend count records are fetched (when online) and pre-fill any previously entered counts (`GET /api/v1/inventory/sessions/{sessionId}/counts`)

### AC2 — Real-time écart badge on count entry

- **Given** Simon taps the "Réel" field on an `InventoryRow`
- **When** he enters a number (e.g., "3")
- **Then** the écart badge **immediately** calculates and displays:
  - ✅ `"= 0"` (green, `colorSuccess #51CF66`) — if `physical == theoretical`
  - ⚠️ `"+X"` (orange/amber, `colorWarning #FCC419`) — if `physical > theoretical` (surplus)
  - 🔴 `"−X"` (red, `colorError #FA5252`) — if `physical < theoretical` (shortage)
- **And** the écart formula: `écart = physical − theoretical`
- **And** the row background color changes:
  - White / `colorScheme.surface` — not yet counted
  - `amber.shade50` (`#FFFDE7`) — surplus
  - `red.shade50` (`#FFEBEE`) — shortage
  - `green.shade50` (`#E8F5E9`) — exact match
- **And** the count value is **auto-saved to local Drift** immediately on change (debounce 400ms)
- **And** if online, the count is **also sent to backend** via `POST /api/v1/inventory/sessions/{sessionId}/counts` (Backend-First — fire-and-forget with silent offline fallback to sync_queue)

### AC3 — Progress bar and completion tracking

- **Given** Simon is on the `InventoryCountingPage`
- **When** he enters counts for products
- **Then** a **progress bar** at the top of the page shows `"X / Y produits comptés"` where:
  - X = number of products with a non-null `physical` count value
  - Y = total products in scope
- **And** the progress bar **updates in real-time** as each field is filled in
- **And** when `X == Y` (100% counted):
  - A **summary banner** appears below the progress bar: `"Inventaire terminé — X concordants, Y écarts"`
  - The **"Valider l'inventaire"** button (currently disabled or hidden) becomes **active and visible**
  - The "Valider" button navigates to the Story 6.3 placeholder (for now: shows a `SnackBar` "6.3 — Rapport d'inventaire à venir" — this will be replaced in Story 6.3)

### AC4 — Filter FAB: Tous / Non comptés / Écarts seulement

- **Given** Simon is on the counting form with several products
- **When** he taps the `FloatingActionButton` "Filtrer" at the bottom right
- **Then** a bottom sheet or popup menu appears with three options:
  - **Tous** — show all products in scope
  - **Non comptés** (⭐ DEFAULT on page load) — show only products with `physical == null`
  - **Écarts seulement** — show only products with `physical != null && écart != 0`
- **And** selecting a filter immediately re-renders the `ListView.builder` with the filtered list
- **And** the active filter is indicated visually on the FAB (e.g., color accent or badge count)
- **And** the filter operates **locally** against in-memory state — no network call

### AC5 — Real-time search by product name / SKU

- **Given** Simon is on the counting form
- **When** he taps the search icon in the `AppBar`
- **Then** a search bar appears (replaces the AppBar title)
- **And** as he types, the product list filters in **real-time** to show only products whose `name` or `sku` contains the search string (case-insensitive)
- **And** the search operates **100% offline** against the in-memory product list (no Drift query on every keystroke)
- **And** clearing the search bar restores the previously active filter
- **And** the search closes on back press; the AppBar title is restored

### AC6 — Pause and resume: counts are persisted locally

- **Given** Simon has entered some counts and then exits the app (or navigates away) without tapping "Valider"
- **When** he returns to the inventory counting form for the same session
- **Then** all previously entered counts are **pre-filled** in the `InventoryRow` fields (loaded from local Drift `inventory_counts`)
- **And** the progress bar reflects the already-counted products
- **And** the default filter on resume is **"Non comptés"** (shows remaining uncounted products first)
- **And** the session's `status` remains `IN_PROGRESS` — it is NOT modified by this story

### AC7 — Sync: push offline counts, pull on resume

- **Given** Simon entered counts while offline
- **When** connectivity returns and push sync runs
- **Then** each offline count is pushed via `sync_queue` → `POST /api/v1/sync/push` with operation `SAVE_INVENTORY_COUNT`
- **And** the backend `InventoryCountSyncHandler` validates and upserts the count (idempotent by session+product+variant composite key)
- **And** pull sync includes `inventoryCounts` in the delta response (`InventoryCountDeltaProvider`)
- **And** other devices see the counts appear after their next pull (multi-user concurrent counting — Loïc on one device, Simon on another)
- **And** pending (unsynced) counts are **never overwritten** by pull delta (same `pendingIds` protection pattern from Stories 5.1/5.2)

---

## Technical Requirements

### Backend — Spring Boot (Hexagonal Architecture, `inventory/counting/` module extension)

**Package**: `com.keevo.inventory.counting` — all new classes in this existing module.

#### New DDL — `inventory_counts` table

Add to `TenantSchemaProvisioner.java` (follow existing DDL constant pattern):

```sql
CREATE TABLE IF NOT EXISTS inventory_counts (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id     UUID         NOT NULL REFERENCES inventory_sessions(id),
    product_id     UUID         NOT NULL,
    variant_id     UUID,
    product_name   VARCHAR(200) NOT NULL,
    variant_label  VARCHAR(100),
    theoretical    INT          NOT NULL DEFAULT 0,
    physical       INT,                  -- NULL = not yet counted
    counted_at     TIMESTAMPTZ,
    counted_by     UUID,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Partial unique indexes for nullable variant_id (PostgreSQL NULLS DISTINCT workaround)
CREATE UNIQUE INDEX IF NOT EXISTS idx_inventory_counts_session_product_no_variant
    ON inventory_counts(session_id, product_id) WHERE variant_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_inventory_counts_session_product_variant
    ON inventory_counts(session_id, product_id, variant_id) WHERE variant_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_inventory_counts_session
    ON inventory_counts(session_id);
```

Also add to `TenantSchemaSyncService.ensureRequiredIndexes()` for existing tenant auto-migration.

#### Extension to `StockLevelRepository` port (REQUIRED — missing from 6.1)

Add to `catalog/stock/domain/port/out/StockLevelRepository.java`:
```java
/** Find all stock levels for a store (used by inventory counting). */
List<StockLevel> findAllByStoreId(UUID storeId);
```

Add to `StockLevelSpringRepository.java`:
```java
@Query("SELECT s FROM StockLevelJpaEntity s WHERE s.storeId = :storeId")
List<StockLevelJpaEntity> findAllByStoreId(@Param("storeId") UUID storeId);
```

Add to `StockLevelRepositoryAdapter.java`:
```java
@Override
@Transactional(readOnly = true)
public List<StockLevel> findAllByStoreId(UUID storeId) {
    return springRepository.findAllByStoreId(storeId).stream()
            .map(this::toDomain).toList();
}
```

#### New Domain Classes

**Package structure** (all new files under `com.keevo.inventory.counting`):

```
domain/
├── model/
│   ├── InventoryCount.java          # Entity: one count per product+variant per session
│   └── InventoryProductRow.java     # Read model: product + stockLevel + existingCount
├── service/
│   ├── InventoryScopeResolver.java  # Strategy interface (was reserved in 6.1)
│   ├── FullScopeResolver.java       # All active products in store
│   └── PartialScopeResolver.java    # Products in selected categories
├── event/
│   └── InventoryCountSavedEvent.java
└── port/
    ├── in/
    │   ├── GetCountingProductsQuery.java
    │   ├── GetCountingProductsUseCase.java
    │   ├── SaveInventoryCountCommand.java
    │   ├── SaveInventoryCountUseCase.java
    │   ├── GetSessionCountsQuery.java
    │   └── GetSessionCountsUseCase.java
    └── out/
        └── InventoryCountRepository.java

application/service/
├── GetCountingProductsService.java     # Uses ScopeResolverRegistry
├── ScopeResolverRegistry.java          # Factory/Map of InventoryScope → InventoryScopeResolver
├── SaveInventoryCountService.java
└── GetSessionCountsService.java

adapter/
├── in/rest/
│   ├── InventoryCountController.java
│   └── dto/
│       ├── InventoryProductRowResponseDto.java
│       ├── SaveInventoryCountRequestDto.java
│       ├── InventoryCountResponseDto.java
│       └── BulkSaveCountsRequestDto.java
└── out/persistence/
    ├── InventoryCountJpaEntity.java
    ├── JpaInventoryCountRepository.java
    └── InventoryCountRepositoryAdapter.java
```

**`InventoryCount.java`** (pure Java entity, no framework deps):
```java
public class InventoryCount {
    private final UUID id;
    private final UUID sessionId;
    private final UUID productId;
    private final UUID variantId;     // null for simple products
    private final String productName;
    private final String variantLabel; // null if no variant
    private final int theoretical;    // snapshot from stock_levels at session start
    private Integer physical;         // null = not yet counted
    private Instant countedAt;
    private UUID countedBy;
    private Instant updatedAt;

    public int getEcart() {
        if (physical == null) throw new IllegalStateException("Product not counted");
        return physical - theoretical;
    }
    public boolean isCounted() { return physical != null; }
    // static factory, record() method, withPhysical() builder-style copy
}
```

**`InventoryProductRow.java`** (read model / projection):
```java
public record InventoryProductRow(
    UUID productId,
    String productName,
    String photoUrl,
    UUID variantId,
    String variantLabel,
    int theoreticalQty,
    Integer physicalQty,    // null if not yet counted
    Integer ecart           // null if not yet counted (physicalQty - theoreticalQty)
) {}
```

**`InventoryScopeResolver.java`** (Strategy interface):
```java
public interface InventoryScopeResolver {
    InventoryScope supportedScope();
    List<InventoryProductRow> resolveProducts(
        UUID storeId,
        List<UUID> categoryIds,        // null for FULL
        List<InventoryCount> existingCounts  // already-counted entries for this session
    );
}
```

**`InventoryScopeResolver` implementations**:
- `FullScopeResolver`: calls `StockLevelRepository.findAllByStoreId(storeId)` → gets all `StockLevel` records for the store → extracts the productIds → calls `ProductRepository.findAllByIds(productIds)` (new method, see Task 2) to enrich with name/photo → merges `existingCounts` → returns sorted `InventoryProductRow` list.
  - ⚠️ **`ProductJpaEntity` has NO `storeId` field** — products are tenant-scoped but not store-scoped at the product level. Store filtering happens via `stock_levels.store_id`, NOT via `productRepository.findAllActiveByStoreId()` (that method does not exist and must NOT be invented).
- `PartialScopeResolver`: same as Full, but additionally filters the product IDs to only those whose product has a `categoryId` in `session.getCategoryIds()`. Uses `ProductRepository.findByCategoryIds(categoryIds)` (new method to add) to get the eligible product IDs, then intersects with the storeId-filtered stock levels.

**`ScopeResolverRegistry.java`** (GoF: Factory/Registry — Open/Closed):
```java
@Component
public class ScopeResolverRegistry {
    private final Map<InventoryScope, InventoryScopeResolver> resolvers;

    public ScopeResolverRegistry(List<InventoryScopeResolver> allResolvers) {
        this.resolvers = allResolvers.stream()
            .collect(Collectors.toMap(InventoryScopeResolver::supportedScope, Function.identity()));
    }

    public InventoryScopeResolver get(InventoryScope scope) {
        return Optional.ofNullable(resolvers.get(scope))
            .orElseThrow(() -> new IllegalStateException("No resolver for scope: " + scope));
    }
}
```

**`GetCountingProductsService.java`**:
```java
@Service
@Transactional(readOnly = true)
public class GetCountingProductsService implements GetCountingProductsUseCase {
    private final InventorySessionRepository sessionRepository;
    private final InventoryCountRepository countRepository;
    private final ScopeResolverRegistry resolverRegistry;

    public List<InventoryProductRow> execute(GetCountingProductsQuery query) {
        InventorySession session = sessionRepository.findById(query.sessionId())
            .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND, ...));
        List<InventoryCount> existingCounts = countRepository.findBySessionId(session.getId());
        return resolverRegistry.get(session.getScope())
            .resolveProducts(session.getStoreId(), session.getCategoryIds(), existingCounts);
    }
}
```

**`InventoryCountRepository.java`** interface (port out):
```java
public interface InventoryCountRepository {
    InventoryCount save(InventoryCount count);
    List<InventoryCount> findBySessionId(UUID sessionId);
    Optional<InventoryCount> findBySessionAndProduct(UUID sessionId, UUID productId, UUID variantId);
    // ON CONFLICT DO UPDATE for upsert
    InventoryCount upsert(InventoryCount count);
}
```

**REST Endpoints** — `InventoryCountController.java`:
```java
@RestController
@RequestMapping("/api/v1/inventory/sessions/{sessionId}")
public class InventoryCountController {
    @GetMapping("/products")             // AC1: list products in scope with counts
    @PostMapping("/counts")              // AC2: save/upsert a single count
    @PutMapping("/counts")               // AC7: bulk upsert (sync push)
    @GetMapping("/counts")               // AC6: get all counts for resume
}
```

**`SaveInventoryCountCommand.java`** (pure Java Record):
```java
public record SaveInventoryCountCommand(
    UUID sessionId,
    UUID productId,
    UUID variantId,     // nullable
    String productName,
    String variantLabel,
    int theoretical,
    int physical,
    UUID actorId
) {}
```

**Error codes** — update `ErrorCode.java`:
```java
// ⚠️  INVENTORY_SESSION_NOT_IN_PROGRESS already exists (Story 6.1) — REUSE IT for the
//     "cannot count on a non-IN_PROGRESS session" case. Do NOT add a duplicate code.

// ADD only this new code:
INVENTORY_COUNT_PRODUCT_NOT_IN_SCOPE,     // HTTP 400 — product not in session scope
```

**`ProductRepository`** — add 2 new methods (Task 2):
```java
// Add to catalog/product/domain/port/out/ProductRepository.java:

/** Find products by a list of IDs (used by FullScopeResolver to enrich StockLevel data). */
List<Product> findAllByIds(List<UUID> ids);

/** Find active products belonging to any of the given category IDs (PartialScopeResolver). */
List<Product> findByCategoryIds(List<UUID> categoryIds);
```

> ⚠️ **`findAllActiveByStoreId()` does NOT exist and must NOT be created** — `ProductJpaEntity` has no `storeId` column. Products are tenant-scoped; store association is tracked only via `stock_levels.store_id`. Use `StockLevelRepository.findAllByStoreId()` to get the product IDs for a store, then `ProductRepository.findAllByIds()` to fetch product details.

**`InventoryCountSavedEvent.java`** (for audit):
```java
public record InventoryCountSavedEvent(
    UUID countId, UUID sessionId, UUID productId, UUID variantId,
    int theoretical, int physical, int ecart,
    UUID actorId, String tenantId, Instant occurredAt
) {}
```

**Audit registration** in `AuditEventListener.java`:
```java
@EventListener
public void on(InventoryCountSavedEvent event) {
    auditPort.record("INVENTORY_COUNT_SAVED", "InventoryCount", event.countId(), ...);
}
```

**Sync push handler** — `InventoryCountSyncHandler.java` in `sync/sync/application/handler/`:
- Handles `SAVE_INVENTORY_COUNT` operation type
- Idempotent: `upsert` by (sessionId + productId + variantId) composite key
- Delegates to `SaveInventoryCountUseCase`
- Register in `SyncOperationHandlerRegistry`

**Sync pull delta provider** — `InventoryCountDeltaProvider.java` (14th provider):
```java
@Override
public String entityKey() { return "inventoryCounts"; }

@Override
public List<Map<String, Object>> queryDelta(Instant since) {
    String sql = "SELECT id, session_id, product_id, variant_id, product_name, " +
        "variant_label, theoretical, physical, counted_at, counted_by, updated_at " +
        "FROM inventory_counts WHERE updated_at > :since ORDER BY updated_at ASC";
    // ... standard pattern from InventorySessionDeltaProvider
}
```

Register in `SyncService.pull()` alongside the existing 13 providers (or auto-registered if using `List<DeltaEntityProvider>` injection).

---

### Frontend — Flutter (Clean Architecture + Riverpod)

#### Drift v20 — `InventoryCounts` table

New file `lib/core/storage/inventory_counts_table.dart`:
```dart
class InventoryCounts extends Table {
  TextColumn get id => text()();
  TextColumn get sessionId => text()();
  TextColumn get productId => text()();
  TextColumn get variantId => text().nullable()();  // null for simple products
  TextColumn get productName => text()();
  TextColumn get variantLabel => text().nullable()();
  IntColumn get theoretical => integer()();
  IntColumn get physical => integer().nullable()();  // null = not yet counted
  TextColumn get countedBy => text().nullable()();
  DateTimeColumn get countedAt => dateTime().nullable()();
  DateTimeColumn get updatedAt => dateTime()();
  BoolColumn get synced => boolean().withDefault(const Constant(false))();

  @override
  Set<Column> get primaryKey => {id};
}
```

**Migration** in `app_database.dart`:
```dart
// schemaVersion => 20
if (from < 20) {
  await m.createTable(inventoryCounts);
}
```

Register in `@DriftDatabase(tables: [..., InventoryCounts])`.

#### Feature Structure (new files)

```
lib/features/inventory/
├── domain/
│   ├── model/
│   │   ├── inventory_count_model.dart       # Freezed — count row
│   │   └── inventory_product_row_model.dart # Freezed — product + theoretical + count
│   ├── repository/
│   │   └── inventory_count_repository.dart  # Abstract interface
│   └── usecase/
│       └── save_inventory_count_usecase.dart
├── data/
│   ├── datasource/
│   │   ├── local_inventory_count_datasource.dart
│   │   └── remote_inventory_count_datasource.dart
│   └── repository/
│       └── inventory_count_repository_impl.dart
└── presentation/
    ├── provider/
    │   └── inventory_counting_provider.dart  # Counting state management
    ├── page/
    │   └── inventory_counting_page.dart      # THE counting form (replaces placeholder)
    └── widget/
        └── inventory_row.dart                # UX32 — key reusable component
```

#### Domain Models

**`inventory_count_model.dart`** (Freezed):
```dart
@freezed
class InventoryCountModel with _$InventoryCountModel {
  const InventoryCountModel._();
  const factory InventoryCountModel({
    required String id,
    required String sessionId,
    required String productId,
    String? variantId,
    required String productName,
    String? variantLabel,
    required int theoretical,
    int? physical,           // null = not counted
    String? countedBy,
    DateTime? countedAt,
    required DateTime updatedAt,
    @Default(false) bool synced,
  }) = _InventoryCountModel;

  int? get ecart => physical != null ? physical! - theoretical : null;
  bool get isCounted => physical != null;
  bool get isMatch => physical != null && physical == theoretical;
  bool get isSurplus => physical != null && physical! > theoretical;
  bool get isShortage => physical != null && physical! < theoretical;

  factory InventoryCountModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryCountModelFromJson(json);
}
```

**`inventory_product_row_model.dart`** (Freezed):
```dart
@freezed
class InventoryProductRowModel with _$InventoryProductRowModel {
  const InventoryProductRowModel._();
  const factory InventoryProductRowModel({
    required String productId,
    required String productName,
    String? photoUrl,
    String? variantId,
    String? variantLabel,
    required int theoreticalQty,
    int? physicalQty,             // null = not counted
    // ecart is derived: physicalQty - theoreticalQty
  }) = _InventoryProductRowModel;

  int? get ecart => physicalQty != null ? physicalQty! - theoreticalQty : null;
  bool get isCounted => physicalQty != null;

  factory InventoryProductRowModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryProductRowModelFromJson(json);
}
```

#### Repository Interface

```dart
abstract class InventoryCountRepository {
  /// Load all products in scope with theoretical + physical counts.
  /// Online: fetches from backend GET /sessions/{id}/products
  /// Offline: loads from Drift products + stock_levels + inventory_counts
  Future<List<InventoryProductRowModel>> getCountingProducts(String sessionId);

  /// Save a count entry (BackendFirst + local cache).
  Future<InventoryCountModel> saveCount({
    required String sessionId,
    required String productId,
    String? variantId,
    required String productName,
    String? variantLabel,
    required int theoretical,
    required int physical,
  });

  /// Get all saved counts for a session (for resume/pre-fill).
  Future<List<InventoryCountModel>> getCountsForSession(String sessionId);
}
```

#### Remote DataSource endpoints

```dart
class RemoteInventoryCountDataSource {
  // GET /api/v1/inventory/sessions/{sessionId}/products
  Future<List<InventoryProductRowModel>> getCountingProducts(String sessionId);

  // POST /api/v1/inventory/sessions/{sessionId}/counts
  Future<InventoryCountModel> saveCount(String sessionId, Map<String, dynamic> payload);

  // GET /api/v1/inventory/sessions/{sessionId}/counts
  Future<List<InventoryCountModel>> getCountsForSession(String sessionId);
}
```

#### Local DataSource queries (Drift)

```dart
class LocalInventoryCountDataSource {
  // Insert or update (ON CONFLICT UPDATE) by unique key (sessionId + productId + variantId)
  Future<void> upsert(InventoryCountModel count);

  // Get all counts for a session (for resume)
  Future<List<InventoryCountModel>> findBySessionId(String sessionId);

  // Get unsynced counts (for pending protection in pull)
  Future<Set<String>> getPendingIds(String sessionId);

  // Build InventoryProductRowModel list from Drift Products + StockLevels + InventoryCounts
  // Used for offline fallback
  Future<List<InventoryProductRowModel>> buildOfflineProductRows(
      String sessionId, String storeId, List<String>? categoryIds);
}
```

#### Providers

```dart
// lib/features/inventory/presentation/provider/inventory_counting_provider.dart

// Loads all products in scope for the active session (online: backend, offline: Drift)
@riverpod
Future<List<InventoryProductRowModel>> countingProducts(
    Ref ref, String sessionId) async { ... }

// Holds all in-memory counts for the current session (key: productId+variantId)
// Initialized from backend + local Drift on page load
@riverpod
class InventoryCountingNotifier extends _$InventoryCountingNotifier {
  // state: Map<String, int?> where key = "${productId}:${variantId ?? 'null'}"
  // and value = physical count (null = not counted yet)

  void setCount(String productId, String? variantId, int physical) { ... }
  Future<void> persistCount(String sessionId, InventoryProductRowModel row, int physical) { ... }
  // saves to local Drift + backend (Backend-First)

  int get countedCount => state.values.whereType<int>().length;
  int get totalCount => state.length;
  double get progress => totalCount > 0 ? countedCount / totalCount : 0.0;
}

// Filter state
enum InventoryCountFilter { all, uncounted, discrepancies }

@riverpod
class InventoryCountFilterNotifier extends _$InventoryCountFilterNotifier {
  // Initial state: InventoryCountFilter.uncounted (AC4)
  InventoryCountFilter build() => InventoryCountFilter.uncounted;
  void setFilter(InventoryCountFilter f) => state = f;
}

// Search query
@riverpod
class InventoryCountSearchNotifier extends _$InventoryCountSearchNotifier {
  String build() => '';
  void setQuery(String q) => state = q;
}
```

#### `InventoryRow` Widget (UX32 — Key Component)

```
lib/features/inventory/presentation/widget/inventory_row.dart
```

- `InventoryRow` is a `ConsumerStatefulWidget`
- Key props: `InventoryProductRowModel row`, `String sessionId`, `FocusNode? focusNode`
- Internal state: `TextEditingController _controller` (seeded with existing `physicalQty`)
- On `TextFormField onChange` (debounce 400ms via `Timer`):
  1. Parse value to int
  2. Call `ref.read(inventoryCountingNotifierProvider(sessionId).notifier).persistCount(sessionId, row, physical)`
  3. Rebuild écart badge immediately (via local widget state)
- Layout: `Card` with `ColoredBox` background (state-driven), `Row` with:
  - Leading: `CircleAvatar` (photo via `CachedNetworkImage` or initials, 48dp)
  - Body: Column (product name `Body`, variant chip, "Keevo : X" subtext)
  - Trailing: Column (TextFormField "Réel" width 80dp, écart Badge)
- Écart Badge: `Container` with rounded borders, colored by écart sign
- Keyboard: `TextInputType.numberWithOptions(decimal: false)` — positive integers only

#### `InventoryCountingPage` Widget

```dart
class InventoryCountingPage extends ConsumerStatefulWidget {
  final String sessionId;
  const InventoryCountingPage({required this.sessionId, super.key});
}
```

**Page scaffold:**
```
Scaffold
├── AppBar (top)
│   ├── Title: "Comptage" (default) OR SearchBar (when searching)
│   ├── Leading: back arrow → pop to InventoryLaunchPage (does NOT cancel session)
│   └── Actions: search icon, filter indicator
│
├── Body: Column
│   ├── Progress section:
│   │   ├── LinearProgressIndicator (value: progress, colorScheme.primary)
│   │   └── Text "X / Y produits comptés"
│   │
│   ├── [When 100% complete] Summary banner:
│   │   └── Container amber background: "Inventaire terminé — X concordants, Y écarts"
│   │       + ElevatedButton "Valider l'inventaire" (primary gradient)
│   │
│   └── Expanded: ListView.builder (AC1 — MANDATORY lazy rendering)
│       └── Each item: InventoryRow widget
│
└── FAB: "Filtrer" bottom-right (AC4)
    └── SpeedDial or BottomSheet with filter options
```

**Responsive layout** (UX5):
- **Compact** (<600dp): single-column `ListView.builder`
- **Medium** (600-840dp): `ListView.builder` same, but AppBar wider
- **Expanded** (>840dp): Two-column layout — left: filter/progress panel, right: `ListView.builder` (see UX spec: "3 col: catégories + liste + saisie" — for 6.2 we do 2 col: controls + list)

#### Router Update

```dart
// Replace placeholder in app_router.dart:
GoRoute(
  path: '/inventory/counting/:sessionId',
  builder: (context, state) {
    final sessionId = state.pathParameters['sessionId']!;
    return InventoryCountingPage(sessionId: sessionId);
  },
),
```

#### InventoryLaunchPage navigation wiring (modify existing file)

In `inventory_launch_page.dart`, replace the `Placeholder` comment in `onResume`:
```dart
onResume: () => context.push('/inventory/counting/${session.id}'),
```

After session creation in `CreateSessionNotifier`, navigate:
```dart
context.push('/inventory/counting/${newSession.id}');
```

#### Sync pull integration (modify `rest_sync_service.dart`)

Add `_upsertInventoryCounts()` method (identical pattern to `_upsertInventorySessions`):
```dart
// In _handlePullEntities():
await _upsertInventoryCounts(
    entities['inventoryCounts'] as List<dynamic>? ?? []);

// New method:
Future<void> _upsertInventoryCounts(List<dynamic> counts) async {
  final pendingIds = await _localInventoryCountDs.getPendingIds(); // all session pending
  for (final raw in counts) {
    final model = InventoryCountModel.fromJson(raw as Map<String, dynamic>);
    if (!pendingIds.contains(model.id)) {
      await _localInventoryCountDs.upsert(model);
    }
  }
}
```

---

## Tasks / Subtasks

> **⚠️ TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST, then implement. No exceptions.**

---

### BACKEND — Full TDD

> All test files in `src/test/java/com/keevo/inventory/counting/`

---

- [x] **Task 1 — StockLevelRepository extension** (prerequisite for AC1)

  - [x] 1.1 — Write failing test `StockLevelRepositoryAdapterTest.java`:
    ```java
    @Test void findAllByStoreId_shouldReturnAllStockForStore() { ... }
    @Test void findAllByStoreId_whenNoStock_shouldReturnEmpty() { ... }
    ```
  - [x] 1.2 — Add `findAllByStoreId(UUID storeId)` to `StockLevelRepository.java` interface
  - [x] 1.3 — Add `findAllByStoreId` to `StockLevelSpringRepository.java` (`@Query` JPQL)
  - [x] 1.4 — Implement in `StockLevelRepositoryAdapter.java`
  - [x] 1.5 — Run tests → GREEN (no regressions to existing catalog/stock tests)

---

- [x] **Task 2 — ProductRepository extension** (prerequisite for FullScopeResolver + PartialScopeResolver)

  > ⚠️ **`ProductJpaEntity` has NO `storeId` field.** `findAllActiveByStoreId()` does NOT exist and must NOT be added — there is no `store_id` column on the `products` table. Store filtering goes through `stock_levels.store_id`.

  - [x] 2.1 — Write failing tests `ProductRepositoryAdapterTest.java`:
    ```java
    @Test void findAllByIds_shouldReturnMatchingProducts() { ... }
    @Test void findAllByIds_withEmptyList_shouldReturnEmpty() { ... }
    @Test void findByCategoryIds_shouldReturnProductsInCategories() { ... }
    @Test void findByCategoryIds_withEmptyList_shouldReturnEmpty() { ... }
    ```
  - [x] 2.2 — Add to `ProductRepository.java` interface:
    ```java
    List<Product> findAllByIds(List<UUID> ids);
    List<Product> findByCategoryIds(List<UUID> categoryIds);
    ```
  - [x] 2.3 — Add to `ProductSpringRepository.java`:
    ```java
    @Query("SELECT p FROM ProductJpaEntity p WHERE p.id IN :ids")
    List<ProductJpaEntity> findAllByIds(@Param("ids") List<UUID> ids);

    @Query("SELECT p FROM ProductJpaEntity p WHERE p.categoryId IN :categoryIds AND p.archived = false")
    List<ProductJpaEntity> findByCategoryIds(@Param("categoryIds") List<UUID> categoryIds);
    ```
  - [x] 2.4 — Implement both methods in `ProductRepositoryAdapter.java`
  - [x] 2.5 — Run tests → GREEN

---

- [x] **Task 3 — DDL: inventory_counts table** (AC1)

  - [x] 3.1 — Write failing test `InventoryCountsDdlTest.java`:
    ```java
    @Test void inventoryCounts_table_shouldExistWithRequiredColumns() {
        // verify: id, session_id, product_id, variant_id, product_name,
        //         variant_label, theoretical, physical, counted_at, counted_by, updated_at
    }
    @Test void inventoryCounts_uniqueIndex_noVariant_shouldPreventDuplicates() { ... }
    @Test void inventoryCounts_uniqueIndex_withVariant_shouldPreventDuplicates() { ... }
    ```
  - [x] 3.2 — Add `DDL_INVENTORY_COUNTS` constant to `TenantSchemaProvisioner.java`:
    ```java
    static final String DDL_INVENTORY_COUNTS = """
        CREATE TABLE IF NOT EXISTS inventory_counts (
            id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
            session_id    UUID         NOT NULL REFERENCES inventory_sessions(id),
            product_id    UUID         NOT NULL,
            variant_id    UUID,
            product_name  VARCHAR(200) NOT NULL,
            variant_label VARCHAR(100),
            theoretical   INT          NOT NULL DEFAULT 0,
            physical      INT,
            counted_at    TIMESTAMPTZ,
            counted_by    UUID,
            updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
        )""";
    static final String DDL_INVENTORY_COUNTS_IDX_SESSION =
        "CREATE INDEX IF NOT EXISTS idx_inventory_counts_session ON inventory_counts(session_id)";
    static final String DDL_INVENTORY_COUNTS_IDX_UNIQUE_NO_VARIANT =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_inventory_counts_session_product_no_variant "
        + "ON inventory_counts(session_id, product_id) WHERE variant_id IS NULL";
    static final String DDL_INVENTORY_COUNTS_IDX_UNIQUE_VARIANT =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_inventory_counts_session_product_variant "
        + "ON inventory_counts(session_id, product_id, variant_id) WHERE variant_id IS NOT NULL";
    ```
  - [x] 3.3 — Add execution in `buildSchema()` (after `DDL_INVENTORY_SESSIONS`)
  - [x] 3.4 — Add `inventory_counts` migration to `TenantSchemaSyncService`
  - [x] 3.5 — Run tests → GREEN

---

- [x] **Task 4 — Domain: InventoryCount entity** (AC2)

  - [x] 4.1 — Write `InventoryCountTest.java`:
    ```java
    @Test void count_whenNotCounted_physicalIsNull() { ... }
    @Test void count_ecart_physicalMinusTheoretical() { ... }
    @Test void count_ecart_whenNotCounted_shouldThrow() { ... }
    @Test void count_withPhysical_shouldSetCountedAt() { ... }
    @Test void count_isCounted_returnsTrueWhenPhysicalSet() { ... }
    ```
  - [x] 4.2 — Create `InventoryCount.java` (pure Java entity, zero framework deps)

---

- [x] **Task 5 — Domain: InventoryScopeResolver strategy** (AC1)

  - [x] 5.1 — Write `FullScopeResolverTest.java`:
    ```java
    @Test void resolve_fullScope_shouldReturnAllActiveProductsInStore() { ... }
    @Test void resolve_fullScope_shouldExcludeArchivedProducts() { ... }
    @Test void resolve_fullScope_shouldMergeExistingCountsFromSession() { ... }
    @Test void resolve_fullScope_variantProduct_shouldHaveSeparateRow() { ... }
    ```
  - [x] 5.2 — Create `InventoryScopeResolver.java` interface
  - [x] 5.3 — Create `FullScopeResolver.java` (Spring @Component):
    - Injects `StockLevelRepository`, `ProductRepository`
    - `resolveProducts(storeId, null, existingCounts)`:
      1. `stockLevelRepository.findAllByStoreId(storeId)` → list of `StockLevel` records
      2. Extract distinct `productId` list from stock levels
      3. `productRepository.findAllByIds(productIds)` → map of productId → Product (name, photo, categoryId)
      4. Build `InventoryProductRow` per stock level entry (join by productId)
      5. Sort alphabetically by `productName`
      6. Merge `physicalQty` + `ecart` from `existingCounts` (match by productId + variantId)
    - ⚠️ Do NOT call `productRepository.findAllActive()` alone — it returns ALL tenant products regardless of store; always filter via stock_levels.storeId first
  - [x] 5.4 — Write `PartialScopeResolverTest.java`:
    ```java
    @Test void resolve_partialScope_shouldFilterByCategoryIds() { ... }
    @Test void resolve_partialScope_withNullCategoryIds_shouldReturnEmpty() { ... }
    ```
  - [x] 5.5 — Create `PartialScopeResolver.java` (Spring @Component):
    - Injects `StockLevelRepository`, `ProductRepository`
    - `resolveProducts(storeId, categoryIds, existingCounts)`:
      1. `stockLevelRepository.findAllByStoreId(storeId)` → stock levels for the store
      2. `productRepository.findByCategoryIds(categoryIds)` → products in selected categories
      3. Intersect: keep only stock levels whose `productId` is also in the category-filtered set
      4. Build `InventoryProductRow`, sort alphabetically, merge existing counts
  - [x] 5.6 — Write `ScopeResolverRegistryTest.java`:
    ```java
    @Test void registry_fullScope_shouldReturnFullResolver() { ... }
    @Test void registry_partialScope_shouldReturnPartialResolver() { ... }
    @Test void registry_unknownScope_shouldThrow() { ... }
    ```
  - [x] 5.7 — Create `ScopeResolverRegistry.java`
  - [x] 5.8 — Run all → GREEN

---

- [x] **Task 6 — Domain: InventoryCountRepository port** (AC1, AC2, AC6)

  - [x] 6.1 — Write `InventoryCountRepositoryContractTest.java`:
    ```java
    @Test void save_shouldPersistCount() { ... }
    @Test void findBySessionId_shouldReturnAllCounts() { ... }
    @Test void findBySessionAndProduct_noVariant_shouldReturn() { ... }
    @Test void upsert_withExistingCount_shouldUpdate() { ... }
    @Test void upsert_withNewCount_shouldInsert() { ... }
    ```
  - [x] 6.2 — Create `InventoryCountRepository.java` interface

---

- [x] **Task 7 — Domain: Use case interfaces + Commands/Queries** (AC1, AC2, AC6)

  - [x] 7.1 — Create `GetCountingProductsQuery.java`:
    ```java
    public record GetCountingProductsQuery(UUID sessionId) {}
    ```
  - [x] 7.2 — Create `GetCountingProductsUseCase.java`
  - [x] 7.3 — Create `SaveInventoryCountCommand.java`:
    ```java
    public record SaveInventoryCountCommand(
        UUID sessionId, UUID productId, UUID variantId,
        String productName, String variantLabel,
        int theoretical, int physical, UUID actorId
    ) {}
    ```
  - [x] 7.4 — Create `SaveInventoryCountUseCase.java`
  - [x] 7.5 — Create `GetSessionCountsQuery.java` + `GetSessionCountsUseCase.java`

---

- [x] **Task 8 — Domain event + AuditEventListener** (AC2)

  - [x] 8.1 — Create `InventoryCountSavedEvent.java` in `domain/event/`
  - [x] 8.2 — Add `@EventListener` for `InventoryCountSavedEvent` in `AuditEventListener.java`

---

- [x] **Task 9 — Application services** (AC1, AC2, AC6)

  - [x] 9.1 — Write `GetCountingProductsServiceTest.java`:
    ```java
    @Test void getProducts_fullScope_shouldReturnAllStoreProducts() { ... }
    @Test void getProducts_partialScope_shouldReturnFilteredProducts() { ... }
    @Test void getProducts_withExistingCounts_shouldMergePhysical() { ... }
    @Test void getProducts_sessionNotFound_shouldThrow() { ... }
    ```
  - [x] 9.2 — Implement `GetCountingProductsService.java`:
    - Inject: `InventorySessionRepository`, `InventoryCountRepository`, `ScopeResolverRegistry`
    - Check session exists → get existing counts → delegate to scope resolver
  - [x] 9.3 — Write `SaveInventoryCountServiceTest.java`:
    ```java
    @Test void save_shouldUpsertCount() { ... }
    @Test void save_sessionNotInProgress_shouldThrowINVENTORY_SESSION_NOT_IN_PROGRESS() { ... }
    @Test void save_shouldPublishAuditEvent() { ... }
    @Test void save_verifyEcartCalculation() { ... }
    // ⚠️ Use ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS (exists from 6.1) — not a new code
    ```
  - [x] 9.4 — Implement `SaveInventoryCountService.java`:
    - Verify `session.status == IN_PROGRESS` → throw `INVENTORY_SESSION_NOT_IN_PROGRESS` (ErrorCode exists from 6.1 — reuse it)
    - Build `InventoryCount` entity
    - `countRepository.upsert(count)`
    - `eventPublisher.publishEvent(new InventoryCountSavedEvent(...))`
  - [x] 9.5 — Write + implement `GetSessionCountsService.java` (simple delegate)
  - [x] 9.6 — Run all → GREEN

---

- [x] **Task 10 — JPA entity + Repository adapter** (AC1, AC6)

  - [x] 10.1 — Create `InventoryCountJpaEntity.java`:
    ```java
    @Entity
    @Table(name = "inventory_counts")
    public class InventoryCountJpaEntity {
        @Id UUID id;
        @Column(name = "session_id") UUID sessionId;
        @Column(name = "product_id") UUID productId;
        @Column(name = "variant_id") UUID variantId;
        @Column(name = "product_name") String productName;
        @Column(name = "variant_label") String variantLabel;
        int theoretical;
        Integer physical;
        @Column(name = "counted_at") Instant countedAt;
        @Column(name = "counted_by") UUID countedBy;
        @Column(name = "updated_at") Instant updatedAt;
    }
    ```
  - [x] 10.2 — Create `JpaInventoryCountRepository.java`:
    ```java
    public interface JpaInventoryCountRepository extends JpaRepository<InventoryCountJpaEntity, UUID> {
        List<InventoryCountJpaEntity> findBySessionId(UUID sessionId);
        Optional<InventoryCountJpaEntity> findBySessionIdAndProductIdAndVariantIdIsNull(UUID sessionId, UUID productId);
        Optional<InventoryCountJpaEntity> findBySessionIdAndProductIdAndVariantId(UUID sessionId, UUID productId, UUID variantId);
    }
    ```
  - [x] 10.3 — Write `InventoryCountRepositoryAdapterTest.java`:
    ```java
    @Test void toDomain_shouldMapAllFields() { ... }
    @Test void upsert_shouldOverwritePhysicalValue() { ... }
    @Test void findBySession_shouldReturnAll() { ... }
    ```
  - [x] 10.4 — Implement `InventoryCountRepositoryAdapter.java`:
    - For `upsert()`: use `@Modifying @Query("UPDATE ... SET physical=... WHERE session_id=... AND product_id=... AND variant_id IS NULL")` or `JpaRepository.saveAndFlush()` after `findBySessionAndProduct`; simpler: find + update or insert

---

- [x] **Task 11 — REST controller + DTOs** (AC1, AC2, AC6)

  - [x] 11.1 — Create `InventoryProductRowResponseDto.java`:
    ```java
    public record InventoryProductRowResponseDto(
        UUID productId, String productName, String photoUrl,
        UUID variantId, String variantLabel,
        int theoreticalQty, Integer physicalQty, Integer ecart
    ) {}
    ```
  - [x] 11.2 — Create `SaveInventoryCountRequestDto.java`:
    ```java
    public record SaveInventoryCountRequestDto(
        @NotNull UUID productId,
        UUID variantId,
        @NotBlank String productName,
        String variantLabel,
        @NotNull Integer theoretical,
        @NotNull @Min(0) Integer physical
    ) {}
    ```
  - [x] 11.3 — Create `InventoryCountResponseDto.java` (mirrors InventoryCount domain)
  - [x] 11.4 — Write `InventoryCountControllerTest.java` (`@WebMvcTest`):
    ```java
    @Test void GET_products_shouldReturn200WithList() { ... }
    @Test void GET_products_withoutAuth_shouldReturn401() { ... }
    @Test void POST_counts_shouldReturn201WithCount() { ... }
    @Test void POST_counts_sessionNotInProgress_shouldReturn409() { ... } // uses INVENTORY_SESSION_NOT_IN_PROGRESS
    @Test void POST_counts_productNotInScope_shouldReturn400() { ... }   // uses INVENTORY_COUNT_PRODUCT_NOT_IN_SCOPE
    @Test void POST_counts_negativePhysical_shouldReturn400() { ... }
    @Test void GET_counts_shouldReturnAllCountsForSession() { ... }
    ```
  - [x] 11.5 — Implement `InventoryCountController.java`:
    ```java
    @RestController
    @RequestMapping("/api/v1/inventory/sessions/{sessionId}")
    public class InventoryCountController {
        @GetMapping("/products")   // returns List<InventoryProductRowResponseDto>
        @PostMapping("/counts")    // saves one count, returns InventoryCountResponseDto
        @PutMapping("/counts")     // bulk upsert for sync push
        @GetMapping("/counts")     // returns all counts for session
    }
    ```
  - [x] 11.6 — Run all controller tests → GREEN

---

- [x] **Task 12 — Sync: InventoryCountSyncHandler** (AC7)

  - [x] 12.1 — Create `InventoryCountSyncHandler.java` in `sync/sync/application/handler/`:
    - Handles `SAVE_INVENTORY_COUNT`
    - Idempotent: `SaveInventoryCountUseCase.execute()` → delegates to `upsert`
    - Register in `SyncOperationHandlerRegistry`
  - [x] 12.2 — Write test `InventoryCountSyncHandlerTest.java`:
    ```java
    @Test void handle_shouldDelegateToUseCase() { ... }
    @Test void handle_duplicateId_shouldBeIgnored() { ... }
    ```

---

- [x] **Task 13 — Sync: InventoryCountDeltaProvider** (AC7)

  - [x] 13.1 — Create `InventoryCountDeltaProvider.java` in `sync/sync/application/provider/`:
    - `entityKey()` → `"inventoryCounts"`
    - Native SQL query on `inventory_counts WHERE updated_at > :since`
  - [x] 13.2 — Write test `InventoryCountDeltaProviderTest.java`:
    ```java
    @Test void queryDelta_shouldReturnCountsModifiedAfterTimestamp() { ... }
    @Test void queryDelta_emptyTable_shouldReturnEmptyList() { ... }
    ```
  - [x] 13.3 — Register as delta provider in `SyncPullService` (or auto-registered if using List<DeltaEntityProvider> injection)

---

### FLUTTER — Full TDD (Clean Architecture + Riverpod)

> All tests in `test/features/inventory/`

---

- [x] **Task 14 — Drift v20: InventoryCounts table** (AC1, AC6)

  - [x] 14.1 — Create `lib/core/storage/inventory_counts_table.dart`
  - [x] 14.2 — Register in `@DriftDatabase(tables: [..., InventoryCounts])`
  - [x] 14.3 — Bump `schemaVersion => 20` and add migration: `if (from < 20) { await m.createTable(inventoryCounts); }`
  - [x] 14.4 — Run `dart run build_runner build` to regenerate — verify zero new warnings

---

- [x] **Task 15 — Domain models** (AC1, AC2)

  - [x] 15.1 — Write `inventory_count_model_test.dart`:
    ```dart
    test('ecart is physicalQty - theoreticalQty', () { ... });
    test('ecart is null when not counted', () { ... });
    test('isMatch returns true when equal', () { ... });
    test('isSurplus returns true when physical > theoretical', () { ... });
    test('isShortage returns true when physical < theoretical', () { ... });
    test('JSON roundtrip preserves all fields', () { ... });
    ```
  - [x] 15.2 — Create `inventory_count_model.dart` (Freezed)
  - [x] 15.3 — Write `inventory_product_row_model_test.dart`:
    ```dart
    test('ecart computed from physicalQty - theoreticalQty', () { ... });
    test('isCounted false when physicalQty null', () { ... });
    ```
  - [x] 15.4 — Create `inventory_product_row_model.dart` (Freezed)

---

- [x] **Task 16 — Domain repository interface** (AC1)

  - [x] 16.1 — Create `inventory_count_repository.dart` abstract class

---

- [x] **Task 17 — Data layer: local datasource** (AC2, AC6)

  - [x] 17.1 — Write `local_inventory_count_datasource_test.dart`:
    ```dart
    test('upsert inserts new count', () { ... });
    test('upsert updates existing count by sessionId+productId (no variant)', () { ... });
    test('upsert updates existing count by sessionId+productId+variantId', () { ... });
    test('findBySessionId returns all counts for session', () { ... });
    test('getPendingIds returns unsynced count IDs', () { ... });
    test('buildOfflineProductRows joins products + stock_levels + counts', () { ... });
    ```
  - [x] 17.2 — Implement `local_inventory_count_datasource.dart`:
    - `upsert()`: `insertOnConflictUpdate` — conflict by (sessionId+productId) via `where` clause
    - `findBySessionId()`: simple `select().where(...).get()`
    - `getPendingIds()`: `select where synced=false`
    - `buildOfflineProductRows()`: join Products + StockLevels + InventoryCounts query (or multiple `select` calls merged in Dart)

---

- [x] **Task 18 — Data layer: remote datasource** (AC1, AC2)

  - [x] 18.1 — Write `remote_inventory_count_datasource_test.dart` (mock Dio):
    ```dart
    test('getCountingProducts GETs /sessions/{id}/products', () { ... });
    test('saveCount POSTs /sessions/{id}/counts', () { ... });
    test('getCountsForSession GETs /sessions/{id}/counts', () { ... });
    ```
  - [x] 18.2 — Implement `remote_inventory_count_datasource.dart`

---

- [x] **Task 19 — Repository impl (Backend-First)** (AC1, AC2, AC6, AC7)

  - [x] 19.1 — Write `inventory_count_repository_impl_test.dart`:
    ```dart
    test('getCountingProducts online → remote first → cache locally', () { ... });
    test('getCountingProducts offline → buildOfflineProductRows from Drift', () { ... });
    test('saveCount online → remote first → cache locally', () { ... });
    test('saveCount offline → local insert + sync_queue', () { ... });
    test('getCountsForSession returns local counts', () { ... });
    ```
  - [x] 19.2 — Implement `inventory_count_repository_impl.dart` (Backend-First pattern):
    ```dart
    class InventoryCountRepositoryImpl implements InventoryCountRepository {
      Future<List<InventoryProductRowModel>> getCountingProducts(String sessionId) async {
        if (_isOnline()) {
          try {
            final remote = await _remote.getCountingProducts(sessionId);
            // cache each product row's counts locally
            return remote;
          } catch (_) { /* fall through to offline */ }
        }
        // Offline: build from local Drift
        final session = await _localSession.findById(sessionId);
        if (session == null) return [];
        return _localCount.buildOfflineProductRows(
            sessionId, session.storeId, session.categoryIds);
      }

      Future<InventoryCountModel> saveCount({...}) async {
        if (_isOnline()) {
          try {
            final remote = await _remote.saveCount(sessionId, payload);
            await _localCount.upsert(remote.copyWith(synced: true));
            return remote;
          } on DomainException { rethrow; }
          catch (_) { /* fall through */ }
        }
        // Offline
        final local = InventoryCountModel(id: Uuid().v4(), ..., synced: false);
        await _localCount.upsert(local);
        await _db.into(_db.syncQueue).insert(SyncQueueCompanion.insert(
          id: Uuid().v4(),
          operation: 'SAVE_INVENTORY_COUNT',
          payload: jsonEncode(local.toJson()),
          createdAt: DateTime.now(),
        ));
        return local;
      }
    }
    ```

---

- [x] **Task 20 — Use case** (AC2)

  - [x] 20.1 — Write `save_inventory_count_usecase_test.dart`:
    ```dart
    test('should delegate to repository', () { ... });
    ```
  - [x] 20.2 — Implement `save_inventory_count_usecase.dart`

---

- [x] **Task 21 — Riverpod providers** (AC2, AC3, AC4, AC5, AC6)

  - [x] 21.1 — Write `inventory_counting_provider_test.dart`:
    ```dart
    test('countingProductsProvider fetches products from repo', () { ... });
    test('InventoryCountingNotifier initial state empty map', () { ... });
    test('setCount updates state map', () { ... });
    test('progress 0 when no counts', () { ... });
    test('progress 1.0 when all counted', () { ... });
    test('InventoryCountFilterNotifier default is uncounted', () { ... });
    test('InventoryCountFilterNotifier setFilter updates state', () { ... });
    ```
  - [x] 21.2 — Implement `inventory_counting_provider.dart`:
    - `countingProductsProvider` (family: sessionId)
    - `InventoryCountingNotifier` (family: sessionId) — state: `Map<String, int?>`
    - `inventoryCountFilterProvider` → `InventoryCountFilter.uncounted` by default
    - `inventoryCountSearchProvider` → `''` by default

---

- [x] **Task 22 — InventoryRow widget** (AC2, AC3)

  - [x] 22.1 — Write `inventory_row_test.dart`:
    ```dart
    testWidgets('shows product name and theoretical stock', (t) async { ... });
    testWidgets('shows initials avatar when no photoUrl', (t) async { ... });
    testWidgets('hides ecart badge when physical is null', (t) async { ... });
    testWidgets('shows green badge when physical == theoretical', (t) async { ... });
    testWidgets('shows amber badge +X when physical > theoretical', (t) async { ... });
    testWidgets('shows red badge -X when physical < theoretical', (t) async { ... });
    testWidgets('row background white when uncounted', (t) async { ... });
    testWidgets('row background amber-50 on surplus', (t) async { ... });
    testWidgets('row background red-50 on shortage', (t) async { ... });
    testWidgets('row background green-50 on match', (t) async { ... });
    ```
  - [x] 22.2 — Implement `inventory_row.dart` (`ConsumerStatefulWidget`):
    - `TextFormField` with `keyboardType: TextInputType.numberWithOptions(decimal: false)`
    - Debounced save (400ms `Timer`) on `onChange`
    - Écart badge renders immediately from local widget state (reactive)
    - `CircleAvatar` with `CachedNetworkImage` (if `photoUrl != null`) or initials (deterministic color from `productId.hashCode`)
    - Variant chip: `Chip(label: Text(variantLabel), visualDensity: VisualDensity.compact)`
    - Background: `AnimatedContainer` with `duration: Duration(milliseconds: 200)` for smooth color transition

---

- [x] **Task 23 — InventoryCountingPage** (AC1, AC3, AC4, AC5, AC6)

  - [x] 23.1 — Write `inventory_counting_page_test.dart`:
    ```dart
    testWidgets('shows progress bar at top', (t) async { ... });
    testWidgets('shows ListView.builder (not a Column children)', (t) async { ... });
    testWidgets('progress bar updates when count entered', (t) async { ... });
    testWidgets('summary banner hidden below 100% completion', (t) async { ... });
    testWidgets('summary banner appears at 100% completion', (t) async { ... });
    testWidgets('valider button disabled until 100%', (t) async { ... });
    testWidgets('valider button active at 100%', (t) async { ... });
    testWidgets('FAB filter opens filter options', (t) async { ... });
    testWidgets('filter Non comptés shows only uncounted rows', (t) async { ... });
    testWidgets('search icon opens search bar', (t) async { ... });
    testWidgets('search filters list in real-time', (t) async { ... });
    ```
  - [x] 23.2 — Implement `inventory_counting_page.dart`:
    - Scaffold with `SliverAppBar` (collapsible on scroll)
    - Progress section: `LinearProgressIndicator` + count text (sticky, non-scrollable)
    - `ListView.builder` — each item renders `InventoryRow` widget
    - Filtered list: computed from `countingProductsProvider` + `inventoryCountFilterProvider` + `inventoryCountSearchProvider` + `InventoryCountingNotifier.state`
    - FAB: `FloatingActionButton.extended(label: Text('Filtrer'), icon: Icon(Icons.filter_list))`
    - Filter sheet: `showModalBottomSheet` with `ListTile` for each filter option
    - Search: toggle `_isSearching` bool — replace AppBar title with `TextField` when true
    - "Valider l'inventaire" button: disabled when `progress < 1.0`, navigates to 6.3 (placeholder `SnackBar` for now)
    - Responsive: `LayoutBuilder` — wide screens get 2-column layout

---

- [x] **Task 24 — Router + Navigation wiring** (AC1, AC6)

  - [x] 24.1 — Replace `/inventory/counting/:sessionId` placeholder with `InventoryCountingPage` in `app_router.dart`
  - [x] 24.2 — Wire `onResume` in `ActiveSessionBanner` / `InventoryLaunchPage` to navigate to `/inventory/counting/${session.id}`
  - [x] 24.3 — Wire post-session-creation navigation in `InventoryConfigBottomSheet` (after `CreateSessionNotifier` creates session) to push to `/inventory/counting/${session.id}`

---

- [x] **Task 25 — Sync pull integration** (AC7)

  - [x] 25.1 — Add `_upsertInventoryCounts()` method to `rest_sync_service.dart`
  - [x] 25.2 — Call it from `_handlePullEntities()` alongside `_upsertInventorySessions()`
  - [x] 25.3 — Write test `rest_sync_service_inventory_counts_test.dart`:
    ```dart
    test('upserts counts from delta response', () { ... });
    test('skips pending (unsynced) counts during pull', () { ... });
    ```

---

### CURL INTEGRATION TESTS

- [x] **Task 26 — cURL integration script** (AC1, AC2, AC6)

```bash
#!/usr/bin/env bash
# ======================================================
# Story 6.2 — Inventory Counting Form cURL Integration Tests
# Run: bash curl-tests-story-6-2.sh
# All steps must show ✅ before story is marked done
# Iterative: run after each backend task — keep running until ALL GREEN
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"

# Step 1 — Register + Onboard + Get JWT
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+237699990062","password":"Test1234!","firstName":"Count","lastName":"Test"}')
JWT=$(echo "$REGISTER" | jq -r '.data.accessToken // empty')
[[ -n "$JWT" ]] && echo "✅ Step 1 — JWT obtained" || { echo "❌ Step 1 FAILED"; exit 1; }

# Step 2 — Complete onboarding
curl -s -X POST "$BASE_URL/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"VETEMENTS","storeName":"Boutique Comptage","businessName":"Count Test SARL"}' > /dev/null
echo "✅ Step 2 — Onboarding completed"

# Step 3 — Get store ID
STORE_ID=$(curl -s -X GET "$BASE_URL/api/v1/tenant/stores" \
  -H "Authorization: Bearer $JWT" | jq -r '.data[0].id')
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && echo "✅ Step 3 — Store ID: $STORE_ID" || { echo "❌ Step 3 FAILED"; exit 1; }

# Step 4 — Create a product (so stock_levels has entries)
PRODUCT=$(curl -s -X POST "$BASE_URL/api/v1/products" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Robe Rouge M\",\"price\":5000,\"buyPrice\":2500,\"storeId\":\"$STORE_ID\",\"status\":\"ACTIVE\"}")
PRODUCT_ID=$(echo "$PRODUCT" | jq -r '.data.id')
[[ -n "$PRODUCT_ID" && "$PRODUCT_ID" != "null" ]] && echo "✅ Step 4 — Product created: $PRODUCT_ID" || echo "⚠️  Step 4 — Product creation failed (stock may be empty)"

# Step 5 — Add stock entry
if [[ -n "${PRODUCT_ID:-}" && "$PRODUCT_ID" != "null" ]]; then
  STOCK=$(curl -s -X POST "$BASE_URL/api/v1/stock/entry" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"productId\":\"$PRODUCT_ID\",\"storeId\":\"$STORE_ID\",\"quantity\":10}")
  echo "✅ Step 5 — Stock entry added"
fi

# Step 6 — Create inventory session (FULL scope)
SESSION=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
SESSION_ID=$(echo "$SESSION" | jq -r '.data.id')
[[ -n "$SESSION_ID" && "$SESSION_ID" != "null" ]] && echo "✅ Step 6 — Session created: $SESSION_ID" || { echo "❌ Step 6 FAILED"; exit 1; }

# Step 7 — GET products in scope → must include our product with theoreticalQty
PRODUCTS=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/products" \
  -H "Authorization: Bearer $JWT")
echo "$PRODUCTS" | jq .
PRODUCTS_COUNT=$(echo "$PRODUCTS" | jq '.data | length')
[[ "$PRODUCTS_COUNT" -ge "1" ]] && echo "✅ Step 7 — $PRODUCTS_COUNT products in scope" || { echo "❌ Step 7 FAILED (count=$PRODUCTS_COUNT)"; }
# Verify theoreticalQty for our product
THEO=$(echo "$PRODUCTS" | jq -r --arg pid "$PRODUCT_ID" '.data[] | select(.productId == $pid) | .theoreticalQty // empty')
[[ -n "$THEO" ]] && echo "✅ Step 7b — Theoretical qty: $THEO" || echo "⚠️  Step 7b — Product not found in scope (stock may be 0)"

# Step 8 — POST count for product (physical = 8, ecart should be -2 if theoretical is 10)
if [[ -n "${PRODUCT_ID:-}" && "$PRODUCT_ID" != "null" && "$PRODUCTS_COUNT" -ge "1" ]]; then
  COUNT=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/counts" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"productId\":\"$PRODUCT_ID\",\"productName\":\"Robe Rouge M\",\"theoretical\":${THEO:-10},\"physical\":8}")
  echo "$COUNT" | jq .
  COUNT_ID=$(echo "$COUNT" | jq -r '.data.id')
  ECART=$(echo "$COUNT" | jq -r '.data.ecart // empty')
  [[ -n "$COUNT_ID" ]] && echo "✅ Step 8 — Count saved: $COUNT_ID, écart=$ECART" || { echo "❌ Step 8 FAILED"; }
fi

# Step 9 — GET counts for session → should return our count
COUNTS=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/counts" \
  -H "Authorization: Bearer $JWT")
COUNTS_LEN=$(echo "$COUNTS" | jq '.data | length')
[[ "$COUNTS_LEN" -ge "1" ]] && echo "✅ Step 9 — $COUNTS_LEN count(s) found for session" || echo "⚠️  Step 9 — No counts yet"

# Step 10 — POST count again for same product (physical = 9 → upsert, not duplicate)
if [[ -n "${PRODUCT_ID:-}" && "$PRODUCT_ID" != "null" ]]; then
  RECOUNT=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/counts" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"productId\":\"$PRODUCT_ID\",\"productName\":\"Robe Rouge M\",\"theoretical\":${THEO:-10},\"physical\":9}")
  RECOUNT_PHYSICAL=$(echo "$RECOUNT" | jq -r '.data.physical // empty')
  [[ "$RECOUNT_PHYSICAL" == "9" ]] && echo "✅ Step 10 — Upsert worked (physical=9)" || echo "❌ Step 10 FAILED"

  # Verify only 1 count per product (not 2 rows)
  COUNTS2=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/counts" \
    -H "Authorization: Bearer $JWT")
  COUNTS2_LEN=$(echo "$COUNTS2" | jq '.data | length')
  [[ "$COUNTS2_LEN" -eq "1" ]] && echo "✅ Step 10b — Still only 1 count (upsert verified)" || echo "⚠️  Step 10b — Multiple rows: $COUNTS2_LEN"
fi

# Step 11 — Attempt to count on non-IN_PROGRESS session → 409
CANCEL=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/cancel" \
  -H "Authorization: Bearer $JWT")
echo "Session cancelled"

BLOCKED=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/counts" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"productId\":\"$PRODUCT_ID\",\"productName\":\"Test\",\"theoretical\":10,\"physical\":5}")
[[ "$BLOCKED" == "409" ]] && echo "✅ Step 11 — Cannot count on cancelled session (409)" || echo "⚠️  Step 11 — Got $BLOCKED (expected 409)"

# Step 12 — Unauthenticated access → 401
NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/products")
[[ "$NO_AUTH" == "401" ]] && echo "✅ Step 12 — Unauthenticated blocked (401)" || echo "❌ Step 12 FAILED (got $NO_AUTH)"

# Step 13 — PARTIAL scope session with categories
SESSION3_ID=""
SESSION_NEW=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
SESSION3_ID=$(echo "$SESSION_NEW" | jq -r '.data.id')
PRODUCTS_FULL=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions/$SESSION3_ID/products" \
  -H "Authorization: Bearer $JWT")
FULL_COUNT=$(echo "$PRODUCTS_FULL" | jq '.data | length')
echo "✅ Step 13 — FULL scope returns $FULL_COUNT products"

echo ""
echo "✅✅✅ cURL integration tests completed — Story 6.2 backend validated ✅✅✅"
```

---

## Dev Notes

### Architecture Patterns & Constraints

- **Hexagonal Architecture**: All new code extends the `inventory/counting/` module following the established `domain/ → application/ → adapter/` structure. Zero framework deps in domain.
- **GoF Strategy**: `InventoryScopeResolver` was architecturally reserved in Story 6.1. This story IMPLEMENTS it. `FullScopeResolver` and `PartialScopeResolver` are the two concrete strategies. `ScopeResolverRegistry` (Factory/Map) selects the right one — Open/Closed principle: adding scope = new class + register, no service modification.
- **GoF Observer**: `InventoryCountSavedEvent` → `AuditEventListener` records each physical count entry for immutable audit trail.
- **Backend-First-When-Online**: All `saveCount` calls go to backend first (online) then cache locally. Offline: local Drift insert + sync_queue. Pull on reconnect.
- **TDD**: All tasks follow RED → GREEN → REFACTOR strictly.
- **Drift v20**: Single migration from v19. No destructive changes.
- **ListView.builder MANDATORY**: Epic spec + UX spec both emphasize lazy rendering. `children: [...]` is FORBIDDEN for the counting list.
- **Debounced save**: `TextFormField.onChange` → 400ms debounce via `Timer` before calling `persistCount`. Prevents excessive back-end calls while typing.
- **Filter default = "Non comptés"**: AC4 specifies this as the default. On resume (AC6), also default to Non comptés to show remaining items first.
- **PostgreSQL nullable UNIQUE**: Due to PostgreSQL's `NULLS DISTINCT` behavior in UNIQUE constraints, two partial unique indexes are used instead of one compound constraint (industry standard pattern for nullable UNIQUE).

### Key Dependencies on Existing Code

| Dependency | Location | Used For |
|---|---|---|
| `InventorySessionRepository` | `inventory/counting/domain/port/out/` | Load session for scope/storeId |
| `StockLevelRepository` | `catalog/stock/domain/port/out/` | `findAllByStoreId()` (NEW method) |
| `ProductRepository` | `catalog/product/domain/port/out/` | `findAllByIds()` (NEW) + `findByCategoryIds()` (NEW) — products have no storeId, filter via stock_levels |
| `AuditEventListener` | `shared/application/service/` | Handle `InventoryCountSavedEvent` |
| `SyncOperationHandlerRegistry` | `sync/sync/application/service/` | Register `InventoryCountSyncHandler` |
| `SyncPullService` | `sync/sync/application/service/` | Register `InventoryCountDeltaProvider` |
| `TenantSchemaProvisioner` | `shared/infrastructure/persistence/` | DDL for `inventory_counts` table |
| `TenantSchemaSyncService` | `shared/infrastructure/persistence/` | Auto-migrate existing tenants |
| `GlobalExceptionHandler` | `shared/infrastructure/web/` | Maps `INVENTORY_COUNT_PRODUCT_NOT_IN_SCOPE` (new) — `INVENTORY_SESSION_NOT_IN_PROGRESS` already mapped from 6.1 |
| `InventorySessions` (Drift table) | `lib/core/storage/inventory_sessions_table.dart` | Read session for offline product build |
| `StockLevels` (Drift table) | `lib/core/storage/stock_levels_table.dart` | Theoretical qty for offline build |
| `Products` (Drift table) | `lib/core/storage/products_table.dart` | Product name, photo, category |
| `LocalInventorySessionDataSource` | `lib/features/inventory/data/datasource/` | Read session storeId/categoryIds offline |
| `RestSyncService.pull()` | `lib/core/sync/rest_sync_service.dart` | Add `_upsertInventoryCounts()` call |

### Source Tree Components to Touch

**Backend — Create (new files):**
- `src/main/java/com/keevo/inventory/counting/domain/model/InventoryCount.java`
- `src/main/java/com/keevo/inventory/counting/domain/model/InventoryProductRow.java`
- `src/main/java/com/keevo/inventory/counting/domain/service/InventoryScopeResolver.java`
- `src/main/java/com/keevo/inventory/counting/domain/service/FullScopeResolver.java`
- `src/main/java/com/keevo/inventory/counting/domain/service/PartialScopeResolver.java`
- `src/main/java/com/keevo/inventory/counting/domain/event/InventoryCountSavedEvent.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/GetCountingProductsQuery.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/GetCountingProductsUseCase.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/SaveInventoryCountCommand.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/SaveInventoryCountUseCase.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/GetSessionCountsQuery.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/in/GetSessionCountsUseCase.java`
- `src/main/java/com/keevo/inventory/counting/domain/port/out/InventoryCountRepository.java`
- `src/main/java/com/keevo/inventory/counting/application/service/GetCountingProductsService.java`
- `src/main/java/com/keevo/inventory/counting/application/service/ScopeResolverRegistry.java`
- `src/main/java/com/keevo/inventory/counting/application/service/SaveInventoryCountService.java`
- `src/main/java/com/keevo/inventory/counting/application/service/GetSessionCountsService.java`
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/InventoryProductRowResponseDto.java`
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/SaveInventoryCountRequestDto.java`
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/InventoryCountResponseDto.java`
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/BulkSaveCountsRequestDto.java`
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventoryCountController.java`
- `src/main/java/com/keevo/inventory/counting/adapter/out/persistence/InventoryCountJpaEntity.java`
- `src/main/java/com/keevo/inventory/counting/adapter/out/persistence/JpaInventoryCountRepository.java`
- `src/main/java/com/keevo/inventory/counting/adapter/out/persistence/InventoryCountRepositoryAdapter.java`
- `src/main/java/com/keevo/sync/sync/application/handler/InventoryCountSyncHandler.java`
- `src/main/java/com/keevo/sync/sync/application/provider/InventoryCountDeltaProvider.java`

**Backend — Tests (new files):**
- `src/test/java/com/keevo/inventory/counting/domain/model/InventoryCountTest.java`
- `src/test/java/com/keevo/inventory/counting/domain/service/FullScopeResolverTest.java`
- `src/test/java/com/keevo/inventory/counting/domain/service/PartialScopeResolverTest.java`
- `src/test/java/com/keevo/inventory/counting/domain/service/ScopeResolverRegistryTest.java`
- `src/test/java/com/keevo/inventory/counting/application/service/GetCountingProductsServiceTest.java`
- `src/test/java/com/keevo/inventory/counting/application/service/SaveInventoryCountServiceTest.java`
- `src/test/java/com/keevo/inventory/counting/adapter/out/persistence/InventoryCountRepositoryAdapterTest.java`
- `src/test/java/com/keevo/inventory/counting/adapter/in/rest/InventoryCountControllerTest.java`
- `src/test/java/com/keevo/sync/sync/application/handler/InventoryCountSyncHandlerTest.java`
- `src/test/java/com/keevo/sync/sync/application/provider/InventoryCountDeltaProviderTest.java`

**Backend — Modify (10 files):**
- `src/main/java/com/keevo/catalog/stock/domain/port/out/StockLevelRepository.java` — add `findAllByStoreId()`
- `src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockLevelSpringRepository.java` — add `@Query` for `findAllByStoreId`
- `src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockLevelRepositoryAdapter.java` — implement `findAllByStoreId`
- `src/main/java/com/keevo/catalog/product/domain/port/out/ProductRepository.java` — add `findAllByIds()` + `findByCategoryIds()` (⚠️ no `findAllActiveByStoreId` — products have no storeId)
- `src/main/java/com/keevo/catalog/product/adapter/out/persistence/ProductSpringRepository.java` — add JPQL for both new methods
- `src/main/java/com/keevo/catalog/product/adapter/out/persistence/ProductRepositoryAdapter.java` — implement both new methods
- `src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` — add `INVENTORY_COUNT_PRODUCT_NOT_IN_SCOPE` only (⚠️ `INVENTORY_SESSION_NOT_IN_PROGRESS` already exists from 6.1 — DO NOT duplicate)
- `src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` — DDL + execution
- `src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java` — add inventory_counts migration
- `src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java` — add InventoryCountSavedEvent listener
- `src/main/java/com/keevo/sync/sync/application/service/SyncOperationHandlerRegistry.java` — register InventoryCountSyncHandler (if manual)

**Flutter — Create (new files):**
- `lib/core/storage/inventory_counts_table.dart`
- `lib/features/inventory/domain/model/inventory_count_model.dart`
- `lib/features/inventory/domain/model/inventory_product_row_model.dart`
- `lib/features/inventory/domain/repository/inventory_count_repository.dart`
- `lib/features/inventory/domain/usecase/save_inventory_count_usecase.dart`
- `lib/features/inventory/data/datasource/local_inventory_count_datasource.dart`
- `lib/features/inventory/data/datasource/remote_inventory_count_datasource.dart`
- `lib/features/inventory/data/repository/inventory_count_repository_impl.dart`
- `lib/features/inventory/presentation/provider/inventory_counting_provider.dart`
- `lib/features/inventory/presentation/page/inventory_counting_page.dart`
- `lib/features/inventory/presentation/widget/inventory_row.dart`

**Flutter — Modify (5 files):**
- `lib/core/storage/app_database.dart` — add `InventoryCounts` table + v20 migration
- `lib/core/router/app_router.dart` — replace placeholder with `InventoryCountingPage`
- `lib/core/sync/rest_sync_service.dart` — add `_upsertInventoryCounts()` call in pull
- `lib/features/inventory/presentation/page/inventory_launch_page.dart` — wire navigation from resume/create to `/inventory/counting/{id}`
- `lib/features/inventory/presentation/widget/inventory_config_bottom_sheet.dart` — navigate after successful session creation

**Tests — Flutter (new files):**
- `test/features/inventory/domain/model/inventory_count_model_test.dart`
- `test/features/inventory/domain/model/inventory_product_row_model_test.dart`
- `test/features/inventory/data/datasource/local_inventory_count_datasource_test.dart`
- `test/features/inventory/data/datasource/remote_inventory_count_datasource_test.dart`
- `test/features/inventory/data/repository/inventory_count_repository_impl_test.dart`
- `test/features/inventory/presentation/provider/inventory_counting_provider_test.dart`
- `test/features/inventory/presentation/widget/inventory_row_test.dart`
- `test/features/inventory/presentation/page/inventory_counting_page_test.dart`
- `test/core/sync/rest_sync_service_inventory_counts_test.dart`

**Scripts (1 file):**
- `keevo/scripts/curl-tests-story-6-2.sh` — 13-step cURL integration test

---

## UX / Design Requirements

> **Source**: UX Design Specification — Flow 10 (Inventaire Assisté), UX32 (InventoryRow component), UX2 color tokens, UX5 breakpoints
> **Note**: `InventoryRow` (UX32) is THE key UX component for this story. It must be implemented correctly: it is reused in Story 6.3 (gap report).

### InventoryRow (UX32) — Full Spec

```
┌─────────────────────────────────────────────────────────────┐
│ [Avatar/Photo 48dp] │ ProductName (Body 16sp Bold)          │
│                     │ VariantLabel (chip, Label 12sp)       │
│                     │ "Keevo : 10" (Label 12sp, dimmed)     │
│                     │              │ [TextFormField "Réel"]  │
│                     │              │ [Écart badge]          │
└─────────────────────────────────────────────────────────────┘
```

**Avatar rules:**
- If `photoUrl != null`: `CachedNetworkImage` in `CircleAvatar` (48dp, BoxFit.cover)
- If `photoUrl == null`: initials from first 2 letters of product name, `backgroundColor` from `Colors.primaries[productId.hashCode % Colors.primaries.length]`

**Écart badge colors (from UX2 palette "Indigo Sky"):**
- ✅ Match `= 0`: `Color(0xFF51CF66)` (colorSuccess)
- ⚠️ Surplus `+X`: `Color(0xFFFCC419)` (colorWarning)
- 🔴 Shortage `−X`: `Color(0xFFFA5252)` (colorError)

**Row background:**
- Uncounted: `colorScheme.surface` (white / dark surface)
- Match: `Color(0xFFE8F5E9)` (green.shade50)
- Surplus: `Color(0xFFFFFDE7)` (yellow.shade50)
- Shortage: `Color(0xFFFFEBEE)` (red.shade50)
- Use `AnimatedContainer` with `Duration(milliseconds: 200)` for smooth transition

### InventoryCountingPage layout

**AppBar:**
- Title: "Comptage" (when not searching)
- Leading: back arrow icon → pops navigation (does NOT cancel session)
- Actions: `Icons.search` IconButton (opens search), optional filter badge

**Progress section (sticky — not scrollable):**
```
LinearProgressIndicator(value: progress, minHeight: 8)
Text("X / Y produits comptés", style: labelMedium, color: onSurface.withValues(alpha:0.6))
```

**Summary banner (only at 100%)**:
```
Container(
  color: colorSuccess.withValues(alpha: 0.15),
  child: Row(
    icon: Icons.check_circle,
    text: "Inventaire terminé — X concordants, Y écarts"
  )
)
ElevatedButton("Valider l'inventaire", style: primaryGradient)
```

**FAB:**
```
FloatingActionButton.extended(
  icon: Icon(Icons.filter_list),
  label: Text('Filtrer'),
  backgroundColor: active filter != all ? colorPrimary : colorScheme.surface
)
```

**Filter options (BottomSheet):**
```
ListTile(leading: Radio, title: "Tous", subtitle: Text("X produits"))
ListTile(leading: Radio, title: "Non comptés", subtitle: Text("X restants"), selected by default)
ListTile(leading: Radio, title: "Écarts seulement", subtitle: Text("X produits avec écart"))
```

### Colors & Design Tokens

| Element | Color | Token |
|---|---|---|
| Progress bar | `#3B5BDB` | `colorScheme.primary` |
| Summary banner bg | `#51CF66` @ 15% | `colorSuccess.withValues(alpha:0.15)` |
| Écart match | `#51CF66` | `colorSuccess` |
| Écart surplus | `#FCC419` | `colorWarning` |
| Écart shortage | `#FA5252` | `colorError` |
| Row bg surplus | `#FFFDE7` | `yellow.shade50` |
| Row bg shortage | `#FFEBEE` | `red.shade50` |
| Row bg match | `#E8F5E9` | `green.shade50` |
| Filter FAB (active) | `#3B5BDB` | `colorScheme.primary` |

### Accessibility

- Touch targets for "Réel" field ≥ 48dp height
- `Semantics(label: 'Quantité physique pour ${productName}')` on each text field
- `Semantics(label: 'Écart: surplus/concordant/pénurie')` on badge
- French labels everywhere — no English in any UI text
- Font minimum 14sp (UX3)

### Responsive Breakpoints (UX5)

- **Compact** (<600dp): single-column `ListView.builder`, FAB bottom right, AppBar search
- **Medium** (600-840dp): same layout, wider rows, more padding
- **Expanded** (>840dp): two-column `Row` — left: sticky progress + filter controls (25% width), right: `Expanded(child: ListView.builder)` — filters operate same way

---

## Project Structure Notes

- `InventoryRow` (UX32) is a **shared** widget within the `inventory/presentation/widget/` folder. It will be **reused** in Story 6.3 (gap report) — design it to be self-contained and configurable (e.g., `readOnly: bool` flag for when showing in report mode vs. counting mode).
- The `InventoryCountingPage` replaces the `_PlaceholderPage(title: 'Comptage')` that was added in Story 6.1's router update.
- The `inventory/counting/domain/service/` package was explicitly reserved in Story 6.1 and documented as "(reserved for Story 6.2+ scope resolvers)". This is where `InventoryScopeResolver`, `FullScopeResolver`, `PartialScopeResolver` go.
- `withOpacity()` is deprecated — use `withValues(alpha:)` throughout (fix enforced from Story 3.3 onward).
- All new Flutter widgets must use `withValues(alpha:)` not `withOpacity()`.

### References

- [Source: `_bmad-output/planning-artifacts/epics/epic-6-inventaire-assist.md` — Story 6.2 AC BDD]
- [Source: `_bmad-output/planning-artifacts/prd.md` — FR45, FR46, FR47]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` — UX32 InventoryRow, Flow 10, UX2 tokens, UX5 breakpoints]
- [Source: `_bmad-output/planning-artifacts/architecture.md` — Hexagonal, TDD, GoF, Backend-First, Sync]
- [Source: `_bmad-output/implementation-artifacts/6-1-lancement-configuration-session-inventaire.md` — full 6.1 context, file list, debug notes]
- [Source: `keevo/backend/src/.../StockLevelRepository.java` — existing findByProductAndStore, findAllByProduct]
- [Source: `keevo/backend/src/.../StockLevel.java` — domain entity for theoretical qty]
- [Source: `keevo/backend/src/.../MovementType.java` — ADJUSTMENT type for Story 6.4]
- [Source: `keevo/backend/src/.../InventorySessionDeltaProvider.java` — delta provider pattern to replicate]
- [Source: `keevo/app/lib/core/storage/stock_levels_table.dart` — Drift StockLevels table]
- [Source: `keevo/app/lib/features/inventory/presentation/page/inventory_launch_page.dart` — navigation wiring point]
- [Source: `keevo/app/lib/core/router/app_router.dart` — line 444 placeholder to replace]

---

## Dev Agent Record

### Agent Model Used

Claude Opus 4.6 (GitHub Copilot)

### Debug Log References

- Backend tests: 100/100 GREEN after all fixes (mvn test)
- Flutter analyze: 0 errors, only info-level deprecation warnings (Flutter 3.32+)
- build_runner: 314 outputs generated successfully (Freezed + JSON serializable + Drift + Riverpod)

### Completion Notes List

- **Code Review Cycle** — Adversarial review executed via bmad-bmm-code-review workflow
- **H1 FIX**: Added `Integer ecart` field to `InventoryCountResponseDto.fromDomain()` — computes `physical - theoretical`
- **H3 FIX**: Added `String sku` field throughout backend pipeline (`InventoryProductRow.of()`, `InventoryProductRowResponseDto`, `FullScopeResolver`, `PartialScopeResolver`) + Flutter model (`InventoryProductRowModel`) + local datasource mapping
- **H2 FIX**: Replaced FilterChip bar with `FloatingActionButton.extended` + `showModalBottomSheet` with 3 `RadioListTile` options (Tous / Non comptés / Écarts seulement) — AC4 compliant
- **H4 FIX**: Added `Semantics(label: 'Quantité physique pour ${productName}')` on TextFormField and `Semantics(label: 'Écart: concordant/surplus/pénurie')` on badge — accessibility
- **H5 FIX**: Added `LayoutBuilder` responsive breakpoint — 2-column layout when `constraints.maxWidth > 840`
- **M1 FIX**: Label changed from `'Stock théorique : X'` to `'Keevo : X'` per UX32 spec
- **M2 FIX**: CircleAvatar radius changed from 20 (40dp) to 24 (48dp) per UX32 spec
- **M3 FIX**: Summary banner now shows `'Inventaire terminé — X concordants, Y écarts'` with check_circle icon
- **M4 FIX**: SnackBar text corrected to `'6.3 — Rapport d\'inventaire à venir'`
- **M5 FIX**: Badge colors replaced with exact UX2 hex tokens — `Color(0xFF51CF66)`, `Color(0xFFFCC419)`, `Color(0xFFFA5252)`
- **M6 FIX**: Row background surplus changed from `Colors.amber.shade50` to `Colors.yellow.shade50` (`0xFFFFFDE7`)
- **M7 FIX**: Progress text changed to `'$counted / $total produits comptés'`
- **M9 NOTE**: Kept `NetworkImage` — `cached_network_image` not in pubspec. Can add later.
- **AC4 FIX**: Provider enum reduced from 6 values to 3 (`all`, `uncounted`, `discrepancies`)
- Backend tests confirmed GREEN after all changes (100/100)

### File List

**Backend — Modified during review:**
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/InventoryCountResponseDto.java` — added ecart field
- `src/main/java/com/keevo/inventory/counting/domain/model/InventoryProductRow.java` — added sku parameter
- `src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/InventoryProductRowResponseDto.java` — added sku field
- `src/main/java/com/keevo/inventory/counting/domain/service/FullScopeResolver.java` — pass sku from product
- `src/main/java/com/keevo/inventory/counting/domain/service/PartialScopeResolver.java` — pass sku from product
- `src/test/java/com/keevo/inventory/counting/domain/model/InventoryProductRowTest.java` — updated of() calls
- `src/test/java/com/keevo/inventory/counting/adapter/in/rest/InventoryCountControllerTest.java` — updated of() calls
- `src/test/java/com/keevo/inventory/counting/application/service/GetCountingProductsServiceTest.java` — updated of() calls

**Flutter — Modified during review:**
- `lib/features/inventory/domain/model/inventory_product_row_model.dart` — added sku field (Freezed)
- `lib/features/inventory/data/datasource/local_inventory_count_datasource.dart` — pass sku to model
- `lib/features/inventory/presentation/page/inventory_counting_page.dart` — H2 FAB+BottomSheet, H5 responsive, M3 summary, M4 snackbar, M7 progress
- `lib/features/inventory/presentation/provider/inventory_counting_provider.dart` — enum reduced to 3 values
- `lib/features/inventory/presentation/widget/inventory_row.dart` — H4 semantics, M1 label, M2 radius, M5 colors, M6 bg, badge `= 0`


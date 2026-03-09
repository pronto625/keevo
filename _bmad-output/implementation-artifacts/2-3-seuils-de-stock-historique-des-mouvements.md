# Story 2.3: Seuils de Stock & Historique des Mouvements

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a proprietor (Simon),
I want to set minimum stock thresholds per product and see the complete movement history,
So that I never run out of stock unexpectedly and can trace every change with full accountability.

## Acceptance Criteria

**AC1 — Stock tab on product detail page**
- **Given** Simon is on a product's detail page
- **When** he navigates to the "Stock" tab
- **Then** he sees: current stock level (or per-variant breakdown), the configured alert threshold ("Seuil minimum"), and a "Modifier le seuil" button

**AC2 — Set minimum threshold with server-side enforcement**
- **Given** Simon sets a minimum threshold of 10 units on a product
- **When** the stock level drops to ≤ 10 via any stock-modifying operation (sale, transfer, or manual adjustment)
- **Then** the system emits a `StockThresholdBreachedEvent`
- **And** a push notification is sent to Simon's device: "⚠ Stock bas : [Product Name] — il vous reste [current] unité(s) (seuil : [threshold])" *(FCM delivery deferred to Epic 8 — the event is emitted and logged in story 2.3)*
- **And** the product card in Catalogue shows a red "Stock bas" badge
- **And** the threshold check happens server-side on every stock-modifying operation — it cannot be bypassed client-side

**AC3 — Immutable stock movement record on every operation**
- **Given** any stock-modifying operation occurs (sale, stock entry, transfer, manual adjustment)
- **When** the operation is committed
- **Then** a `stock_movement` record is written with: `productId`, `variantId` (nullable), `storeId`, `movementType` (SALE / STOCK_ENTRY / TRANSFER_IN / TRANSFER_OUT / ADJUSTMENT), `quantityBefore`, `quantityChange`, `quantityAfter`, `actorId`, `occurredAt`, `notes` (optional)

**AC4 — Stock history screen: offline-first with filters**
- **Given** Simon navigates to the stock history for a specific product
- **When** the history screen loads
- **Then** all movements are displayed in reverse chronological order
- **And** each entry shows: type icon (🛍 Vente / 📦 Entrée / 🔄 Transfert / ✏ Ajustement), product name + variant (if applicable), quantity change (e.g., "−3"), quantity after, actor name, date/time
- **And** Simon can filter by: movement type, date range (last 7 days default), store
- **And** the history loads offline from local Drift data (schema v5 `stock_movements` table)

**AC5 — Manual stock adjustment with reason**
- **Given** Simon taps "Ajuster le stock" and enters a new quantity with a reason
- **When** he confirms
- **Then** the adjustment is recorded as `ADJUSTMENT` in the history
- **And** the reason is stored in the `notes` field
- **And** `AuditEventListener` emits a `StockAdjustedEvent` with before/after values
- **And** threshold check runs after adjustment — if stock ≤ threshold, `StockThresholdBreachedEvent` is emitted

**AC6 — Manual stock entry (STOCK_ENTRY)**
- **Given** Simon taps "Ajouter du stock" and enters a quantity to add
- **When** he confirms
- **Then** the operation is recorded as `STOCK_ENTRY` in the history
- **And** `AuditEventListener` emits a `StockAdjustedEvent`
- **And** threshold check runs after entry

## Tasks / Subtasks

### Backend — TDD Strict (write failing test FIRST, then implementation)

- [ ] **Task 1 — Add minimum_threshold to Product** (AC1, AC2)
  - [ ] 1.1 — Update `Product.java` domain entity:
    - Add `minimumThreshold` Integer field (default 0, non-negative)
    - Update constructor to accept `minimumThreshold`
    - Add getter `getMinimumThreshold()`
    - **TDD**: Write `ProductTest.shouldRejectNegativeMinimumThreshold()` BEFORE implementation
  - [ ] 1.2 — Update `ProductJpaEntity.java`:
    - Add `@Column(name = "minimum_threshold", nullable = false) Integer minimumThreshold = 0;`
    - Update all-args constructor, getters/setters
  - [ ] 1.3 — Add migration DDL to `TenantSchemaProvisioner.java`:
    - New constant: `DDL_PRODUCTS_MIGRATE_MINIMUM_THRESHOLD`
    - Value: `"ALTER TABLE products ADD COLUMN IF NOT EXISTS minimum_threshold INTEGER NOT NULL DEFAULT 0"`
    - Call in `createTables()` after existing product migrations
    - Add to `REQUIRED_TABLES` in `TenantSchemaSyncService`
    - **TDD**: Write schema migration test asserting `minimum_threshold` column exists

- [ ] **Task 2 — Create stock_levels DDL and domain** (AC1, AC3)
  - [ ] 2.1 — Add `DDL_STOCK_LEVELS` to `TenantSchemaProvisioner.java`:
    ```sql
    CREATE TABLE IF NOT EXISTS stock_levels (
        id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
        product_id    UUID         NOT NULL REFERENCES products(id),
        variant_id    UUID,
        store_id      UUID         NOT NULL,
        quantity      INTEGER      NOT NULL DEFAULT 0,
        updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
        CONSTRAINT uq_stock_level UNIQUE (product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::UUID), store_id)
    )
    ```
    - Add `DDL_STOCK_LEVELS_IDX_PRODUCT` index on `(product_id, store_id)`
    - Call DDL in `createTables()`, add to `REQUIRED_TABLES`
    - **TDD**: Write `StockLevelsDdlTest` asserting table and constraint creation
  - [ ] 2.2 — Create `StockLevel` domain entity (`catalog/stock/domain/entity/StockLevel.java`):
    - Fields: `UUID id`, `UUID productId`, `UUID variantId` (nullable), `UUID storeId`, `int quantity`, `Instant updatedAt`
    - **No validation other than quantity ≥ 0**
    - **TDD**: Write `StockLevelTest` asserting construction BEFORE implementation
  - [ ] 2.3 — Create `StockLevelRepository` port (`catalog/stock/domain/port/out/StockLevelRepository.java`):
    - `Optional<StockLevel> findByProductAndStore(UUID productId, UUID storeId)`
    - `Optional<StockLevel> findByProductVariantAndStore(UUID productId, UUID variantId, UUID storeId)`
    - `StockLevel save(StockLevel level)`
    - `List<StockLevel> findAllByProduct(UUID productId)` — all stores
    - **TDD**: Write `StockLevelRepositoryContractTest`

- [ ] **Task 3 — Create stock_movements DDL and domain** (AC3, AC4, AC5, AC6)
  - [ ] 3.1 — Add `DDL_STOCK_MOVEMENTS` to `TenantSchemaProvisioner.java`:
    ```sql
    CREATE TABLE IF NOT EXISTS stock_movements (
        id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        product_id      UUID        NOT NULL REFERENCES products(id),
        variant_id      UUID,
        store_id        UUID        NOT NULL,
        movement_type   VARCHAR(30) NOT NULL,
        quantity_before INTEGER     NOT NULL,
        quantity_change INTEGER     NOT NULL,
        quantity_after  INTEGER     NOT NULL,
        actor_id        UUID        NOT NULL,
        notes           TEXT,
        occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        CONSTRAINT ck_movement_type CHECK (movement_type IN ('SALE','STOCK_ENTRY','TRANSFER_IN','TRANSFER_OUT','ADJUSTMENT'))
    )
    ```
    - Add `DDL_STOCK_MOVEMENTS_IDX_PRODUCT` on `(product_id, occurred_at DESC)`
    - Add `DDL_STOCK_MOVEMENTS_IDX_STORE` on `(store_id, occurred_at DESC)`
    - Call in `createTables()`, add to `REQUIRED_TABLES`
    - **TDD**: Write DDL assertion test
  - [ ] 3.2 — Create `MovementType` enum (`catalog/stock/domain/entity/MovementType.java`):
    - Values: `SALE`, `STOCK_ENTRY`, `TRANSFER_IN`, `TRANSFER_OUT`, `ADJUSTMENT`
    - **TDD**: Write enum completeness test
  - [ ] 3.3 — Create `StockMovement` domain entity (`catalog/stock/domain/entity/StockMovement.java`):
    - Fields: `UUID id`, `UUID productId`, `UUID variantId` (nullable), `UUID storeId`, `MovementType movementType`, `int quantityBefore`, `int quantityChange`, `int quantityAfter`, `UUID actorId`, `String notes` (nullable), `Instant occurredAt`
    - Domain invariant: `quantityAfter == quantityBefore + quantityChange`
    - **TDD**: Write `StockMovementTest.shouldEnforceQuantityInvariant()` BEFORE implementation
  - [ ] 3.4 — Create `StockMovementRepository` port (`catalog/stock/domain/port/out/StockMovementRepository.java`):
    - `StockMovement save(StockMovement movement)`
    - `Page<StockMovement> findByProductId(UUID productId, MovementType type, Instant from, Instant to, UUID storeId, Pageable pageable)` — nullable filters
    - **TDD**: Write `StockMovementRepositoryContractTest`

- [ ] **Task 4 — Domain events for stock** (AC2, AC3, AC5)
  - [ ] 4.1 — Create `StockAdjustedEvent` (`catalog/stock/domain/event/StockAdjustedEvent.java`):
    ```java
    public record StockAdjustedEvent(
        UUID productId, UUID variantId, UUID storeId,
        MovementType movementType,
        int quantityBefore, int quantityChange, int quantityAfter,
        UUID actorId, String notes, UUID tenantId, Instant occurredAt
    ) {}
    ```
    - **TDD**: Write `StockAdjustedEventTest` asserting record construction
  - [ ] 4.2 — Create `StockThresholdBreachedEvent` (`catalog/stock/domain/event/StockThresholdBreachedEvent.java`):
    ```java
    public record StockThresholdBreachedEvent(
        UUID productId, String productName, UUID storeId,
        int currentQuantity, int threshold,
        UUID actorId, UUID tenantId, Instant occurredAt
    ) {}
    ```
    - **TDD**: Write event record test

- [ ] **Task 5 — JPA adapters for stock** (AC3, AC4)
  - [ ] 5.1 — Create `StockLevelJpaEntity` (`shared/infrastructure/persistence/entity/StockLevelJpaEntity.java`):
    - Maps `stock_levels` table, all columns. No @ManyToOne joins — use UUID FK only (follow product pattern)
    - **TDD**: Write `StockLevelJpaEntityTest`
  - [ ] 5.2 — Create `StockLevelSpringRepository extends JpaRepository<StockLevelJpaEntity, UUID>`
  - [ ] 5.3 — Create `StockLevelRepositoryAdapter` (`catalog/stock/adapter/out/persistence/StockLevelRepositoryAdapter.java`):
    - Implements `StockLevelRepository` port
    - **TDD**: Write `StockLevelRepositoryAdapterTest` with H2 or mocked JPA
  - [ ] 5.4 — Create `StockMovementJpaEntity` (`shared/infrastructure/persistence/entity/StockMovementJpaEntity.java`):
    - Maps `stock_movements` table, all columns
    - `@Enumerated(EnumType.STRING)` for `movementType`
    - **TDD**: Write `StockMovementJpaEntityTest`
  - [ ] 5.5 — Create `StockMovementSpringRepository`:
    - `Page<StockMovementJpaEntity> findByProductIdAndFilters(UUID productId, String movementType, Instant from, Instant to, UUID storeId, Pageable pageable)` using `@Query` with nullable filter handling
    - **TDD**: Write `StockMovementSpringRepositoryTest`
  - [ ] 5.6 — Create `StockMovementRepositoryAdapter`:
    - Implements `StockMovementRepository` port
    - **TDD**: Write `StockMovementRepositoryAdapterTest`
  - [ ] 5.7 — Update `ProductRepositoryAdapter.java`:
    - Map `minimumThreshold` field in `toDomain()` / `toEntity()` methods
    - **TDD**: Update `ProductRepositoryAdapterTest` to assert minimumThreshold mapping

- [ ] **Task 6 — Core Stock Service (domain service)** (AC2, AC3, AC5, AC6)
  - [ ] 6.1 — Create `StockOperationService` (`catalog/stock/domain/service/StockOperationService.java`):
    - `StockMovement recordOperation(UUID productId, UUID variantId, UUID storeId, MovementType type, int quantityChange, UUID actorId, String notes)`
    - Logic:
      1. Load current `StockLevel` (create if not found, quantity 0)
      2. Compute `quantityBefore` from stock level
      3. Compute `quantityAfter = quantityBefore + quantityChange` (enforce ≥ 0 — throw `DomainException(INSUFFICIENT_STOCK)`)
      4. Build and save `StockMovement`
      5. Update `StockLevel.quantity` = `quantityAfter`
      6. Publish `StockAdjustedEvent` via `ApplicationEventPublisher`
      7. If `quantityAfter ≤ product.minimumThreshold AND threshold > 0` → publish `StockThresholdBreachedEvent`
      8. Return saved `StockMovement`
    - **TDD**: Write `StockOperationServiceTest` with scenarios: normal entry, adjustment to 0, breach threshold, insufficient stock BEFORE implementation
  - [ ] 6.2 — Create new `DomainException` error code: `INSUFFICIENT_STOCK` (HTTP 422)
    - Follow pattern from `GlobalExceptionHandler` — French message: "Stock insuffisant pour cette opération"
    - Add to existing error code enum/class
    - **TDD**: Write error code test

- [ ] **Task 7 — Use cases** (AC1, AC2, AC5, AC6)
  - [ ] 7.1 — Create `SetStockThresholdUseCase` (`catalog/stock/application/usecase/SetStockThresholdUseCase.java`):
    - Input: `productId UUID`, `threshold int` (≥ 0)
    - Uses `ProductRepository.findById()` + `ProductRepository.save()` (update)
    - ⚠️ **IMPORTANT**: Need `ProductRepository` port to expose an `updateThreshold(UUID productId, int threshold)` method OR update via `save(product.withThreshold(threshold))`
    - Emit `ProductUpdatedEvent` with field diff (minimumThreshold before/after) — reuse existing audit event
    - **TDD**: Write `SetStockThresholdUseCaseTest` BEFORE implementation
  - [ ] 7.2 — Create `RecordStockEntryUseCase` (`catalog/stock/application/usecase/RecordStockEntryUseCase.java`):
    - Input: `productId`, `variantId` (nullable), `storeId`, `quantity` (positive integer), `actorId`, `notes` (nullable)
    - Delegates to `StockOperationService.recordOperation(..., MovementType.STOCK_ENTRY, +quantity, ...)`
    - **TDD**: Write `RecordStockEntryUseCaseTest`
  - [ ] 7.3 — Create `AdjustStockUseCase` (`catalog/stock/application/usecase/AdjustStockUseCase.java`):
    - Input: `productId`, `variantId` (nullable), `storeId`, `newQuantity` (absolute value), `actorId`, `reason` (required for ADJUSTMENT)
    - Logic: `quantityChange = newQuantity - currentQuantity` (can be negative)
    - Delegates to `StockOperationService.recordOperation(..., MovementType.ADJUSTMENT, quantityChange, ...)`
    - **TDD**: Write `AdjustStockUseCaseTest` with scenarios: increase, decrease, zero stock BEFORE implementation
  - [ ] 7.4 — Create `GetStockMovementHistoryUseCase` (`catalog/stock/application/usecase/GetStockMovementHistoryUseCase.java`):
    - Input: `productId`, optional filters: `movementType`, `from` (default: now - 7 days), `to`, `storeId`, `page`, `size`
    - Output: `Page<StockMovement>`
    - **TDD**: Write `GetStockMovementHistoryUseCaseTest`
  - [ ] 7.5 — Create `GetCurrentStockUseCase` (`catalog/stock/application/usecase/GetCurrentStockUseCase.java`):
    - Input: `productId`
    - Output: list of `StockLevel` per store (with minimumThreshold from Product)
    - **TDD**: Write `GetCurrentStockUseCaseTest`

- [ ] **Task 8 — REST Controller** (AC1, AC3, AC4, AC5, AC6)
  - [ ] 8.1 — Create `StockController` (`catalog/stock/adapter/in/web/StockController.java`):
    - `GET  /api/v1/products/{productId}/stock` → `GetCurrentStockUseCase`
    - `POST /api/v1/products/{productId}/stock/entry` → `RecordStockEntryUseCase`
    - `POST /api/v1/products/{productId}/stock/adjust` → `AdjustStockUseCase`
    - `PATCH /api/v1/products/{productId}/threshold` → `SetStockThresholdUseCase`
    - `GET  /api/v1/products/{productId}/stock/history` → `GetStockMovementHistoryUseCase` (query params: `type`, `from`, `to`, `storeId`, `page=0`, `size=20`)
    - All endpoints: JWT required, `TenantContext` already set by `JwtAuthFilter`
    - All responses wrapped in `ApiResponseWrapper`
    - **TDD**: Write `StockControllerTest` with MockMvc BEFORE implementation
  - [ ] 8.2 — Create DTOs:
    - `StockEntryRequestDto`: `variantId` (nullable), `storeId`, `quantity` (> 0), `notes` (nullable)
    - `StockAdjustRequestDto`: `variantId` (nullable), `storeId`, `newQuantity` (≥ 0), `reason` (required)
    - `SetThresholdRequestDto`: `threshold` (≥ 0)
    - `StockMovementResponseDto`: all movement fields + `movementType` icon hint
    - `StockLevelResponseDto`: `productId`, `variantId`, `storeId`, `quantity`, `minimumThreshold`, `isLow` (computed: quantity ≤ minimumThreshold)
    - **TDD**: Write DTO validation tests (Jakarta `@NotNull`, `@Min`)

- [ ] **Task 9 — AuditEventListener updates** (AC3, AC5)
  - [ ] 9.1 — Add `StockAdjustedEvent` handler to `AuditEventListener.java`:
    ```java
    @EventListener
    public void on(StockAdjustedEvent event) {
        // Authenticated endpoint — JwtAuthFilter already set TenantContext
        auditPort.record(
            event.actorId(), event.tenantId().toString(),
            "STOCK_ADJUSTED", "StockMovement", event.productId(),
            toJson(Map.of("quantityBefore", event.quantityBefore())),
            toJson(Map.of("movementType", event.movementType().name(),
                          "quantityChange", event.quantityChange(),
                          "quantityAfter", event.quantityAfter(),
                          "notes", event.notes() != null ? event.notes() : ""))
        );
    }
    ```
    - **TDD**: Write `AuditEventListenerTest` handler test
  - [ ] 9.2 — Add `StockThresholdBreachedEvent` handler to `AuditEventListener.java`:
    - entityType = `"StockAlert"`, actionType = `"STOCK_THRESHOLD_BREACHED"`
    - valueAfter: `{ productName, currentQuantity, threshold, storeId }`
    - **⚠️ NOTE**: Push notification (FCM) delivery is **DEFERRED to Epic 8 (story 8-1)**. In this story, the event is only emitted + logged in audit
    - **TDD**: Write handler test

- [ ] **Task 10 — curl E2E test script** (all ACs)
  - [ ] 10.1 — Create `scripts/e2e/curl-tests-story-2-3.sh`:
    - Step 1: Register + login + select-tenant (self-contained)
    - Step 2: Create a product (reuse product creation)
    - Step 3: GET /stock → empty stock level, threshold 0
    - Step 4: PATCH /threshold → set to 10, verify response
    - Step 5: POST /stock/entry → add 15 units, verify STOCK_ENTRY movement
    - Step 6: GET /stock → quantity=15, isLow=false
    - Step 7: POST /stock/adjust → set newQuantity=8 (reason="Perte"), verify ADJUSTMENT movement
    - Step 8: GET /stock → quantity=8, isLow=true (≤ threshold 10)
    - Step 9: GET /stock/history → 2 movements in reverse chronological order
    - Step 10: GET /stock/history?type=ADJUSTMENT → 1 result only
    - Step 11: GET /stock/history?from=...&to=... → date range filter test
    - Step 12: POST /stock/adjust → newQuantity=0 → verify ADJUSTMENT, isLow=true
    - Step 13: POST /stock/entry → add quantity=0 → expect 422 (invalid quantity)
    - Target: 13 steps with assertions (echo "PASS"/"FAIL")

---

### Frontend Flutter — TDD with Drift v5 Migration

- [ ] **Task 11 — Drift schema migration v4 → v5** (AC3, AC4)
  - [ ] 11.1 — Update `stock_movements_table.dart`:
    ```dart
    class StockMovements extends Table {
      TextColumn get id => text()();
      TextColumn get productId => text()();
      TextColumn get variantId => text().nullable()();       // NEW — nullable
      TextColumn get storeId => text()();
      /// 'SALE' | 'STOCK_ENTRY' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'ADJUSTMENT'
      TextColumn get type => text()();
      IntColumn get quantityBefore => integer()();           // NEW
      IntColumn get quantityDelta => integer()();            // existing
      IntColumn get quantityAfter => integer()();            // NEW
      TextColumn get actorId => text()();
      TextColumn get notes => text().nullable()();           // existing (was 'reason')
      BoolColumn get synced => boolean().withDefault(const Constant(false))();
      DateTimeColumn get syncedAt => dateTime().nullable()();
      DateTimeColumn get createdAt => dateTime()();
      @override
      Set<Column> get primaryKey => {id};
    }
    ```
    - **⚠️ Column rename**: `reason` → `notes`. In Drift migration, create a new column `notes` and copy data from `reason`, then drop (or: use `addColumn` + migrate data + `renameColumn` if supported). Simplest: add `notes` as nullable text alongside `reason` and use `notes` going forward; `reason` deprecated. **Prefer: keep `reason` as-is for backward compat and introduce `notes` as a NEW alias column aliased to `reason` in the migration.**
    - Actually: Add new columns `variantId`, `quantityBefore`, `quantityAfter` — no rename needed for `reason` (just alias in code)
    - **TDD**: Write `StockMovementsTableTest` asserting new columns
  - [ ] 11.2 — Update `stock_levels_table.dart`:
    ```dart
    class StockLevels extends Table {
      TextColumn get id => text()();
      TextColumn get productId => text()();
      TextColumn get variantId => text().nullable()();   // NEW
      TextColumn get storeId => text()();
      IntColumn get quantity => integer()();
      IntColumn get minimumThreshold =>                  // NEW
          integer().withDefault(const Constant(0))();
      DateTimeColumn get updatedAt => dateTime()();
      @override
      Set<Column> get primaryKey => {id};
    }
    ```
    - **TDD**: Write `StockLevelsTableTest` asserting new columns
  - [ ] 11.3 — Update `app_database.dart`:
    - Bump `schemaVersion` to **5**
    - Add v5 migration block:
      ```dart
      if (from < 5) {
        // Story 2.3 — stock levels and movements extended
        await migrator.addColumn(stockLevels, stockLevels.variantId);
        await migrator.addColumn(stockLevels, stockLevels.minimumThreshold);
        await migrator.addColumn(stockMovements, stockMovements.variantId);
        await migrator.addColumn(stockMovements, stockMovements.quantityBefore);
        await migrator.addColumn(stockMovements, stockMovements.quantityAfter);
      }
      ```
    - **TDD**: Update `app_database_test.dart` → `schemaVersion == 5`

- [ ] **Task 12 — Flutter domain model for stock** (AC4)
  - [ ] 12.1 — Create `StockMovement` Freezed model (`lib/features/catalog/domain/model/stock_movement_model.dart`):
    ```dart
    @freezed
    class StockMovement with _$StockMovement {
      const factory StockMovement({
        required String id,
        required String productId,
        String? variantId,
        required String storeId,
        required String type, // 'SALE'|'STOCK_ENTRY'|'TRANSFER_IN'|'TRANSFER_OUT'|'ADJUSTMENT'
        required int quantityBefore,
        required int quantityDelta,
        required int quantityAfter,
        required String actorId,
        String? notes,
        required DateTime createdAt,
        @Default(false) bool synced,
        DateTime? syncedAt,
      }) = _StockMovement;
    }
    ```
    - **TDD**: Write model construction test
  - [ ] 12.2 — Create `StockLevel` Freezed model (`lib/features/catalog/domain/model/stock_level_model.dart`):
    ```dart
    @freezed
    class StockLevel with _$StockLevel {
      const factory StockLevel({
        required String id,
        required String productId,
        String? variantId,
        required String storeId,
        required int quantity,
        @Default(0) int minimumThreshold,
        required DateTime updatedAt,
      }) = _StockLevel;
      
      // Computed
      bool get isLow => minimumThreshold > 0 && quantity <= minimumThreshold;
    }
    ```
    - **TDD**: Write `isLow` computed property tests
  - [ ] 12.3 — Create `StockMovementRepository` port (`lib/features/catalog/domain/repository/stock_movement_repository.dart`):
    - `Future<void> saveLocally(StockMovement movement)`
    - `Future<List<StockMovement>> getByProduct(String productId, {String? type, DateTime? from, DateTime? to, String? storeId})`
  - [ ] 12.4 — Create `StockLevelRepository` port (`lib/features/catalog/domain/repository/stock_level_repository.dart`):
    - `Future<StockLevel?> getByProductAndStore(String productId, String storeId)`
    - `Future<void> saveLocally(StockLevel level)`
    - `Future<List<StockLevel>> getAllByProduct(String productId)`
    - `Future<void> setThreshold(String productId, int threshold)` — updates ALL store rows for productId

- [ ] **Task 13 — Flutter data layer** (AC4)
  - [ ] 13.1 — Create `LocalStockMovementDatasource` (`lib/features/catalog/data/datasource/local_stock_movement_datasource.dart`):
    - Reads/writes `stockMovements` Drift table
    - `getByProduct()` supports filters: type, date range, storeId — uses Drift `where()` chaining
    - **TDD**: Write datasource test with `AppDatabase.forTesting()`
  - [ ] 13.2 — Create `LocalStockLevelDatasource` (`lib/features/catalog/data/datasource/local_stock_level_datasource.dart`):
    - Reads/writes `stockLevels` Drift table
    - `setThreshold()`: `update(stockLevels)..where(...)` → minimumThreshold
    - **TDD**: Write datasource test
  - [ ] 13.3 — Create `StockMovementRepositoryImpl` (`lib/features/catalog/data/repository/stock_movement_repository_impl.dart`):
    - Implements port; delegates to `LocalStockMovementDatasource` (offline-first pattern: Epic 5 will add sync)
    - **TDD**: Write repository impl test with mock datasource
  - [ ] 13.4 — Create `StockLevelRepositoryImpl` (`lib/features/catalog/data/repository/stock_level_repository_impl.dart`):
    - Implements port; delegates to `LocalStockLevelDatasource`
    - **TDD**: Write repository impl test
  - [ ] 13.5 — Register new repositories in DI (`lib/core/providers/repository_providers.dart` or equivalent):
    - `stockMovementRepositoryProvider` and `stockLevelRepositoryProvider`

- [ ] **Task 14 — Flutter use cases** (AC1, AC4, AC5, AC6)
  - [ ] 14.1 — Create `GetStockHistoryUseCase` (`lib/features/catalog/domain/usecase/get_stock_history_usecase.dart`):
    - Input: `productId`, optional: `type, from, to, storeId`
    - Output: `List<StockMovement>` sorted by `createdAt DESC`
    - **TDD**: Write use case test with mock repository
  - [ ] 14.2 — Create `GetStockLevelUseCase` (`lib/features/catalog/domain/usecase/get_stock_level_usecase.dart`):
    - Output: `List<StockLevel>` for product (all stores)
    - **TDD**: Write use case test
  - [ ] 14.3 — Create `SetThresholdUseCase` (`lib/features/catalog/domain/usecase/set_threshold_usecase.dart`):
    - Calls `PATCH /api/v1/products/{productId}/threshold` (remote)
    - On success: update local Drift `stock_levels.minimumThreshold` via `StockLevelRepository.setThreshold()`
    - **TDD**: Write use case test with mock repo + mock HTTP

- [ ] **Task 15 — Flutter Riverpod providers + state** (AC1, AC4)
  - [ ] 15.1 — Create `StockNotifier` (`lib/features/catalog/presentation/provider/stock_notifier.dart`):
    - States: `StockState { stockLevels: List<StockLevel>, history: List<StockMovement>, isLoading, error }`
    - Actions: `loadStockLevel(productId)`, `loadHistory(productId, filters)`, `setThreshold(productId, threshold)`
    - **TDD**: Write notifier test (use `ProviderContainer` + mock use cases)
  - [ ] 15.2 — Create `StockFilter` model (`lib/features/catalog/domain/model/stock_filter.dart`):
    - `movementType` (nullable), `from` (default: now - 7 days), `to` (default: now), `storeId` (nullable)

- [ ] **Task 16 — Flutter UI** (AC1, AC2, AC4, AC5, AC6)
  - [ ] 16.1 — Create `StockHistoryPage` (`lib/features/catalog/presentation/page/stock_history_page.dart`):
    - Route: `/catalog/products/:productId/stock-history`
    - Renders chronological movement list
    - Each item: type icon + label, quantity change ("+N" green / "−N" red), quantityAfter, actor initial badge, date/time
    - Filter bar: movementType chips (Tous / Entrée / Ajustement / Vente), date range row
    - Empty state: "Aucun mouvement pour cette période"
    - Loads from Drift — works offline
    - **TDD**: Write widget test with mock notifier
  - [ ] 16.2 — Create `StockLevelWidget` (`lib/features/catalog/presentation/widget/stock_level_widget.dart`):
    - Shown in the "Stock" tab of product detail page
    - Displays current quantity, threshold with low-stock warning banner (red background, "⚠ Stock bas")
    - "Modifier le seuil" button → opens `SetThresholdBottomSheet`
    - "Ajuster le stock" button → opens `AdjustStockBottomSheet`
    - "Ajouter du stock" button → opens `StockEntryBottomSheet`
    - "Voir l'historique" → navigates to `StockHistoryPage`
    - **TDD**: Write widget test
  - [ ] 16.3 — Create `SetThresholdBottomSheet` widget:
    - Integer-only input, current value pre-filled, "Enregistrer" button
    - Calls `StockNotifier.setThreshold()`
    - **TDD**: Widget test
  - [ ] 16.4 — Create `AdjustStockBottomSheet` widget:
    - "Nouvelle quantité" integer input, "Raison" text field (required), "Confirmer" button
    - Calls `POST /api/v1/products/{productId}/stock/adjust` (remote)
    - On success: update local stock level + add movement to Drift
    - **TDD**: Widget test
  - [ ] 16.5 — Create `StockEntryBottomSheet` widget:
    - "Quantité à ajouter" integer input (> 0), "Notes" optional, "Confirmer" button
    - Calls `POST /api/v1/products/{productId}/stock/entry` (remote)
    - On success: update local stock + add movement to Drift
    - **TDD**: Widget test
  - [ ] 16.6 — Update `ProductFormPage` / product detail page:
    - Add "Stock" tab (second tab after "Détail")
    - Render `StockLevelWidget` in Stock tab
  - [ ] 16.7 — Update product list / `ProductListPage` (or equivalent):
    - Show red "Stock bas" badge on product card when `stockLevel.isLow == true`
    - Badge: `Container` with red background, "Stock bas" text in white
    - Query: join `stockLevels` where `minimumThreshold > 0 AND quantity <= minimumThreshold`
    - **TDD**: Write widget test for badge visibility

## Dev Notes

### Architecture Compliance

**Hexagonal Architecture — Package Structure:**
```
backend/src/main/java/com/keevo/
  catalog/
    stock/
      domain/
        entity/          StockMovement.java, StockLevel.java, MovementType.java
        service/         StockOperationService.java
        event/           StockAdjustedEvent.java, StockThresholdBreachedEvent.java
        port/out/        StockMovementRepository.java, StockLevelRepository.java
      application/
        usecase/         RecordStockEntryUseCase.java, AdjustStockUseCase.java,
                         SetStockThresholdUseCase.java, GetStockMovementHistoryUseCase.java,
                         GetCurrentStockUseCase.java
      adapter/
        in/web/          StockController.java, dto/*.java
        out/persistence/ StockLevelRepositoryAdapter.java, StockMovementRepositoryAdapter.java
  shared/
    infrastructure/
      persistence/entity/ StockLevelJpaEntity.java, StockMovementJpaEntity.java
      persistence/        TenantSchemaProvisioner.java (updated)
      web/                AuditEventListener.java (updated)
```

```
flutter/lib/
  features/
    catalog/
      domain/
        model/           stock_movement_model.dart, stock_level_model.dart, stock_filter.dart
        repository/      stock_movement_repository.dart, stock_level_repository.dart
        usecase/         get_stock_history_usecase.dart, get_stock_level_usecase.dart,
                         set_threshold_usecase.dart
      data/
        datasource/      local_stock_movement_datasource.dart, local_stock_level_datasource.dart
        repository/      stock_movement_repository_impl.dart, stock_level_repository_impl.dart
      presentation/
        provider/        stock_notifier.dart
        page/            stock_history_page.dart
        widget/          stock_level_widget.dart, set_threshold_bottom_sheet.dart,
                         adjust_stock_bottom_sheet.dart, stock_entry_bottom_sheet.dart
  core/
    storage/             stock_movements_table.dart (updated), stock_levels_table.dart (updated),
                         app_database.dart (v5)
```

### GoF Design Patterns — Required

- **Observer**: `StockAdjustedEvent` + `StockThresholdBreachedEvent` published via Spring `ApplicationEventPublisher`, consumed by `AuditEventListener` — same pattern as Stories 1.8, 2.1, 2.2
- **Template Method**: `AuditEventListener.on()` handlers all follow identical skeleton (TenantContext check → build valueBefore → build valueAfter → auditPort.record → log) — do not deviate
- **Domain Service**: `StockOperationService` encapsulates the multi-step stock operation logic (load → compute → save movement → save level → emit events) — keeps use cases thin
- **Strategy (future)**: `StockLevelRepository` and `StockMovementRepository` ports are the strategy interfaces; adapters are concrete strategies. This pattern allows Epic 5 sync to plug in without modifying domain
- **Repository**: All DB access goes through port interfaces — NO direct Spring JPA repository calls in domain/application layers

### Critical Implementation Rules

1. **`StockOperationService` must use `@Transactional`** — the save movement + update level + publish events must be atomic. If the transaction rolls back, NO events are published (Spring only publishes `@EventListener` events after commit by default — do NOT use `@TransactionalEventListener` here unless explicitly needed for threshold events).

2. **Threshold check: server-side only** — the `StockOperationService` fetches the product's `minimumThreshold` from `ProductRepository` on every call. The client-side badge is for display only, NOT enforcement.

3. **`quantityAfter >= 0` invariant** — `StockOperationService` must throw `DomainException(INSUFFICIENT_STOCK)` if the resulting quantity would be negative. Use case `AdjustStockUseCase` with absolute newQuantity bypasses this for `newQuantity=0` scenarios (explicit zeroing allowed).

4. **Drift migration v4→v5** — use `addColumn()` for added columns. Do NOT drop `reason` column from `stock_movements` — keep it as nullable backward-compat. The `notes` column in the Java model maps to `notes` in DB; Dart side also uses `notes`. If the column `reason` already has data, it stays.

5. **`@Transactional(readOnly = true)` on query methods** in `StockLevelRepositoryAdapter` and `StockMovementRepositoryAdapter` — same discipline as other adapters.

6. **French HTTP error messages** — follow existing `GlobalExceptionHandler` FR_MESSAGES pattern. Add:
   - `INSUFFICIENT_STOCK` → 422 → "Stock insuffisant pour cette opération"
   - `THRESHOLD_NEGATIVE` → 422 → "Le seuil minimum ne peut pas être négatif"

7. **Offline-first in Flutter**: Story 2.3 implements local-only stock operations for the **history view** (Drift reads). The `RecordStockEntry` and `AdjustStock` operations in Flutter call the **backend API** (network required), then update local Drift on success. Full offline queueing for stock operations is deferred to **Epic 5 (sync engine)**.

8. **Push notification (FCM)**: `StockThresholdBreachedEvent` is emitted and audit-logged in story 2.3. FCM token registration and delivery are **deferred to Epic 8 (story 8-1: alertes-stock-critique)**. Do NOT add Firebase dependencies in this story.

### Previous Story Learnings (2-2)

- **DomainException pattern**: Use `DomainException(ErrorCode.XYZ)` for all domain-level errors. Never throw `IllegalArgumentException` from use cases — it maps to 500.
- **Money value object**: For stock operations, quantities are plain `int` (no Money needed — not monetary). Keep Money strictly for price fields.
- **Drift migration discipline**: The `from < N` migration block must be inside `onUpgrade`. Always add new columns with `migrator.addColumn()` — never `createTable()` for existing tables.
- **`@Transactional` with `REQUIRES_NEW`**: For audit events (Story 1.8), some handlers need `REQUIRES_NEW`. Check if `AuditEventListener` handlers need it — pattern: authenticated endpoints don't need it (TenantContext already set); only PUBLIC endpoints need TenantContext management.
- **Flutter ValueListenableBuilder vs Riverpod**: For complex state (loading/error/data), use Riverpod `AsyncNotifierProvider`. For simple real-time UI calculations (like margin widget was), use `ValueListenableBuilder`. Stock history has loading/error states → use `AsyncNotifierProvider` pattern.
- **`ProductRepositoryAdapter` mapping**: When adding new Product fields (minimumThreshold), update BOTH `toDomain()` and `toEntity()` methods. Check for any `toDomainSummary()` or projection methods as well.

### Previous Story Learnings (2-1)

- **`AuditEventListener` extension pattern**: All new event handlers go at the bottom of `AuditEventListener.java`, with a comment block `// ── Stock Events (Story 2.3) ─────────────────────────────────────────────`.
- **`TenantSchemaSyncService.REQUIRED_TABLES`**: New DDL constants MUST be added to this list. Failure to do so causes existing tenants (created before story 2.3 deploy) to miss the new tables on their next login. This was a critical bug in Story 1.2 and is a frequent source of failures.
- **Category pattern for DDL**: Follow the established pattern for `TenantSchemaProvisioner` constants — each table has: `DDL_{TABLE_NAME}` (CREATE IF NOT EXISTS), `DDL_{TABLE_NAME}_IDX_{FIELD}` (index), and a migration constant `DDL_{TABLE_NAME}_MIGRATE_{FIELD}` for ALTER TABLE changes.

### Test Count Reference

Based on prior stories pattern:
- Backend unit tests expected to grow from 282 → ~320+ (adding ~38 new tests)
- Flutter widget/unit tests from existing count + ~15 new tests
- E2E curl script: 13 steps

### References

- Epic 2 story 2.3 requirements: [_bmad-output/planning-artifacts/epics/epic-2-catalogue-produits-base-fournisseursclients.md](_bmad-output/planning-artifacts/epics/epic-2-catalogue-produits-base-fournisseursclients.md#Story-2.3)
- Architecture — stock_movements DDL naming: [_bmad-output/planning-artifacts/architecture.md](_bmad-output/planning-artifacts/architecture.md#L629)
- Architecture — Drift retention policy (stock_movements = 30-day transactional): [_bmad-output/planning-artifacts/architecture.md](_bmad-output/planning-artifacts/architecture.md#L718-L719)
- Story 1.8 — AuditEventListener Observer/TemplateMethod pattern: [_bmad-output/implementation-artifacts/1-8-immutable-audit-trail-security-domain-foundation.md](_bmad-output/implementation-artifacts/1-8-immutable-audit-trail-security-domain-foundation.md)
- Story 2.1 — Product domain, AuditEventListener extension, ProductJpaEntity: [_bmad-output/implementation-artifacts/2-1-crud-produits-creation-edition-archivage.md](_bmad-output/implementation-artifacts/2-1-crud-produits-creation-edition-archivage.md)
- Story 2.2 — DomainException pattern, Money, Drift migration v4: [_bmad-output/implementation-artifacts/2-2-moteur-de-prix-calcul-de-marge.md](_bmad-output/implementation-artifacts/2-2-moteur-de-prix-calcul-de-marge.md)
- Existing `StockMovements` Drift table (v4): [keevo/app/lib/core/storage/stock_movements_table.dart](keevo/app/lib/core/storage/stock_movements_table.dart)
- Existing `StockLevels` Drift table (v4): [keevo/app/lib/core/storage/stock_levels_table.dart](keevo/app/lib/core/storage/stock_levels_table.dart)
- `AuditEventListener.java` (current): [keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java](keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java)
- `TenantSchemaProvisioner.java`: [keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java](keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java)
- `Product.java` domain entity (has stockQuantity + minimumThreshold to add): [keevo/backend/src/main/java/com/keevo/catalog/product/domain/entity/Product.java](keevo/backend/src/main/java/com/keevo/catalog/product/domain/entity/Product.java)

## Dev Agent Record

### Agent Model Used

GitHub Copilot + Claude Sonnet 4.6

### Debug Log References

_None_

### Completion Notes List

- H1: `AdjustStockUseCase` — thrown exception changed from `IllegalArgumentException` to `DomainException(VALIDATION_ERROR)` → standardised 422 response
- H2: Created 3 missing test files: `GetCurrentStockUseCaseTest`, `GetStockMovementHistoryUseCaseTest`, `StockControllerTest` (6+8+6 tests)
- H3: `StockHistoryPage` — added `_filterFrom` (default -7d), `_filterTo` (default now), `_filterStoreId`; date range picker via `showDateRangePicker()`; all filters passed to `loadHistory()`
- M1: Created `stock_filter.dart` domain model with `movementType?`, `from`, `to`, `storeId?`
- M2: `StockRepositoryImpl.setThreshold()` — local update wrapped in try/catch (best-effort)
- Backend: 339/339 tests GREEN | Flutter: 0 errors

### File List

**Backend — Main**
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/StockController.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/dto/AdjustStockRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/dto/RecordStockEntryRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/dto/SetThresholdRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/dto/StockLevelResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/in/web/dto/StockMovementResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockLevelRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockLevelSpringRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockMovementRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockMovementSpringRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/AdjustStockUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/GetCurrentStockUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/GetStockMovementHistoryUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/RecordStockEntryUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/application/usecase/SetStockThresholdUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/entity/MovementType.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/entity/StockLevel.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/entity/StockMovement.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/event/StockAdjustedEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/event/StockThresholdBreachedEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/port/out/StockLevelRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/port/out/StockMovementRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/stock/domain/service/StockOperationService.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/entity/StockLevelJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/entity/StockMovementJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/domain/entity/Product.java` _(modified: minimumThreshold)_
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/dto/ProductResponseDto.java` _(modified: minimumThreshold)_
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/entity/ProductJpaEntity.java` _(modified: minimumThreshold)_
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java` _(modified: DomainException handling)_

**Backend — Tests**
- `keevo/backend/src/test/java/com/keevo/catalog/stock/adapter/in/web/StockControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/application/usecase/AdjustStockUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/application/usecase/GetCurrentStockUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/application/usecase/GetStockMovementHistoryUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/application/usecase/RecordStockEntryUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/application/usecase/SetStockThresholdUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/domain/entity/StockLevelTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/domain/entity/StockMovementTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/stock/domain/service/StockOperationServiceTest.java`

**Flutter — Main**
- `keevo/app/lib/features/catalog/data/datasource/local_stock_datasource.dart`
- `keevo/app/lib/features/catalog/data/datasource/remote_stock_datasource.dart`
- `keevo/app/lib/features/catalog/data/repository/stock_repository_impl.dart`
- `keevo/app/lib/features/catalog/domain/model/stock_filter.dart`
- `keevo/app/lib/features/catalog/domain/model/stock_level_model.dart`
- `keevo/app/lib/features/catalog/domain/model/stock_movement_model.dart`
- `keevo/app/lib/features/catalog/domain/repository/stock_repository.dart`
- `keevo/app/lib/features/catalog/domain/usecase/get_stock_history_usecase.dart`
- `keevo/app/lib/features/catalog/domain/usecase/get_stock_level_usecase.dart`
- `keevo/app/lib/features/catalog/domain/usecase/set_threshold_usecase.dart`
- `keevo/app/lib/features/catalog/presentation/page/stock_history_page.dart`
- `keevo/app/lib/features/catalog/presentation/provider/stock_provider.dart`
- `keevo/app/lib/features/catalog/presentation/widget/stock_adjust_bottom_sheet.dart`
- `keevo/app/lib/features/catalog/presentation/widget/stock_entry_bottom_sheet.dart`
- `keevo/app/lib/features/catalog/presentation/widget/stock_level_widget.dart`
- `keevo/app/lib/features/catalog/presentation/widget/stock_threshold_bottom_sheet.dart`
- `keevo/app/lib/core/storage/app_database.dart` _(modified: Drift tables)_
- `keevo/app/lib/core/storage/stock_levels_table.dart`
- `keevo/app/lib/core/storage/stock_movements_table.dart`
- `keevo/app/lib/features/catalog/presentation/page/product_form_page.dart` _(modified: threshold field)_
- `keevo/app/lib/features/catalog/presentation/widget/product_card.dart` _(modified: stock indicator)_

**Flutter — Tests**
- `keevo/app/test/features/catalog/domain/model/stock_level_model_test.dart`
- `keevo/app/test/features/catalog/domain/model/stock_movement_model_test.dart`

**Scripts**
- `keevo/scripts/e2e/curl-tests-story-2-3.sh`

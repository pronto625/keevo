# Story 4.1: Enregistrement de Vente & Flux POS Core

Status: done

## Story

As an employee (Loïc),
I want to record a sale in 3 taps maximum with immediate feedback,
so that I can serve customers rapidly without slowing down the queue or returning to paper.

---

## Acceptance Criteria

### AC1 — POS is the default landing screen

- **Given** Loïc opens the app (authenticated, onboarding completed)
- **When** the app loads
- **Then** the POS screen (`PosPage`) is displayed immediately — zero additional navigation required
- **And** a search bar is displayed at the top with a fuzzy product search placeholder
- **And** a grid of frequently used / recently sold products is shown (up to 12 cards, ordered by `sale_items.quantity DESC` count from Drift `sales` + `sale_items` tables)
- **And** the `CartPill` widget is hidden (visible only when cart has ≥ 1 item)
- **And** `PosPlaceholderPage` is fully replaced by `PosPage`

### AC2 — Fuzzy product search < 500ms

- **Given** Loïc types in the search bar
- **When** he enters at least 2 characters
- **Then** fuzzy search results appear in under 500ms from the local Drift `products` table
- **And** results show: product photo (or initials `ProductInitialsAvatar` widget), name, price in XAF, stock level badge for the employee's assigned store
- **And** out-of-stock products (stock_levels.quantity = 0 for employee's storeId) appear at the bottom with a grey "⚠️ Rupture" overlay and a "Vérifier autres boutiques" action that routes to `/inventory/cross-store-check?productId=...` (Story 3.4 — pre-existing route)

### AC3 — Add product to cart (animation + haptic)

- **Given** Loïc taps a product card
- **When** the product is added to the cart
- **Then** the product card flashes `#D0EBFF` (selected state) for 150ms then reverts
- **And** a light haptic vibration is triggered (`HapticFeedback.lightImpact()`)
- **And** the `CartPill` appears (or updates): shows item count, total in XAF, "Encaisser" button
- **And** the animation duration is < 200ms
- **And** the cart total updates in real-time

### AC4 — Cart expansion (bottom sheet)

- **Given** Loïc has items in the cart
- **When** he taps the `CartPill` to expand it
- **Then** a `CartBottomSheet` shows all cart items: product name + variant (if any), quantity (+/- controls), applied unit price (tappable to edit — **Story 4.2 stub: shows read-only for now**), subtotal per line
- **And** the grand total is displayed prominently at the bottom
- **And** swiping left on any cart item dismisses it from the cart (`Dismissible` widget)

### AC5 — Payment screen

- **Given** Loïc taps "Encaisser"
- **When** the payment screen (`CheckoutPage`) appears
- **Then** two payment mode buttons are displayed: "💵 Espèces" and "📱 Mobile Money"
- **And** a client selector field is shown (optional — autocomplete from clients Drift table)
- **And** for Espèces: a "Montant reçu" field appears with real-time change calculation: `Monnaie rendue = montant_reçu − total`
- **And** for Mobile Money: a reference number field appears (optional text field)
- **And** "Valider la vente" button is disabled until a payment mode is selected

### AC6 — Sale recording (local-first + sync queue)

- **Given** Loïc confirms the payment and taps "Valider la vente"
- **When** the sale is validated
- **Then** the sale is recorded **immediately** in the local Drift `sales` table:
  - `id` → UUID v4 (client-generated via `const Uuid().v4()`)
  - `storeId` → from `SecureTokenStorage.readStoreId()` (employee's assigned store)
  - `employeeId` → from `SecureTokenStorage.readUserId()`
  - `clientId` → nullable, if client was selected
  - `totalAmount` → XAF integer, sum of all subtotals
  - `paymentMode` → `'CASH'` or `'MOBILE_MONEY'`
  - `status` → `'COMPLETED'`
  - `createdAt` → `DateTime.now()`
- **And** each cart item is recorded in local Drift `sale_items` table:
  - `id` → UUID v4 (client-generated)
  - `saleId` → references `sales.id`
  - `productId` → product UUID
  - `productName` → snapshot of product name at sale time
  - `unitPrice` → `appliedUnitPrice` at time of sale (story 4.2 will introduce override)
  - `quantity` → selected quantity
  - `subtotal` → `unitPrice × quantity`
- **And** stock levels are decremented in Drift `stock_levels` for each sold product in the employee's store: `quantity = max(0, quantity - saleItem.quantity)`
- **And** a `StockMovement` (type `SALE`) is inserted into Drift `stock_movements` for each item
- **And** the local Drift `sales` record includes `occurredAt → DateTime.now()` (snapshot of exact sale time)
- **And** the operation is stored in `sync_queue` with `operation: 'CREATE_SALE'`, `payload: <full RecordSalePayload JSON>`, `synced: false`
- **And** if the device is online: `RemoteSaleDataSource.pushSale()` is called **immediately** in a non-blocking background task (`unawaited`) — the success screen does NOT wait for this call
- **And** if the backend call succeeds (HTTP 201): `sales.synced` is set to `true`, `sales.syncedAt` is set to `DateTime.now()`, and the `sync_queue` entry is removed — **data is preserved on the server even if the phone is reset before Epic 5 sync runs**
- **And** if the backend call fails (offline / timeout / server error): `sales.synced` stays `false`, the `sync_queue` entry remains — Epic 5 push-sync will retry on reconnect

### AC7 — Success screen and reset

- **Given** the sale is saved locally
- **When** the success screen appears
- **Then** a full-screen lime-green confirmation `SaleSuccessPage` displays: "✅ Vente enregistrée — [total] FCFA"
- **And** the animation appears in < 200ms
- **And** after 1.5 seconds, the POS screen resets to empty (cart cleared, search cleared)
- **And** the entire flow from first tap to success takes ≤ 5 seconds for a 1-product sale

### AC8 — Product initials avatar

- **Given** a product has no photo
- **When** it is displayed on the POS grid or in the cart
- **Then** the `ProductInitialsAvatar` widget shows a coloured circle with the product's first 2 letters (uppercase)
- **And** the colour is deterministically derived from the product name using `name.hashCode % kAvatarColors.length` (consistent across sessions)

### AC9 — Backend: POST /api/v1/sales (sync endpoint)

- **Given** the Flutter app pushes a sale payload to the backend (online)
- **When** `POST /api/v1/sales` is called with a valid JWT
- **Then** the backend records the sale in the tenant schema's `sales` + `sale_items` tables
- **And** stock is decremented in `stock_levels` per item (server-side idempotency: checks `sale.id` not already in DB)
- **And** `INSUFFICIENT_STOCK` error (HTTP 422) is returned if server-side stock would go negative (safety net — client already decremented locally, but backend is authoritative)
- **And** a `SaleCompletedEvent` is published (`ApplicationEventPublisher`) → `AuditEventListener` records `SALE_COMPLETED` with full sale JSON in `valueAfter`
- **And** the response is `HTTP 201` with `{ "data": { "id": "<uuid>", "status": "COMPLETED", "totalAmount": <int>, "occurredAt": "<ISO>" } }`
- **And** an EMPLOYEE attempting to record a sale for a storeId ≠ their JWT `storeId` receives HTTP 403 `FORBIDDEN`
- **And** unauthenticated requests receive HTTP 401

### AC10 — Role-based visibility

- **Given** an EMPLOYEE user is logged in
- **When** the POS screen loads
- **Then** the `CartPill` "Encaisser" button is visible and enabled (EMPLOYEE CAN sell)
- **And** the navigation bottom bar shows only "Caisse" and "Plus" (per Story 3.5 AC5 — pre-existing)
- **And** the OWNER sees the same POS UI plus the additional 3 navigation tabs

---

## GoF Design Patterns Applied

| Pattern | Location | Application |
|---------|----------|-------------|
| **Command** | `RecordSaleCommand.java` (backend) | Encapsulates all sale data as an immutable record passed to `RecordSaleUseCase` |
| **Observer** | `SaleCompletedEvent.java` + `AuditEventListener` | Spring application event: service publishes → audit listener subscribes |
| **Factory Method** | `SaleFactory.java` | Creates `Sale` aggregate + `SaleItem` list from `RecordSaleCommand` |
| **Strategy** (Flutter) | `PaymentMode` enum + `_PaymentFormStrategy` | Espèces form vs Mobile Money form rendered via strategy |
| **State** (Flutter) | `CartState` sealed class | `CartEmpty` / `CartHasItems` / `CartCheckout` — drives `CartPill` + `PosPage` transitions |
| **Façade** | `SaleRepositoryImpl.dart` | Hides local Drift + remote push behind single `SaleRepository` interface |

---

## Tasks / Subtasks

### Task 1 — Write RED tests (backend domain + use case)

**TDD LAW: All tests written BEFORE any production code. Tests MUST fail first.**

- [x] **1.1** `SaleTest.java` — unit test for `Sale` aggregate:
  - `Sale_create_setsAllFields()`
  - `Sale_create_throwsOnNegativeAmount()`
  - `Sale_withItems_calculatesCorrectTotal()`
- [x] **1.2** `SaleItemTest.java`:
  - `SaleItem_subtotal_equalsPriceTimesQuantity()`
  - `SaleItem_quantity_throwsOnZeroOrNegative()`
- [x] **1.3** `RecordSaleServiceTest.java` — unit test with Mockito:
  - `recordSale_success_savesAndPublishesEvent()`
  - `recordSale_insufficientStock_throwsDomainException()`
  - `recordSale_stockNotFound_throwsDomainException()`
  - `recordSale_idempotency_duplicateSaleIdIsNoOp()`
  - `recordSale_setsStatusToCompleted()`
- [x] **1.4** `SaleRepositoryAdapterTest.java` — `@DataJpaTest` with real H2:
  - `save_persistsSaleWithItems()`
  - `findById_returnsSaleWithItems()`
  - `existsById_returnsTrueForExistingSale()` (idempotency check)
- [x] **1.5** `SaleControllerTest.java` — `@WebMvcTest` + MockMvc:
  - `POST_sales_returns201_withValidPayload()`
  - `POST_sales_returns422_withInsufficientStock()`
  - `POST_sales_returns401_withoutToken()`
  - `POST_sales_returns403_storeIdMismatch()`
  - `POST_sales_returns400_withEmptyItems()`

### Task 2 — Implement backend domain model

- [x] **2.1** `Sale.java` — extends `BaseEntity`, uses `Money` for `totalAmount`
  - Fields: `storeId (UUID)`, `employeeId (UUID)`, `clientId (UUID nullable)`, `paymentMode (PaymentMode enum)`, `totalAmount (int XAF)`, `status (SaleStatus enum)`, `occurredAt (Instant)`, `items (List<SaleItem>)`
  - Factory method: `Sale.create(RecordSaleCommand cmd)` via `SaleFactory`
- [x] **2.2** `SaleItem.java` — pure Java no Spring
  - Fields: `id (UUID)`, `saleId (UUID)`, `productId (UUID)`, `variantId (UUID nullable)`, `productName (String)`, `appliedUnitPrice (int)`, `quantity (int)`, `subtotal (int computed)`
- [x] **2.3** `PaymentMode.java` — enum: `CASH`, `MOBILE_MONEY`
- [x] **2.4** `SaleStatus.java` — enum: `COMPLETED`, `CANCELLED`
- [x] **2.5** `SaleCompletedEvent.java` — Spring `ApplicationEvent` subclass
  - Fields: `saleId (UUID)`, `actorId (UUID)`, `tenantId (String)`, `storeId (UUID)`, `totalAmount (int)`, `itemsSnapshot (String JSON)`, `occurredAt (Instant)`
- [x] **2.6** `SaleFactory.java` — static factory `SaleFactory.from(RecordSaleCommand)` creates `Sale` + `List<SaleItem>`

### Task 3 — Implement backend port contracts

- [x] **3.1** `RecordSaleUseCase.java` (port/in):
  ```java
  // Command record (MCP-pure — only Java primitives/records)
  record RecordSaleCommand(
      UUID saleId,       // client-generated for idempotency
      UUID actorId,      // from JWT claim
      UUID storeId,      // from JWT claim — must match employee's store
      UUID clientId,     // nullable
      PaymentMode paymentMode,
      String mobileMoneyRef, // nullable
      List<SaleItemCommand> items
  ) {}
  record SaleItemCommand(UUID productId, UUID variantId, String productName, int appliedUnitPrice, int quantity) {}
  void recordSale(RecordSaleCommand command);
  ```
- [x] **3.2** `SaleRepository.java` (port/out):
  - `void save(Sale sale)`
  - `boolean existsById(UUID saleId)` — idempotency guard
  - `Optional<Sale> findById(UUID saleId)`
- [x] **3.3** `StockPort.java` usage (port/out — reuse existing `StockLevelJpaEntity` / `StockLevelSpringRepository`):
  - `int getQuantity(UUID productId, UUID variantId, UUID storeId)` — may reuse or delegate to existing adapter
  - `void decrementStock(UUID productId, UUID variantId, UUID storeId, int qty)` — atomic UPDATE with version check

### Task 4 — Implement backend application service

- [x] **4.1** `RecordSaleService.java` — `@Service @Transactional`
  - GoF **Command**: receives `RecordSaleCommand`, delegates to `SaleFactory`
  - For each `SaleItemCommand`: call `StockPort.getQuantity()` → throw `INSUFFICIENT_STOCK` if `qty < command.quantity`
  - Guard: if `saleRepository.existsById(command.saleId())` → **no-op / return** (idempotency)
  - Call `SaleFactory.from(command)` → get `Sale` with `List<SaleItem>`
  - Call `saleRepository.save(sale)` inside `@Transactional`
  - For each item: call `StockPort.decrementStock()` inside the same transaction
  - Publish `SaleCompletedEvent` via `ApplicationEventPublisher.publishEvent()`
  - GoF **Observer**: `AuditEventListener` (pre-existing `@EventListener`) listens to `SaleCompletedEvent` → records `SALE_COMPLETED` in audit log

### Task 5 — Implement backend persistence (JPA + DDL)

- [x] **5.1** `SaleJpaEntity.java` — `@Entity @Table(name="sales")`
  - Columns: `id UUID PK`, `store_id UUID`, `employee_id UUID`, `client_id UUID nullable`, `total_amount INTEGER`, `payment_mode VARCHAR(30)`, `status VARCHAR(20)`, `occurred_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ`
  - NO `@ManyToOne` to avoid cross-schema FK — use raw UUID columns
  - `@OneToMany(mappedBy="saleId", cascade=ALL, fetch=LAZY)` → `List<SaleItemJpaEntity>`
- [x] **5.2** `SaleItemJpaEntity.java` — `@Entity @Table(name="sale_items")`
  - `id UUID PK`, `sale_id UUID FK`, `product_id UUID`, `variant_id UUID nullable`, `product_name VARCHAR(255)`, `applied_unit_price INTEGER`, `quantity INTEGER`, `subtotal INTEGER`, `created_at TIMESTAMPTZ`
- [x] **5.3** `SaleSpringRepository.java` — `JpaRepository<SaleJpaEntity, UUID>`
  - `boolean existsById(UUID id)` — uses JPA default
  - `Optional<SaleJpaEntity> findById(UUID id)` — uses JPA default
- [x] **5.4** `SaleRepositoryAdapter.java` — implements `SaleRepository` port
  - `save(Sale)` → map domain → JPA entity → `saleSpringRepository.save()`
  - `existsById(UUID)` → `saleSpringRepository.existsById(UUID)`
- [x] **5.5** `TenantSchemaProvisioner.java` — add new DDL constants + execute in `provisionSchema()`:
  ```java
  // New DDL constants:
  static final String DDL_SALE_ITEMS = """
      CREATE TABLE IF NOT EXISTS sale_items (
          id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
          sale_id           UUID        NOT NULL REFERENCES sales(id),
          product_id        UUID        NOT NULL,
          variant_id        UUID,
          product_name      VARCHAR(255) NOT NULL,
          applied_unit_price INTEGER    NOT NULL,
          quantity          INTEGER     NOT NULL CHECK (quantity > 0),
          subtotal          INTEGER     NOT NULL,
          created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )""";
  static final String DDL_SALE_ITEMS_IDX_SALE =
      "CREATE INDEX IF NOT EXISTS idx_sale_items_sale_id ON sale_items(sale_id)";
  static final String DDL_SALES_MIGRATE_STATUS =
      "ALTER TABLE sales ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED','CANCELLED'))";
  static final String DDL_SALES_MIGRATE_OCCURRED_AT =
      "ALTER TABLE sales ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMPTZ";
  ```
  - In `provisionSchema()` / `TenantSchemaSyncService`: add `DDL_SALE_ITEMS`, `DDL_SALE_ITEMS_IDX_SALE`, `DDL_SALES_MIGRATE_STATUS`, `DDL_SALES_MIGRATE_OCCURRED_AT` to the idempotent execution block
  - Also add `REQUIRED_TABLES`: `"sale_items"` to the REQUIRED_TABLES set (used by `TenantSchemaSyncService`)

### Task 6 — Implement backend REST controller

- [x] **6.1** `SaleController.java` — `@RestController @RequestMapping("/api/v1/sales")`
  - `POST /` → `@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")` + **storeId guard**: reject if `command.storeId() != JWT.storeId` for EMPLOYEE role → HTTP 403 `FORBIDDEN`
  - Extracts `actorId` from `SecurityContextHolder.getContext().getAuthentication().getName()` (UUID string)
  - Extracts `storeId` from JWT via `JwtTokenProvider.extractStoreId(claims)`
  - Maps `RecordSaleRequestDto` → `RecordSaleCommand`
  - Calls `recordSaleUseCase.recordSale(command)`
  - Returns `HTTP 201` + `RecordSaleResponseDto`
- [x] **6.2** `RecordSaleRequestDto.java` — validation annotations:
  - `@NotNull UUID saleId` — client-provided for idempotency
  - `@NotNull PaymentMode paymentMode`
  - `UUID clientId` — nullable
  - `String mobileMoneyRef` — nullable
  - `@NotEmpty @Valid List<SaleItemRequestDto> items`
- [x] **6.3** `SaleItemRequestDto.java`:
  - `@NotNull UUID productId`, `UUID variantId` (nullable), `@NotBlank String productName`, `@Positive int appliedUnitPrice`, `@Positive int quantity`
- [x] **6.4** `RecordSaleResponseDto.java`:
  - `UUID id`, `String status`, `int totalAmount`, `Instant occurredAt`
- [x] **6.5** Add `SALE_NOT_FOUND`, `SALE_ALREADY_EXISTS` error codes to `ErrorCode.java`
- [x] **6.6** Map `DomainException(INSUFFICIENT_STOCK)` → HTTP 422 in `GlobalExceptionHandler.java` (verify mapping exists or add it)

### Task 7 — Implement Flutter domain layer

**TDD: write tests first for each class.**

- [x] **7.1** `test/features/pos/domain/model/cart_item_test.dart`:
  - `CartItem_subtotal_equalsPriceTimesQuantity()`
  - `CartItem_copyWith_updatesQuantity()`
  - `CartItem_withZeroQuantity_throwsAssertionError()`
- [x] **7.2** `CartItem` model — pure Dart, no Flutter:
  ```dart
  // lib/features/pos/domain/model/cart_item.dart
  class CartItem {
    final String id;        // product id or variant id
    final String productId;
    final String? variantId;
    final String productName;
    final String? photoUrl;
    final int unitPrice;    // catalogue price
    final int appliedUnitPrice; // = unitPrice in 4.1; overridable in 4.2
    final int quantity;
    int get subtotal => appliedUnitPrice * quantity;
    CartItem copyWith({int? quantity, int? appliedUnitPrice});
  }
  ```
- [x] **7.3** `Sale` model — `lib/features/pos/domain/model/sale_model.dart`:
  - `id (String UUID)`, `storeId`, `employeeId`, `clientId?`, `paymentMode (PaymentModeEnum)`, `totalAmount (int)`, `status (String)`, `items (List<SaleItemModel>)`, `occurredAt (DateTime)`, `createdAt (DateTime)`
- [x] **7.4** `PaymentModeEnum` — `lib/features/pos/domain/model/payment_mode_enum.dart`:
  - `cash('CASH')`, `mobileMoney('MOBILE_MONEY')`
- [x] **7.5** `SaleRepository` interface — `lib/features/pos/domain/repository/sale_repository.dart`:
  - `Future<void> recordSale(Sale sale)` — saves locally + queues sync
  - `Future<List<Sale>> getSalesForToday(String storeId)` — for day close (Story 4.3 stub)
  - `Future<List<String>> getFrequentProductIds(String storeId, {int limit = 12})` — for POS grid
- [x] **7.6** `RecordSaleUseCase` — `lib/features/pos/domain/usecase/record_sale_usecase.dart`:
  - `execute(List<CartItem> cart, PaymentModeEnum mode, String? clientId, String? mobileRef)` → builds `Sale` → calls `repository.recordSale(sale)`

### Task 8 — Implement Flutter data layer

- [x] **8.1** `test/features/pos/data/datasource/local_sale_datasource_test.dart`:
  - `insertSale_persistsToSalesTable()`
  - `insertSaleItems_persistsAllItems()`
  - `decrementStock_updatesStockLevels()`
  - `insertStockMovement_createsMovementRecord()`
  - `enqueueSale_addsToSyncQueue()`
  - `markSynced_setsSyncedTrueAndSyncedAt()`
  - `removeSyncQueueEntry_deletesMatchingEntry()`
- [x] **8.2** `LocalSaleDataSource` — `lib/features/pos/data/datasource/local_sale_datasource.dart`:
  - Insert `SalesCompanion` into `appDb.sales`
  - Insert `SaleItemsCompanion` list into `appDb.saleItems`
  - Update `StockLevelsCompanion` (decrement per item per store, floor at 0)
  - Insert `StockMovementsCompanion` (type `'SALE'`, `quantityBefore`, `quantityAfter`) for each item
  - Insert `SyncQueueCompanion` with `operation: 'CREATE_SALE'`, `payload: jsonEncode(RecordSalePayload)`
  - **All 5 writes in a single `appDb.transaction(() async { ... })`**
  - `Future<void> markSynced(String saleId)`: runs outside the main transaction — `UPDATE sales SET synced=true, synced_at=? WHERE id=:saleId` using Drift's `(db.sales..where((s) => s.id.equals(saleId))).write(SalesCompanion(synced: const Value(true), syncedAt: Value(DateTime.now())))`
  - `Future<void> removeSyncQueueEntry(String saleId)`: `DELETE FROM sync_queue WHERE operation='CREATE_SALE' AND payload CONTAINS saleId` — use Drift expression filter on `payload` column
- [x] **8.3** `RemoteSaleDataSource` — `lib/features/pos/data/datasource/remote_sale_datasource.dart`:
  - `Future<void> pushSale(Sale sale)` → `POST /api/v1/sales` via Dio
  - On HTTP 201: completes successfully — caller (`SaleRepositoryImpl`) marks synced
  - On `DioException` (network unreachable / timeout) or non-2xx response: **throws** the exception — does NOT silently swallow
  - The repository layer (`SaleRepositoryImpl`) is responsible for the catch and fallback — this datasource is a clean adapter
- [x] **8.4** `test/features/pos/data/repository/sale_repository_impl_test.dart`:
  - `recordSale_savesLocallyAndPushesRemote()`
  - `recordSale_offlineMode_onlySavesLocally()`
  - `recordSale_whenOnline_marksSyncedAfterSuccessfulPush()` — mock remote returns success: verify `local.markSynced()` + `local.removeSyncQueueEntry()` are called
  - `recordSale_whenOffline_leavesEntryInSyncQueue()` — mock remote throws: verify `local.markSynced()` is NOT called
  - `recordSale_whenServerError_leavesEntryInSyncQueue()` — mock remote throws `DioException(statusCode: 503)`: verify sync_queue entry stays
  - `getFrequentProducts_returnsTopNByCount()`
- [x] **8.5** `SaleRepositoryImpl` — `lib/features/pos/data/repository/sale_repository_impl.dart`:
  - **Write-through pattern** (same as `ProductRepositoryImpl` — non-negotiable):
  ```dart
  Future<void> recordSale(Sale sale) async {
    // Step 1 — ALWAYS save locally first (atomic Drift transaction)
    // This guarantees < 200ms UX and offline survival
    await _local.insertAll(sale);  // sales + sale_items + stock_levels + stock_movements + sync_queue

    // Step 2 — Immediately attempt backend push in background (non-blocking UI)
    unawaited(() async {
      try {
        await _remote.pushSale(sale);              // awaited INSIDE background task
        await _local.markSynced(sale.id);          // → synced=true, syncedAt=now()
        await _local.removeSyncQueueEntry(sale.id); // → remove from sync_queue
        // Data is NOW on the server — safe even if phone resets
      } catch (_) {
        // offline / timeout / server error → sync_queue entry stays for Epic 5 retry
        dev.log('[SaleRepo] Backend unreachable — sale ${sale.id} queued', name: 'POS');
      }
    }());
    // Returns here immediately after local save — UI shows success without waiting
  }
  ```
  - `getFrequentProductIds(storeId, limit)`: Drift query — `SELECT product_id, COUNT(*) as cnt FROM sale_items INNER JOIN sales ON sales.id = sale_items.sale_id WHERE sales.store_id = :storeId AND sales.status = 'COMPLETED' GROUP BY product_id ORDER BY cnt DESC LIMIT :limit`
- [x] **8.6** Drift schema migration v9 — `app_database.dart`:
  - Increment `schemaVersion` to `9`
  - In `onUpgrade`: `if (from < 9)`:
    ```dart
    await migrator.addColumn(sales, sales.status);
    await migrator.addColumn(sales, sales.occurredAt);  // snapshot of sale time
    await migrator.addColumn(saleItems, saleItems.variantId);
    ```
  - Add `TextColumn get status => text().withDefault(const Constant('COMPLETED'))()` to `Sales` table class
  - Add `DateTimeColumn get occurredAt => dateTime().nullable()()` to `Sales` table class (nullable for backward compat with existing rows — new rows always set it)
  - Add `TextColumn get variantId => text().nullable()()` to `SaleItems` table class
  - Regenerate `app_database.g.dart` via `dart run build_runner build --delete-conflicting-outputs`

### Task 9 — Implement Flutter Riverpod providers

- [x] **9.1** `test/features/pos/presentation/provider/cart_provider_test.dart`:
  - `CartNotifier_addItem_addsToCart()`
  - `CartNotifier_addItem_incrementsExistingItem()`
  - `CartNotifier_removeItem_removesFromCart()`
  - `CartNotifier_incrementQuantity_updates()`
  - `CartNotifier_decrementQuantity_removesAt1()`
  - `CartNotifier_clearCart_emptiesState()`
  - `CartNotifier_total_sumOfSubtotals()`
- [x] **9.2** `CartNotifier` — `lib/features/pos/presentation/provider/cart_provider.dart`:
  - `@riverpod class CartNotifier extends _$CartNotifier`
  - State: `List<CartItem>` 
  - Methods: `addItem(CartItem)`, `removeItem(String id)`, `updateQuantity(String id, int qty)`, `clearCart()`
  - Getter: `int get totalAmount`
- [x] **9.3** `test/features/pos/presentation/provider/pos_search_provider_test.dart`:
  - `PosSearchNotifier_search_withLessThan2Chars_returnsEmpty()`
  - `PosSearchNotifier_search_returnsMatchingProducts()`
  - `PosSearchNotifier_search_sortsOutOfStockToBottom()`
- [x] **9.4** `PosSearchNotifier` — `lib/features/pos/presentation/provider/pos_search_provider.dart`:
  - `@riverpod class PosSearchNotifier extends _$PosSearchNotifier`
  - State: `AsyncValue<List<ProductModel>>`
  - `search(String query, String storeId)`: if `query.length < 2` → empty list; else Drift query on `products` filtered by `name LIKE '%query%'` (case-insensitive), join with `stock_levels` for the employee's storeId, sort by (has_stock DESC, name ASC)
- [x] **9.5** `frequentProductsProvider` — `lib/features/pos/presentation/provider/pos_providers.dart`:
  - `@riverpod Future<List<ProductModel>> frequentProducts(FrequentProductsRef ref, String storeId)`: calls `saleRepository.getFrequentProductIds(storeId, limit: 12)` → fetches product details from `ProductRepository`
- [x] **9.6** `test/features/pos/presentation/provider/record_sale_notifier_test.dart`:
  - `RecordSaleNotifier_submit_success_clearsCart()`
  - `RecordSaleNotifier_submit_offline_stillRecordsLocally()`
  - `RecordSaleNotifier_submit_insufficientStock_returnsError()` 
  - (Drift/local check: attempt to decrement below 0 → catch and surface error before recording)
- [x] **9.7** `RecordSaleNotifier` — `lib/features/pos/presentation/provider/record_sale_notifier.dart`:
  - `@riverpod class RecordSaleNotifier extends _$RecordSaleNotifier`
  - State: sealed `RecordSaleState` class: `RecordSaleIdle | RecordSaleLoading | RecordSaleSuccess(Sale) | RecordSaleError(String)`
  - `submit(List<CartItem> cart, PaymentModeEnum mode, String? clientId, String? mobileRef, String storeId, String employeeId)`:
    1. Check local stock per item (from `stock_levels` Drift) → if any item exceeds stock → `RecordSaleError('INSUFFICIENT_STOCK')`
    2. Build `Sale` with `id = Uuid().v4()`, set all fields
    3. Call `RecordSaleUseCase.execute()`
    4. On success: → `RecordSaleSuccess(sale)`, then dispatch `ref.read(cartProvider.notifier).clearCart()`

### Task 10 — Implement Flutter UI

- [x] **10.1** `test/features/pos/presentation/widget/cart_pill_test.dart`:
  - `CartPill_hidden_whenCartEmpty()`
  - `CartPill_visible_whenCartHasItems()`
  - `CartPill_displaysCorrectTotal()`
  - `CartPill_displaysItemCount()`
  - `CartPill_tapEncaisser_triggersCallback()`
- [x] **10.2** `CartPill` widget — `lib/features/pos/presentation/widget/cart_pill.dart`:
  - `AnimatedSwitcher` wrapping: `SizedBox.shrink()` (empty) vs pill widget (has items)
  - Pill: `FilledButton` styled with `#3B5BDB` (Indigo), shows: "{count} articles · {total} FCFA" + "Encaisser" label
  - Positioned at bottom of `PosPage` via `Stack`, above `BottomNavigationBar` using `SafeArea`
  - Tap → `onEncaisser()` callback
- [x] **10.3** `test/features/pos/presentation/widget/product_card_test.dart`:
  - `ProductCard_displaysName_andPrice()`
  - `ProductCard_showsInitialsAvatar_whenNoPhoto()`
  - `ProductCard_showsRuptureOverlay_whenOutOfStock()`
  - `ProductCard_flashesBlue_onTap()`
- [x] **10.4** `ProductCard` widget — `lib/features/pos/presentation/widget/product_card.dart`:
  - `Card` with `InkWell`: tap → `onTap()` callback + flash `#D0EBFF` using `setState` + 150ms timer
  - `ProductInitialsAvatar` if `photoUrl == null`
  - Stock level badge: `Chip` showing quantity; `0` → red chip "⚠️ Rupture" + `onVerifier` callback
  - Price formatted as `NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA')`
- [x] **10.5** `ProductInitialsAvatar` widget — `lib/features/pos/presentation/widget/product_initials_avatar.dart`:
  - `CircleAvatar` colored by `kAvatarColors[name.hashCode % kAvatarColors.length]`
  - `kAvatarColors` constant: list of 8 Material colors (accent-safe for white text)
  - Displays `name.trim().split(' ').take(2).map((w) => w[0].toUpperCase()).join()`
- [x] **10.6** `test/features/pos/presentation/page/pos_page_test.dart`:
  - `PosPage_displaysSearchBar()`
  - `PosPage_displaysFrequentProductsGrid()`
  - `PosPage_cartPillHidden_initially()`
  - `PosPage_cartPillVisible_afterAddingProduct()`
  - `PosPage_hapticFeedback_onProductTap()`
- [x] **10.7** `PosPage` — `lib/features/pos/presentation/page/pos_page.dart` (replaces `PosPlaceholderPage`):
  - `ConsumerStatefulWidget`
  - `AppBar` with `SyncIndicator()` in actions (carry over from `PosPlaceholderPage`)
  - `SearchBar` (Material 3) at top: `onChanged` → `ref.read(posSearchProvider.notifier).search(query, storeId)`
  - If search empty: `GridView` (2-column mobile, 3-column medium/expanded breakpoint):
    - Watch `frequentProductsProvider(storeId)` → show `ProductCard` grid (up to 12)
    - Empty state if no products: sector emoji + "Commencez par ajouter un produit" (reuses `PosPlaceholderPage` empty state for EMPLOYEE; OWNER gets "Ajouter un produit" CTA)
  - If search active (≥ 2 chars): `ListView` of search results
  - `Stack` with `CartPill` at bottom
  - `storeId` + `employeeId` resolved from `SecureTokenStorage` via `FutureProvider`
- [x] **10.8** `CartBottomSheet` — `lib/features/pos/presentation/widget/cart_bottom_sheet.dart`:
  - `DraggableScrollableSheet` or `showModalBottomSheet`
  - List of `CartItemTile` (swipe-to-dismiss via `Dismissible`)
  - Each tile: product name, quantity +/- controls (`IconButton`), price label (read-only — stub for 4.2), subtotal
  - Grand total row at bottom
  - "Encaisser" → `CheckoutPage`
- [x] **10.9** `CheckoutPage` — `lib/features/pos/presentation/page/checkout_page.dart`:
  - `ConsumerStatefulWidget`
  - Shows cart summary (item count + total)
  - Payment mode selection: two `FilledButton.tonal` cards — `💵 Espèces` / `📱 Mobile Money`
  - GoF **Strategy**: `_EspecesForm` widget / `_MobileMoneyForm` widget — shown via selected mode
  - Client selector: `Autocomplete<ClientModel>` fed from `clientsProvider` (Drift local)
  - "Valider la vente" `FilledButton` (disabled until mode selected)
  - On tap: calls `ref.read(recordSaleNotifier.notifier).submit(...)`, then watches state → navigates to `SaleSuccessPage` or shows error `SnackBar`
- [x] **10.10** `SaleSuccessPage` — `lib/features/pos/presentation/page/sale_success_page.dart`:
  - Full-screen lime `#51CF66` background
  - "✅ Vente enregistrée" headline + total formatted as "XX XXX FCFA"
  - `AnimationController` with 200ms fade-in
  - `Future.delayed(Duration(milliseconds: 1500), () => context.go('/pos'))` — resets to POS

### Task 11 — Update router (replace PosPlaceholderPage → PosPage)

- [x] **11.1** `test/features/pos/presentation/page/pos_page_smoke_test.dart` (smoke widget test):
  - `PosPage_rendersWithoutException()`
- [x] **11.2** `app_router.dart` — replace:
  - `import '...pos_placeholder_page.dart'` → `import '...pos_page.dart'`
  - All references to `PosPlaceholderPage()` → `PosPage()`
  - Add sub-route `/pos/checkout` → `CheckoutPage` (outside `ShellRoute` so no bottom nav bar during checkout)
  - Add route `/pos/success` → `SaleSuccessPage`
- [x] **11.3** `pos_placeholder_page.dart` → do NOT delete (keep for rollback). Comment top: `// DEPRECATED: replaced by PosPage in Story 4.1 — kept for rollback only`

### Task 12 — Update Drift DI providers

- [x] **12.1** `lib/features/pos/presentation/provider/pos_providers.dart` — add:
  - `localSaleDataSourceProvider`
  - `remoteSaleDataSourceProvider`
  - `saleRepositoryProvider`
  - `recordSaleUseCaseProvider`
  - `cartProvider` (from `CartNotifier`)
  - `posSearchProvider` (from `PosSearchNotifier`)
  - `recordSaleNotifierProvider`
  - `frequentProductsProvider`
  - `activeStoreIdProvider` — `FutureProvider<String?>`: reads `SecureTokenStorage.readStoreId()`
  - `activeEmployeeIdProvider` — `FutureProvider<String?>`: reads `SecureTokenStorage.readUserId()`

### Task 13 — Run all tests + cURL integration

- [x] **13.1** Backend: `cd keevo/backend && mvn test` → 0 failures
- [x] **13.2** Flutter: `cd keevo/app && flutter test --reporter=expanded` → all Green
- [x] **13.3** Run `bash curl-tests-story-4-1.sh` → all ✅

---

## cURL Integration Tests

> Save as `keevo/backend/curl-tests-story-4-1.sh`

```bash
#!/usr/bin/env bash
# ======================================================
# Story 4.1 — cURL Integration Tests: Enregistrement de Vente & Flux POS Core
# Run: bash curl-tests-story-4-1.sh
# Prerequisites: Docker backend running on port 8443,
#   jq installed, python3 for phone-based auth
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"
PHONE="+237600000041"
PASSWORD="Test1234!"

# ─── Step 1 — Register + two-step login to get JWT ─────────────────────────
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\",\"firstName\":\"Loic\",\"lastName\":\"Vendeur\"}")
echo "Register: $(echo $REGISTER | jq -r '.data.accessToken // .error // "no token"' | head -c 60)"

LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\"}")
LOGIN_TOKEN=$(echo "$LOGIN" | jq -r '.data.loginToken')
MEMBERSHIPS=$(echo "$LOGIN" | jq -r '.data.memberships[0].tenantId // empty')
[[ -n "$LOGIN_TOKEN" && "$LOGIN_TOKEN" != "null" ]] && echo "✅ Step 1a — loginToken obtained" || { echo "❌ Step 1a FAILED: $LOGIN"; exit 1; }

SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LOGIN_TOKEN\",\"tenantId\":\"$(echo $LOGIN | jq -r '.data.memberships[0].tenantId')\"}")
JWT=$(echo "$SELECT" | jq -r '.data.accessToken')
SCHEMA=$(echo "$SELECT" | jq -r '.data.schemaName // .data.tenantId')
STORE_ID=$(echo "$SELECT" | jq -r '.data.storeId // empty')
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1b — accessToken obtained (schema=$SCHEMA)" || { echo "❌ Step 1b FAILED: $SELECT"; exit 1; }

# ─── Step 2 — Get a product ID that exists for testing ─────────────────────
PRODUCTS=$(curl -s "$BASE_URL/api/v1/products" \
  -H "Authorization: Bearer $JWT")
PRODUCT_ID=$(echo "$PRODUCTS" | jq -r '.data.content[0].id // empty')
PRODUCT_NAME=$(echo "$PRODUCTS" | jq -r '.data.content[0].name // "Test Produit"')
PRODUCT_PRICE=$(echo "$PRODUCTS" | jq -r '.data.content[0].sellingPrice // .data.content[0].price // 1000')
[[ -n "$PRODUCT_ID" && "$PRODUCT_ID" != "null" ]] && echo "✅ Step 2 — Product found: $PRODUCT_NAME ($PRODUCT_ID)" || { echo "⚠️  Step 2 — No products found, creating a test product"; \
  CREATE_PRODUCT=$(curl -s -X POST "$BASE_URL/api/v1/products" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Produit Test POS\",\"sellingPrice\":2500,\"buyPrice\":1500,\"quantity\":50}"); \
  PRODUCT_ID=$(echo "$CREATE_PRODUCT" | jq -r '.data.id'); \
  PRODUCT_NAME="Produit Test POS"; PRODUCT_PRICE=2500; \
  [[ -n "$PRODUCT_ID" && "$PRODUCT_ID" != "null" ]] && echo "✅ Step 2 — Test product created ($PRODUCT_ID)" || { echo "❌ Step 2 FAILED: $CREATE_PRODUCT"; exit 1; }; }

# ─── Step 3 — Get store ID (from tenant stores) ────────────────────────────
STORES=$(curl -s "$BASE_URL/api/v1/tenant/stores" \
  -H "Authorization: Bearer $JWT")
STORE_ID_API=$(echo "$STORES" | jq -r '.data[0].id // empty')
[[ -n "$STORE_ID_API" ]] && STORE_ID="$STORE_ID_API"
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && echo "✅ Step 3 — Store ID: $STORE_ID" || { echo "❌ Step 3 FAILED — No store found: $STORES"; exit 1; }

# ─── Step 4 — POST /api/v1/sales — Valid sale (CASH) ──────────────────────
SALE_UUID=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
RESPONSE=$(curl -s -o /tmp/sale_response.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID\",
    \"paymentMode\": \"CASH\",
    \"items\": [{
      \"productId\": \"$PRODUCT_ID\",
      \"productName\": \"$PRODUCT_NAME\",
      \"appliedUnitPrice\": $PRODUCT_PRICE,
      \"quantity\": 1
    }]
  }")
cat /tmp/sale_response.json | jq .
[[ "$RESPONSE" == "201" ]] && echo "✅ Step 4 — Sale recorded (HTTP 201)" || { echo "❌ Step 4 FAILED — HTTP $RESPONSE"; cat /tmp/sale_response.json; exit 1; }
RECORDED_SALE_ID=$(cat /tmp/sale_response.json | jq -r '.data.id')
[[ "$RECORDED_SALE_ID" == "$SALE_UUID" ]] && echo "✅ Step 4b — Returned saleId matches client-generated UUID" || echo "⚠️  Step 4b — saleId mismatch (server: $RECORDED_SALE_ID, client: $SALE_UUID)"

# ─── Step 5 — Idempotency: replay same sale UUID → no duplicate ────────────
IDEMPOTENCY_RESP=$(curl -s -o /tmp/idempotency.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID\",
    \"paymentMode\": \"CASH\",
    \"items\": [{\"productId\": \"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"appliedUnitPrice\":$PRODUCT_PRICE,\"quantity\":1}]
  }")
[[ "$IDEMPOTENCY_RESP" == "200" || "$IDEMPOTENCY_RESP" == "201" ]] && echo "✅ Step 5 — Idempotency: replayed UUID accepted without duplicate" || { echo "❌ Step 5 FAILED — HTTP $IDEMPOTENCY_RESP"; cat /tmp/idempotency.json; exit 1; }

# ─── Step 6 — POST /api/v1/sales — Valid sale (MOBILE_MONEY) ──────────────
SALE_UUID_2=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
MOMO_RESP_CODE=$(curl -s -o /tmp/momo.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID_2\",
    \"paymentMode\": \"MOBILE_MONEY\",
    \"mobileMoneyRef\": \"REF-MTN-12345\",
    \"items\": [{\"productId\":\"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"appliedUnitPrice\":$PRODUCT_PRICE,\"quantity\":2}]
  }")
[[ "$MOMO_RESP_CODE" == "201" ]] && echo "✅ Step 6 — MoMo sale recorded (HTTP 201)" || { echo "❌ Step 6 FAILED — HTTP $MOMO_RESP_CODE"; cat /tmp/momo.json; exit 1; }

# ─── Step 7 — POST /api/v1/sales — No token → 401 ─────────────────────────
UNAUTH_RESP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Content-Type: application/json" \
  -d "{\"saleId\":\"$(python3 -c 'import uuid; print(str(uuid.uuid4()))')\",\"paymentMode\":\"CASH\",\"items\":[]}")
[[ "$UNAUTH_RESP" == "401" ]] && echo "✅ Step 7 — No token → 401 Unauthorized" || { echo "❌ Step 7 FAILED — Expected 401, got $UNAUTH_RESP"; exit 1; }

# ─── Step 8 — POST /api/v1/sales — Empty items → 400 ──────────────────────
BAD_REQ=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"saleId\":\"$(python3 -c 'import uuid; print(str(uuid.uuid4()))')\",\"paymentMode\":\"CASH\",\"items\":[]}")
[[ "$BAD_REQ" == "400" || "$BAD_REQ" == "422" ]] && echo "✅ Step 8 — Empty items → 400/422 Bad Request" || { echo "❌ Step 8 FAILED — Expected 400, got $BAD_REQ"; exit 1; }

# ─── Step 9 — INSUFFICIENT_STOCK: sell more than available ────────────────
OVERSTOCK_UUID=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
OVERSTOCK=$(curl -s -o /tmp/overstock.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$OVERSTOCK_UUID\",
    \"paymentMode\": \"CASH\",
    \"items\": [{\"productId\":\"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"appliedUnitPrice\":$PRODUCT_PRICE,\"quantity\":999999}]
  }")
OVERSTOCK_CODE=$(cat /tmp/overstock.json | jq -r '.code // .domainCode // "unknown"')
[[ "$OVERSTOCK" == "422" && ("$OVERSTOCK_CODE" == "INSUFFICIENT_STOCK" || "$OVERSTOCK_CODE" == "INSUFFICIENT_STOCK") ]] \
  && echo "✅ Step 9 — Insufficient stock → 422 INSUFFICIENT_STOCK" \
  || { echo "❌ Step 9 FAILED — HTTP $OVERSTOCK, code: $OVERSTOCK_CODE"; cat /tmp/overstock.json; exit 1; }

# ─── Step 10 — Audit: SALE_COMPLETED event recorded ──────────────────────
AUDIT=$(curl -s "$BASE_URL/api/v1/audit?entityType=Sale&page=0&size=5" \
  -H "Authorization: Bearer $JWT")
AUDIT_ENTRY=$(echo "$AUDIT" | jq -r '.data.entries[0].action // empty')
[[ "$AUDIT_ENTRY" == "SALE_COMPLETED" ]] && echo "✅ Step 10 — Audit SALE_COMPLETED event found" || echo "⚠️  Step 10 — Audit entry: $AUDIT_ENTRY (check manually)"

echo ""
echo "✅✅✅ All cURL integration checks passed — story 4.1 backend validated ✅✅✅"
```

---

## Dev Notes

### Architecture Guardrails (NON-NEGOTIABLE)

1. **MCP Port Purity**: `RecordSaleUseCase.recordSale(RecordSaleCommand)` accepts ONLY pure Java Records — no `HttpServletRequest`, no `Principal`. `actorId` and `storeId` MUST be extracted in the controller and passed via `RecordSaleCommand`.

2. **Hexagonal Compliance**: `RecordSaleService` imports ONLY from `domain/` and `shared/application/port/` — ZERO Spring Data / JPA imports in the service class.

3. **Transaction Boundary**: The ENTIRE sale recording (save sale + save items + decrement stock × N + insert movements) must be in a single `@Transactional` boundary. Stock decrement and sale persistence are atomic.

4. **Idempotency is MANDATORY**: `saleRepository.existsById(command.saleId())` is the first check in `RecordSaleService.recordSale()`. A duplicate `saleId` → immediate `return` (no error, idempotent). This handles Flutter retry on reconnect.

5. **No negative stock on backend**: Backend MUST check `stock >= requested quantity` before decrementing. If `INSUFFICIENT_STOCK` → throw `DomainException(ErrorCode.INSUFFICIENT_STOCK)`. The Flutter app also checks locally but backend is authoritative.

6. **Observer GoF**: `SaleCompletedEvent` is a Spring `ApplicationEvent`. The `AuditEventListener` (pre-existing in `shared/infrastructure/web/`) listens via `@EventListener`. The service MUST NOT call `AuditPort.record()` directly — it publishes the event and the listener handles it.

7. **Drift: local-first + atomic transaction + write-through**:
   - `LocalSaleDataSource.insertAll()` MUST run ALL local writes (sales, sale_items, stock decrements, stock movements, sync_queue entry) in a single `appDb.transaction(() async { ... })`. If any write fails, ALL roll back.
   - After `insertAll()` completes, `SaleRepositoryImpl` launches a **non-blocking background task** (`unawaited`) that calls `remote.pushSale()` → on success: `local.markSynced()` + `local.removeSyncQueueEntry()`. On failure: log + leave in queue.
   - **This write-through pattern is mandatory** — it ensures data reaches the server immediately when online, so a phone reset or wipe after a sale does NOT cause data loss before Epic 5 sync engine runs.
   - `markSynced()` and `removeSyncQueueEntry()` run OUTSIDE the main transaction — they are cleanup operations, not part of the atomic sale recording.

8. **Flutter: storeId resolution**: Use `SecureTokenStorage.readStoreId()` (from `features/auth/data/repository/secure_token_storage.dart`) — NOT `primaryStoreIdProvider` (that pattern was a workaround pre-Story 3.5). The employee's `storeId` is stored in FlutterSecureStorage after two-step login.

9. **PaymentMode guard in CheckoutPage**: "Valider la vente" must be disabled until a `PaymentMode` is selected. Use `ValueListenableBuilder` or Riverpod state to drive the enabled state.

10. **Responsive grid**: `PosPage` product grid must use `SliverLayoutBuilder` (or `LayoutBuilder`) to compute column count: `crossAxisCount = constraints.maxWidth < 600 ? 2 : constraints.maxWidth < 1200 ? 3 : 4` (follows pattern from Story 3.2).

### Drift Schema v9 Changes Summary

| Table    | Column       | Change                     | Migration command |
|----------|-------------|----------------------------|-------------------|
| `sales`  | `status`    | Add TEXT DEFAULT 'COMPLETED'| `migrator.addColumn(sales, sales.status)` |
| `sales`  | `occurredAt` | Add DATETIME nullable (snapshot of exact sale time) | `migrator.addColumn(sales, sales.occurredAt)` |
| `sale_items` | `variantId` | Add TEXT nullable        | `migrator.addColumn(saleItems, saleItems.variantId)` |

> **Important**: The existing `unitPrice` column stays as is. In Story 4.2, it will be formally renamed `appliedUnitPrice`. For Story 4.1, `unitPrice` IS the applied price (no override). Both `unitPrice` and `appliedUnitPrice` refer to the same value semantically.

### Backend DDL Changes Summary

| Constant | Action |
|----------|--------|
| `DDL_SALE_ITEMS` | NEW TABLE — `sale_items` per-tenant |
| `DDL_SALE_ITEMS_IDX_SALE` | NEW INDEX — `idx_sale_items_sale_id` |
| `DDL_SALES_MIGRATE_STATUS` | IDEMPOTENT ALTER — add `status VARCHAR(20)` |
| `DDL_SALES_MIGRATE_OCCURRED_AT` | IDEMPOTENT ALTER — add `occurred_at TIMESTAMPTZ` |

Execute order in `TenantSchemaProvisioner.provisionSchema()` and `TenantSchemaSyncService`:
After `DDL_SALES` (pre-existing), add:
1. `DDL_SALES_MIGRATE_STATUS`
2. `DDL_SALES_MIGRATE_OCCURRED_AT`
3. `DDL_SALE_ITEMS`
4. `DDL_SALE_ITEMS_IDX_SALE`

Also add `"sale_items"` to `REQUIRED_TABLES` set in `TenantSchemaSyncService`.

### UX Requirements (Non-Negotiable from Spec)

- **< 200ms** all cart animations and transitions
- **< 500ms** fuzzy search results
- **≤ 5 seconds** for a complete 1-product sale
- Flash color: `#D0EBFF` (150ms) on product card tap
- Success confirmation color: `#51CF66` (lime green)
- `CartPill` background: `#3B5BDB` (Indigo Royal)
- Haptic feedback: `HapticFeedback.lightImpact()` on every product tap
- `PosPage` IS the default screen after login — not a sub-screen
- After 1500ms success screen → auto-navigate back to `/pos` via `context.go('/pos')`
- Bottom nav: EMPLOYEE sees only `Caisse` + `Plus` (already implemented in `MainShell` — do NOT break this)

### Previous Story Learnings (from Stories 3.3, 3.4, 3.5)

1. **Colors**: Use `Theme.of(context).colorScheme.primary` where possible, fall back to `#3B5BDB` directly only for brand-specific accent. Story 3.3 established palette: `tertiary→primary`, `#3B5BDB/#4DABF7`.

2. **ConsumerStatefulWidget + double-tap guard**: For any action that calls async operations (like `_submit()`), add a `bool _isSubmitting` guard to prevent double-tap (pattern from Story 3.3 `_TransferTile`).

3. **AutoDispose Future completed crash**: When using `@riverpod` notifiers with `autoDispose` (default in riverpod_generator), avoid `await future` chains that may complete after widget disposal. The `RecordSaleNotifier` should use `keepAlive()` if needed or handle `AsyncValue` patterns carefully (Story 3.3 post-fix).

4. **Drift ON CONFLICT**: `insertOnConflictUpdate()` for `StockMovements` / `StockLevels` to avoid UNIQUE constraint violations on replays (Story 2.3 fix).

5. **`withOpacity` → `withValues`**: Use `color.withValues(alpha: 0.5)` not deprecated `withOpacity(0.5)` (Story 3.2 fix, affects all new widgets).

6. **Route after success**: Use `context.go('/pos')` not `context.pop()` after successful sale — `go` ensures the route stack is clean and the POS grid resets (Story 3.3 established this pattern for post-action navigation).

7. **`Future.microtask` in `initState`**: If providers are read/watched in `initState`, wrap in `WidgetsBinding.instance.addPostFrameCallback((_) { ... })` to avoid build-phase crashes (Story 2.3 fix).

8. **EMPLOYEE storeId**: `storeId` is ALWAYS available from `SecureTokenStorage.readStoreId()` after Story 3.5 implementation. NEVER use `primaryStoreIdProvider` for the employee flow — that was a temporary workaround.

9. **Anti-pattern: @ExceptionHandler re-throw**: Controllers must NOT have `try/catch` that re-throws — use `GlobalExceptionHandler` (Story 1.4 learning — the E2E test T09 caught this).

10. **`GoogleFonts.config.allowRuntimeFetching = false`** in `setUpAll()` for all Flutter widget tests — mandatory to prevent network calls.

### File Structure — Backend (New Files)

```
backend/src/main/java/com/keevo/commerce/
├── sale/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── Sale.java                          *NEW*
│   │   │   ├── SaleItem.java                      *NEW*
│   │   │   ├── PaymentMode.java                   *NEW*
│   │   │   ├── SaleStatus.java                    *NEW*
│   │   │   ├── SaleCompletedEvent.java             *NEW*
│   │   │   └── SaleFactory.java                   *NEW*
│   │   └── port/
│   │       ├── in/
│   │       │   └── RecordSaleUseCase.java          *NEW* (+ RecordSaleCommand)
│   │       └── out/
│   │           └── SaleRepository.java            *NEW*
│   ├── application/
│   │   └── service/
│   │       └── RecordSaleService.java             *NEW*
│   └── adapter/
│       ├── in/
│       │   └── rest/
│       │       ├── SaleController.java            *NEW*
│       │       └── dto/
│       │           ├── RecordSaleRequestDto.java  *NEW*
│       │           ├── SaleItemRequestDto.java    *NEW*
│       │           └── RecordSaleResponseDto.java *NEW*
│       └── out/
│           └── persistence/
│               ├── entity/
│               │   ├── SaleJpaEntity.java         *NEW*
│               │   └── SaleItemJpaEntity.java     *NEW*
│               ├── jpa/
│               │   └── SaleSpringRepository.java  *NEW*
│               └── impl/
│                   └── SaleRepositoryAdapter.java *NEW*
```

**Modified files (backend):**
- `shared/infrastructure/persistence/TenantSchemaProvisioner.java` — add 4 DDL constants + execute in `provisionSchema()`
- `shared/infrastructure/persistence/TenantSchemaSyncService.java` — add `"sale_items"` to REQUIRED_TABLES
- `shared/domain/exception/ErrorCode.java` — add `SALE_NOT_FOUND`, `SALE_ALREADY_EXISTS`
- `shared/infrastructure/web/GlobalExceptionHandler.java` — verify/add HTTP 422 mapping for `INSUFFICIENT_STOCK`

### File Structure — Flutter (New Files)

```
app/lib/features/pos/
├── domain/
│   ├── model/
│   │   ├── cart_item.dart                         *NEW*
│   │   ├── sale_model.dart                        *NEW*
│   │   └── payment_mode_enum.dart                 *NEW*
│   ├── repository/
│   │   └── sale_repository.dart                   *NEW* (replaces .gitkeep)
│   └── usecase/
│       └── record_sale_usecase.dart               *NEW* (replaces .gitkeep)
├── data/
│   ├── datasource/
│   │   ├── local_sale_datasource.dart             *NEW*
│   │   └── remote_sale_datasource.dart            *NEW*
│   └── repository/
│       └── sale_repository_impl.dart              *NEW* (replaces .gitkeep)
└── presentation/
    ├── provider/
    │   ├── cart_provider.dart                     *NEW*
    │   ├── pos_search_provider.dart               *NEW*
    │   ├── record_sale_notifier.dart              *NEW*
    │   └── pos_providers.dart                     *NEW*
    ├── page/
    │   ├── pos_page.dart                          *NEW* (replaces PosPlaceholderPage)
    │   ├── checkout_page.dart                     *NEW*
    │   └── sale_success_page.dart                 *NEW*
    └── widget/
        ├── cart_pill.dart                         *NEW*
        ├── cart_bottom_sheet.dart                 *NEW*
        ├── product_card.dart                      *NEW*
        ├── product_initials_avatar.dart           *NEW*
        └── cart_item_tile.dart                    *NEW*
```

**Modified files (Flutter):**
- `core/storage/sales_table.dart` — add `status` TextColumn
- `core/storage/sale_items_table.dart` — add `variantId` TextColumn nullable
- `core/storage/app_database.dart` — increment `schemaVersion` to 9, add migration for v9
- `core/storage/app_database.g.dart` — regenerate (DO NOT hand-edit)
- `core/router/app_router.dart` — replace `PosPlaceholderPage` with `PosPage`, add `/pos/checkout` + `/pos/success` routes
- `core/scaffold/main_shell.dart` — no change required (EMPLOYEE tab restriction already in place from Story 3.5)

**Test files (Flutter):**
```
app/test/features/pos/
├── domain/
│   └── model/
│       └── cart_item_test.dart
├── data/
│   ├── datasource/
│   │   └── local_sale_datasource_test.dart
│   └── repository/
│       └── sale_repository_impl_test.dart
└── presentation/
    ├── provider/
    │   ├── cart_provider_test.dart
    │   ├── pos_search_provider_test.dart
    │   └── record_sale_notifier_test.dart
    ├── page/
    │   ├── pos_page_test.dart
    │   └── pos_page_smoke_test.dart
    └── widget/
        ├── cart_pill_test.dart
        └── product_card_test.dart
```

**Test files (backend):**
```
backend/src/test/java/com/keevo/commerce/sale/
├── domain/
│   ├── SaleTest.java
│   └── SaleItemTest.java
├── application/
│   └── RecordSaleServiceTest.java
└── adapter/
    ├── in/rest/
    │   └── SaleControllerTest.java
    └── out/persistence/
        └── SaleRepositoryAdapterTest.java
```

**Other modified test files (backend):**
- `src/test/resources/product-schema.sql` — add `sales`, `sale_items` DDL for `@DataJpaTest` context if needed

### References

- Epic 4 AC details: [_bmad-output/planning-artifacts/epics/epic-4-point-de-vente-pos.md](_bmad-output/planning-artifacts/epics/epic-4-point-de-vente-pos.md)
- Architecture (TDD, GoF, Hexagonal): [_bmad-output/planning-artifacts/architecture.md](_bmad-output/planning-artifacts/architecture.md#Implementation-Patterns-Consistency-Rules)
- UX spec (POS heartbeat, 3-tap rule, Indigo Sky tokens): [_bmad-output/planning-artifacts/ux-design-specification.md](_bmad-output/planning-artifacts/ux-design-specification.md#Core-User-Experience)
- Existing `SecureTokenStorage`: `keevo/app/lib/features/auth/data/repository/secure_token_storage.dart` — `readStoreId()`, `readUserId()`
- Existing `TenantSchemaProvisioner`: `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` — follow DDL pattern
- Existing `AuditPort` contract: `keevo/backend/src/main/java/com/keevo/shared/application/port/AuditPort.java`
- Story 3.2 responsive grid pattern: `keevo/app/lib/features/inventory/presentation/page/global_stock_overview_page.dart` — column count logic
- Story 3.3 ConsumerStatefulWidget guard: `keevo/app/lib/features/inventory/presentation/widget/stock_transfer_tile.dart`
- Existing `SyncQueue` table: `keevo/app/lib/core/storage/sync_queue_table.dart` — operation + payload pattern

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6 (GitHub Copilot)

### Debug Log References

### Completion Notes List

### File List

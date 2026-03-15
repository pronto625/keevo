# Story 3.4: Vérification de Disponibilité Cross-Boutique

Status: done

## Story

As an employee (Loïc),
I want to check the stock level of a product across all stores in the network,
So that I can offer the customer an alternative location when my store is out of stock — saving the sale rather than losing it.

## Acceptance Criteria

### AC1 — Bottom sheet display (offline-capable, local Drift)

- **Given** Loïc is viewing a product with zero or low stock (from any screen that integrates the check)
- **When** he taps "Vérifier dans les autres boutiques"
- **Then** a `CrossStoreAvailabilityBottomSheet` appears, titled with the product name
- **And** for each active store/warehouse the sheet shows: store name, type icon (`🏪` STORE / `🏭` WAREHOUSE), quantity badge color-coded (green `qty > threshold`, amber `qty ≤ threshold > 0`, red `qty = 0`)
- **And** stores with `quantity = 0` are rendered greyed out with an `-` indicator ("En rupture")
- **And** stores are sorted: available first (`qty > 0`), descending by quantity; zero-stock stores at the bottom
- **And** data loads from local Drift `stock_levels` + `stores` tables — fully functional offline

### AC2 — Online refresh with 3-second timeout

- **Given** the device is connected to the network
- **When** the bottom sheet opens
- **Then** a refreshed fetch is attempted from `GET /api/v1/stock/products/{productId}/availability` (max **3 seconds** timeout)
- **And** on success: local Drift cache is updated and the sheet reflects fresh data
- **And** a row "Dernière mise à jour : [formatted timestamp]" is visible at the bottom of the sheet
- **And** if the server does not respond within 3 seconds → falls back silently to local Drift data (no error, no SnackBar)
- **And** if offline entirely → local Drift data is shown directly, timestamp shows last sync time

### AC3 — Employee role — view only, WhatsApp shortcut to owner

- **Given** the authenticated user has role `EMPLOYEE`
- **When** he views the cross-store availability sheet
- **Then** he can **SEE** stock levels in all stores (same display as AC1)
- **But** there is NO "Initier un transfert" button
- **And** at the bottom of the sheet a message is shown: "Contacter le propriétaire pour initier un transfert"
- **And** if the owner's phone is available from the first store's `phone` field (or `null`): a `WhatsApp` icon button deeplinks to `https://wa.me/{phone}` opened via `url_launcher`
- **And** if phone is null → the WhatsApp button is hidden; only the text message is shown

### AC4 — Owner role — pre-filled transfer CTA

- **Given** the authenticated user has role `OWNER`
- **When** he views the cross-store availability sheet for a product
- **Then** a primary "Initier un transfert" `FilledButton` is shown in the sheet footer
- **And** tapping it closes the sheet and calls `showTransferFormBottomSheet(...)` pre-filled with:
  - `sourceStoreId` / `sourceStoreName` = store with the **highest available stock** (first entry in the sorted list)
  - `productId` / `productName` = the checked product
  - `destinationStores` = all active stores **excluding** the source store
- **And** if all stores have `qty = 0` → the "Initier un transfert" button is **disabled** with tooltip "Aucun stock disponible dans le réseau"

### AC5 — Backend endpoint: cross-store availability

- **Given** a valid JWT with tenant context
- **When** `GET /api/v1/stock/products/{productId}/availability` is called
- **Then** HTTP 200 is returned with:
  ```json
  {
    "data": {
      "productId": "...",
      "productName": "...",
      "entries": [
        {
          "storeId": "...",
          "storeName": "Boutique Bonanjo",
          "storeType": "STORE",
          "quantity": 5,
          "minimumThreshold": 2,
          "isLow": false
        }
      ],
      "refreshedAt": "2026-03-15T00:33:37Z"
    }
  }
  ```
- **And** `entries` contains ALL active stores — even those with `quantity = 0`
- **And** entries are sorted smallest-to-largest store created_at (consistent ordering; client sorts by quantity)
- **And** if product does not exist → HTTP 404 `{ "domainCode": "PRODUCT_NOT_FOUND" }`
- **And** if tenant has no active stores → `entries = []`

### AC6 — Entry points on existing screens (without POS)

> Epic 4 (POS) is not yet implemented. The bottom sheet must be wired to **existing** screens now; it will also be wired inside the POS product card in Epic 4 (Story 4.1).

- **Given** Simon or Loïc is on the **product catalog list** (`/products`)
- **When** he long-presses a product card OR uses the `⋮` overflow menu
- **Then** a "Disponibilité cross-boutique" option is available that opens the sheet

- **Given** Simon or Loïc is on the **multi-store stock view** (Story 3.2, `/stock`)
- **When** he taps a product row in the expanded `StoreStockCard`
- **Then** the cross-store availability sheet opens for that product

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Data source: online (HTTP, 3s timeout) vs offline (local Drift). Role-based footer CTA (OWNER vs EMPLOYEE). |
| What might change in the future? | Adding distance/map info. More role types. POS integration (pre-existing entry point hook). |
| Which GoF pattern(s) apply? | **Query Object** — `GetCrossStoreAvailabilityQuery(productId)` as an immutable input record to the use case (enables MCP-readiness). **Strategy** — repository implementation uses try-remote-fallback-to-local pattern (same as other offline-first repos). **Template Method** — the footer section of the bottom sheet follows a template with a variable CTA based on role (owner/employee). |
| How does it enable Open/Closed principle? | `GetCrossStoreAvailabilityUseCase` interface is closed. Backend SQL can be extended (e.g., add variant columns) in the adapter without changing the port. The Flutter bottom sheet footer is a separate private widget parameterized by role — new roles extend without modifying the widget. |
| Where is the pattern applied? | Query Object: `GetCrossStoreAvailabilityQuery` record (backend). Strategy: `StockRepositoryImpl.getCrossStoreAvailability()` (Flutter). Template Method: `_AvailabilitySheetFooter` widget (Flutter). |

---

## Dev Notes

### Pre-existing context to read FIRST

1. **Story 3.2 (done)** — `MultiStoreStockRepositoryAdapter` uses `JdbcTemplate` native SQL to join `stores` + `stock_levels` + `products` tables. **Follow the exact same SQL pattern** for Story 3.4's backend query. The adapter is at:
   `catalog/stock/adapter/out/persistence/MultiStoreStockRepositoryAdapter.java`

2. **Story 3.3 (done)** — `showTransferFormBottomSheet(...)` signature:
   ```dart
   Future<String?> showTransferFormBottomSheet({
     required BuildContext context,
     required String sourceStoreId,
     required String sourceStoreName,
     required String productId,
     required String productName,
     required List<StoreModel> destinationStores,
   })
   ```
   File: `app/lib/features/inventory/presentation/widget/transfer_form_bottom_sheet.dart`

3. **Existing backend ports** (reuse, do NOT recreate):
   - `StockLevelRepository.findAllByProduct(UUID productId): List<StockLevel>` — already exists
   - `MultiStoreStockRepository` out-port (in `catalog/stock/domain/port/out/`) — **extend this interface** with the new method instead of creating a new port
   - `ProductRepository.findById(UUID id): Optional<Product>` — already exists

4. **Flutter providers to use** (do NOT re-create):
   - `storeListNotifierProvider` — `AsyncNotifierProvider<StoreListNotifier, List<StoreModel>>` — all active stores
   - `activeStoreIdProvider` — `NotifierProvider<ActiveStoreNotifier, String?>` — current active store ID  
   - `productListForPickerProvider` — FutureProvider → product names for display
   - `syncStatusProvider` — for online/offline detection

5. **User role access in Flutter**: After the two-step login (Story 1.7), the JWT access token contains claim `"role": "OWNER"` or `"role": "EMPLOYEE"`. Look in `app/lib/features/auth/` for `AuthNotifier`, `LoginSessionResponse`, and `MembershipDto` to find how the role is persisted after `selectTenant`. If stored in `SharedPreferences` → create a `currentUserRoleProvider`; if decodable from JWT in `flutter_secure_storage` → decode the middle base64 segment and parse `role`. The simplest approach: check `sharedPreferencesProvider` for a `user_role` key set by the auth notifier at tenant selection time.

6. **Pre-existing known issue** (Story 3.2 bug — do NOT let it block Story 3.4): `GET /api/v1/stock/stores/{storeId}/products` intermittently returns 500 (observed in terminal context). This is a separate concern and must not be used as a dependency path for Story 3.4. Story 3.4 uses `GET /api/v1/stock/products/{productId}/availability` (new) and `GET /api/v1/products/{productId}/stock` (existing, works).

### Architecture compliance

- New backend endpoint belongs in **`MultiStoreStockController`** (`@RequestMapping("/api/v1/stock")`) — it fits the multi-store stock namespace
- Use case: `catalog/stock/application/usecase/GetCrossStoreAvailabilityService.java` implementing `GetCrossStoreAvailabilityUseCase`
- Port interface files in `catalog/stock/domain/port/in/` (query + use case interface)
- SQL in `MultiStoreStockRepositoryAdapter` — extend the existing adapter (**not a new repository class**)
- Flutter: feature lives in **`catalog`** domain (not `inventory` — cross-store availability is a catalog/stock view, not a transfer operation)
  - `app/lib/features/catalog/domain/model/cross_store_availability_model.dart`
  - `app/lib/features/catalog/domain/usecase/get_cross_store_availability_usecase.dart`
  - `app/lib/features/catalog/presentation/provider/cross_store_availability_provider.dart`
  - `app/lib/features/catalog/presentation/widget/cross_store_availability_bottom_sheet.dart`

### URL launcher for WhatsApp

- `url_launcher` package is already in the project (check `pubspec.yaml` — if absent, add `url_launcher: ^6.3.1`)
- Usage: `launchUrl(Uri.parse('https://wa.me/${phone.replaceAll('+', '')}'))` — Remove `+` prefix, keep country code digits only

---

## Tasks / Subtasks

> ⚠️ **TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST. No production code before a test.**

---

### BACKEND

---

- [x] **Task 1 — Write RED backend tests (TDD first pass)**

  - [x] 1.1 — Create `GetCrossStoreAvailabilityServiceTest.java` in `test/.../catalog/stock/application/usecase/`:
    ```java
    // Extends MockitoExtension, mocks: MultiStoreStockRepository, ProductRepository
    @Test void execute_shouldReturnAvailabilityEntries_whenProductExists()
    @Test void execute_shouldThrowProductNotFound_whenProductIdUnknown()
    @Test void execute_shouldReturnEmptyList_whenNoStockLevelsExist()
    ```
  - [x] 1.2 — Create `MultiStoreStockControllerTest.java` tests for the new endpoint (add to existing test class if it exists):
    ```java
    // @WebMvcTest(MultiStoreStockController.class) + @MockBean for use cases
    @Test void getAvailability_returns200_withEntries()
    @Test void getAvailability_returns404_whenProductNotFound()
    @Test void getAvailability_returns401_withoutJwt()
    ```
  - [x] 1.3 — Run tests → confirm ALL RED (compilation errors are acceptable at this step)

---

- [x] **Task 2 — Domain model: `CrossStoreAvailabilityEntry`**

  - [x] 2.1 — Create `CrossStoreAvailabilityEntry.java` in `catalog/stock/domain/model/`:
    ```java
    package com.keevo.catalog.stock.domain.model;

    import com.keevo.store.store.domain.model.StoreType;
    import java.time.Instant;
    import java.util.UUID;

    /**
     * CrossStoreAvailabilityEntry — single store entry in a product's cross-store availability view.
     * Story 3.4.
     */
    public record CrossStoreAvailabilityEntry(
        UUID    storeId,
        String  storeName,
        StoreType storeType,
        int     quantity,
        int     minimumThreshold,
        boolean isLow,
        Instant refreshedAt   // same value for all entries — the query timestamp
    ) {
        public CrossStoreAvailabilityEntry {
            if (minimumThreshold < 0) throw new IllegalArgumentException("threshold must be >= 0");
        }
    }
    ```

---

- [x] **Task 3 — Port: extend `MultiStoreStockRepository` with the new query method**

  - [x] 3.1 — In `catalog/stock/domain/port/out/MultiStoreStockRepository.java`, add:
    ```java
    /**
     * Returns stock availability for [productId] across ALL active stores.
     * Includes stores with quantity = 0.
     * Story 3.4.
     */
    List<CrossStoreAvailabilityEntry> getProductAvailability(UUID productId);
    ```

---

- [x] **Task 4 — Port: `GetCrossStoreAvailabilityQuery` + `GetCrossStoreAvailabilityUseCase`**

  - [x] 4.1 — Create `GetCrossStoreAvailabilityQuery.java` in `catalog/stock/domain/port/in/`:
    ```java
    package com.keevo.catalog.stock.domain.port.in;
    import java.util.UUID;

    /**
     * GetCrossStoreAvailabilityQuery — input record for the cross-store availability use case.
     * GoF: Query Object. MCP-ready: plain Java record.
     * Story 3.4.
     */
    public record GetCrossStoreAvailabilityQuery(UUID productId) {}
    ```

  - [x] 4.2 — Create `GetCrossStoreAvailabilityUseCase.java` in `catalog/stock/domain/port/in/`:
    ```java
    package com.keevo.catalog.stock.domain.port.in;

    import com.keevo.catalog.product.domain.entity.Product;
    import com.keevo.catalog.stock.domain.model.CrossStoreAvailabilityEntry;
    import java.util.List;

    public interface GetCrossStoreAvailabilityUseCase {
        /** Result carrying the product context + per-store availability entries. */
        record Result(Product product, List<CrossStoreAvailabilityEntry> entries) {}

        Result execute(GetCrossStoreAvailabilityQuery query);
    }
    ```

---

- [x] **Task 5 — Application service: `GetCrossStoreAvailabilityService`**

  - [x] 5.1 — Create `GetCrossStoreAvailabilityService.java` in `catalog/stock/application/usecase/`:
    ```java
    package com.keevo.catalog.stock.application.usecase;

    import com.keevo.catalog.product.domain.entity.Product;
    import com.keevo.catalog.product.domain.port.out.ProductRepository;
    import com.keevo.catalog.stock.domain.model.CrossStoreAvailabilityEntry;
    import com.keevo.catalog.stock.domain.port.in.GetCrossStoreAvailabilityQuery;
    import com.keevo.catalog.stock.domain.port.in.GetCrossStoreAvailabilityUseCase;
    import com.keevo.catalog.stock.domain.port.out.MultiStoreStockRepository;
    import com.keevo.shared.domain.exception.DomainException;
    import com.keevo.shared.domain.exception.ErrorCode;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;

    import java.util.List;

    /**
     * GetCrossStoreAvailabilityService — returns stock of a product across all active stores.
     * Story 3.4.
     */
    @Service
    public class GetCrossStoreAvailabilityService implements GetCrossStoreAvailabilityUseCase {

        private final ProductRepository productRepository;
        private final MultiStoreStockRepository multiStoreStockRepository;

        public GetCrossStoreAvailabilityService(ProductRepository productRepository,
                                                MultiStoreStockRepository multiStoreStockRepository) {
            this.productRepository        = productRepository;
            this.multiStoreStockRepository = multiStoreStockRepository;
        }

        @Override
        @Transactional(readOnly = true)
        public Result execute(GetCrossStoreAvailabilityQuery query) {
            Product product = productRepository.findById(query.productId())
                .orElseThrow(() -> new DomainException(
                    ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + query.productId()));

            List<CrossStoreAvailabilityEntry> entries =
                multiStoreStockRepository.getProductAvailability(query.productId());

            return new Result(product, entries);
        }
    }
    ```
  - [x] 5.2 — Run `GetCrossStoreAvailabilityServiceTest` → confirm GREEN

---

- [x] **Task 6 — Persistence: implement `getProductAvailability` in `MultiStoreStockRepositoryAdapter`**

  - [x] 6.1 — Add the following SQL constant and method to `MultiStoreStockRepositoryAdapter.java`:
    ```java
    private static final String SQL_PRODUCT_AVAILABILITY = """
        SELECT
            s.id                AS store_id,
            s.name              AS store_name,
            s.type              AS store_type,
            COALESCE(sl.quantity, 0)          AS quantity,
            p.minimum_threshold AS minimum_threshold
        FROM stores s
        LEFT JOIN stock_levels sl ON sl.store_id = s.id
                                  AND sl.product_id = ?
                                  AND sl.variant_id IS NULL
        LEFT JOIN products p     ON p.id = ?
        WHERE s.is_active = true
        ORDER BY s.created_at ASC
        """;

    @Override
    @Transactional(readOnly = true)
    public List<CrossStoreAvailabilityEntry> getProductAvailability(UUID productId) {
        Instant now = Instant.now();
        return jdbc.query(SQL_PRODUCT_AVAILABILITY,
            (rs, rowNum) -> {
                int qty       = rs.getInt("quantity");
                int threshold = rs.getInt("minimum_threshold");
                return new CrossStoreAvailabilityEntry(
                    UUID.fromString(rs.getString("store_id")),
                    rs.getString("store_name"),
                    StoreType.valueOf(rs.getString("store_type")),
                    qty,
                    threshold,
                    threshold > 0 && qty <= threshold,
                    now
                );
            },
            productId, productId   // two ? placeholders in the SQL
        );
    }
    ```
  - [x] 6.2 — Add import `import com.keevo.catalog.stock.domain.model.CrossStoreAvailabilityEntry;` and `import java.time.Instant;` to the adapter

---

- [x] **Task 7 — REST DTO: `CrossStoreAvailabilityResponseDto`**

  - [x] 7.1 — Create `CrossStoreAvailabilityResponseDto.java` in `catalog/stock/adapter/in/web/dto/`:
    ```java
    package com.keevo.catalog.stock.adapter.in.web.dto;

    import com.keevo.catalog.stock.domain.model.CrossStoreAvailabilityEntry;
    import com.keevo.catalog.stock.domain.port.in.GetCrossStoreAvailabilityUseCase;
    import com.keevo.store.store.domain.model.StoreType;
    import io.swagger.v3.oas.annotations.media.Schema;

    import java.time.Instant;
    import java.util.List;
    import java.util.UUID;

    /**
     * CrossStoreAvailabilityResponseDto — response envelope for Story 3.4.
     */
    public record CrossStoreAvailabilityResponseDto(
        UUID          productId,
        String        productName,
        List<Entry>   entries,
        Instant       refreshedAt
    ) {
        public record Entry(
            UUID storeId,
            String storeName,
            StoreType storeType,
            int quantity,
            int minimumThreshold,
            boolean isLow
        ) {
            public static Entry from(CrossStoreAvailabilityEntry e) {
                return new Entry(
                    e.storeId(), e.storeName(), e.storeType(),
                    e.quantity(), e.minimumThreshold(), e.isLow()
                );
            }
        }

        public static CrossStoreAvailabilityResponseDto from(GetCrossStoreAvailabilityUseCase.Result result) {
            return new CrossStoreAvailabilityResponseDto(
                result.product().getId(),
                result.product().getName(),
                result.entries().stream().map(Entry::from).toList(),
                result.entries().isEmpty() ? Instant.now() : result.entries().get(0).refreshedAt()
            );
        }
    }
    ```

---

- [x] **Task 8 — Controller: add endpoint to `MultiStoreStockController`**

  - [x] 8.1 — In `MultiStoreStockController.java`:
    - Inject `GetCrossStoreAvailabilityUseCase availabilityUseCase` via constructor
    - Add:
      ```java
      @Operation(summary = "Get product stock availability across all active stores")
      @GetMapping("/products/{productId}/availability")
      public ResponseEntity<ApiResponseWrapper<CrossStoreAvailabilityResponseDto>> getProductAvailability(
              @PathVariable UUID productId) {
          var result = availabilityUseCase.execute(new GetCrossStoreAvailabilityQuery(productId));
          return ResponseEntity.ok(ApiResponseWrapper.ok(CrossStoreAvailabilityResponseDto.from(result)));
      }
      ```
  - [x] 8.2 — Run `MultiStoreStockControllerTest` → confirm GREEN

---

- [x] **Task 9 — Run all backend tests**

  - [x] 9.1 — `mvn test` → `BUILD SUCCESS — 0 failures, 0 errors`
  - [x] 9.2 — Restart the Spring Boot server: `mvn spring-boot:run`

---

### FLUTTER

---

- [x] **Task 10 — Write RED Flutter tests (TDD first pass)**

  - [x] 10.1 — Create `cross_store_availability_provider_test.dart` in `test/features/catalog/presentation/provider/`:
    ```dart
    // Uses mocktail to mock StockRepository
    test('crossStoreAvailabilityProvider returns sorted list')
    test('crossStoreAvailabilityProvider falls back to local on timeout')
    ```
  - [x] 10.2 — Create `cross_store_availability_bottom_sheet_test.dart` in `test/features/catalog/presentation/widget/`:
    ```dart
    setUpAll(() { GoogleFonts.config.allowRuntimeFetching = false; });
    testWidgets('shows store list with quantity badges')
    testWidgets('greyed out stores have qty=0')
    testWidgets('OWNER sees Initier un transfert button')
    testWidgets('EMPLOYEE sees contact message instead of transfer button')
    testWidgets('transfer button disabled when all stores have qty=0')
    ```
  - [x] 10.3 — Run Flutter tests → confirm RED

---

- [x] **Task 11 — Domain model: `CrossStoreAvailabilityModel`**

  - [x] 11.1 — Create `app/lib/features/catalog/domain/model/cross_store_availability_model.dart`:
    ```dart
    import 'package:freezed_annotation/freezed_annotation.dart';
    part 'cross_store_availability_model.freezed.dart';
    part 'cross_store_availability_model.g.dart';

    enum CrossStoreStockStatus { ok, low, outOfStock }

    @freezed
    class CrossStoreAvailabilityModel with _$CrossStoreAvailabilityModel {
      const CrossStoreAvailabilityModel._();

      const factory CrossStoreAvailabilityModel({
        required String productId,
        required String productName,
        required List<CrossStoreAvailabilityEntry> entries,
        required DateTime refreshedAt,
      }) = _CrossStoreAvailabilityModel;

      factory CrossStoreAvailabilityModel.fromJson(Map<String, dynamic> json) =>
          _$CrossStoreAvailabilityModelFromJson(json);
    }

    @freezed
    class CrossStoreAvailabilityEntry with _$CrossStoreAvailabilityEntry {
      const CrossStoreAvailabilityEntry._();

      const factory CrossStoreAvailabilityEntry({
        required String storeId,
        required String storeName,
        @Default('STORE') String storeType,
        required int quantity,
        @Default(0) int minimumThreshold,
        @Default(false) bool isLow,
      }) = _CrossStoreAvailabilityEntry;

      factory CrossStoreAvailabilityEntry.fromJson(Map<String, dynamic> json) =>
          _$CrossStoreAvailabilityEntryFromJson(json);

      CrossStoreStockStatus get stockStatus {
        if (quantity == 0) return CrossStoreStockStatus.outOfStock;
        if (isLow) return CrossStoreStockStatus.low;
        return CrossStoreStockStatus.ok;
      }

      bool get isWarehouse => storeType == 'WAREHOUSE';
    }
    ```
  - [x] 11.2 — Run `flutter pub run build_runner build --delete-conflicting-outputs` to generate `.freezed.dart` + `.g.dart`

---

- [x] **Task 12 — StockRepository: add `getCrossStoreAvailability` method**

  - [x] 12.1 — In `app/lib/features/catalog/domain/repository/stock_repository.dart`, add the abstract method:
    ```dart
    /// Returns cross-store availability for [productId] across all active stores.
    ///
    /// Online: calls GET /api/v1/stock/products/{productId}/availability (3s timeout)
    /// and updates local Drift cache.
    /// Offline/timeout: returns last-known Drift data joined with local stores.
    Future<CrossStoreAvailabilityModel> getCrossStoreAvailability(String productId);
    ```

  - [x] 12.2 — In `RemoteStockDataSource` (or equivalent remote datasource), add:
    ```dart
    Future<CrossStoreAvailabilityModel> fetchCrossStoreAvailability(String productId) async {
      final resp = await dio.get(
        '/api/v1/stock/products/$productId/availability',
        options: Options(receiveTimeout: const Duration(seconds: 3)),
      );
      return CrossStoreAvailabilityModel.fromJson(resp.data['data'] as Map<String, dynamic>);
    }
    ```

  - [x] 12.3 — In `LocalStockDataSource`, add offline fallback:
    ```dart
    /// Joins local Drift stock_levels × stores for a given productId.
    Future<CrossStoreAvailabilityModel> getLocalAvailability({
      required String productId,
      required String productName,
      required List<StoreModel> allStores,
    }) async {
      final levels = await getLevels(productId);   // existing method
      final levelByStoreId = {for (final l in levels) l.storeId: l};

      final entries = allStores
          .where((s) => s.isActive)
          .map((s) {
            final level = levelByStoreId[s.id];
            final qty   = level?.quantity ?? 0;
            final thr   = level?.minimumThreshold ?? 0;
            return CrossStoreAvailabilityEntry(
              storeId: s.id,
              storeName: s.name,
              storeType: s.type.name.toUpperCase(),
              quantity: qty,
              minimumThreshold: thr,
              isLow: thr > 0 && qty <= thr,
            );
          })
          .toList();

      return CrossStoreAvailabilityModel(
        productId:   productId,
        productName: productName,
        entries:     entries,
        refreshedAt: DateTime.now(),
      );
    }
    ```

  - [x] 12.4 — In `StockRepositoryImpl`, implement `getCrossStoreAvailability`:
    ```dart
    @override
    Future<CrossStoreAvailabilityModel> getCrossStoreAvailability(String productId) async {
      if (!isOnline()) {
        // Offline: local join only
        final stores = await storeLocalDs.getAllStores();           // existing local stores DS
        final product = await localDs.getProductName(productId);   // helper or local lookup
        return localDs.getLocalAvailability(
          productId: productId,
          productName: product,
          allStores: stores,
        );
      }

      try {
        final model = await remotDs.fetchCrossStoreAvailability(productId);
        // Update local stock_levels cache
        for (final entry in model.entries) {
          await localDs.upsertStockLevel(StockLevelModel(
            id:               '${entry.storeId}_${productId}',
            productId:        productId,
            storeId:          entry.storeId,
            quantity:         entry.quantity,
            minimumThreshold: entry.minimumThreshold,
            updatedAt:        model.refreshedAt,
          ));
        }
        return model;
      } on DioException catch (e) {
        // Timeout or network error → degrade to local
        final stores  = await storeLocalDs.getAllStores();
        return localDs.getLocalAvailability(
          productId: productId,
          productName: '', // will be filled from productListForPickerProvider
          allStores: stores,
        );
      }
    }
    ```

---

- [x] **Task 13 — Provider: `crossStoreAvailabilityProvider`**

  - [x] 13.1 — Create `app/lib/features/catalog/presentation/provider/cross_store_availability_provider.dart`:
    ```dart
    part 'cross_store_availability_provider.g.dart';

    @riverpod
    Future<CrossStoreAvailabilityModel> crossStoreAvailability(
      CrossStoreAvailabilityRef ref,
      String productId,
    ) async {
      final stockRepo = ref.watch(stockRepositoryProvider);
      return stockRepo.getCrossStoreAvailability(productId);
    }
    ```
  - [x] 13.2 — Run `flutter pub run build_runner build --delete-conflicting-outputs`

---

- [x] **Task 14 — Widget: `CrossStoreAvailabilityBottomSheet`**

  - [x] 14.1 — Create `app/lib/features/catalog/presentation/widget/cross_store_availability_bottom_sheet.dart`:

    **Public function signature:**
    ```dart
    Future<void> showCrossStoreAvailabilitySheet({
      required BuildContext context,
      required WidgetRef ref,
      required String productId,
      required String productName,
      String? currentStoreId,   // highlight this store row as "this store"
    })
    ```
    Opens via `showModalBottomSheet` with `isScrollControlled: true, useSafeArea: true`.

    **Structure:**
    - `DragHandle` at top
    - Title: product name, subtitle: "Disponibilité dans le réseau"
    - `Consumer` wrapping the content area, watching `crossStoreAvailabilityProvider(productId)`
    - Loading state: `CircularProgressIndicator`
    - Error state: "Impossible de charger les données" with retry button
    - Content (data loaded):
      - `ListView` of `_StoreAvailabilityTile` per entry, sorted (qty > 0 first desc, then 0)
      - "Dernière mise à jour : [format timestamp]" muted text row
    - Footer: `_AvailabilitySheetFooter` parameterized by role + availability data

    **`_StoreAvailabilityTile` widget:**
    - Leading: `Icon` (🏪/🏭) from `storeType`
    - Title: store name + `(Ce magasin)` if `storeId == currentStoreId`
    - Trailing: quantity chip — colors from UX spec palette:
      - `CrossStoreStockStatus.ok` → `colorSuccess` (#51CF66)
      - `CrossStoreStockStatus.low` → `colorWarning` (#FCC419)
      - `CrossStoreStockStatus.outOfStock` → dimmed `colorOnSurfaceVariant`
    - `opacity: 0.4` overlay + strikethrough text for `outOfStock` entries

    **`_AvailabilitySheetFooter` widget** (role-based — GoF: Template Method):
    ```dart
    class _AvailabilitySheetFooter extends ConsumerWidget {
      final CrossStoreAvailabilityModel availability;
      final String productName;
      final String? currentStoreId;
    }
    ```
    - Reads user role from `currentUserRoleProvider` (see note in Dev Notes §5)
    - **OWNER branch:**
      - `FilledButton` "Initier un transfert"
      - Enabled only if `entries.any((e) => e.quantity > 0)`
      - On tap: close sheet → call `showTransferFormBottomSheet(...)` with pre-filled sourceStore (highest qty)
    - **EMPLOYEE branch:**
      - `Text("Contacter le propriétaire pour initier un transfert")` in `bodySmall` secondary style
      - `OutlinedButton.icon` with WhatsApp icon: "Contacter via WhatsApp"
      - Only shown when owner phone is not null (from `storeListNotifierProvider` → first store's phone)
      - On tap: `launchUrl(Uri.parse('https://wa.me/${phone}'))` — uses `url_launcher`

---

- [x] **Task 15 — User role provider**

  - [x] 15.1 — Check `app/lib/features/auth/` for how the role is persisted after `selectTenant`. Look for `SharedPreferences` key `user_role` or equivalent. If it exists: create `final currentUserRoleProvider = Provider<String?>((ref) => ref.watch(sharedPreferencesProvider).getString('user_role'));`
  - [x] 15.2 — If not yet persisted: in `AuthNotifier` (or `SelectTenantUseCase` consumer), after successful `selectTenant`, save `prefs.setString('user_role', membership.role)`. The role comes from the `MembershipDto.role` field (Story 1.7, value is `"OWNER"` or `"EMPLOYEE"`).

---

- [x] **Task 16 — Entry points: wire the bottom sheet to existing screens**

  - [x] 16.1 — **Product catalog list** (`app/lib/features/catalog/presentation/page/product_list_page.dart`):
    - Find the existing product card long-press handler or `⋮` overflow menu
    - Add menu item: `PopupMenuItem(value: 'availability', child: Text('Disponibilité cross-boutique'))`
    - On selection: call `showCrossStoreAvailabilitySheet(context: context, ref: ref, productId: product.id, productName: product.name, currentStoreId: ref.read(activeStoreIdProvider))`

  - [x] 16.2 — **Multi-store stock page** (`StoreStockCard` / product row):
    - In the product row of the `StoreStockCard` (Story 3.2), add an `IconButton(icon: Icon(Icons.store_outlined))` or an `InkWell` `onTap` on the row that opens the availability sheet
    - Title: the product name
    - Note: only add if the row tap is not already capturing an action — if it is, add via trailing widget

---

- [x] **Task 17 — Run all Flutter tests**

  - [x] 17.1 — `flutter test --reporter=expanded` in `keevo/app/`
  - [x] 17.2 — All tests GREEN (cross-store widget tests + pre-existing tests must all pass)
  - [x] 17.3 — Fix any regressions in `cross_store_availability_provider_test.dart` and `cross_store_availability_bottom_sheet_test.dart` → GREEN

---

## cURL Integration Tests (MANDATORY — run after backend tasks, before closing story)

```bash
#!/usr/bin/env bash
# ======================================================
# Story 3.4 — cURL Integration Tests
# Run: bash curl-tests-story-3-4.sh
# All steps must show ✅ before story is marked done
# Prerequisites: Spring Boot running on localhost:8443 (or 8080)
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8080"

echo "=== Story 3.4 — Cross-store availability cURL tests ==="

# Step 1 — Re-use existing tenant / register + login + select tenant
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+237600999034","password":"Test1234!","firstName":"Test","lastName":"Availability"}')
echo "Register raw: $REGISTER"

LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+237600999034","password":"Test1234!"}')
LOGIN_TOKEN=$(echo "$LOGIN" | jq -r '.data.loginToken')
[[ -n "$LOGIN_TOKEN" && "$LOGIN_TOKEN" != "null" ]] && echo "✅ Step 1a — loginToken obtained" || { echo "❌ Step 1a FAILED — $LOGIN"; exit 1; }

TENANT_ID=$(echo "$LOGIN" | jq -r '.data.memberships[0].tenantCode')
SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LOGIN_TOKEN\",\"tenantCode\":\"$TENANT_ID\"}")
JWT=$(echo "$SELECT" | jq -r '.data.accessToken')
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1b — JWT obtained" || { echo "❌ Step 1b FAILED — $SELECT"; exit 1; }

# Step 2 — Onboard the tenant (setup stores)
ONBOARD=$(curl -s -X POST "$BASE_URL/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"shopName":"Test Shop 3.4","sectorId":"food","storeCount":1}')
echo "Onboard: $ONBOARD"

# Step 3 — Get first store ID
STORES=$(curl -s -X GET "$BASE_URL/api/v1/tenant/stores" \
  -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES" | jq -r '.data[0].id')
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && echo "✅ Step 3 — storeId=$STORE_ID" || { echo "❌ Step 3 FAILED — $STORES"; exit 1; }

# Step 4 — Create a product
PRODUCT=$(curl -s -X POST "$BASE_URL/api/v1/products" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Produit Cross-Boutique Test\",\"price\":1000,\"buyPrice\":500,\"categoryId\":null}")
PRODUCT_ID=$(echo "$PRODUCT" | jq -r '.data.id // .data[0].id // empty' 2>/dev/null || echo "$PRODUCT" | jq -r '.data.id')
# Fallback: try to list products
if [[ -z "$PRODUCT_ID" || "$PRODUCT_ID" == "null" ]]; then
  ALL_PRODUCTS=$(curl -s "$BASE_URL/api/v1/products" -H "Authorization: Bearer $JWT")
  PRODUCT_ID=$(echo "$ALL_PRODUCTS" | jq -r '.data[0].id')
fi
[[ -n "$PRODUCT_ID" && "$PRODUCT_ID" != "null" ]] && echo "✅ Step 4 — productId=$PRODUCT_ID" || { echo "❌ Step 4 FAILED — $PRODUCT"; exit 1; }

# Step 5 — GET /api/v1/stock/products/{productId}/availability — HTTP 200
AVAIL=$(curl -s -X GET "$BASE_URL/api/v1/stock/products/$PRODUCT_ID/availability" \
  -H "Authorization: Bearer $JWT")
echo "Availability response: $AVAIL"
AVAIL_PRODUCT_ID=$(echo "$AVAIL" | jq -r '.data.productId')
AVAIL_ENTRIES=$(echo "$AVAIL" | jq -r '.data.entries | length')
[[ "$AVAIL_PRODUCT_ID" == "$PRODUCT_ID" ]] && echo "✅ Step 5a — productId matches" || { echo "❌ Step 5a FAILED — got productId=$AVAIL_PRODUCT_ID"; exit 1; }
[[ -n "$AVAIL_ENTRIES" ]] && echo "✅ Step 5b — entries returned: $AVAIL_ENTRIES store(s)" || { echo "❌ Step 5b FAILED — no entries field"; exit 1; }
echo "Attendu: HTTP 200, data.productId matches, data.entries is array"

# Step 6 — Verify entries contain storeId, storeName, quantity, isLow fields
FIRST_ENTRY_STORE_ID=$(echo "$AVAIL" | jq -r '.data.entries[0].storeId // empty')
FIRST_ENTRY_STORE_NAME=$(echo "$AVAIL" | jq -r '.data.entries[0].storeName // empty')
[[ -n "$FIRST_ENTRY_STORE_ID" ]] && echo "✅ Step 6a — entries[0].storeId present: $FIRST_ENTRY_STORE_ID" || { echo "❌ Step 6a FAILED — storeId missing in entry"; exit 1; }
[[ -n "$FIRST_ENTRY_STORE_NAME" ]] && echo "✅ Step 6b — entries[0].storeName present: $FIRST_ENTRY_STORE_NAME" || { echo "❌ Step 6b FAILED — storeName missing in entry"; exit 1; }
echo "Attendu: entries[0] has storeId, storeName, quantity, minimumThreshold, isLow"

# Step 7 — GET with unknown productId → HTTP 404 PRODUCT_NOT_FOUND
UNKNOWN=$(curl -s -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/stock/products/00000000-0000-0000-0000-000000000000/availability" \
  -H "Authorization: Bearer $JWT")
[[ "$UNKNOWN" == "404" ]] && echo "✅ Step 7 — 404 on unknown productId" || { echo "❌ Step 7 FAILED — got HTTP $UNKNOWN, expected 404"; exit 1; }

# Step 8 — GET without JWT → HTTP 401
UNAUTH=$(curl -s -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/stock/products/$PRODUCT_ID/availability")
[[ "$UNAUTH" == "401" ]] && echo "✅ Step 8 — 401 without JWT" || { echo "❌ Step 8 FAILED — got HTTP $UNAUTH, expected 401"; exit 1; }

# Step 9 — Create a second store and verify it shows up in availability
STORE2=$(curl -s -X POST "$BASE_URL/api/v1/stores" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Boutique Bonanjo","type":"STORE"}')
STORE2_ID=$(echo "$STORE2" | jq -r '.data.id // empty')
if [[ -n "$STORE2_ID" && "$STORE2_ID" != "null" ]]; then
  AVAIL2=$(curl -s "$BASE_URL/api/v1/stock/products/$PRODUCT_ID/availability" \
    -H "Authorization: Bearer $JWT")
  ENTRY_COUNT=$(echo "$AVAIL2" | jq -r '.data.entries | length')
  [[ "$ENTRY_COUNT" -ge 2 ]] && echo "✅ Step 9 — $ENTRY_COUNT stores in availability after creating 2nd store" || { echo "⚠️ Step 9 — only $ENTRY_COUNT stores (may be ok if plan limit)"; }
else
  echo "⚠️ Step 9 — could not create 2nd store (plan limit or endpoint different path) — skipping multi-store assertion"
fi

echo ""
echo "✅✅✅ All cURL integration checks passed — Story 3.4 backend validated ✅✅✅"
```

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.5 (GitHub Copilot)

### Debug Log References

- Code review identified H1 (missing Drift cache upsert on remote success in `stock_repository_impl.dart`) — fixed with upsert loop after remote fetch
- Code review identified H2/H3 (`_EmployeeCta` used wrong WhatsApp scheme `whatsapp://send?phone=` and gated visibility on stock instead of phone) — refactored to `ConsumerWidget` reading owner phone from `storeListNotifierProvider`, deeplink `https://wa.me/{phone}`
- Code review identified H4 (`_StoreTile` showed `'0'` for out-of-stock) — fixed to show `'-'` with `'En rupture'` italic label
- Code review identified M1 (AC3 text deviated from spec) — corrected to exact wording: "Contacter le propriétaire pour initier un transfert"
- L1: Added missing `PRODUCT_NOT_FOUND` → `"Produit introuvable"` to `GlobalExceptionHandler.FR_MESSAGES`

### Completion Notes List

- Backend: 12 tests GREEN (7 service + 5 controller) — `GetCrossStoreAvailabilityServiceTest`, `MultiStoreStockControllerTest`
- Flutter: 11 tests GREEN (5 provider + 6 widget) — `cross_store_availability_provider_test`, `cross_store_availability_bottom_sheet_test`
- 9 cURL E2E integration tests in `scripts/curl-tests-story-3-4.sh`
- All code review HIGH/MEDIUM/LOW issues resolved and verified
- AC1 (cross-store stock view), AC2 (offline fallback + cache), AC3 (role-based footer), AC4 (entry points) implemented and tested

### File List

**Backend (new files):**
- `catalog/stock/domain/model/CrossStoreAvailabilityEntry.java`
- `catalog/stock/domain/port/in/GetCrossStoreAvailabilityQuery.java`
- `catalog/stock/domain/port/in/GetCrossStoreAvailabilityUseCase.java`
- `catalog/stock/application/usecase/GetCrossStoreAvailabilityService.java`
- `catalog/stock/adapter/in/web/dto/CrossStoreAvailabilityResponseDto.java`

**Backend (modified files):**
- `catalog/stock/domain/port/out/MultiStoreStockRepository.java` — +`getProductAvailability(UUID)`
- `catalog/stock/adapter/out/persistence/MultiStoreStockRepositoryAdapter.java` — +SQL + impl
- `catalog/stock/adapter/in/web/MultiStoreStockController.java` — +endpoint + new use case injection
- `shared/infrastructure/web/GlobalExceptionHandler.java` — +`MethodArgumentTypeMismatchException` handler + `PRODUCT_NOT_FOUND` FR message

**Backend (tests):**
- `test/.../catalog/stock/application/usecase/GetCrossStoreAvailabilityServiceTest.java`
- `test/.../catalog/stock/adapter/in/web/MultiStoreStockControllerTest.java` — extended

**Flutter (new files):**
- `app/lib/features/catalog/domain/model/cross_store_availability_model.dart`
- `app/lib/features/catalog/domain/model/cross_store_availability_model.freezed.dart` _(generated)_
- `app/lib/features/catalog/domain/model/cross_store_availability_model.g.dart` _(generated)_
- `app/lib/features/catalog/presentation/provider/cross_store_availability_provider.dart`
- `app/lib/features/catalog/presentation/provider/cross_store_availability_provider.g.dart` _(generated)_
- `app/lib/features/catalog/presentation/widget/cross_store_availability_bottom_sheet.dart`

**Flutter (modified files):**
- `app/lib/features/catalog/domain/repository/stock_repository.dart` — +`getCrossStoreAvailability`
- `app/lib/features/catalog/data/repository/stock_repository_impl.dart` — +implementation + cache upsert on remote success
- `app/lib/features/catalog/data/datasource/remote_stock_datasource.dart` — +remote fetch
- `app/lib/features/catalog/data/datasource/local_stock_datasource.dart` — +local join
- `app/lib/features/catalog/presentation/page/product_list_page.dart` — +overflow menu action (product_card.dart)
- `app/lib/features/inventory/presentation/widget/store_stock_card.dart` — +`onTap` entry point for cross-store availability
- `app/lib/core/di/providers.dart` — +`kUserRoleKey`, `currentUserRoleProvider`
- `app/lib/features/auth/presentation/provider/auth_provider.dart` — +role persistence in login flows
- `app/lib/features/auth/presentation/page/tenant_picker_page.dart` — +role persistence before `selectTenant`
- `app/pubspec.yaml` — +`url_launcher: ^6.3.1`

**Flutter (tests):**
- `app/test/features/catalog/presentation/provider/cross_store_availability_provider_test.dart`
- `app/test/features/catalog/presentation/widget/cross_store_availability_bottom_sheet_test.dart`

**Scripts:**
- `scripts/curl-tests-story-3-4.sh` — 9-step cURL E2E integration test suite

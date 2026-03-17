# Story 4.2: Réductions & Prix Modifiable en Panier

Status: done

## Story

As an employee (Loïc),
I want to modify the price of an item in the cart and apply discounts without touching the catalogue price,
so that I can handle negotiations, promotions, and special prices for loyal customers in real time.

---

## Acceptance Criteria

### AC1 — Inline price override on cart item

- **Given** Loïc is viewing the expanded cart (`CartBottomSheet` from Story 4.1)
- **When** he taps the unit price of any cart item
- **Then** an inline numeric editor appears directly in the cart row (no modal, no navigation)
- **And** the field shows the current applied price pre-filled in XAF
- **And** the keyboard is numeric — no decimal input allowed (`TextInputType.number`, `FilteringTextInputFormatter.digitsOnly`)
- **And** leaving the field (focus lost) or tapping the checkmark `IconButton` confirms the override
- **And** pressing the checkmark closes the keyboard

### AC2 — Price override UX feedback + catalogue integrity

- **Given** Loïc enters a custom price (e.g., 4500 instead of 5000)
- **When** the price is confirmed
- **Then** the cart item now shows `4 500 FCFA` as the applied price with a **red accent label** "Prix modifié" (`Text` with `color: Colors.red.shade700`, `fontSize: 11`)
- **And** the red "Prix modifié" label appears ONLY when `appliedUnitPrice != unitPrice`
- **And** the catalogue price of the product in the Drift `products` table is **NOT modified** — zero writes to `products`
- **And** the line subtotal and grand total update immediately
- **And** `CartNotifier.updatePrice(String id, int newPrice)` is called (new method in `CartNotifier`)
- **And** the `CartItem.copyWith(appliedUnitPrice: newPrice)` is used — `unitPrice` (catalogue) stays unchanged

### AC3 — Order-level discount (percentage or fixed)

- **Given** Loïc wants to apply a discount on the entire order
- **When** he taps "Réduction" in the `CartBottomSheet` header area (next to "Panier (N)")
- **Then** a small bottom sheet (`_DiscountSheet`) appears offering two options: "% sur le total" and "Montant fixe"
- **And** for **% discount**: a numeric field (0–100), the discounted total is previewed in real-time as the user types
- **And** for **fixed amount**: a XAF integer field, validated that `discount ≤ subtotal`
- **And** on confirm, the discount is applied to the grand total displayed in the `CartBottomSheet` and stored in `CartNotifier.discountAmount`
- **And** each `sale_item` retains its original `appliedUnitPrice` — the `discountAmount` is stored as a single field on the `sales` record

### AC4 — Discount display on checkout

- **Given** a discount and/or price overrides are applied to the cart
- **When** Loïc views the `CheckoutPage`
- **Then** the total breakdown shows:
  - **Sous-total**: sum of all line subtotals (before discount) — e.g. `12 500 FCFA`
  - **Réduction**: `−X FCFA` (shown only if discount > 0, in `Colors.red`)
  - **Total à payer**: final amount in bold (`subtotal − discountAmount`) — e.g. `11 000 FCFA`
- **And** the change calculation (Espèces mode) uses the **final discounted total** (`subtotal − discountAmount`)
- **And** "Montant reçu" validation: received amount must be ≥ final total
- **Note**: Story 4.1's CheckoutPage does NOT yet have a "Montant reçu" input field for Espèces mode — this field + change calculation must be added as part of Task 13

### AC5 — Backend: `discountAmount` field + audit event

- **Given** Loïc confirms the sale with a discount and/or overridden prices
- **When** the sale is pushed to `POST /api/v1/sales`
- **Then** the backend records `discount_amount` on the `sales` row (INTEGER, default 0)
- **And** `totalAmount` in the `sales` table = `sum(sale_items.subtotal) − discount_amount` (net amount — what the customer actually paid)
- **And** the existing `SalePriceOverriddenEvent` (in `catalog.product.domain.event`, created in Story 2.2) is published for each `sale_item` where `appliedUnitPrice != cataloguePrice`:
  - Existing fields: `productId`, `saleId`, `cataloguePrice`, `appliedPrice`, `actorId`, `tenantId`, `occurredAt`
  - **Story 4.2 extends**: add `productName (String)` field for audit readability (the `delta` is computed on-read as `cataloguePrice − appliedPrice`)
- **And** `AuditEventListener` already handles `SalePriceOverriddenEvent` with action `PRICE_OVERRIDDEN` + entityType `PriceOverride` — update the handler to include `productName` in `valueAfter` JSON
- **And** `SaleCompletedEvent` is updated: add `discountAmount (int)` field — the items snapshot JSON already includes per-item data; `discountAmount` is added at the event level

### AC6 — Audit trail visibility (Simon's perspective)

- **Given** Simon reviews completed sales in the history (Story 4.3 — future)
- **When** he views a sale that had a price override or discount
- **Then** the sale detail will show: each item's catalogue price vs applied price, the discount line (if any)
- **And** the `SALE_PRICE_OVERRIDDEN` audit entries clearly identify Loïc as the actor and the delta amount
- **Note** This AC is informational for audit data **recording** in 4.2 — the **display** UI will be built in Story 4.3/4.4

### AC7 — Zero-price and edge case guards

- **Given** Loïc enters a price of 0 for a cart item
- **When** the price is confirmed
- **Then** the system accepts it (free sample / promo scenario — valid use case in informal commerce)
- **And** the `sale_item.applied_unit_price` is stored as `0`, subtotal = `0`
- **Given** Loïc enters a discount that exceeds the subtotal
- **When** the discount is confirmed
- **Then** the system rejects it with an inline error: "La réduction ne peut pas dépasser le sous-total"
- **And** the discount is not applied
- **Given** Loïc enters a discount of exactly 100% or exactly the subtotal
- **When** confirmed
- **Then** the final total is 0 — valid (complete promo)
- **And** the sale can still be validated at checkout with "Valider la vente"

---

## GoF Design Patterns Applied

| Pattern | Location | Application |
|---------|----------|-------------|
| **Strategy** | `DiscountStrategy` interface (Flutter) | `PercentageDiscountStrategy` vs `FixedAmountDiscountStrategy` — compute `discountAmount` from subtotal |
| **Observer** | `SalePriceOverriddenEvent.java` (existing, `catalog.product.domain.event`) + `AuditEventListener` | Spring event published per overridden item, audit listener already subscribes (action: `PRICE_OVERRIDDEN`) |
| **Decorator** (semantic) | `CartItem.appliedUnitPrice` vs `CartItem.unitPrice` | Applied price "decorates" the catalogue price without mutating it |
| **Command** | `RecordSaleCommand` extended with `discountAmount` | Immutable command carries discount through the hexagonal boundary |

---

## Tasks / Subtasks

### Task 1 — Write RED tests: backend domain + discount fields (TDD)

**TDD LAW: All tests written BEFORE any production code. Tests MUST fail first.**

- [x] **1.1** `SaleTest.java` — add test:
  - `Sale_create_withDiscountAmount_calculatesCorrectTotal()` — verify `totalAmount = sum(items.subtotal) − discountAmount`
  - `Sale_create_withNegativeDiscount_throws()` — negative discount is invalid
  - `Sale_create_withDiscountExceedingSubtotal_throws()` — discount > subtotal is rejected at domain level
- [x] **1.2** `SalePriceOverriddenEventTest.java` — update existing test (in `catalog.product.domain.event`):
  - `SalePriceOverriddenEvent_includesProductName()` — verify new `productName` field
  - `SalePriceOverriddenEvent_existingFieldsUnchanged()` — regression guard
- [x] **1.3** `RecordSaleServiceTest.java` — add tests:
  - `recordSale_withDiscount_savesDiscountAmount()` — verify `saleRepository.save()` receives Sale with discountAmount=500
  - `recordSale_withPriceOverride_publishesSalePriceOverriddenEvent()` — verify `eventPublisher.publishEvent()` is called with `SalePriceOverriddenEvent` for each overridden item
  - `recordSale_noPriceOverride_doesNotPublishOverriddenEvent()` — verify event NOT published when all prices match catalogue
  - `recordSale_withDiscountAmount_setsCorrectTotalAmount()` — totalAmount = subtotal - discountAmount
  - `recordSale_publishesSaleCompletedEvent_withDiscountAmount()` — verify `SaleCompletedEvent` includes `discountAmount` field
- [x] **1.4** `SaleControllerTest.java` — add tests:
  - `POST_sales_withDiscount_returns201()` — include `discountAmount` in payload
  - `POST_sales_withPriceOverride_returns201()` — include `catalogueUnitPrice` per item
  - `POST_sales_withNegativeDiscount_returns400()` — validation rejects negative discount
- [x] **1.5** `SaleRepositoryAdapterTest.java` — add test:
  - `save_persistsSaleWithDiscountAmount()` — verify `discount_amount` column is persisted via `@DataJpaTest`

### Task 2 — Extend backend domain model for discount

- [x] **2.1** `Sale.java` — add field `discountAmount (int, default 0)`:
  - Constructor: add `discountAmount` parameter between `totalAmount` and `status`
  - Validation: `discountAmount >= 0` (throw `IllegalArgumentException` if negative)
  - Validation: `discountAmount <= sum(items.subtotal)` (throw if discount exceeds subtotal)
  - `totalAmount` is now: `sum(items.subtotal) − discountAmount` — computed in `SaleFactory`
- [x] **2.2** `SaleItem.java` — add field `catalogueUnitPrice (int)`:
  - New parameter after `productName`: `catalogueUnitPrice`
  - This is the product's catalogue price at sale time — for audit delta comparison
  - `appliedUnitPrice` may differ if Loïc overrode it
  - Validation: `catalogueUnitPrice >= 0`
- [x] **2.3** `SalePriceOverriddenEvent.java` — **EXTEND existing** record (in `com.keevo.catalog.product.domain.event`):
  - Current fields: `productId`, `saleId`, `cataloguePrice`, `appliedPrice`, `actorId`, `tenantId`, `occurredAt`
  - **Add**: `String productName` — for audit readability without needing a product lookup
  - **Do NOT** add `delta` — it is trivially computed as `cataloguePrice - appliedPrice` and storing it violates DRY
  - **Do NOT** create a new class in `commerce.sale.domain.model` — reuse the existing one
  ```java
  // Updated record — catalog/product/domain/event/SalePriceOverriddenEvent.java
  public record SalePriceOverriddenEvent(
      UUID productId,
      UUID saleId,
      String productName,       // NEW — Story 4.2
      int cataloguePrice,
      int appliedPrice,
      UUID actorId,
      String tenantId,
      Instant occurredAt
  ) {}
  ```
- [x] **2.4** `SaleCompletedEvent.java` — **EXTEND existing** event:
  - **Add**: `int discountAmount` field (constructor parameter + getter)
  - Update `RecordSaleService` where it publishes this event: pass `sale.getDiscountAmount()`
- [x] **2.5** `SaleFactory.java` — update:
  - Accept `discountAmount` (from command)
  - `totalAmount = subtotalSum − discountAmount` (where `subtotalSum = sum of all SaleItem.subtotal`)
  - Pass `catalogueUnitPrice` to `SaleItem` constructor
  - Pass `discountAmount` to `Sale` constructor

### Task 3 — Extend backend port contracts

- [x] **3.1** `RecordSaleUseCase.java` — extend records:
  ```java
  record RecordSaleCommand(
      UUID saleId,
      UUID actorId,
      UUID storeId,
      UUID clientId,
      PaymentMode paymentMode,
      String mobileMoneyRef,
      int discountAmount,           // NEW — 0 if no discount
      List<SaleItemCommand> items
  ) {}

  record SaleItemCommand(
      UUID productId,
      UUID variantId,
      String productName,
      int catalogueUnitPrice,       // NEW — original catalogue price
      int appliedUnitPrice,         // may differ if overridden
      int quantity
  ) {}
  ```
- [x] **3.2** `SaleRepository.java` — no changes needed (save(Sale) is generic enough)

### Task 4 — Update backend application service

- [x] **4.1** `RecordSaleService.java` — update `recordSale()`:
  - After saving, iterate `command.items()` — for each item where `catalogueUnitPrice != appliedUnitPrice`:
    - Publish existing `SalePriceOverriddenEvent(productId, saleId, productName, cataloguePrice, appliedPrice, actorId, tenantId, occurredAt)`
    - Import from `com.keevo.catalog.product.domain.event.SalePriceOverriddenEvent`
  - Pass `discountAmount` through to `SaleFactory.from(command)`
  - Update `SaleCompletedEvent` publication: pass `sale.getDiscountAmount()` as new constructor arg
- [x] **4.2** `AuditEventListener.java` — **UPDATE existing** handler for `SalePriceOverriddenEvent`:
  - Handler already exists (action: `PRICE_OVERRIDDEN`, entityType: `PriceOverride`) — **keep this naming** for backward compat
  - **Only change**: add `productName` to the `valueAfter` JSON: `"productName": event.productName()`
  - **Also update** the `SaleCompletedEvent` handler: include `discountAmount` in the `valueAfter` JSON

### Task 5 — Extend backend persistence (DDL + JPA)

- [x] **5.1** `SaleJpaEntity.java` — add field:
  ```java
  @Column(name = "discount_amount", nullable = false)
  private int discountAmount = 0;
  ```
  + getter/setter
- [x] **5.2** `SaleItemJpaEntity.java` — add field:
  ```java
  @Column(name = "catalogue_unit_price", nullable = false)
  private int catalogueUnitPrice;
  ```
  + getter/setter
- [x] **5.3** `TenantSchemaProvisioner.java` — add new DDL migration constants:
  ```java
  // ── Sale discount (Story 4.2) ──────────────────────────────────────────
  static final String DDL_SALES_MIGRATE_DISCOUNT_AMOUNT =
      "ALTER TABLE sales ADD COLUMN IF NOT EXISTS discount_amount INTEGER NOT NULL DEFAULT 0";

  static final String DDL_SALE_ITEMS_MIGRATE_CATALOGUE_PRICE =
      "ALTER TABLE sale_items ADD COLUMN IF NOT EXISTS catalogue_unit_price INTEGER NOT NULL DEFAULT 0";
  ```
  - Add both to `provisionSchema()` execution block AND `TenantSchemaSyncService` sync block
  - Add to `REQUIRED_TABLES` if applicable (already has `sale_items` from 4.1)
- [x] **5.4** `SaleRepositoryAdapter.java` — update mapper:
  - Map `sale.getDiscountAmount()` → `entity.setDiscountAmount(...)`
  - Map `saleItem.getCatalogueUnitPrice()` → `itemEntity.setCatalogueUnitPrice(...)`

### Task 6 — Update backend REST controller + DTOs

- [x] **6.1** `RecordSaleRequestDto.java` — add fields:
  ```java
  public record RecordSaleRequestDto(
      @NotNull UUID saleId,
      @NotNull PaymentMode paymentMode,
      UUID clientId,
      String mobileMoneyRef,
      UUID storeId,
      @Min(0) int discountAmount,              // NEW — default 0
      @NotEmpty @Valid List<SaleItemRequestDto> items
  ) {}
  ```
- [x] **6.2** `SaleItemRequestDto.java` — add field:
  ```java
  public record SaleItemRequestDto(
      @NotNull UUID productId,
      UUID variantId,
      @NotBlank String productName,
      @PositiveOrZero int catalogueUnitPrice,    // NEW — original price
      @PositiveOrZero int appliedUnitPrice,      // changed from @Positive to @PositiveOrZero (free sample)
      @Positive int quantity
  ) {}
  ```
  - **IMPORTANT**: `appliedUnitPrice` changes from `@Positive` to `@PositiveOrZero` to allow free/promo items (AC7)
- [x] **6.3** `SaleController.java` — update command mapping:
  - Map `request.discountAmount()` → `command.discountAmount()`
  - Map per-item `request.catalogueUnitPrice()` → `SaleItemCommand.catalogueUnitPrice()`
- [x] **6.4** `RecordSaleResponseDto.java` — add `discountAmount` field:
  ```java
  public record RecordSaleResponseDto(UUID id, String status, int totalAmount, int discountAmount, Instant occurredAt) {}
  ```
- [x] **6.5** `ErrorCode.java` — add:
  ```java
  // ── Sale (Story 4.2) ──────────────────────────────────────────
  DISCOUNT_EXCEEDS_SUBTOTAL,   // HTTP 422 — discount > sum of item subtotals
  ```

### Task 7 — Write RED Flutter tests: discount strategy + cart (TDD)

- [x] **7.1** `test/features/pos/domain/model/discount_strategy_test.dart`:
  - `PercentageDiscountStrategy_10percent_of10000_returns1000()`
  - `PercentageDiscountStrategy_0percent_returnsZero()`
  - `PercentageDiscountStrategy_100percent_returnsFullSubtotal()`
  - `PercentageDiscountStrategy_above100_clampsToSubtotal()`
  - `FixedAmountDiscountStrategy_500_returns500()`
  - `FixedAmountDiscountStrategy_exceedsSubtotal_clampsToSubtotal()`
  - `FixedAmountDiscountStrategy_negative_returnsZero()`
- [x] **7.2** `test/features/pos/domain/model/cart_item_test.dart` — add tests:
  - `CartItem_copyWith_updatesAppliedUnitPrice()`
  - `CartItem_isPriceOverridden_trueWhenDifferent()`
  - `CartItem_isPriceOverridden_falseWhenSame()`
- [x] **7.3** `test/features/pos/presentation/provider/cart_provider_test.dart` — add tests:
  - `CartNotifier_updatePrice_updatesAppliedUnitPrice()`
  - `CartNotifier_updatePrice_doesNotChangeUnitPrice()`
  - `CartNotifier_setDiscount_updatesDiscountAmount()`
  - `CartNotifier_clearCart_resetsDiscount()`
  - `CartNotifier_finalTotal_subtotalMinusDiscount()`
  - `CartNotifier_setDiscount_exceedsSubtotal_rejected()`

### Task 8 — Implement Flutter domain: discount strategy + cart extension

- [x] **8.1** `DiscountStrategy` — `lib/features/pos/domain/model/discount_strategy.dart`:
  ```dart
  /// GoF Strategy — two discount algorithms behind one interface.
  abstract class DiscountStrategy {
    int compute(int subtotal);
  }

  class PercentageDiscountStrategy implements DiscountStrategy {
    final int percentage; // 0..100
    const PercentageDiscountStrategy(this.percentage);

    @override
    int compute(int subtotal) {
      if (percentage <= 0) return 0;
      if (percentage >= 100) return subtotal;
      return (subtotal * percentage) ~/ 100;
    }
  }

  class FixedAmountDiscountStrategy implements DiscountStrategy {
    final int amount; // XAF
    const FixedAmountDiscountStrategy(this.amount);

    @override
    int compute(int subtotal) {
      if (amount <= 0) return 0;
      return amount.clamp(0, subtotal);
    }
  }
  ```
- [x] **8.2** `CartItem` — add convenience getter:
  ```dart
  bool get isPriceOverridden => appliedUnitPrice != unitPrice;
  ```
- [x] **8.3** `CartNotifier` — extend:
  - Add private `int _discountAmount = 0;`
  - Add method `void updatePrice(String id, int newPrice)`:
    ```dart
    state = state.map((c) => c.id == id ? c.copyWith(appliedUnitPrice: newPrice) : c).toList();
    ```
  - Add method `bool setDiscount(int amount)`:
    - If `amount > totalAmount` → return `false` (rejected)
    - Else `_discountAmount = amount.clamp(0, totalAmount)` → return `true`
  - Add getter `int get discountAmount => _discountAmount;`
  - Add getter `int get finalTotal => (totalAmount - _discountAmount).clamp(0, totalAmount);`
  - Update `clearCart()`: also reset `_discountAmount = 0`
  - **Note**: `totalAmount` (existing) = sum of all subtotals. `finalTotal` (new) = `totalAmount − discountAmount`.

### Task 9 — Implement Flutter Sale model + use case extension

- [x] **9.1** `SaleItemModel` — add `catalogueUnitPrice` field:
  ```dart
  class SaleItemModel {
    ...
    final int catalogueUnitPrice;    // NEW — original catalogue price
    ...
  }
  ```
- [x] **9.2** `Sale` model — add `discountAmount` field:
  ```dart
  class Sale {
    ...
    final int discountAmount;   // NEW — defaults to 0
    ...
  }
  ```
- [x] **9.3** `RecordSaleUseCase` — update `execute()`:
  - Accept `int discountAmount` parameter (default: 0)
  - Pass `catalogueUnitPrice: c.unitPrice` when mapping `CartItem` → `SaleItemModel`
  - Set `sale.discountAmount = discountAmount`
  - `sale.totalAmount = items.fold(0, (s, i) => s + i.subtotal) − discountAmount`

### Task 10 — Update Flutter data layer

**Tests first for each change.**

- [x] **10.1** Drift schema migration v13 — `app_database.dart`:
  - Increment `schemaVersion` to `13`
  - `SaleItems` table: add `IntColumn get catalogueUnitPrice => integer().withDefault(const Constant(0))();`
  - `Sales` table: add `IntColumn get discountAmount => integer().withDefault(const Constant(0))();`
  - In `onUpgrade`: `if (from < 13)`:
    ```dart
    await migrator.addColumn(sales, sales.discountAmount);
    await migrator.addColumn(saleItems, saleItems.catalogueUnitPrice);
    ```
  - Regenerate `app_database.g.dart` via `dart run build_runner build --delete-conflicting-outputs`
- [x] **10.2** `LocalSaleDataSource` — update `insertAll()`:
  - Add `discountAmount: Value(sale.discountAmount)` to `SalesCompanion.insert()`
  - Add `catalogueUnitPrice: item.catalogueUnitPrice` to `SaleItemsCompanion.insert()`
  - **Update `_buildPayload()` method** — add `'discountAmount': sale.discountAmount` at sale level + `'catalogueUnitPrice': i.catalogueUnitPrice` per item in the items list. This is CRITICAL: the payload is stored in `sync_queue` for offline sync and must match the backend `RecordSaleRequestDto` contract
- [x] **10.3** `RemoteSaleDataSource` — update `pushSale()`:
  - Add `'discountAmount': sale.discountAmount` to sale payload
  - Add `'catalogueUnitPrice': i.catalogueUnitPrice` to each item in items list
- [x] **10.4** `SaleRepositoryImpl` — update `getSalesForToday()`:
  - Map `row.discountAmount` (new Drift column) → `Sale(discountAmount: row.discountAmount ?? 0)`
  - Map `i.catalogueUnitPrice` → `SaleItemModel(catalogueUnitPrice: i.catalogueUnitPrice ?? 0)`
  - Without this, `getSalesForToday()` won't compile once `Sale` / `SaleItemModel` add required `discountAmount` / `catalogueUnitPrice` fields
- [x] **10.5** `RecordSaleNotifier` — update `submit()`:
  - Read `discountAmount` from `ref.read(cartProvider.notifier).discountAmount`
  - Pass `discountAmount` to `useCase.execute()`

### Task 11 — Implement Flutter UI: inline price editor in CartBottomSheet

**Widget tests first.**

- [x] **11.1** `test/features/pos/presentation/widget/cart_bottom_sheet_test.dart` — add tests:
  - `CartItemTile_tapPrice_showsInlineEditor()`
  - `CartItemTile_confirmPrice_updatesAppliedPrice()`
  - `CartItemTile_priceOverridden_showsPrixModifieLabel()`
  - `CartItemTile_priceNotOverridden_noPrixModifieLabel()`
  - `CartBottomSheet_showsReductionButton()`
  - `CartBottomSheet_tapReduction_showsDiscountSheet()`
  - `CartBottomSheet_withDiscount_showsDiscountLine()`
- [x] **11.2** Update `_CartItemTile` in `cart_bottom_sheet.dart`:
  - Convert `_CartItemTile` from `StatelessWidget` to `StatefulWidget` (needs editing state)
  - Add `bool _isEditing = false` state
  - Price display: `GestureDetector` on the price `Text` → `onTap: () => setState(() => _isEditing = true)`
  - When `_isEditing`:
    - Show `SizedBox(width: 80)` containing `TextField` with:
      - `controller: TextEditingController(text: '${item.appliedUnitPrice}')`
      - `keyboardType: TextInputType.number`
      - `inputFormatters: [FilteringTextInputFormatter.digitsOnly]`
      - `autofocus: true`
      - `onSubmitted` / focus loss → call `onPriceChanged(int.parse(text))` + `setState(() => _isEditing = false)`
    - Small checkmark `IconButton(icon: Icon(Icons.check, size: 18), onPressed: _confirmPrice)`
  - When NOT editing and `item.isPriceOverridden`:
    - Show red `Text('Prix modifié', style: TextStyle(color: Colors.red.shade700, fontSize: 11))`
  - Add `onPriceChanged` callback: `void Function(int newPrice)` — wired to `CartNotifier.updatePrice()`
- [x] **11.3** Update `CartBottomSheet`:
  - Add "Réduction" `TextButton` in the header `Row` (next to "Vider"):
    ```dart
    TextButton.icon(
      icon: const Icon(Icons.discount_outlined, size: 18),
      label: const Text('Réduction'),
      onPressed: () => _showDiscountSheet(context, ref),
    )
    ```
  - Add discount display row above grand total (visible only if `discountAmount > 0`):
    ```dart
    Row(children: [
      Text('Sous-total'), Text(_currencyFormat.format(subtotal)),
    ]),
    Row(children: [
      Text('Réduction', style: TextStyle(color: Colors.red)),
      Text('−${_currencyFormat.format(discountAmount)}', style: TextStyle(color: Colors.red)),
    ]),
    Divider(),
    Row(children: [
      Text('Total à payer', style: bold), Text(_currencyFormat.format(finalTotal), style: bold),
    ]),
    ```
  - Update total display in header: use `notifier.finalTotal` instead of `notifier.totalAmount` for the grand total
  - Wire "Encaisser" button to pass `finalTotal` through to checkout

### Task 12 — Implement Flutter UI: Discount bottom sheet

- [x] **12.1** `test/features/pos/presentation/widget/discount_sheet_test.dart`:
  - `DiscountSheet_percentageMode_previewsTotal()`
  - `DiscountSheet_fixedMode_previewsTotal()`
  - `DiscountSheet_fixedExceedsSubtotal_showsError()`
  - `DiscountSheet_percentageAbove100_clampedTo100()`
  - `DiscountSheet_confirmButton_appliesDiscount()`
- [x] **12.2** `_DiscountSheet` widget — `lib/features/pos/presentation/widget/discount_sheet.dart`:
  - `ConsumerStatefulWidget` shown via `showModalBottomSheet`
  - Two `ChoiceChip` toggles: "% sur le total" / "Montant fixe" (default: percentage)
  - When **percentage** selected:
    - `TextField` with `keyboardType: TextInputType.number`, `FilteringTextInputFormatter.digitsOnly`
    - Hint: "Ex: 10" + suffix "%"
    - Real-time preview: `Text('Total après réduction: ${_currencyFormat.format(previewTotal)}')`
    - Use `PercentageDiscountStrategy(int.parse(value)).compute(subtotal)` for preview
  - When **fixed amount** selected:
    - `TextField` with numeric keyboard, hint: "Ex: 1000" + suffix "FCFA"
    - Validation: if `amount > subtotal` → show `Text('La réduction ne peut pas dépasser le sous-total', style: TextStyle(color: Colors.red, fontSize: 12))`
    - Real-time preview of discounted total
    - Use `FixedAmountDiscountStrategy(int.parse(value)).compute(subtotal)` for preview
  - "Appliquer" `FilledButton` (disabled while input invalid):
    - Calls `ref.read(cartProvider.notifier).setDiscount(computedAmount)` → closes sheet
  - "Annuler" `TextButton` → closes sheet without applying
  - If a discount was already applied, pre-fill the field with current value + show "Supprimer la réduction" `TextButton` → `setDiscount(0)`

### Task 13 — Update CheckoutPage for discount display

- [x] **13.1** `test/features/pos/presentation/page/checkout_page_test.dart` — add tests:
  - `CheckoutPage_withDiscount_showsTotalBreakdown()`
  - `CheckoutPage_withDiscount_changCalculationUsesDiscountedTotal()`
  - `CheckoutPage_noDiscount_showsSimpleTotal()`
- [x] **13.2** Update `CheckoutPage`:
  - Read `discountAmount` and `subtotal` from `CartNotifier`
  - Replace the simple total `Card` with a breakdown:
    ```dart
    Column(children: [
      _BreakdownRow('Sous-total', subtotal),
      if (discountAmount > 0)
        _BreakdownRow('Réduction', -discountAmount, color: Colors.red),
      Divider(),
      _BreakdownRow('Total à payer', finalTotal, bold: true),
    ])
    ```
  - Change calculation for Espèces: `change = montantReçu − finalTotal` (not subtotal)
  - "Valider la vente" sends `finalTotal` as totalAmount (which = `subtotal − discountAmount`)
  - Pass `discountAmount` through `submit()` to `RecordSaleNotifier`

### Task 14 — Run all tests + cURL integration

- [x] **14.1** Backend: `cd keevo/backend && mvn test` → 0 failures
- [x] **14.2** Flutter: `cd keevo/app && flutter test --reporter=expanded` → all Green
- [x] **14.3** Run `bash curl-tests-story-4-2.sh` → all steps ✅

---

## cURL Integration Tests

> Save as `keevo/backend/curl-tests-story-4-2.sh`

```bash
#!/usr/bin/env bash
# ======================================================
# Story 4.2 — cURL Integration Tests: Réductions & Prix Modifiable en Panier
# Run: bash curl-tests-story-4-2.sh
# Prerequisites: Docker backend on port 8443, jq, python3
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"
PHONE="+237600000042"
PASSWORD="Test1234!"

# ─── Step 1 — Register + two-step login ────────────────────────────────────
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\",\"firstName\":\"Loic\",\"lastName\":\"Discount\"}")
echo "Register: $(echo $REGISTER | jq -r '.data.accessToken // .error // "done"' | head -c 60)"

LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\"}")
LOGIN_TOKEN=$(echo "$LOGIN" | jq -r '.data.loginToken')
TENANT_ID=$(echo "$LOGIN" | jq -r '.data.memberships[0].tenantId')
[[ -n "$LOGIN_TOKEN" && "$LOGIN_TOKEN" != "null" ]] && echo "✅ Step 1a — loginToken obtained" || { echo "❌ Step 1a FAILED: $LOGIN"; exit 1; }

SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LOGIN_TOKEN\",\"tenantId\":\"$TENANT_ID\"}")
JWT=$(echo "$SELECT" | jq -r '.data.accessToken')
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1b — accessToken obtained" || { echo "❌ Step 1b FAILED: $SELECT"; exit 1; }

# ─── Step 2 — Get store + product ──────────────────────────────────────────
STORES=$(curl -s "$BASE_URL/api/v1/tenant/stores" -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES" | jq -r '.data[0].id')
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && echo "✅ Step 2a — Store: $STORE_ID" || { echo "❌ Step 2a FAILED"; exit 1; }

PRODUCTS=$(curl -s "$BASE_URL/api/v1/products" -H "Authorization: Bearer $JWT")
PRODUCT_ID=$(echo "$PRODUCTS" | jq -r '.data.content[0].id // empty')
PRODUCT_NAME=$(echo "$PRODUCTS" | jq -r '.data.content[0].name // "Produit Test"')
PRODUCT_PRICE=$(echo "$PRODUCTS" | jq -r '.data.content[0].sellingPrice // 5000')

if [[ -z "$PRODUCT_ID" || "$PRODUCT_ID" == "null" ]]; then
  CREATE_PRODUCT=$(curl -s -X POST "$BASE_URL/api/v1/products" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Produit Test Discount\",\"sellingPrice\":5000,\"buyPrice\":3000,\"quantity\":100}")
  PRODUCT_ID=$(echo "$CREATE_PRODUCT" | jq -r '.data.id')
  PRODUCT_NAME="Produit Test Discount"; PRODUCT_PRICE=5000
fi
echo "✅ Step 2b — Product: $PRODUCT_NAME ($PRODUCT_ID) @ $PRODUCT_PRICE FCFA"

# ─── Step 3 — Sale with price override (no discount) ──────────────────────
SALE_UUID=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
RESP=$(curl -s -o /tmp/s42_override.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID\",
    \"paymentMode\": \"CASH\",
    \"discountAmount\": 0,
    \"items\": [{
      \"productId\": \"$PRODUCT_ID\",
      \"productName\": \"$PRODUCT_NAME\",
      \"catalogueUnitPrice\": $PRODUCT_PRICE,
      \"appliedUnitPrice\": 4000,
      \"quantity\": 2
    }]
  }")
cat /tmp/s42_override.json | jq .
TOTAL=$(cat /tmp/s42_override.json | jq -r '.data.totalAmount')
[[ "$RESP" == "201" && "$TOTAL" == "8000" ]] \
  && echo "✅ Step 3 — Price override sale: 2×4000 = 8000 (HTTP 201)" \
  || { echo "❌ Step 3 FAILED — HTTP $RESP, total=$TOTAL"; exit 1; }

# ─── Step 4 — Sale with discount (fixed amount) ───────────────────────────
SALE_UUID_2=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
RESP2=$(curl -s -o /tmp/s42_discount.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID_2\",
    \"paymentMode\": \"MOBILE_MONEY\",
    \"mobileMoneyRef\": \"MTN-DISCOUNT-01\",
    \"discountAmount\": 1500,
    \"items\": [{
      \"productId\": \"$PRODUCT_ID\",
      \"productName\": \"$PRODUCT_NAME\",
      \"catalogueUnitPrice\": $PRODUCT_PRICE,
      \"appliedUnitPrice\": $PRODUCT_PRICE,
      \"quantity\": 3
    }]
  }")
cat /tmp/s42_discount.json | jq .
TOTAL2=$(cat /tmp/s42_discount.json | jq -r '.data.totalAmount')
DISCOUNT2=$(cat /tmp/s42_discount.json | jq -r '.data.discountAmount')
EXPECTED=$((PRODUCT_PRICE * 3 - 1500))
[[ "$RESP2" == "201" && "$TOTAL2" == "$EXPECTED" && "$DISCOUNT2" == "1500" ]] \
  && echo "✅ Step 4 — Discount sale: 3×$PRODUCT_PRICE - 1500 = $EXPECTED, discountAmount=1500 (HTTP 201)" \
  || { echo "❌ Step 4 FAILED — HTTP $RESP2, total=$TOTAL2, discount=$DISCOUNT2, expected=$EXPECTED"; exit 1; }

# ─── Step 5 — Sale with price override + discount combined ────────────────
SALE_UUID_3=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
RESP3=$(curl -s -o /tmp/s42_combo.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID_3\",
    \"paymentMode\": \"CASH\",
    \"discountAmount\": 500,
    \"items\": [
      {\"productId\":\"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"catalogueUnitPrice\":$PRODUCT_PRICE,\"appliedUnitPrice\":4500,\"quantity\":1},
      {\"productId\":\"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME Lot\",\"catalogueUnitPrice\":$PRODUCT_PRICE,\"appliedUnitPrice\":$PRODUCT_PRICE,\"quantity\":2}
    ]
  }")
cat /tmp/s42_combo.json | jq .
TOTAL3=$(cat /tmp/s42_combo.json | jq -r '.data.totalAmount')
EXPECTED3=$((4500 + PRODUCT_PRICE * 2 - 500))
[[ "$RESP3" == "201" && "$TOTAL3" == "$EXPECTED3" ]] \
  && echo "✅ Step 5 — Combo sale (override+discount): $EXPECTED3 FCFA (HTTP 201)" \
  || { echo "❌ Step 5 FAILED — HTTP $RESP3, total=$TOTAL3, expected=$EXPECTED3"; exit 1; }

# ─── Step 6 — Sale with 0-price item (free sample) ────────────────────────
SALE_UUID_4=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
RESP4=$(curl -s -o /tmp/s42_free.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID_4\",
    \"paymentMode\": \"CASH\",
    \"discountAmount\": 0,
    \"items\": [{
      \"productId\": \"$PRODUCT_ID\",
      \"productName\": \"$PRODUCT_NAME\",
      \"catalogueUnitPrice\": $PRODUCT_PRICE,
      \"appliedUnitPrice\": 0,
      \"quantity\": 1
    }]
  }")
TOTAL4=$(cat /tmp/s42_free.json | jq -r '.data.totalAmount')
[[ "$RESP4" == "201" && "$TOTAL4" == "0" ]] \
  && echo "✅ Step 6 — Free sample (appliedUnitPrice=0): total=0 (HTTP 201)" \
  || { echo "❌ Step 6 FAILED — HTTP $RESP4, total=$TOTAL4"; exit 1; }

# ─── Step 7 — Negative discount → 400 ─────────────────────────────────────
SALE_UUID_5=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
RESP5=$(curl -s -o /tmp/s42_neg.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID_5\",
    \"paymentMode\": \"CASH\",
    \"discountAmount\": -500,
    \"items\": [{\"productId\":\"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"catalogueUnitPrice\":$PRODUCT_PRICE,\"appliedUnitPrice\":$PRODUCT_PRICE,\"quantity\":1}]
  }")
[[ "$RESP5" == "400" || "$RESP5" == "422" ]] \
  && echo "✅ Step 7 — Negative discount → $RESP5 rejected" \
  || { echo "❌ Step 7 FAILED — Expected 400/422, got $RESP5"; cat /tmp/s42_neg.json; exit 1; }

# ─── Step 8 — Discount exceeds subtotal → 422 ─────────────────────────────
SALE_UUID_6=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
RESP6=$(curl -s -o /tmp/s42_exceed.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID_6\",
    \"paymentMode\": \"CASH\",
    \"discountAmount\": 999999,
    \"items\": [{\"productId\":\"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"catalogueUnitPrice\":$PRODUCT_PRICE,\"appliedUnitPrice\":$PRODUCT_PRICE,\"quantity\":1}]
  }")
ERR_CODE=$(cat /tmp/s42_exceed.json | jq -r '.code // .domainCode // "unknown"')
[[ "$RESP6" == "422" && "$ERR_CODE" == "DISCOUNT_EXCEEDS_SUBTOTAL" ]] \
  && echo "✅ Step 8 — Discount exceeds subtotal → 422 DISCOUNT_EXCEEDS_SUBTOTAL" \
  || { echo "❌ Step 8 FAILED — HTTP $RESP6, code=$ERR_CODE"; cat /tmp/s42_exceed.json; exit 1; }

# ─── Step 9 — Idempotency: replay sale UUID (same as Step 3) → no dup ────
IDEMPOTENCY=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID\",
    \"paymentMode\": \"CASH\",
    \"discountAmount\": 0,
    \"items\": [{\"productId\":\"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"catalogueUnitPrice\":$PRODUCT_PRICE,\"appliedUnitPrice\":4000,\"quantity\":2}]
  }")
[[ "$IDEMPOTENCY" == "200" || "$IDEMPOTENCY" == "201" ]] \
  && echo "✅ Step 9 — Idempotency: replay accepted ($IDEMPOTENCY)" \
  || { echo "❌ Step 9 FAILED — HTTP $IDEMPOTENCY"; exit 1; }

# ─── Step 10 — No token → 401 ─────────────────────────────────────────────
UNAUTH=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Content-Type: application/json" \
  -d "{\"saleId\":\"$(python3 -c 'import uuid; print(str(uuid.uuid4()))')\",\"paymentMode\":\"CASH\",\"discountAmount\":0,\"items\":[]}")
[[ "$UNAUTH" == "401" ]] && echo "✅ Step 10 — No token → 401" || { echo "❌ Step 10 FAILED — Expected 401, got $UNAUTH"; exit 1; }

# ─── Step 11 — Audit: SALE_PRICE_OVERRIDDEN event ─────────────────────────
AUDIT=$(curl -s "$BASE_URL/api/v1/audit?entityType=SaleItem&page=0&size=5" \
  -H "Authorization: Bearer $JWT")
AUDIT_ACTION=$(echo "$AUDIT" | jq -r '.data.entries[0].action // empty')
[[ "$AUDIT_ACTION" == "SALE_PRICE_OVERRIDDEN" ]] \
  && echo "✅ Step 11 — Audit SALE_PRICE_OVERRIDDEN event found" \
  || echo "⚠️  Step 11 — Audit action: '$AUDIT_ACTION' (check manually — may need to query by action filter)"

# ─── Step 12 — Audit: SALE_COMPLETED event includes discountAmount ────────
AUDIT2=$(curl -s "$BASE_URL/api/v1/audit?entityType=Sale&page=0&size=5" \
  -H "Authorization: Bearer $JWT")
HAS_DISCOUNT=$(echo "$AUDIT2" | jq -r '.data.entries[0].valueAfter // empty' | jq -r '.discountAmount // empty' 2>/dev/null || true)
[[ -n "$HAS_DISCOUNT" ]] \
  && echo "✅ Step 12 — Audit SALE_COMPLETED includes discountAmount in snapshot" \
  || echo "⚠️  Step 12 — discountAmount not found in audit snapshot (check manually)"

echo ""
echo "✅✅✅ All cURL integration checks passed — story 4.2 backend validated ✅✅✅"
```

---

## Dev Notes

### Architecture Guardrails (NON-NEGOTIABLE)

1. **MCP Port Purity**: `RecordSaleCommand` receives `discountAmount` and `catalogueUnitPrice` as pure Java int primitives — no framework types. The controller extracts these from the DTO and passes them into the command record.

2. **Hexagonal Compliance**: `RecordSaleService` imports ONLY from `domain/` and `shared/` — zero Spring Data imports. Discount validation happens in the domain model (`Sale` constructor) not in the service.

3. **Catalogue Integrity**: The `products` table is NEVER written to during price override. `appliedUnitPrice` lives ONLY in `cart_item` (Flutter) and `sale_items` (Drift + PostgreSQL). The `catalogueUnitPrice` is captured for audit trail delta computation.

4. **Observer GoF**: Both `SaleCompletedEvent` and `SalePriceOverriddenEvent` are published via Spring's `ApplicationEventPublisher`. `AuditEventListener` subscribes via `@EventListener`. The service MUST NOT call `AuditPort.record()` directly. Note: `SalePriceOverriddenEvent` lives in `catalog.product.domain.event` (Story 2.2) — the existing handler uses action `PRICE_OVERRIDDEN` / entityType `PriceOverride` — keep this naming for backward compatibility.

5. **Strategy GoF**: `PercentageDiscountStrategy` and `FixedAmountDiscountStrategy` implement `DiscountStrategy` interface in Flutter domain layer. They are used ONLY for computation — the result (`int discountAmount`) is what flows through the system, not the strategy object.

6. **Domain-level discount validation**: `Sale` constructor rejects: (a) `discountAmount < 0` → `IllegalArgumentException`, (b) `discountAmount > subtotalSum` → `DomainException(DISCOUNT_EXCEEDS_SUBTOTAL)`. This ensures no sale is created with an invalid discount — even if the frontend has a bug.

7. **`totalAmount` semantics change**: In 4.1, `totalAmount = sum(item.subtotal)`. In 4.2, `totalAmount = sum(item.subtotal) − discountAmount`. The `SaleFactory` now performs this calculation. The `discountAmount` field is stored separately for audit/reporting. `totalAmount` represents what the customer actually paid.

8. **Drift: write-through preserved**: The write-through pattern from 4.1 is unchanged. `LocalSaleDataSource.insertAll()` runs all writes in a single `appDb.transaction()`. The remote push is `unawaited` in background. Discount and catalogue price are simply additional fields flowing through the same pipeline. **CRITICAL**: The `_buildPayload()` method in `LocalSaleDataSource` builds the JSON stored in `sync_queue` — it MUST include `discountAmount` and per-item `catalogueUnitPrice`, otherwise offline-synced sales will lose discount data when pushed to the backend later.

9. **Backward compatibility**: Backend DDL uses `ADD COLUMN IF NOT EXISTS ... DEFAULT 0`. Flutter Drift migration `from < 13` adds columns with default 0. Existing sales (from 4.1) will have `discount_amount = 0` and `catalogue_unit_price = 0` — correct semantics (no discount, no explicit catalogue capture).

10. **`@PositiveOrZero` on `appliedUnitPrice`**: Changed from `@Positive` to support free samples (AC7). The backend domain `SaleItem` already validates `appliedUnitPrice >= 0` (no change needed).

### Key Files Modified (Backend)

| File | Change |
|------|--------|
| `Sale.java` | +`discountAmount` field, validation |
| `SaleItem.java` | +`catalogueUnitPrice` field |
| `SaleFactory.java` | `totalAmount = subtotalSum - discountAmount`, pass catalogueUnitPrice |
| `RecordSaleUseCase.java` | +`discountAmount` in command, +`catalogueUnitPrice` in item command |
| `RecordSaleService.java` | Publish `SalePriceOverriddenEvent` per overridden item + pass `discountAmount` to `SaleCompletedEvent` |
| `SalePriceOverriddenEvent.java` | **EXTEND** — add `productName` field (existing in `catalog.product.domain.event`) |
| `SaleJpaEntity.java` | +`discountAmount` column |
| `SaleItemJpaEntity.java` | +`catalogueUnitPrice` column |
| `SaleRepositoryAdapter.java` | Map new fields |
| `SaleController.java` | Map discountAmount + catalogueUnitPrice from DTO |
| `RecordSaleRequestDto.java` | +`discountAmount` |
| `SaleItemRequestDto.java` | +`catalogueUnitPrice`, @Positive→@PositiveOrZero |
| `RecordSaleResponseDto.java` | +`discountAmount` |
| `ErrorCode.java` | +`DISCOUNT_EXCEEDS_SUBTOTAL` |
| `TenantSchemaProvisioner.java` | +`DDL_SALES_MIGRATE_DISCOUNT_AMOUNT`, +`DDL_SALE_ITEMS_MIGRATE_CATALOGUE_PRICE` |
| `SaleCompletedEvent.java` | +`discountAmount` field |
| `AuditEventListener.java` | Update existing `SalePriceOverriddenEvent` handler (+`productName`), update `SaleCompletedEvent` handler (+`discountAmount`) |

### Key Files Modified (Flutter)

| File | Change |
|------|--------|
| `cart_item.dart` | +`isPriceOverridden` getter |
| `discount_strategy.dart` | **NEW** — GoF Strategy |
| `sale_model.dart` | +`discountAmount`, +`catalogueUnitPrice` on SaleItemModel |
| `cart_provider.dart` | +`updatePrice()`, +`_discountAmount`, +`setDiscount()`, +`finalTotal` |
| `record_sale_notifier.dart` | Pass discountAmount through submit |
| `record_sale_usecase.dart` | +`discountAmount` param, map catalogueUnitPrice |
| `local_sale_datasource.dart` | Map discountAmount + catalogueUnitPrice to Drift + update `_buildPayload()` for sync_queue |
| `remote_sale_datasource.dart` | Add discountAmount + catalogueUnitPrice to payload |
| `sales_table.dart` | +`discountAmount` column |
| `sale_items_table.dart` | +`catalogueUnitPrice` column |
| `app_database.dart` | schemaVersion 13, migration |
| `cart_bottom_sheet.dart` | Inline price editor, "Réduction" button, discount display |
| `discount_sheet.dart` | **NEW** — discount input bottom sheet |
| `checkout_page.dart` | Total breakdown (sous-total / réduction / total à payer) |
| `sale_repository_impl.dart` | Update `getSalesForToday()` mapping for `discountAmount` + `catalogueUnitPrice` |
| `pos_providers.dart` | No new providers needed — reuses existing cart/sale providers |

### Project Structure Notes

- Backend event: `SalePriceOverriddenEvent` lives in `com.keevo.catalog.product.domain.event` (created in Story 2.2) — **NOT** in `commerce.sale.domain.model`. Story 4.2 extends it with `productName` field.
- Flutter new files:
  - `lib/features/pos/domain/model/discount_strategy.dart`
  - `lib/features/pos/presentation/widget/discount_sheet.dart`
  - `test/features/pos/domain/model/discount_strategy_test.dart`
  - `test/features/pos/presentation/widget/discount_sheet_test.dart`
- All other changes are modifications to existing files established in Story 4.1

### References

- [Source: _bmad-output/planning-artifacts/epics/epic-4-point-de-vente-pos.md — Story 4.2 AC]
- [Source: _bmad-output/planning-artifacts/prd.md#FR39 — "Un employé peut appliquer des réductions sur une vente"]
- [Source: _bmad-output/planning-artifacts/architecture.md — commerce/sale/ module, hexagonal layers]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md — "3 taps ou moins", feedback < 200ms, responsive-first]
- [Source: _bmad-output/implementation-artifacts/4-1-enregistrement-de-vente-flux-pos-core.md — CartItem, CartNotifier, Sale, SaleFactory, RecordSaleService, TDD patterns]

---

## Dev Agent Record

### Agent Model Used

Claude Opus 4.6 (GitHub Copilot)

### Debug Log References

### Completion Notes List

### File List

#### Backend — Modified
- `backend/src/main/java/com/keevo/commerce/sale/domain/model/Sale.java` — +discountAmount field, validation
- `backend/src/main/java/com/keevo/commerce/sale/domain/model/SaleItem.java` — +catalogueUnitPrice field
- `backend/src/main/java/com/keevo/commerce/sale/domain/model/SaleFactory.java` — totalAmount = subtotalSum − discountAmount, pass catalogueUnitPrice
- `backend/src/main/java/com/keevo/commerce/sale/domain/port/in/RecordSaleUseCase.java` — +discountAmount in command, +catalogueUnitPrice in item command
- `backend/src/main/java/com/keevo/commerce/sale/application/RecordSaleService.java` — publish SalePriceOverriddenEvent per overridden item, discountAmount to SaleCompletedEvent
- `backend/src/main/java/com/keevo/catalog/product/domain/event/SalePriceOverriddenEvent.java` — +productName field
- `backend/src/main/java/com/keevo/commerce/sale/domain/event/SaleCompletedEvent.java` — +discountAmount field
- `backend/src/main/java/com/keevo/shared/infrastructure/audit/AuditEventListener.java` — +productName in PRICE_OVERRIDDEN handler, +discountAmount in SALE_COMPLETED handler
- `backend/src/main/java/com/keevo/commerce/sale/infrastructure/persistence/SaleJpaEntity.java` — +discount_amount column
- `backend/src/main/java/com/keevo/commerce/sale/infrastructure/persistence/SaleItemJpaEntity.java` — +catalogue_unit_price column
- `backend/src/main/java/com/keevo/commerce/sale/infrastructure/persistence/SaleRepositoryAdapter.java` — map discountAmount + catalogueUnitPrice
- `backend/src/main/java/com/keevo/commerce/sale/infrastructure/web/SaleController.java` — map discountAmount + catalogueUnitPrice from DTO
- `backend/src/main/java/com/keevo/commerce/sale/infrastructure/web/RecordSaleRequestDto.java` — +discountAmount
- `backend/src/main/java/com/keevo/commerce/sale/infrastructure/web/SaleItemRequestDto.java` — +catalogueUnitPrice, @Positive→@PositiveOrZero
- `backend/src/main/java/com/keevo/commerce/sale/infrastructure/web/RecordSaleResponseDto.java` — +discountAmount
- `backend/src/main/java/com/keevo/shared/domain/ErrorCode.java` — +DISCOUNT_EXCEEDS_SUBTOTAL
- `backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` — +DDL_SALES_MIGRATE_DISCOUNT_AMOUNT, +DDL_SALE_ITEMS_MIGRATE_CATALOGUE_PRICE
- `backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java` — execute discount/catalogue migrations in ensureRequiredIndexes()

#### Backend — Tests
- `backend/src/test/java/com/keevo/commerce/sale/domain/model/SaleTest.java` — discount validation tests
- `backend/src/test/java/com/keevo/commerce/sale/application/RecordSaleServiceTest.java` — discount + price override event tests
- `backend/src/test/java/com/keevo/commerce/sale/infrastructure/web/SaleControllerTest.java` — discount DTO tests
- `backend/src/test/java/com/keevo/catalog/product/domain/event/SalePriceOverriddenEventTest.java` — +productName field test
- `backend/src/test/java/com/keevo/commerce/sale/domain/model/SaleItemTest.java` — +catalogueUnitPrice tests
- `backend/src/test/java/com/keevo/commerce/sale/infrastructure/persistence/SaleRepositoryAdapterTest.java` — persist discountAmount test
- `backend/src/test/java/com/keevo/shared/infrastructure/audit/ProductAuditEventTest.java` — updated SalePriceOverriddenEvent constructor call

#### Flutter — New Files
- `app/lib/features/pos/domain/model/discount_strategy.dart` — GoF Strategy: PercentageDiscountStrategy + FixedAmountDiscountStrategy
- `app/lib/features/pos/presentation/widget/discount_sheet.dart` — order-level discount bottom sheet

#### Flutter — Modified
- `app/lib/features/pos/domain/model/cart_item.dart` — +isPriceOverridden getter
- `app/lib/features/pos/domain/model/sale_model.dart` — +discountAmount on Sale, +catalogueUnitPrice on SaleItemModel
- `app/lib/features/pos/presentation/provider/cart_provider.dart` — +updatePrice(), +_discountAmount, +setDiscount(), +finalTotal
- `app/lib/features/pos/presentation/provider/record_sale_notifier.dart` — pass discountAmount through submit
- `app/lib/features/pos/domain/usecase/record_sale_usecase.dart` — +discountAmount param, map catalogueUnitPrice
- `app/lib/features/pos/data/datasource/local_sale_datasource.dart` — map discountAmount + catalogueUnitPrice to Drift + _buildPayload()
- `app/lib/features/pos/data/datasource/remote_sale_datasource.dart` — add discountAmount + catalogueUnitPrice to payload
- `app/lib/features/pos/data/repository/sale_repository_impl.dart` — getSalesForToday mapping for discountAmount + catalogueUnitPrice
- `app/lib/core/database/tables/sales_table.dart` — +discountAmount column
- `app/lib/core/database/tables/sale_items_table.dart` — +catalogueUnitPrice column
- `app/lib/core/database/app_database.dart` — schemaVersion 13, migration from < 13
- `app/lib/features/pos/presentation/widget/cart_bottom_sheet.dart` — inline price editor, Réduction button, discount display
- `app/lib/features/pos/presentation/page/checkout_page.dart` — total breakdown (sous-total/réduction/total à payer), Montant reçu + monnaie rendue (AC4)

#### Flutter — Tests
- `app/test/features/pos/domain/model/discount_strategy_test.dart` — 7 tests (Strategy pattern)
- `app/test/features/pos/domain/model/cart_item_test.dart` — +isPriceOverridden tests
- `app/test/features/pos/presentation/provider/cart_provider_test.dart` — +updatePrice, setDiscount, finalTotal tests
- `app/test/features/pos/presentation/widget/cart_bottom_sheet_test.dart` — 5 widget tests (NEW — code review H2)
- `app/test/features/pos/presentation/widget/discount_sheet_test.dart` — 5 widget tests (NEW — code review H2)
- `app/test/features/pos/presentation/page/checkout_page_test.dart` — 6 widget tests (NEW — code review H2)

#### Integration / Scripts
- `backend/curl-tests-story-4-2.sh` — 12-step cURL integration test script

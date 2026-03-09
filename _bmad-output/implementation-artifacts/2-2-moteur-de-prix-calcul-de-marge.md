# Story 2.2: Moteur de Prix & Calcul de Marge

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a proprietor (Simon),
I want to set purchase price, transport costs, and selling price with automatic margin calculation,
So that I always know my profitability per product and can price intelligently.

## Acceptance Criteria

**AC1 — Pricing fields with XAF currency validation**
- **Given** Simon is creating or editing a product
- **When** he fills in the pricing section
- **Then** three fields are available: "Prix d'achat" (XAF integer, required), "Coût de transport" (XAF integer, optional, default 0), "Prix de vente catalogue" (XAF integer, required)
- **And** all monetary inputs show a "FCFA" suffix label and reject decimal input (integers only)
- **And** the `Money` value object is enforced both on the client (Freezed DTO validation) and on the server (domain model constructor)

**AC2 — Real-time margin calculation with color coding**
- **Given** Simon enters: Prix d'achat = 5000, Coût de transport = 500, Prix de vente = 8000
- **When** any of the three values changes
- **Then** the margin is calculated and displayed in real-time:
  - Coût total = 5000 + 500 = 5500 FCFA
  - Marge brute = 8000 - 5500 = 2500 FCFA  
  - Marge % = (2500 / 5500) × 100 = 45,5%
- **And** the margin indicator uses color coding: green (≥ 20%), orange (10–19%), red (< 10%)
- **And** if the selling price is lower than the purchase cost, a warning banner appears: "⚠ Prix de vente inférieur au coût — vous vendez à perte"

**AC3 — POS price override system**
- **Given** the price-in-cart editable rule (UX10)
- **When** Loïc (employee) processes a sale in the POS
- **Then** he can modify the unit price of an item directly in the cart for that specific transaction
- **And** this temporary override does NOT modify the catalogue price of the product
- **And** the audit log records: `SalePriceOverriddenEvent` with `cataloguePrice`, `appliedPrice`, `actorId`, `saleId`
- **And** Simon can see all price overrides in the sale history per transaction

**AC4 — Catalogue price update independence**
- **Given** a product has been created with a catalogue price
- **When** Simon updates the catalogue price later
- **Then** existing completed sales are NOT retroactively modified — their stored `appliedPrice` is preserved
- **And** only future sales use the new catalogue price as the default

**AC5 — Integer XAF calculation rules**
- **Given** all monetary values in the system
- **When** any calculation is performed (margin, total, change)
- **Then** the result is always an integer (XAF — no centimes, no decimals, no floating point)
- **And** rounding uses standard mathematical rounding (0.5 → 1) if intermediate calculation produces a decimal

## Tasks / Subtasks

### Backend — TDD Strict (write failing test FIRST, then implementation)

- [x] **Task 1 — Add transport cost field to Product entity** (AC1)
  - [x] 1.1 — Add `transportCost` field to Product domain entity:
    - INTEGER type, default 0, non-negative validation
    - Update constructor and validation logic
    - **TDD**: Write `ProductTest` asserting transportCost validation BEFORE implementation
  - [x] 1.2 — Add `transport_cost` column to DDL in TenantSchemaProvisioner:
    - `transport_cost INTEGER NOT NULL DEFAULT 0`
    - Add to `DDL_PRODUCTS` migration script
    - **TDD**: Write migration test asserting column exists

- [x] **Task 2 — Create Money value object for XAF currency** (AC1, AC5)
  - [x] 2.1 — Create `Money` value object (`shared/domain/valueobject/Money.java`):
    - Constructor accepting only INTEGER XAF amount
    - Validation: non-negative, no decimals allowed
    - Methods: `add(Money)`, `subtract(Money)`, `multiply(double)` with rounding
    - `toString()` formatting: "5500 FCFA"
    - **TDD**: Write `MoneyTest` with all operations and edge cases BEFORE implementation
  - [x] 2.2 — Update Product entity to use Money objects:
    - `buyPrice`, `price`, `transportCost` as Money instances
    - Constructor validation through Money objects
    - **TDD**: Update ProductTest to use Money objects

- [x] **Task 3 — Create PricingCalculator service** (AC2, AC5)
  - [x] 3.1 — Create PricingCalculator (`catalog/product/domain/service/PricingCalculator.java`):
    - `calculateMargin(Money buyPrice, Money transportCost, Money sellingPrice)` → MarginCalculation
    - `MarginCalculation` record with: totalCost, grossMargin, marginPercentage, isLoss
    - All calculations use integer arithmetic with proper rounding
    - **TDD**: Write `PricingCalculatorTest` with multiple scenarios BEFORE implementation
  - [x] 3.2 — Create MarginThreshold enum:
    - `PROFITABLE (≥20%, GREEN)`, `MODERATE (10-19%, ORANGE)`, `LOW (<10%, RED)`, `LOSS (<0%, RED)`
    - Method `categorizeMargin(double marginPercentage)` → MarginThreshold
    - **TDD**: Write threshold categorization tests

- [x] **Task 4 — Update Product use cases with pricing logic** (AC1, AC2)
  - [x] 4.1 — Update CreateProductUseCase and UpdateProductUseCase:
    - Accept transportCost in DTOs
    - Validate all Money fields through domain objects
    - Calculate margin on creation/update for validation
    - **TDD**: Update existing use case tests with pricing scenarios
  - [x] 4.2 — Create GetProductPricingUseCase:
    - Input: productId
    - Output: ProductPricingDto with calculated margin information
    - Used by frontend for real-time margin display
    - **TDD**: Write use case test with margin calculation validation

- [x] **Task 5 — Update Product REST endpoints** (AC1, AC2)
  - [x] 5.1 — Update ProductController to handle transportCost:
    - Add transportCost to CreateProductRequestDto, UpdateProductRequestDto
    - Add calculated margin info to ProductResponseDto
    - **TDD**: Update ProductControllerTest with pricing scenarios
  - [x] 5.2 — Create GET /api/v1/products/{id}/pricing endpoint:
    - Returns real-time margin calculation without modifying product
    - Used by frontend for live margin updates
    - **TDD**: Write endpoint test with margin calculation validation

- [x] **Task 6 — Create PriceOverrideEvent for audit** (AC3)
  - [x] 6.1 — Create SalePriceOverriddenEvent:
    - Fields: productId, saleId, cataloguePrice, appliedPrice, actorId, tenantId, occurredAt
    - Follow same pattern as other audit events from Story 1.8
    - **TDD**: Write event test following audit event patterns
  - [x] 6.2 — Update AuditEventListener to handle price override events:
    - Follow exact template from existing audit handlers
    - Record in audit log with proper entityType="PriceOverride"
    - **TDD**: Write audit event listener test

### Frontend — Flutter with TDD

- [x] **Task 7 — Add transport cost to Product data layer** (AC1)
  - [x] 7.1 — Update products table schema in Drift:
    - Add `transportCost` INTEGER Column (default 0)
    - Update schema version and migration
    - **TDD**: Write Drift schema test asserting new column
  - [x] 7.2 — Update Product entity and DTOs:
    - Add transportCost to Freezed Product model
    - Update CreateProductDto, UpdateProductDto
    - Add Money value object validation on client side

- [x] **Task 8 — Create pricing calculation widget** (AC2)
  - [x] 8.1 — Create PricingCalculatorWidget:
    - Real-time calculation as user types
    - Color-coded margin display (green/orange/red)
    - Warning banner for loss scenarios
  - [x] 8.2 — PricingCalculatorWidget uses ValueListenableBuilder for real-time updates (no separate Notifier needed — all logic is pure Dart within the widget)

- [x] **Task 9 — Update product forms with pricing UI** (AC1, AC2)
  - [x] 9.1 — Update ProductFormPage (unified create/edit):
    - Added `_transportCostController` with XAF suffix
    - Integrated `PricingCalculatorWidget` with `ValueListenableBuilder`
    - Integer-only input (FilteringTextInputFormatter.digitsOnly)
  - [x] 9.2 — Updated product use cases to handle transport cost:
    - CreateProductUseCase, UpdateProductUseCase, ProductActions provider

- [ ] **Task 10 — POS price override system (deferred to Epic 4)**
  - [ ] 10.1 — **NOTE**: AC3 (POS price override) is deferred to Epic 4 (Point de Vente)
  - [ ] 10.2 — Create placeholder interfaces for future POS integration:
    - `IPriceOverrideService` interface  
    - Audit event structure for SalePriceOverriddenEvent
    - **TDD**: Write interface contract tests

## Dev Notes

### Project Structure Alignment

**Backend Structure:**
- `catalog/product/domain/entity/Product.java` — Add transportCost field
- `catalog/product/domain/service/PricingCalculator.java` — New service for margin calculations
- `shared/domain/valueobject/Money.java` — New value object for XAF currency
- `catalog/product/application/usecase/GetProductPricingUseCase.java` — New use case
- `catalog/product/adapter/in/web/ProductController.java` — Update existing controller

**Frontend Structure:**  
- `lib/domain/product/entity/product.dart` — Add transportCost field
- `lib/presentation/product/widgets/pricing_calculator_widget.dart` — New widget
- `lib/application/product/notifier/pricing_notifier.dart` — New Riverpod notifier
- `lib/core/storage/products_table.dart` — Add transportCost column

### Architecture Compliance

**Hexagonal Architecture:**
- PricingCalculator as domain service (business logic core)  
- Money value object enforces currency rules
- Use cases orchestrate domain operations
- Controllers adapt HTTP requests to domain operations

**Schema-per-Tenant:**
- DDL changes via TenantSchemaProvisioner
- All queries tenant-isolated via existing mechanisms

### TDD (Test-Driven Development) Requirements

**🚨 NON-NEGOTIABLE: RED-GREEN-REFACTOR CYCLE**

**Phase 1 — RED (Write Failing Test First):**
- Write test asserting expected behavior BEFORE any implementation
- Test must FAIL initially (proves test is working)
- Cover happy path AND edge cases in separate test methods
- Use descriptive test names: `shouldCalculateMarginCorrectly_WhenValidPricesProvided()`

**Phase 2 — GREEN (Minimal Implementation):**
- Write simplest code to make test pass
- No premature optimization
- Focus on making test GREEN, not perfect code

**Phase 3 — REFACTOR (Clean Code):**
- Improve code quality while keeping tests GREEN
- Apply SOLID principles and design patterns
- Extract methods, improve naming, reduce duplication

**TDD Test Categories for This Story:**

**Unit Tests (Business Logic):**
- `MoneyTest` — validation, arithmetic operations, rounding edge cases
- `PricingCalculatorTest` — margin calculations, threshold categorization
- `ProductTest` — transportCost validation, Money object integration
- `MarginThresholdTest` — boundary conditions (19.99%, 20.0%, 9.99%, 10.0%)

**Integration Tests (Ports & Adapters):**
- `ProductRepositoryAdapterTest` — DDL schema with transportCost column
- `ProductControllerTest` — HTTP endpoints with pricing data
- `CreateProductUseCaseTest` — full use case with Money validation

**Widget Tests (Flutter UI):**
- `PricingCalculatorWidgetTest` — real-time calculation, color changes
- `PricingNotifierTest` — state management, debouncing, validation

**Test Coverage Requirements:**
- **Minimum:** 95% line coverage for domain services (PricingCalculator)
- **Calculation Logic:** 100% coverage (no exceptions for Money arithmetic)
- **Edge Cases:** Zero values, negative inputs, rounding scenarios
- **Integration:** All REST endpoints with valid/invalid pricing data

### GoF Design Patterns Analysis

**🎯 MANDATORY PATTERN ANALYSIS BEFORE IMPLEMENTATION**

**Pattern 1: Strategy Pattern (Pricing Calculation)**
- **Problem:** Multiple pricing calculation strategies may be needed (basic margin, bulk pricing, seasonal adjustments)
- **Solution:** `PricingStrategy` interface with `calculateMargin()` method
- **Implementation:** `BasicMarginStrategy` for current story, extensible for future pricing rules
- **Benefit:** Open/Closed Principle — new pricing strategies without modifying existing code

```java
public interface PricingStrategy {
    MarginCalculation calculateMargin(Money buyPrice, Money transportCost, Money sellingPrice);
}

public class BasicMarginStrategy implements PricingStrategy {
    // Current implementation
}

// Future: BulkPricingStrategy, SeasonalPricingStrategy, etc.
```

**Pattern 2: Value Object Pattern (Money)**
- **Problem:** Primitive obsession with integer amounts, currency validation scattered
- **Solution:** `Money` value object encapsulating XAF currency rules
- **Implementation:** Immutable object with validation, arithmetic operations, formatting
- **Benefit:** Single responsibility, domain-driven design, type safety

**Pattern 3: Factory Method Pattern (Margin Threshold Classification)**
- **Problem:** Complex logic for categorizing margins into thresholds
- **Solution:** `MarginThresholdFactory.categorize(percentage)` static method
- **Implementation:** Encapsulates threshold logic, returns appropriate MarginThreshold enum
- **Benefit:** Centralized categorization logic, easy to modify thresholds

**Pattern 4: Observer Pattern (Real-time UI Updates)**
- **Problem:** UI components need to react to pricing calculation changes
- **Solution:** `PricingNotifier` (Riverpod) observes pricing field changes
- **Implementation:** State management with automatic UI updates on calculation changes
- **Benefit:** Loose coupling between calculation logic and UI presentation

**Pattern 5: Command Pattern (Price Override Operations)**
- **Problem:** POS price overrides need audit trail, undo capability
- **Solution:** `PriceOverrideCommand` encapsulating override operation
- **Implementation:** Command with execute(), undo(), audit event emission
- **Benefit:** Audit trail, potential undo functionality, operation encapsulation

**GoF Pattern Implementation Priority:**
1. **Value Object** (Money) — Immediate, foundational for all monetary operations
2. **Strategy** (PricingStrategy) — Immediate, enables future pricing extensions
3. **Factory Method** (MarginThreshold) — Immediate, clean categorization logic
4. **Observer** (PricingNotifier) — Frontend implementation phase
5. **Command** (PriceOverride) — Deferred to Epic 4 (POS implementation)

**Anti-Patterns to Avoid:**
- ❌ **Primitive Obsession:** Using raw integers instead of Money value objects
- ❌ **God Object:** Putting all pricing logic in Product entity
- ❌ **Feature Envy:** UI components directly accessing calculation methods
- ❌ **Hard-coded Values:** Magic numbers for margin thresholds scattered in code

### Critical Technical Requirements

**Integer XAF Currency (AC5):**
- NO floating point arithmetic for monetary calculations
- Use `BigDecimal.setScale(0, RoundingMode.HALF_UP)` for intermediate calculations
- Always round to integer before storing
- Frontend: integer-only input fields with FCFA formatting

**Real-time Calculation (AC2):**
- Debounce input changes (300ms)
- Calculate margin on every keystroke after debounce
- Update UI color indicators immediately
- No network calls for calculation — purely client-side

**Audit Integration (AC3, AC6):**
- Follow exact event pattern from Story 1.8
- Use existing AuditEventListener
- Emit events for price overrides (future POS integration)
- TenantContext already available in authenticated endpoints

### Previous Story Intelligence

**From Story 2-1 (CRUD Produits):**
- Product entity infrastructure already complete
- buyPrice and price fields already INTEGER XAF
- Audit system integration already working  
- JPA mapping and DTOs established
- Frontend product forms/widgets exist
- Sync system handles product updates

**Reuse Patterns:**
- Money field validation (follow existing price field validation)
- Use case structure (follow CreateProductUseCase pattern)
- Controller endpoint patterns (follow existing ProductController)
- Frontend form structure (extend existing CreateProductPage)
- Audit event handling (follow ProductUpdatedEvent pattern)

### Latest Technical Information 

**Spring Boot 3.5 + Java 21:**
- Use Records for DTOs (MarginCalculation, PricingDto)  
- Virtual threads available for async operations (if needed)
- Latest Spring Data JPA features

**Flutter 3.41:**
- Use Freezed for immutable data classes
- Riverpod for state management
- Material 3 design system with color-coded indicators

**Currency Calculation Best Practices:**
- Use `BigDecimal` for intermediate calculations
- Avoid `double` for money operations
- Round using `RoundingMode.HALF_UP` standard
- Validate against negative values

## References

- **Epic Source**: [epic-2-catalogue-produits-base-fournisseursclients.md](/_bmad-output/planning-artifacts/epics/epic-2-catalogue-produits-base-fournisseursclients.md#story-22-moteur-de-prix--calcul-de-marge)
- **Architecture**: [architecture.md](/_bmad-output/planning-artifacts/architecture.md) — Hexagonal, TDD, Integer XAF currency
- **Previous Story**: [2-1-crud-produits-creation-edition-archivage.md](/_bmad-output/implementation-artifacts/2-1-crud-produits-creation-edition-archivage.md) — Product infrastructure foundation
- **Audit System**: Story 1-8 patterns for event handling and audit trail integration

## Dev Agent Record

### Agent Model Used

GitHub Copilot + Claude Sonnet 4.6

### Debug Log References

_None_

### Completion Notes List

- AC2 margin formula: uses `totalCost` as denominator (markup-on-cost), not `sellingPrice` — matches AC2 example (5000+500=5500, (2500/5500)×100=45.5%). Fixed in PricingCalculator.java, PricingCalculatorTest.java, and pricing_calculator_widget.dart during code review.
- AC2 warning banner: added explicit "⚠ Prix de vente inférieur au coût — vous vendez à perte" container in PricingCalculatorWidget when isLoss=true.
- Flutter: no separate pricing_notifier.dart created — PricingCalculatorWidget is stateless pure-Dart (ValueListenableBuilder in product_form_page.dart handles real-time updates, no Riverpod notifier needed).
- Drift schema migrated to v4, schemaVersion test updated accordingly.
- `category_provider_test` failures are pre-existing SecureStorage binding issue, unrelated to story 2.2.
- SalePriceOverriddenEvent placed in `catalog/product/domain/event/` (not `shared/`) — will be moved to `shared/` in Epic 4 when POS consumes it.
- GetProductPricingUseCase: IllegalArgumentException replaced by DomainException(PRODUCT_NOT_FOUND) for proper 404 mapping.

### File List

**Backend — Main:**
- [x] `catalog/product/domain/entity/Product.java` — Added transportCost Money field
- [x] `shared/domain/model/Money.java` — Added `multiply(double)` method with BigDecimal HALF_UP rounding
- [x] `catalog/product/domain/service/PricingCalculator.java` — Margin calculation (totalCost base, AC2-compliant)
- [x] `catalog/product/domain/service/MarginCalculation.java` — Record: totalCost, grossMarginXaf, marginPercentage, isLoss
- [x] `catalog/product/domain/service/MarginThreshold.java` — Enum: LOSS/LOW/MODERATE/PROFITABLE with color
- [x] `catalog/product/application/usecase/GetProductPricingUseCase.java` — Pricing query use case
- [x] `catalog/product/application/usecase/CreateProductUseCase.java` — Updated: accepts transportCost
- [x] `catalog/product/application/usecase/UpdateProductUseCase.java` — Updated: accepts transportCost
- [x] `catalog/product/adapter/in/web/ProductController.java` — Updated: transportCost + GET /pricing endpoint
- [x] `catalog/product/adapter/in/web/dto/CreateProductRequestDto.java` — Added transportCost field
- [x] `catalog/product/adapter/in/web/dto/UpdateProductRequestDto.java` — Added transportCost field
- [x] `catalog/product/adapter/in/web/dto/ProductResponseDto.java` — Added margin fields
- [x] `catalog/product/adapter/out/persistence/ProductRepositoryAdapter.java` — Updated: transportCost mapping
- [x] `shared/infrastructure/persistence/entity/ProductJpaEntity.java` — Added transport_cost column
- [x] `shared/infrastructure/persistence/TenantSchemaProvisioner.java` — Added transport_cost DDL
- [x] `catalog/product/domain/event/SalePriceOverriddenEvent.java` — New audit event (Epic 4 placeholder)
- [x] `shared/infrastructure/web/AuditEventListener.java` — Added SalePriceOverriddenEvent handler

**Backend — Tests:**
- [x] `shared/domain/model/MoneyTest.java` — Updated: multiply(double) tests
- [x] `catalog/product/domain/service/PricingCalculatorTest.java` — Full TDD suite (AC2-compliant formula)
- [x] `catalog/product/domain/service/MarginThresholdTest.java` — Threshold boundary tests
- [x] `catalog/product/application/usecase/GetProductPricingUseCaseTest.java` — Use case tests
- [x] `catalog/product/application/usecase/UpdateProductUseCaseTest.java` — Updated with pricing
- [x] `catalog/product/application/usecase/ArchiveProductUseCaseTest.java` — No-regression update
- [x] `catalog/product/adapter/in/web/ProductControllerTest.java` — Updated with pricing endpoints
- [x] `catalog/product/adapter/out/persistence/ProductRepositoryAdapterTest.java` — transportCost schema test
- [x] `catalog/product/domain/entity/ProductTest.java` — transportCost validation tests
- [x] `catalog/product/domain/event/ProductAuditEventTest.java` — Audit event tests
- [x] `test/resources/product-schema.sql` — Added transport_cost column

**Frontend — Main:**
- [x] `lib/features/catalog/domain/model/product_model.dart` — Added transportCost field (Freezed)
- [x] `lib/features/catalog/domain/model/product_response_dto.dart` — Added transportCost + margin fields
- [x] `lib/features/catalog/domain/repository/product_repository.dart` — Updated port contract
- [x] `lib/features/catalog/data/repository/product_repository_impl.dart` — Updated implementation
- [x] `lib/features/catalog/data/datasource/local_product_datasource.dart` — Updated: transportCost
- [x] `lib/features/catalog/domain/usecase/create_product_usecase.dart` — Added transportCost param
- [x] `lib/features/catalog/domain/usecase/update_product_usecase.dart` — Added transportCost param
- [x] `lib/features/catalog/presentation/provider/product_provider.dart` — Updated ProductActions
- [x] `lib/features/catalog/presentation/page/product_form_page.dart` — Added _transportCostController + PricingCalculatorWidget
- [x] `lib/features/catalog/presentation/widget/pricing_calculator_widget.dart` — NEW: real-time margin widget (AC2-compliant formula + loss warning banner)
- [x] `lib/core/storage/products_table.dart` — Drift schema: added transportCost column + v4 migration
- [x] `lib/core/storage/app_database.dart` — schemaVersion bumped to 4

**Frontend — Tests:**
- [x] `test/core/storage/app_database_test.dart` — schemaVersion v4 test

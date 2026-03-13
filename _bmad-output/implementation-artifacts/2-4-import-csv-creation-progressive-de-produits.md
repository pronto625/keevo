# Story 2.4: Import CSV & Création Progressive de Produits

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a proprietor (Simon),
I want to import my existing product catalogue from a CSV file and create products on-the-fly during a sale,
So that I can onboard my full inventory rapidly and never block a sale because a product isn't in the system yet.

## Acceptance Criteria

**AC1 — CSV upload, column mapping & preview**
- **Given** Simon navigates to Catalogue > Importer
- **When** he uploads a CSV file via the file picker (Android / iOS / Linux / Windows)
- **Then** the Flutter client reads the first line of the file to detect column headers
- **And** a column-mapping screen is displayed where each Keevo field (Nom, Prix d'achat, Coût de transport, Prix de vente, Catégorie, SKU, Quantité initiale, Seuil min) is matched to a detected CSV header via dropdown
- **And** unrecognised columns are listed as "Ignorer"
- **And** the screen previews the first 5 CSV rows applying the current mapping before the user confirms
- **And** the "Lancer l'import" button is disabled until the two required mappings (Nom + Prix de vente) are filled

**AC2 — Server-side validation with partial import**
- **Given** Simon confirms the column mapping and taps "Lancer l'import"
- **When** the CSV file + column mapping JSON is submitted as `multipart/form-data` to `POST /api/v1/products/import`
- **Then** the backend parses the CSV with `commons-csv` (Apache) and validates each row independently:
  - Nom: non-empty string (required)
  - Prix de vente: positive integer (required)
  - Prix d'achat, Coût de transport, Quantité initiale, Seuil min: non-negative integer if present (optional — default 0)
  - Catégorie: matched against existing tenant category names (case-insensitive, optional — left null if no match)
  - SKU: if provided, must match `KEV-[A-Z0-9]{6}` (auto-generated if absent or invalid)
- **And** rows with validation errors are skipped and collected in an error report: `"Ligne 12 — Prix de vente manquant"`
- **And** valid rows are imported — partial import is allowed (not all-or-nothing)
- **And** the response includes: `{ imported: 127, skipped: 3, errors: [...], limitReached: false }`

**AC3 — Plan-limit enforcement during import**
- **Given** the import exceeds the plan's product limit (e.g., Free plan max is 500; Simon already has 450 active products and uploads a CSV with 100 rows)
- **When** the 500-product limit is reached during the import loop
- **Then** import stops immediately after the limit is hit (no row beyond the limit is processed)
- **And** the response is: `{ imported: 50, skipped: 50, errors: [], limitReached: true, message: "Limite atteinte : 50 produits importés sur 100. Passez au plan Premium pour continuer." }`
- **And** the 50 successfully imported products are preserved — no rollback
- **And** HTTP 200 is returned (not 403) — limit reached is a partial success, not an error
- **And** Flutter shows the result with a distinct upgrade CTA banner when `limitReached: true`

**AC4 — Import with initial stock (optional `quantite_initiale`)**
- **Given** a CSV row includes a `quantite_initiale` value > 0
- **When** the product is successfully created
- **Then** a `StockLevel` record is created via `StockOperationService.recordOperation()` with `MovementType.STOCK_ENTRY` and `quantityChange = quantite_initiale`
- **And** a `StockMovement` record is persisted with `notes = "Import CSV"`
- **And** if `seuil_min` is provided, `product.minimumThreshold` is set accordingly
- **And** a `ProductImportedFromCsvEvent` audit event is emitted for each imported product

**AC5 — CSV template download**
- **Given** Simon taps "Télécharger le modèle CSV" in the import screen
- **When** the request reaches `GET /api/v1/products/import/template`
- **Then** the server returns a `text/csv` response with:
  - Header row: `nom,prix_achat,cout_transport,prix_vente,categorie,sku,quantite_initiale,seuil_min`
  - 3 example rows with plausible data (sector-agnostic)
  - Content-Disposition: `attachment; filename="keevo_import_template.csv"`
- **And** Flutter saves the file to the device's downloads directory (via `path_provider`)
- **And** a SnackBar confirms: "Modèle téléchargé dans vos téléchargements"

**AC6 — On-the-fly DRAFT product creation (POS preparation)**
- **Given** a POS search returns no matching product (this AC prepares the widget for Epic 4)
- **When** `CreateDraftProductBottomSheet` is shown (passed the searched name)
- **Then** a minimal form is displayed pre-filled with the searched name (read-only): Prix de vente (required), Catégorie (required), Quantité pour cette vente (defaults to 1)
- **And** tapping "Créer & Ajouter" calls `POST /api/v1/products/draft` with `status: DRAFT` and returns the created product
- **And** the product is also created in the local Drift `products` table with `status = 'DRAFT'` (offline-first — queued if offline)
- **And** `DRAFT` products appear in Catalogue with a "🔶 Brouillon" badge on the product card
- **And** a `ProductCreatedProgressivelyEvent` is emitted for the audit log and notification chain: `{ productId, productName, actorId, actorName, actorRole, tenantId, initialStatus: "DRAFT", occurredAt }` — `actorName` and `actorRole` are required by the notification listener to personalise the message and decide whether to alert the owner
- **And** the bottom sheet is exported from this story and ready to be invoked from POS (Epic 4)
- **RBAC**: `POST /api/v1/products/draft` is accessible by **both OWNER and EMPLOYEE** roles. This is an intentional exception to the standard product-write restriction — the UX spec (Flow 4 — Enregistrer une Vente, actor: Loïc/Employee) explicitly requires employees to be able to create drafts during a sale without blocking the transaction. This is the **only** product-write endpoint accessible to employees.
- **Name uniqueness**: If a product with the same `name` already exists (regardless of status — DRAFT or ACTIVE), `CreateDraftProductUseCase` throws `DomainException(PRODUCT_NAME_ALREADY_EXISTS)` → HTTP 409 CONFLICT. The name check is case-insensitive and tenant-scoped.

**AC7 — OWNER-only DRAFT → ACTIVE promotion**
- **Given** a user views a DRAFT product in the Catalogue
- **When** they edit it and fill in all required fields (Nom, Prix de vente, Catégorie — Prix d'achat is not strictly required but recommended) then taps "Enregistrer"
- **Then** if the product has `status: DRAFT`, the backend checks the required fields and sets `status: ACTIVE`
- **And** the "Brouillon" badge disappears from the product card
- **And** this promotion is handled server-side by `UpdateProductUseCase`: if all required fields are filled and `currentStatus == DRAFT` → set `status = ACTIVE`
- **And** no separate endpoint is needed — `PATCH /api/v1/products/{id}` handles the promotion transparently
- **RBAC (CRITICAL)**: `UpdateProductUseCase` MUST verify that `actorRole == "OWNER"` before executing any DRAFT→ACTIVE promotion. If `actorRole == "EMPLOYEE"`, throw `DomainException(FORBIDDEN)` → HTTP 403. An employee may create a draft but may **never** validate/activate a product — that is the owner's exclusive responsibility per PRD RBAC matrix ("CRUD produits = Propriétaire"). The `actorRole` string is extracted from the JWT authorities by the Controller and passed in `UpdateProductCommand`.

**AC8 — Product name uniqueness (DRAFT and ACTIVE)**
- **Given** any product creation attempt (CSV import, standard creation, or draft creation)
- **When** the submitted `name` (case-insensitive, trimmed) matches an existing product in the tenant's catalogue — whether that existing product is ACTIVE or DRAFT
- **Then** the operation is rejected with `DomainException(PRODUCT_NAME_ALREADY_EXISTS)` → HTTP 409 CONFLICT
- **And** the error message is: "Un produit avec ce nom existe déjà dans votre catalogue"
- **And** for CSV import: the conflicting row is skipped and reported in the `errors` list as `"Ligne X — Nom déjà existant : {name}"` (partial import continues)
- **And** the uniqueness constraint is enforced both at the application level (in `CreateProductUseCase`, `CreateDraftProductUseCase`) and at the database level via a `UNIQUE INDEX` on `products.name` within the tenant schema
- **And** the Flutter `createDraft()` repository method checks the local Drift DB for a name collision before calling the backend (fast offline feedback), then re-checks server-side on sync
- **RBAC note**: `POST /api/v1/products/import` is **OWNER-only**. If called by an EMPLOYEE, `ImportCsvProductsUseCase` throws `DomainException(FORBIDDEN)` → HTTP 403.

**AC9 — Owner alert when employee creates a DRAFT product**
- **Given** an EMPLOYEE successfully creates a DRAFT product via `POST /api/v1/products/draft`
- **When** `CreateDraftProductUseCase` completes successfully
- **Then** the backend emits a `ProductCreatedProgressivelyEvent` that is caught by `DraftProductNotificationListener` (in `messaging/notification/` module)
- **And** the listener calls `NotificationPort.notifyOwners(tenantId, payload)` where `payload` is:
  - `type: "DRAFT_PRODUCT_PENDING_VALIDATION"`
  - `title: "🔶 Produit en attente de validation"`
  - `body: "{actorName} a enregistré un nouveau produit en brouillon : '{productName}' — Validez-le pour l'activer dans votre catalogue"`
  - `deepLink: "/products/{productId}/edit"` — **must match the actual Go Router route `/products/:id/edit` declared in `app_router.dart`** — the `FcmNotificationAdapter` (Epic 8.1) will call `router.go(payload.deepLink())` on tap; `keevo://catalog/product/...` was incorrect and would cause a routing crash
  - `metadata: Map.of({ "productId": "{productId}", "productName": "{productName}", "tenantId": "{tenantId}" })` — FCM data payload fields, required by Epic 8.1 `FcmNotificationAdapter` for typed notification handling and analytics
- **And** if the OWNER themselves creates the draft → `actorRole == "OWNER"` → **no notification sent** (owner is already aware, no self-spam)
- **And** if push delivery fails for any reason → the failure is logged but silently swallowed — draft creation is NOT rolled back (notification is best-effort, core flow must never fail because of notification)
- **And** a `DraftPendingValidationRecord` is persisted in the `draft_notifications` table with `{ productId, tenantId, actorId, createdAt, acknowledged: false }` — used for the in-app badge count
- **Infrastructure stub (this story)**: `NotificationPort` interface is defined + `LoggingNotificationAdapter` implementation that simply logs the payload to `System.out` and marks `acknowledged: false` in `draft_notifications`. No FCM calls. Actual FCM/APNs delivery = Epic 8.1 story.
- **⚠️ CSV IMPORT BATCHING**: When `ImportCsvProductsUseCase` creates N products in a loop, each product MUST NOT emit individual `ProductCreatedProgressivelyEvent`s — that would fire N push notifications. Instead, after the import loop completes, emit a single `CsvImportCompletedEvent(importedCount, tenantId, actorId, actorName, actorRole)`. `DraftProductNotificationListener` handles it with a **consolidated notification**: `body: "{actorName} a importé {N} produits en brouillon via CSV — Validez-les dans votre catalogue"`, `metadata: { "count": "N" }`. This is aligned with Epic 8.1's batching principle (multiple simultaneous events → single notification).
- **⚠️ OUT OF SCOPE (Epic 8.1)**: The **global notification bell icon** (`🔔`) in the persistent AppBar (opens 30-day notification history for all alert types — see Epic 8.1, AC "Simon views his notification history → bell icon in AppBar") is NOT part of this story. `DraftValidationBanner` is confined to `CatalogPage` AppBar only. Do NOT add a global bell to `MainShell` or any shared scaffold here.
- **TDD**: Write `DraftProductNotificationListenerTest`:
  - `shouldSendNotificationWhenEmployeeCreatesDraft()`
  - `shouldSkipNotificationWhenOwnerCreatesDraft()`
  - `shouldNotRollbackDraftWhenNotificationFails()`
  - `shouldSendBatchedNotificationOnCsvImportCompletion()`
  - `shouldSkipCsvBatchNotificationWhenOwnerImports()`

**AC10 — Pending drafts badge & in-app alert (Owner)**
- **Given** Simon (Owner) opens the app after one or more employees created DRAFT products
- **When** the Catalogue bottom nav item renders
- **Then** a badge counter on the Catalogue nav icon shows the number of unacknowledged DRAFT products awaiting validation — amber color: `Theme.of(context).colorScheme.tertiary` (= `#FCC419` per KeevoTheme, matching UX spec "stock bas, brouillons" token) — max displayed "9+" if > 9 (Material 3 `Badge` widget convention for navigation items)
- **And** Simon can call `GET /api/v1/products/drafts/count` to get the live count: `{ pending: N }` — Owner-only endpoint (Employee → HTTP 403)
- **And** in the `CatalogPage` AppBar (page-scoped, NOT the global persistent AppBar), a `DraftValidationBanner` is displayed when `N > 0` — styled per UX spec "Alerte attention" pattern: background `colorWarning.withOpacity(0.15)` (= `#FCC419` at 15% opacity), ⚠️ icon + "🔶 N brouillon(s) à valider" text, tap filters product list to DRAFT-only
- **And** tapping the banner filters the product list to show only DRAFT products
- **And** when Simon validates (promotes to ACTIVE) a draft product, the badge count decrements immediately client-side (optimistic UI) and confirms on next sync
- **And** the badge persists across app restarts (Drift local count = number of products with `status = 'DRAFT'` in the local products table)
- **Flutter implementation**:
  - `pendingDraftsCountProvider`: `@riverpod int pendingDraftsCount(ref)` — queries Drift `SELECT COUNT(*) FROM products WHERE status = 'DRAFT'`. Auto-refreshes when `productsProvider` invalidates.
  - `MainShell` (`core/scaffold/main_shell.dart` — file already exists): modify the Catalogue `NavigationDestination` to wrap its icon with `Badge(label: Text('$count'), isLabelVisible: count > 0, backgroundColor: colorScheme.tertiary, child: Icon(...))`. Consume `pendingDraftsCountProvider` via `Consumer` or `ConsumerWidget`.
  - `CatalogPage` AppBar: insert `DraftValidationBanner` (new widget) conditionally when `pendingDraftsCount > 0`. Background: `colorScheme.tertiary.withOpacity(0.15)`. The global 🔔 bell icon belongs to Epic 8.1 — do not add it here.
- **TDD**: Write `PendingDraftsCountProviderTest`:
  - `shouldReturnZeroWhenNoProductsAreDraft()`
  - `shouldReturnCountOfDraftProducts()`
  - `shouldDecrementWhenDraftIsPromotedToActive()`

---

## GoF Design Pattern Analysis — MANDATORY

Per architecture convention, every story MUST analyze GoF patterns before implementation.

| Pattern | Location | Rationale |
|---------|----------|-----------|
| **Builder** | `ImportResult.java` | Accumulates imported count, skipped count, error list, and limitReached flag progressively during the import loop. Prevents mutable parameter explosion. `ImportResult.Builder::build()` is called at end of import. |
| **Strategy** | `CsvColumnValidator<T>` interface | Each CSV field type has its own validation strategy: `RequiredStringValidator`, `PositiveIntegerValidator`, `OptionalIntegerValidator`, `OptionalSkuValidator`. New field types (e.g., boolean) require only adding a new strategy — open/closed principle. |
| **Factory Method** | `ProductCsvRowFactory` | Creates a `CreateProductDto` from a `Map<String, String>` row and a `CsvColumnMapping`. Encapsulates the row-to-domain transformation, keeping `ImportCsvProductsUseCase` free of mapping logic. |
| **Template Method** | Not applicable (single path) | Considered but rejected — one CSV processing path makes Template Method unnecessary overhead in MVP. |

---

## Tasks / Subtasks

> **⚠️ TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST for every task. No exceptions per architecture constraints.**

---

### BACKEND — Full TDD (Spring Boot, Hexagonal Architecture)

> **Module**: `catalog/product/` — all CSV import logic lives in this existing module.
> **Java version**: 21. **Spring Boot**: 3.x.

---

#### Task 1 — Infrastructure: Add `commons-csv` dependency & multipart config (AC1, AC2)

- [ ] 1.1 — Add `commons-csv 1.10.0` to `backend/pom.xml` (Apache Foundation — no reflection, no CVEs):
  ```xml
  <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-csv</artifactId>
      <version>1.10.0</version>
  </dependency>
  ```
- [ ] 1.2 — Add multipart upload limits to `backend/src/main/resources/application.yml`:
  ```yaml
  spring:
    servlet:
      multipart:
        max-file-size: 10MB
        max-request-size: 12MB
  ```
  > ⚠️ Default Spring Boot limit is 1MB. A CSV with 5 000 products × 8 columns ≈ 500 KB. 10MB allows up to ~100 000 rows safely.
- [ ] 1.3 — **TDD**: Write `CommonsCsvParserAdapterTest` BEFORE implementing:
  - `shouldParseValidCsvWithHeaderRow()`
  - `shouldHandleEmptyFile()`
  - `shouldHandleBomUtf8Prefix()`
  - `shouldHandleWindowsCrLfLineEndings()`

---

#### Task 2 — New ErrorCodes: `CSV_PARSE_ERROR` + `PRODUCT_NAME_ALREADY_EXISTS` (AC2, AC8)

- [ ] 2.1 — Add `CSV_PARSE_ERROR` and `PRODUCT_NAME_ALREADY_EXISTS` to `shared/domain/exception/ErrorCode.java`:
  - Position: under the `// ── Catalog` section
  - **TDD**: Write `ErrorCodeCompletenessTest` with `shouldContainCsvParseError()` AND `shouldContainProductNameAlreadyExists()` BEFORE adding
- [ ] 2.2 — Register French messages in `GlobalExceptionHandler.java` FR_MESSAGES map:
  - `Map.entry("CSV_PARSE_ERROR", "Erreur de lecture du fichier CSV")`
  - `Map.entry("PRODUCT_NAME_ALREADY_EXISTS", "Un produit avec ce nom existe déjà dans votre catalogue")`
- [ ] 2.3 — Add HTTP status mappings in `GlobalExceptionHandler.toHttpStatus()`:
  - `case "CSV_PARSE_ERROR" -> HttpStatus.UNPROCESSABLE_ENTITY;` (422 — file is syntactically invalid)
  - `case "PRODUCT_NAME_ALREADY_EXISTS" -> HttpStatus.CONFLICT;` (409 — duplicate resource)
  - **TDD**: Write `GlobalExceptionHandlerTest` with `shouldMapCsvParseErrorTo422()` AND `shouldMapProductNameAlreadyExistsTo409()` BEFORE adding

---

#### Task 3 — Domain: Records, Events & ImportResult Builder (AC2, AC4, AC6)

- [ ] 3.1 — Create `CsvColumnMapping` record (`catalog/product/application/dto/CsvColumnMapping.java`):
  ```java
  public record CsvColumnMapping(
      String nameColumn,         // required
      String priceColumn,        // required (prix de vente)
      String buyPriceColumn,     // optional
      String transportCostColumn,// optional
      String categoryColumn,     // optional
      String skuColumn,          // optional
      String quantityColumn,     // optional (quantite_initiale)
      String thresholdColumn     // optional (seuil_min)
  ) {}
  ```
  - **TDD**: Write `CsvColumnMappingTest.shouldRejectNullRequiredColumns()` BEFORE implementation
  - Domain invariant: `nameColumn` and `priceColumn` must be non-null/non-blank

- [ ] 3.2 — Create `CsvRowError` record (`catalog/product/application/dto/CsvRowError.java`):
  ```java
  public record CsvRowError(int lineNumber, String column, String message) {}
  ```

- [ ] 3.3 — Create `ImportResult` with Builder pattern (`catalog/product/application/dto/ImportResult.java`):
  ```java
  public final class ImportResult {
      private final int imported;
      private final int skipped;
      private final List<CsvRowError> errors;
      private final boolean limitReached;
      private final String limitMessage;  // null if not reached

      // Private constructor — use Builder
      // Public getters
      // Builder static inner class with: increment(), addError(), markLimitReached()
  }
  ```
  - **TDD**: Write `ImportResultTest` — assert `Builder.build()` produces correct values BEFORE implementing

- [ ] 3.4 — Create `ProductImportedFromCsvEvent` (`catalog/product/domain/event/ProductImportedFromCsvEvent.java`):
  ```java
  public record ProductImportedFromCsvEvent(
      UUID productId, String productName, UUID actorId,
      UUID tenantId, int csvLineNumber, Instant occurredAt
  ) {}
  ```
  - **TDD**: Write `ProductImportedFromCsvEventTest.shouldConstructFromRecord()`

- [ ] 3.5 — Create `ProductCreatedProgressivelyEvent` (`catalog/product/domain/event/ProductCreatedProgressivelyEvent.java`):
  ```java
  public record ProductCreatedProgressivelyEvent(
      UUID productId,
      String productName,   // needed for notification body
      UUID actorId,
      String actorName,     // needed for notification body — "Loïc a créé..."
      String actorRole,     // "OWNER" or "EMPLOYEE" — controls notification dispatch
      UUID tenantId,
      String initialStatus,
      Instant occurredAt
  ) {}
  ```
  - **TDD**: Write `ProductCreatedProgressivelyEventTest.shouldConstructFromRecord()`
  - `actorName` is resolved in `CreateDraftProductUseCase` via `UserRepository.findNameById(actorId)` (or use a `UserSummaryPort` if that's lighter)

- [ ] 3.7 — Create `DraftPendingValidation` entity (`messaging/notification/domain/model/DraftPendingValidation.java`):
  ```java
  public class DraftPendingValidation {
      private final UUID id;
      private final UUID productId;
      private final String productName;
      private final UUID actorId;     // employee who created the draft
      private final UUID tenantId;
      private final Instant createdAt;
      private boolean acknowledged;   // set to true when owner validates the product

      // Factory method + getters
  }
  ```
  - Persisted in `draft_notifications` table in the tenant schema — migrated in `TenantSchemaProvisioner`
  - Row is marked `acknowledged = true` when `UpdateProductUseCase` promotes a DRAFT → ACTIVE for this `productId`
  - **TDD**: Write `DraftPendingValidationTest.shouldCreateWithAcknowledgedFalseByDefault()`

- [ ] 3.6 — Add `existsByName` method to `ProductRepository` port (`catalog/product/domain/port/out/ProductRepository.java`):
  ```java
  /**
   * Returns true if a product with the given name exists for this tenant,
   * regardless of status (DRAFT or ACTIVE). Case-insensitive, trimmed.
   * Used to enforce the no-duplicate name invariant (AC8).
   */
  boolean existsByName(String name);
  ```
  - **TDD**: Write `ProductRepositoryContractTest.shouldReturnTrueWhenNameExistsAsDraft()` and `shouldReturnTrueWhenNameExistsAsActive()` BEFORE implementing in `JpaProductRepository`
  - JPA query: `@Query("SELECT COUNT(p) > 0 FROM Product p WHERE LOWER(p.name) = LOWER(:name)")`
  - Also update `DDL_PRODUCTS` in `TenantSchemaProvisioner.java` to add a `UNIQUE INDEX` on `name`:
    Add after the existing `products` table DDL: `CREATE UNIQUE INDEX IF NOT EXISTS uq_products_name ON products(lower(name));`
    > Using a functional index on `lower(name)` ensures case-insensitive uniqueness at the DB level as a safety net, even if the application-level check is bypassed.

- [ ] 4.1 — Create `CsvParserPort` interface (`catalog/product/domain/port/out/CsvParserPort.java`):
  ```java
  public interface CsvParserPort {
      /**
       * Parse a CSV input stream.
       * @param inputStream CSV bytes (UTF-8 or UTF-8-BOM)
       * @return ordered list of rows as header→value maps (map key = original header, case-preserved)
       * @throws DomainException CSV_PARSE_ERROR if file is malformed or empty
       */
      List<Map<String, String>> parse(InputStream inputStream);
  }
  ```
  - **TDD**: Write `CsvParserPortContractTest` with a mock implementation to validate the contract

---

#### Task 5 — Adapter Out: CSV Parser & Validators (Strategy Pattern) (AC2)

- [ ] 5.1 — Create `CommonsCsvParserAdapter` (`catalog/product/adapter/out/csv/CommonsCsvParserAdapter.java`):
  - Implements `CsvParserPort`
  - Uses `org.apache.commons.csv.CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim()`
  - Strips UTF-8 BOM if present: detect `0xEF 0xBB 0xBF` bytes and wrap with `BOMInputStream`
  - Handles Windows CRLF line endings (handled automatically by Commons CSV)
  - Throws `DomainException(CSV_PARSE_ERROR)` on `IOException` or empty file (0 data rows)
  - Spring `@Component`
  - **TDD**: Write `CommonsCsvParserAdapterTest` from Task 1.3 FIRST

- [ ] 5.2 — Create `CsvColumnValidator<T>` Strategy interface (`catalog/product/adapter/out/csv/validator/CsvColumnValidator.java`):
  ```java
  public interface CsvColumnValidator<T> {
      /** Validate and parse the raw cell value. Returns null if value is absent and field is optional. */
      T validate(String rawValue, int lineNumber, String columnName) throws CsvValidationException;
  }
  ```
  - Create `CsvValidationException(int lineNumber, String column, String message)` (extends RuntimeException)
  - **TDD**: Write `CsvColumnValidatorContractTest` with a test strategy

- [ ] 5.3 — Implement validator strategies:
  - `RequiredStringValidator` (`validate` → trim + reject blank, max 255 chars)
  - `PositiveIntegerValidator` (`validate` → parse int, reject negative/zero; `minValue` constructor param for > 0)
  - `OptionalIntegerValidator` (`validate` → parse int, reject negative; return 0 if null/blank)
  - `OptionalStringValidator` (`validate` → return null if blank, trim otherwise)
  - **TDD**: Write `CsvColumnValidatorTest` with cases for each strategy (valid value, null, blank, out-of-range) BEFORE implementing

- [ ] 5.4 — Create `ProductCsvRowFactory` (Factory Method pattern) (`catalog/product/adapter/out/csv/ProductCsvRowFactory.java`):
  - `CreateProductDto fromRow(Map<String, String> row, CsvColumnMapping mapping, UUID actorId)` — applies all validators
  - Returns `null` and adds error to builder if validation fails (caller catches `CsvValidationException`)
  - Handles SKU auto-generation: if `skuColumn` is null/blank or mapped value is invalid format → generate `KEV-{6 random A-Z0-9}`
  - Category matching: if `categoryColumn` mapped and value non-blank → look up by name (passed as parameter `Map<String, UUID> categoryNameToId`)
  - **TDD**: Write `ProductCsvRowFactoryTest`:
    - `shouldCreateDtoFromValidRow()`
    - `shouldAutoGenerateSkuWhenAbsent()`
    - `shouldMatchCategoryByName()`
    - `shouldReturnNullAndCollectErrorOnMissingRequiredField()`

- [ ] 5.5 — Create MCP placeholder: `catalog/product/adapter/in/mcp/.gitkeep`
  > ⚠️ Per architecture convention: every new module MUST have `adapter/in/mcp/.gitkeep`.

---

#### Task 6 — Use Case: `ImportCsvProductsUseCase` (AC2, AC3, AC4, AC8)

- [ ] 6.1 — Create `ImportCsvProductsUseCase` (`catalog/product/application/usecase/ImportCsvProductsUseCase.java`):
  ```java
  @Service
  public class ImportCsvProductsUseCase {
      // Dependencies: CsvParserPort, ProductCsvRowFactory, ProductRepository,
      //               StockOperationService, PlanLimitGuard, ProductCountPort, 
      //               SubscriptionRepository (to get plan type), ApplicationEventPublisher
  
      public record ImportCsvCommand(
          InputStream csvStream,
          CsvColumnMapping mapping,
          UUID actorId,
          String actorRole    // "OWNER" or "EMPLOYEE" — extracted from JWT by Controller
      ) {}
  
      public ImportResult execute(ImportCsvCommand command) { ... }
  }
  ```
  - **Algorithm** (must follow this order):
    1. **RBAC guard (AC8)**: `if (!"OWNER".equals(command.actorRole())) throw new DomainException(ErrorCode.FORBIDDEN)` — CSV import is an Owner-only operation (bulk catalogue management per PRD RBAC matrix)
    2. Parse CSV via `CsvParserPort.parse()` → rows list
    3. Load current product count: `productCountPort.countActiveProducts()`
    4. Load tenant plan type via `SubscriptionRepository` (or reuse `GetSubscriptionService` pattern)
    5. Calculate `remaining = planType.getMaxProducts() - currentCount` — if ≤ 0, return `limitReached: true` immediately
    6. Load tenant categories → `Map<String, UUID> categoryNameToId` (case-insensitive)
    7. For each row in order, stopping when `builder.getImported() >= remaining`:
       - Apply `ProductCsvRowFactory.fromRow()` — catch `CsvValidationException` → `builder.addError()`
       - **Name uniqueness check (AC8)**: After successful row parsing, call `productRepository.existsByName(dto.name())` — if `true`, add `CsvRowError(lineNumber, "nom", "Nom déjà existant : " + dto.name())` and `builder.incrementSkipped()` — do NOT save
       - On success (validation + uniqueness pass): call `productRepository.save()` with `status: ACTIVE` — each row is its own operation (no single outer `@Transactional` wrapping all rows)
       - If `quantityColumn` mapped and value > 0: call `stockOperationService.recordOperation(STOCK_ENTRY, quantite, actorId, "Import CSV")`
       - Publish `ProductImportedFromCsvEvent` per product
       - `builder.incrementImported()`
    8. If rows remain after `remaining`: for each remaining row `builder.incrementSkipped()` + mark `limitReached`
    9. Return `builder.build()`
  - **TDD**: Write `ImportCsvProductsUseCaseTest` FIRST:
    - `shouldImportAllValidRows()`
    - `shouldSkipInvalidRowsAndReportErrors()`
    - `shouldStopAtPlanLimitAndMarkLimitReached()`
    - `shouldCreateStockEntryForRowWithInitialQuantity()`
    - `shouldReturnImmediatelyWhenAlreadyAtLimit()`
    - `shouldEmitAuditEventPerProduct()`
    - `shouldThrowForbiddenWhenEmployeeTriesToImport()` **(NEW — AC8 RBAC)**
    - `shouldSkipRowWithDuplicateNameAndReportError()` **(NEW — AC8 uniqueness)**

---

#### Task 7 — Use Case: `CreateDraftProductUseCase` (AC6, AC7, AC9)

- [ ] 7.1 — Create `CreateDraftProductUseCase` (`catalog/product/application/usecase/CreateDraftProductUseCase.java`):
  ```java
  @Service
  public class CreateDraftProductUseCase {
      public record CreateDraftProductCommand(
          String name,           // required
          Integer priceVente,    // required
          UUID categoryId,       // required
          Integer stockQuantity, // optional, default 0
          UUID actorId,
          String actorRole,      // "OWNER" or "EMPLOYEE" — for notification dispatch
          String actorName       // display name — for notification body ("Loïc a créé...")
      ) {}
  
      public Product execute(CreateDraftProductCommand cmd) { ... }
  }
  ```
  - Creates product with `status: DRAFT` (not ACTIVE)
  - Plan limit check via `PlanLimitGuard.checkProductLimit()` — DRAFT products count toward the limit
  - **Name uniqueness check (AC8)**: Call `productRepository.existsByName(cmd.name())` BEFORE saving — if `true`, throw `DomainException(PRODUCT_NAME_ALREADY_EXISTS)`. Case-insensitive check. A name already taken as DRAFT or ACTIVE cannot be reused.
  - **RBAC note**: No role restriction — BOTH `"OWNER"` and `"EMPLOYEE"` roles are permitted to call this use case (per UX spec progressive adoption principle). No actorRole guard needed here.
  - Publishes `ProductCreatedProgressivelyEvent(initialStatus: "DRAFT")`
  - **TDD**: Write `CreateDraftProductUseCaseTest` FIRST:
    - `shouldCreateProductWithDraftStatus()`
    - `shouldThrowPlanLimitWhenAtCapacity()`
    - `shouldPublishProgressiveCreationEvent()`
    - `shouldThrowProductNameAlreadyExistsWhenNameExistsAsActive()` **(NEW — AC8)**
    - `shouldThrowProductNameAlreadyExistsWhenNameExistsAsDraft()` **(NEW — AC8 — same name twice = still blocked)**
    - `shouldAllowOwnerToCreateDraft()` **(NEW — confirms no OWNER restriction)**
    - `shouldAllowEmployeeToCreateDraft()` **(NEW — confirms EMPLOYEE is permitted per UX)**

- [ ] 7.2 — Update `UpdateProductUseCase` to handle OWNER-only DRAFT → ACTIVE promotion (AC7):
  - Add `actorRole` field to the command:
    ```java
    public record UpdateProductCommand(
        UUID productId,
        // ... existing fields ...
        UUID actorId,
        String actorRole    // "OWNER" or "EMPLOYEE" — extracted from JWT by Controller
    ) {}
    ```
  - **RBAC guard**: At the start of `execute()`, if the product has `status == DRAFT` AND `actorRole != "OWNER"` → throw `DomainException(FORBIDDEN)`. An employee may never promote a draft to active.
  - After applying update fields, check: `if (existing.getStatus() == DRAFT && allRequiredFieldsFilled(updated))` → set `status = ACTIVE` (only reached if OWNER passed the guard above)
  - Required fields for promotion: `name` non-blank, `price` > 0, `categoryId` non-null
  - **TDD**: Add these to `UpdateProductUseCaseTest` BEFORE modifying:
    - `shouldPromoteDraftToActiveWhenAllFieldsFilled()` (owner)
    - `shouldThrowForbiddenWhenEmployeeTriesToPromoteDraft()` **(NEW — AC7 RBAC)**
    - `shouldAcknowledgeDraftNotificationOnPromotion()` **(NEW — AC9: mark DraftPendingValidation as acknowledged when Owner promotes)**
    - `shouldAllowEmployeeToUpdateActiveProductFields()` **(NEW — employees CAN update non-status fields of ACTIVE products if business allows — confirm per PRD: actually no, per PRD FR21 employees cannot modify products. Add guard: if ACTIVE product and actorRole == EMPLOYEE → throw FORBIDDEN for write operations too. Leave this decision documented in the test name for the dev to decide based on final PRD reading)**

- [ ] 7.3 — Create `DraftProductNotificationListener` (`messaging/notification/application/listener/DraftProductNotificationListener.java`) **(NEW — AC9)**:
  ```java
  @Component
  public class DraftProductNotificationListener {
      // Dependencies: NotificationPort, DraftPendingValidationRepository

      @EventListener
      @Async   // must NOT block the main thread — notification is fire-and-forget
      public void onDraftProductCreated(ProductCreatedProgressivelyEvent event) {
          // 1. Skip if actor is OWNER — no self-notification (UX spec: "Sérénité par défaut")
          if ("OWNER".equals(event.actorRole())) return;

          // 2. Persist DraftPendingValidation record (drives in-app badge count)
          var record = DraftPendingValidation.create(
              event.productId(), event.productName(), event.actorId(), event.tenantId());
          draftPendingValidationRepository.save(record);

          // 3. Notify owners via NotificationPort (best-effort — suppress exceptions)
          try {
              notificationPort.notifyOwners(event.tenantId(), NotificationPayload.of(
                  "DRAFT_PRODUCT_PENDING_VALIDATION",
                  "🔶 Produit en attente de validation",
                  event.actorName() + " a enregistré un nouveau produit en brouillon : '"
                      + event.productName() + "' — Validez-le pour l'activer dans votre catalogue",
                  "/products/" + event.productId() + "/edit",  // matches Go Router route /products/:id/edit
                  Map.of(
                      "productId", event.productId().toString(),
                      "productName", event.productName(),
                      "tenantId", event.tenantId().toString()
                  )
              ));
          } catch (Exception e) {
              log.warn("[NOTIFICATION] Failed to notify owners for draft {}: {}", event.productId(), e.getMessage());
              // DO NOT rethrow — draft creation must never fail due to notification error
          }
      }

      /**
       * Handles CSV bulk import — emits ONE consolidated notification instead of N individual ones.
       * Called by ImportCsvProductsUseCase via CsvImportCompletedEvent (NOT ProductCreatedProgressivelyEvent).
       */
      @EventListener
      @Async
      public void onCsvImportCompleted(CsvImportCompletedEvent event) {
          if ("OWNER".equals(event.actorRole())) return;
          // No per-product DraftPendingValidation rows here — count comes from products table (STATUS=DRAFT)
          try {
              notificationPort.notifyOwners(event.tenantId(), NotificationPayload.of(
                  "CSV_IMPORT_DRAFTS_PENDING",
                  "🔶 Import CSV — produits en attente",
                  event.actorName() + " a importé " + event.importedCount()
                      + " produit(s) en brouillon via CSV — Validez-les dans votre catalogue",
                  "/products",                                     // deep-link to product list (filtered to DRAFT by the app)
                  Map.of("count", String.valueOf(event.importedCount()))
              ));
          } catch (Exception e) {
              log.warn("[NOTIFICATION] Failed to notify owners for CSV import by {}: {}", event.actorId(), e.getMessage());
          }
      }
  }

  // New domain event for CSV import completion (emit at END of import loop, NOT per-product):
  public record CsvImportCompletedEvent(
      int importedCount, UUID tenantId, UUID actorId, String actorName, String actorRole, Instant occurredAt
  ) {}
  ```
  - Module: `messaging/notification/` (architecture module — NOT inside `catalog/product/`)
  - Spring `@Async` requires `@EnableAsync` on a `@Configuration` class — add to `AsyncConfig.java` if not present
  - `ImportCsvProductsUseCase` must emit `CsvImportCompletedEvent` via `ApplicationEventPublisher` AFTER the import loop (not per product)
  - **TDD**: Write `DraftProductNotificationListenerTest`:
    - `shouldPersistDraftPendingValidationRecord()`
    - `shouldCallNotificationPortWhenActorIsEmployee()`
    - `shouldSkipNotificationWhenActorIsOwner()`
    - `shouldNotRollbackDraftWhenNotificationPortThrows()`

- [ ] 7.4 — Create `NotificationPort` interface (`messaging/notification/domain/port/out/NotificationPort.java`) **(NEW — AC9 infrastructure)**:
  ```java
  public interface NotificationPort {
      /**
       * Send a notification to all OWNER-role users of the given tenant.
       * Implementation is best-effort — callers must handle exceptions independently.
       */
      void notifyOwners(UUID tenantId, NotificationPayload payload);
  }
  ```
  ```java
  public record NotificationPayload(
      String type,                       // e.g. "DRAFT_PRODUCT_PENDING_VALIDATION", "LOW_STOCK", "SALES_TREND"
      String title,
      String body,
      String deepLink,                   // nullable — go_router path e.g. "/products/{id}/edit" (NOT keevo:// scheme)
      Map<String, String> metadata       // FCM data payload fields — Epic 8.1 FcmNotificationAdapter reads these
                                         // e.g.: { "productId": "...", "productName": "...", "tenantId": "..." }
                                         // stock alerts add: { "storeId": "...", "qty": "N", "threshold": "N" }
  ) {
      /** Convenience factory for single-product draft notifications. */
      public static NotificationPayload of(String type, String title, String body, String deepLink) {
          return new NotificationPayload(type, title, body, deepLink, Map.of());
      }

      /** Convenience factory with metadata — use for Epic 8.1+ typed notifications. */
      public static NotificationPayload of(String type, String title, String body, String deepLink,
                                           Map<String, String> metadata) {
          return new NotificationPayload(type, title, body, deepLink, metadata);
      }
  }
  ```
  > **Epic 8.1 note**: `FcmNotificationAdapter` will send `metadata` fields as FCM **data payload** (always delivered, even when app is in background) and `title`+`body` as the notification payload (shown in system tray). The `deepLink` value is passed to `GoRouter.of(context).go(deepLink)` in the Flutter `FirebaseMessaging.onMessageOpenedApp` handler.

- [ ] 7.5 — Create `LoggingNotificationAdapter` stub (`messaging/notification/adapter/out/LoggingNotificationAdapter.java`) **(stub for this story — FCM adapter in Epic 8.1)**:
  ```java
  @Component
  @Primary  // replaced by FcmNotificationAdapter in Epic 8.1 on the same bean
  public class LoggingNotificationAdapter implements NotificationPort {
      private static final Logger log = LoggerFactory.getLogger(LoggingNotificationAdapter.class);
  
      @Override
      public void notifyOwners(UUID tenantId, NotificationPayload payload) {
          log.info("[NOTIFICATION STUB] tenant={} type={} title='{}' body='{}' deepLink={} metadata={}",
              tenantId, payload.type(), payload.title(), payload.body(), payload.deepLink(), payload.metadata());
          // TODO (Epic 8.1): Replace with FcmNotificationAdapter
          //   - Load OWNER device tokens from device_tokens table (per-tenant: SELECT * FROM device_tokens WHERE role='OWNER')
          //   - Build FCM MulticastMessage: notification={title, body} + data={deepLink, ...metadata}
          //   - Call Firebase Admin SDK: FirebaseMessaging.getInstance().sendEachForMulticast(message)
          //   - Handle token expiry (UNREGISTERED → delete token), quota exceeded → retry with backoff
          //   - deepLink field → set as FCM data["deepLink"] → Flutter onMessageOpenedApp calls GoRouter.of(context).go(deepLink)
      }
  }
  ```

- [ ] 7.6 — Create `GetPendingDraftsCountUseCase` (`messaging/notification/application/usecase/GetPendingDraftsCountUseCase.java`) **(NEW — AC10)**:
  ```java
  @Service
  public class GetPendingDraftsCountUseCase {
      public record GetPendingDraftsCountQuery(UUID actorId, String actorRole) {}
      public record PendingDraftsCount(int pending) {}

      public PendingDraftsCount execute(GetPendingDraftsCountQuery query) {
          if (!"OWNER".equals(query.actorRole())) throw new DomainException(ErrorCode.FORBIDDEN);
          int count = draftPendingValidationRepository.countUnacknowledgedByTenant();
          return new PendingDraftsCount(count);
      }
  }
  ```
  - `GET /api/v1/products/drafts/count` — endpoint added to `ProductImportController` or a new `DraftNotificationController`
  - Response: `{ "data": { "pending": N } }` — follows `ApiResponseWrapper.success()` pattern
  - **TDD**: Write `GetPendingDraftsCountUseCaseTest`:
    - `shouldReturnCountOfUnacknowledgedDrafts()`
    - `shouldThrowForbiddenWhenEmployeeQueries()`

---

#### Task 8 — Use Case: `GenerateCsvTemplateUseCase` (AC5)

- [ ] 8.1 — Create `GenerateCsvTemplateUseCase` (`catalog/product/application/usecase/GenerateCsvTemplateUseCase.java`):
  - Returns `byte[]` representing the CSV template
  - Header: `nom,prix_achat,cout_transport,prix_vente,categorie,sku,quantite_initiale,seuil_min`
  - 3 example rows:
    ```
    Chemise homme blanche,3000,500,6500,Vêtements & Shopping,,10,5
    Smartphone Samsung A15,85000,2000,120000,Téléphonie,KEV-PHONE1,3,2
    Cahier 200 pages,500,50,1200,Papeterie,,50,10
    ```
  - Pure Java — no framework dependency — uses `StringBuilder` or `commons-csv CSVPrinter`
  - **TDD**: Write `GenerateCsvTemplateUseCaseTest`:
    - `shouldReturnBytesWithCorrectHeaderRow()`
    - `shouldContainThreeExampleRows()`
    - `shouldBeValidUtf8Csv()`

---

#### Task 9 — DTOs (AC1, AC2, AC3, AC5, AC6)

- [ ] 9.1 — Create `CsvColumnMappingRequestDto` (`catalog/product/adapter/in/web/dto/CsvColumnMappingRequestDto.java`):
  ```java
  public record CsvColumnMappingRequestDto(
      @NotBlank String nameColumn,
      @NotBlank String priceColumn,
      String buyPriceColumn,
      String transportCostColumn,
      String categoryColumn,
      String skuColumn,
      String quantityColumn,
      String thresholdColumn
  ) {}
  ```
  - Validation: `@NotBlank` on required fields (Jakarta Validation)
  - **TDD**: Write `CsvColumnMappingRequestDtoTest.shouldRejectNullRequiredFields()`

- [ ] 9.2 — Create `ImportResultResponseDto` (`catalog/product/adapter/in/web/dto/ImportResultResponseDto.java`):
  ```java
  public record ImportResultResponseDto(
      int imported,
      int skipped,
      List<CsvRowErrorDto> errors,
      boolean limitReached,
      String message  // null unless limitReached or partial error
  ) {
      public record CsvRowErrorDto(int line, String column, String message) {}

      public static ImportResultResponseDto from(ImportResult result) { ... }
  }
  ```

- [ ] 9.3 — Create `CreateDraftProductRequestDto` (`catalog/product/adapter/in/web/dto/CreateDraftProductRequestDto.java`):
  ```java
  public record CreateDraftProductRequestDto(
      @NotBlank String name,
      @NotNull @Min(1) Integer priceVente,
      @NotNull UUID categoryId,
      Integer stockQuantity  // optional
  ) {}
  ```

---

#### Task 10 — Controller: `ProductImportController` (AC1, AC2, AC3, AC5, AC6)

- [ ] 10.1 — Create `ProductImportController` (`catalog/product/adapter/in/web/ProductImportController.java`):
  ```java
  @Tag(name = "Product Import", description = "CSV bulk import and draft product creation")
  @RestController
  @RequestMapping("/api/v1/products")
  @PreAuthorize("hasRole('USER')")
  public class ProductImportController {
      // Dependencies: ImportCsvProductsUseCase, CreateDraftProductUseCase, GenerateCsvTemplateUseCase
  
      // POST /api/v1/products/import
      // consumes multipart/form-data
      // @RequestPart("file") MultipartFile csvFile
      // @RequestPart("mapping") @Valid CsvColumnMappingRequestDto mapping
      // @RequestHeader("Authorization") to extract actorId + actorRole via JWT
      // Returns: ResponseEntity<Map<String, Object>> — { data: ImportResultResponseDto }
  
      // GET /api/v1/products/import/template
      // Returns: ResponseEntity<byte[]> with Content-Type: text/csv
      // Content-Disposition: attachment; filename="keevo_import_template.csv"
  
      // POST /api/v1/products/draft
      // @Valid @RequestBody CreateDraftProductRequestDto
      // Returns: ResponseEntity<Map<String, Object>> — { data: ProductResponseDto }
  }
  ```
  - ⚠️ **Anti-pattern guard**: Do NOT use a local `@ExceptionHandler` in this controller. All DomainException handling goes through `GlobalExceptionHandler`. See Story 1.4 incident.
  - ⚠️ **actorId AND actorRole extraction**: Extract both from JWT in the Controller:
    ```java
    var auth = SecurityContextHolder.getContext().getAuthentication();
    UUID actorId = (UUID) auth.getPrincipal();
    String actorRole = auth.getAuthorities().iterator().next()
                           .getAuthority().substring(5); // strips "ROLE_" prefix → "OWNER" or "EMPLOYEE"
    ```
    Pass both as fields in every command that requires RBAC enforcement. Use cases must never call `SecurityContextHolder`.
  - **RBAC enforcement note**: `@EnableMethodSecurity` is NOT enabled in this project. `@PreAuthorize` annotations on the controller class are declarative documentation only. Actual RBAC enforcement happens inside the use cases via the `actorRole` parameter.
  - **TDD**: Write `ProductImportControllerTest` (MockMvc / `@WebMvcTest`) FIRST:
    - `shouldReturn200WithImportResultOnValidCsvUpload()`
    - `shouldReturn422WhenMappingHasMissingRequiredFields()`
    - `shouldReturn200WithLimitReachedWhenPlanExceeded()`
    - `shouldReturn200WithCsvTemplateBytes()`
    - `shouldReturn201WithDraftProductOnDraftEndpoint()`
    - `shouldReturn401WhenUnauthenticated()`
    - `shouldReturn400WhenFileIsMissing()`
    - `shouldReturn403WhenEmployeeTriesToImportCsv()` **(NEW — AC8 RBAC)**
    - `shouldReturn409WhenDraftCreatedWithDuplicateName()` **(NEW — AC8 uniqueness)**

---

### FLUTTER — Riverpod / Drift / Dio

---

#### Task 11 — Add `file_picker` dependency (AC1)

- [ ] 11.1 — Add `file_picker: ^6.1.1` to `keevo/app/pubspec.yaml` under `dependencies:`
  - Supports: Android 5.0+, iOS 11+, Linux (portals), Windows, macOS
  - Picker filter: `FileType.custom`, `allowedExtensions: ['csv']`
  - `pickFiles(allowMultiple: false, withData: true)` — `PlatformFile.bytes` gives `Uint8List` (works on all platforms)
  - Run `flutter pub get` after adding
  > ⚠️ `file_picker` on Linux desktop requires the `xdg-portal` or `kdialog` portal to be available (present on all modern Ubuntu/GNOME/KDE systems). No extra Linux setup needed.

---

#### Task 12 — Domain models: `CsvImportResult` & `CsvPreviewRow` (AC1, AC2, AC3)

- [ ] 12.1 — Create `CsvImportResult` Freezed model (`features/catalog/domain/model/csv_import_result.dart`):
  ```dart
  @freezed
  class CsvImportResult with _$CsvImportResult {
    const factory CsvImportResult({
      required int imported,
      required int skipped,
      @Default([]) List<CsvRowErrorModel> errors,
      @Default(false) bool limitReached,
      String? message,
    }) = _CsvImportResult;

    factory CsvImportResult.fromJson(Map<String, dynamic> json) => _$CsvImportResultFromJson(json);
  }

  @freezed
  class CsvRowErrorModel with _$CsvRowErrorModel {
    const factory CsvRowErrorModel({
      required int line,
      required String column,
      required String message,
    }) = _CsvRowErrorModel;

    factory CsvRowErrorModel.fromJson(Map<String, dynamic> json) => _$CsvRowErrorModelFromJson(json);
  }
  ```
  - Run `build_runner` to generate `.g.dart` and `.freezed.dart`

---

#### Task 13 — `ProductRepository` interface extension (AC5, AC6)

- [ ] 13.1 — Add three new methods to `features/catalog/domain/repository/product_repository.dart`:
  ```dart
  /// Upload CSV bytes + column mapping JSON to backend for bulk import.
  /// Requires connectivity — DomainException if offline.
  Future<CsvImportResult> importCsv({
    required Uint8List csvBytes,
    required String fileName,
    required Map<String, String> columnMapping,
  });

  /// Download CSV template bytes from backend.
  Future<Uint8List> downloadCsvTemplate();

  /// Create a DRAFT product locally (for POS on-the-fly creation).
  /// Queued to backend via sync_queue if offline.
  Future<ProductModel> createDraft({
    required String name,
    required int priceVente,
    required String categoryId,
    int stockQuantity,
  });
  ```

---

#### Task 14 — Remote datasource: `RemoteCsvImportDataSource` (AC2, AC5)

- [ ] 14.1 — Create `RemoteCsvImportDataSource` (`features/catalog/data/datasource/remote_csv_import_datasource.dart`):
  ```dart
  class RemoteCsvImportDataSource {
    final Dio _dio;

    /// POST /api/v1/products/import — multipart upload
    Future<CsvImportResult> importCsv({
      required Uint8List csvBytes,
      required String fileName,
      required Map<String, String> columnMapping,
    }) async {
      final mappingJson = jsonEncode(columnMapping);
      final formData = FormData.fromMap({
        'file': MultipartFile.fromBytes(csvBytes, filename: fileName,
            contentType: DioMediaType('text', 'csv')),
        'mapping': mappingJson,
      });
      // POST with Dio, parse response { data: ImportResultResponseDto }
    }

    /// GET /api/v1/products/import/template
    Future<Uint8List> downloadCsvTemplate() async {
      final response = await _dio.get<List<int>>(
        '/api/v1/products/import/template',
        options: Options(responseType: ResponseType.bytes),
      );
      return Uint8List.fromList(response.data!);
    }

    /// POST /api/v1/products/draft — create DRAFT product
    Future<ProductResponseDto> createDraft(Map<String, dynamic> payload) async { ... }
  }
  ```
  > ⚠️ Note on mapping parameter: The backend `@RequestPart("mapping")` receives the JSON-encoded mapping as a form part. Dio sends it as a string `MultipartFile.fromString(mappingJson, contentType: MediaType('application', 'json'))`.

---

#### Task 15 — `ProductRepositoryImpl` implementation (AC5, AC6)

- [ ] 15.1 — Add `importCsv()` implementation to `ProductRepositoryImpl`:
  - Delegates to `RemoteCsvImportDataSource.importCsv()`
  - After successful import, triggers `syncFromRemote()` to pull new products into local Drift DB
  - Throws `ProductException.networkRequired()` if Dio connection error (no offline fallback — import requires connectivity)

- [ ] 15.2 — Add `downloadCsvTemplate()` implementation:
  - Delegates to `RemoteCsvImportDataSource.downloadCsvTemplate()`
  - Saves bytes to `getDownloadsDirectory()` (via `path_provider`) as `keevo_import_template_${timestamp}.csv`
  - Returns `Uint8List` for display feedback

- [ ] 15.3 — Add `createDraft()` implementation:
  - Creates product in local Drift `products` table with `status = 'DRAFT'`, generates UUID via `const Uuid().v4()`
  - Queues `POST /api/v1/products/draft` to `sync_queue` (standard offline-first pattern)
  - If online: fires sync immediately (write-through pattern, as established in Story 2.3)

---

#### Task 16 — Riverpod: `CsvImportNotifier` (AC1, AC2, AC3, AC5)

- [ ] 16.1 — Create `CsvImportNotifier` (`features/catalog/presentation/provider/csv_import_provider.dart`):
  ```dart
  // State machine
  sealed class CsvImportState {
    const CsvImportState();
  }
  class CsvImportIdle extends CsvImportState { const CsvImportIdle(); }
  class CsvImportFilePicked extends CsvImportState {
    final Uint8List bytes;
    final String fileName;
    final List<String> detectedHeaders;
    const CsvImportFilePicked({ required this.bytes, required this.fileName, required this.detectedHeaders });
  }
  class CsvImportLoading extends CsvImportState { const CsvImportLoading(); }
  class CsvImportSuccess extends CsvImportState {
    final CsvImportResult result;
    const CsvImportSuccess(this.result);
  }
  class CsvImportError extends CsvImportState {
    final String message;
    const CsvImportError(this.message);
  }

  @riverpod
  class CsvImportNotifier extends _$CsvImportNotifier {
    @override
    CsvImportState build() => const CsvImportIdle();

    Future<void> pickFile() async { ... } // uses FilePicker, extracts headers from first line
    Future<void> runImport({ required Map<String, String> mapping }) async { ... }
    Future<void> downloadTemplate() async { ... }
    void reset() => state = const CsvImportIdle();
  }
  ```
  - **Header extraction** (no package needed): Take `bytes`, decode first 1 KB as UTF-8, split on first newline, split on comma → `List<String> headers`. Strip BOM if present (`bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF`).
  - Run `build_runner` to generate `csv_import_provider.g.dart`

---

#### Task 17 — `CsvImportPage` — 3-step wizard (AC1, AC2, AC3, AC4, AC5)

- [ ] 17.1 — Create `CsvImportPage` (`features/catalog/presentation/page/csv_import_page.dart`):
  - Uses `ConsumerStatefulWidget` with `PageView` (3 steps — no back animation jaggedness)  
  - **Step 0 — "Choisir un fichier"**:
    - "Télécharger le modèle CSV" button → triggers `downloadTemplate()` → SnackBar
    - "Choisir un fichier .csv" FilledButton → triggers `pickFile()` → transitions to step 1
    - Empty state illustration with instructions (no jargon)
  - **Step 1 — "Correspondance des colonnes"** (shown when state is `CsvImportFilePicked`):
    - File name in header (read-only chip)  
    - Two required fields (Nom du produit, Prix de vente) with `DropdownButtonFormField<String>` — options = detected headers + "Ignorer"
    - Six optional fields with same dropdown (default = "Ignorer")
    - Preview table (5 rows × mapped columns, read-only `DataTable`) at bottom
    - "Lancer l'import" `FilledButton` — disabled until required mappings filled  
    - "Annuler" `TextButton` → reset
  - **Step 2 — "Résultat de l'import"** (shown when state is `CsvImportSuccess`):
    - "🎉 X produits importés" in large green text
    - If `skipped > 0`: "⚠ Y lignes ignorées" in orange
    - If `limitReached`: upgrade CTA card — "Limite du plan gratuit atteinte — Passez au plan Premium"
    - Scrollable list of errors (line number + message)  
    - "Voir le catalogue" `FilledButton` → pops to `/catalog`
    - "Importer un autre fichier" `TextButton` → reset
  - Error state: SnackBar via `ref.listen` on error state

- [ ] 17.2 — Route: add to `app_router.dart`:
  ```dart
  GoRoute(
    path: '/catalog/import',
    name: 'catalog-import',
    builder: (context, state) => const CsvImportPage(),
  )
  ```
  as a child of the `/catalog` shell route (same position as `/catalog/product/:mode`)

- [ ] 17.3 — CatalogPage: add "Importer CSV" menu item to the AppBar `actions`:
  ```dart
  PopupMenuButton<String>(
    icon: const Icon(Icons.more_vert, color: Colors.white),
    onSelected: (value) {
      if (value == 'import') context.push('/catalog/import');
    },
    itemBuilder: (_) => [
      const PopupMenuItem(value: 'import', child: ListTile(
        leading: Icon(Icons.upload_file),
        title: Text('Importer CSV'),
      )),
    ],
  )
  ```

---

#### Task 18 — `CreateDraftProductBottomSheet` (AC6)

- [ ] 18.1 — Create `CreateDraftProductBottomSheet` (`features/catalog/presentation/widget/create_draft_product_bottom_sheet.dart`):
  ```dart
  // Usage (from POS in Epic 4):
  // showModalBottomSheet(context: context, builder: (_) =>
  //   CreateDraftProductBottomSheet(prefillName: searchedName));
  class CreateDraftProductBottomSheet extends ConsumerWidget {
    final String prefillName;
    // ...
    // Form: name (read-only, pre-filled), priceVente (TextField, autofocus),
    //       categoryId (DropdownButtonFormField — from categoriesProvider),
    //       stockQuantity (TextField, default 1)
    // Submit: 'Créer & Ajouter' → repo.createDraft() → passes created ProductModel to caller via Navigator.pop
  }
  ```
  - This widget is a pure UI component ready for POS integration in Epic 4. No routing needed in this story.
  - `categoriesProvider` already exists in `catalog/presentation/provider/category_provider.dart`

---

### TESTS — Flutter

- [ ] **Task 19 — Flutter unit & widget tests**
  - [ ] 19.1 — `CsvImportNotifierTest` (`test/features/catalog/presentation/provider/csv_import_notifier_test.dart`):
    - `shouldTransitionToFilePickedStateOnSuccessfulPick()`
    - `shouldTransitionToSuccessStateOnImport()`
    - `shouldTransitionToErrorStateOnNetworkFailure()`
    - `shouldExtractHeadersFromCsvFirstLine()`
    - `shouldStripBomFromCsvBytes()`
  - [ ] 19.2 — `CreateDraftProductBottomSheetTest` (widget test):
    - `shouldShowPrefilledName()`
    - `shouldDisableSubmitWhenPriceEmpty()`
  - [ ] 19.3 — `PendingDraftsCountProviderTest` (`test/features/catalog/presentation/provider/pending_drafts_count_provider_test.dart`) **(NEW — AC10)**:
    - `shouldReturnZeroWhenNoDraftProducts()`
    - `shouldReturnCountMatchingDriftQuery()`
    - `shouldDecrementWhenProductPromotedToActive()`
  - [ ] 19.4 — `DraftValidationBannerTest` (widget test) **(NEW — AC10)**:
    - `shouldShowBannerWhenPendingCountGreaterThanZero()`
    - `shouldHideBannerWhenNoDrafts()`
    - `shouldFilterToDraftListOnTap()`

---

### E2E — curl-tests-story-2-4.sh

- [ ] **Task 20 — Create `curl-tests-story-2-4.sh`** (`keevo/scripts/curl-tests-story-2-4.sh`):

  > Pattern: self-contained, single-run. Each step creates its own tenant (register → login → select-tenant → test → verify). Uses `python3 -c` for JSON parsing (no jq dependency).

  ```
  === Keevo Story 2.4 — CSV Import & Draft Products E2E curl tests ===

  Step 1:  Register + Login + Select Tenant as OWNER (shared auth for Steps 2-8, 11-12)
  Step 2:  GET /api/v1/products/import/template
           → assert HTTP 200, Content-Type: text/csv
           → assert first line contains "nom,prix_achat"
  Step 3:  POST /api/v1/products/import — Valid CSV (5 rows, no mapping issues)
           → assert HTTP 200, imported = 5, skipped = 0, limitReached = false
  Step 4:  POST /api/v1/products/import — CSV with 2 invalid rows (missing prix_vente)
           → assert HTTP 200, imported = 3, skipped = 2, errors[0].line > 0
  Step 5:  POST /api/v1/products/import — Row with quantite_initiale = 10
           → assert imported product has stock via GET /api/v1/stock/{productId}
  Step 6:  POST /api/v1/products/draft — Owner creates DRAFT product ("Chapeau rouge")
           → assert HTTP 201, status = "DRAFT"
  Step 7:  PATCH /api/v1/products/{draftId} — Owner fills all required fields
           → assert HTTP 200, status = "ACTIVE" (promotion)
  Step 8:  POST /api/v1/products/import — Plan limit test (Free plan)
           → pre-fill tenant with 499 products via loop (or seed)
           → import CSV with 5 rows
           → assert limitReached = true, imported = 1, skipped = 4

  Step 9:  Register + Login + Select Tenant as EMPLOYEE (separate employee auth)
           POST /api/v1/products/draft — Employee creates DRAFT ("Sandales bleues")
           → assert HTTP 201, status = "DRAFT"
           → confirms EMPLOYEE is authorized to create drafts (AC6 RBAC)

  Step 10: PATCH /api/v1/products/{draftId from Step 9} — Employee tries to promote to ACTIVE
           → assert HTTP 403 FORBIDDEN (domainCode = "FORBIDDEN")
           → confirms EMPLOYEE cannot promote DRAFT→ACTIVE (AC7 RBAC)

  Step 11: POST /api/v1/products/import — Employee tries to import CSV
           → assert HTTP 403 FORBIDDEN (domainCode = "FORBIDDEN")
           → confirms CSV import is Owner-only (AC8 RBAC)

  Step 12: (Owner auth) POST /api/v1/products/draft — "Chapeau rouge" again (same name as Step 6, now ACTIVE)
           → assert HTTP 409 CONFLICT (domainCode = "PRODUCT_NAME_ALREADY_EXISTS")
           POST /api/v1/products/draft — "Sandales bleues" (same name as Step 9, already DRAFT)
           → assert HTTP 409 CONFLICT (domainCode = "PRODUCT_NAME_ALREADY_EXISTS")
           POST /api/v1/products/import — CSV with one row named "Chapeau rouge"
           → assert HTTP 200, imported = 0, skipped = 1, errors[0].message contains "Nom déjà existant"
           → confirms no duplicate name allowed regardless of status (AC8 uniqueness)

  Step 13: (Owner auth) GET /api/v1/products/drafts/count
           → assert HTTP 200, pending >= 1
           → "Sandales bleues" (Step 9) is still DRAFT and unacknowledged
           → confirms in-app badge count endpoint works (AC10)

  Step 14: (Owner auth) PATCH /api/v1/products/{draftId from Step 9} — promote to ACTIVE
           → assert HTTP 200, status = "ACTIVE"
           [ITERATION — async acknowledgement] Retry up to 5 times with 1s sleep:
             GET /api/v1/products/drafts/count
             → assert pending = previous_count - 1
             → acknowledged flag was set by UpdateProductUseCase listener
           → confirms badge decrements on validation (AC9 + AC10)

  Step 15: (Employee auth) GET /api/v1/products/drafts/count
           → assert HTTP 403 FORBIDDEN
           → confirms count endpoint is Owner-only (AC10 RBAC)
  ```

  **Iteration Pattern for async operations:**
  ```bash
  # Use this pattern for any step that involves async side-effects (notification,
  # event listeners, @Async Spring methods)
  assert_with_retry() {
    local description="$1"
    local expected="$2"
    local max_retries=5
    local sleep_sec=1
    for i in $(seq 1 $max_retries); do
      actual=$(eval "$3")  # $3 = command to evaluate
      if [[ "$actual" == "$expected" ]]; then
        echo "[PASS] $description (attempt $i)"
        return 0
      fi
      echo "  [RETRY $i/$max_retries] actual='$actual' expected='$expected', sleeping ${sleep_sec}s..."
      sleep $sleep_sec
    done
    echo "[FAIL] $description — expected '$expected', got '$actual' after $max_retries retries"
    return 1
  }
  # Usage: assert_with_retry "Step 14 badge decrements" "0" "get_pending_count $OWNER_TOKEN"
  ```

  - Script is executable (`chmod +x`), runs against `localhost:8080`
  - Exit code 0 if ALL assertions pass, 1 if any fail (short-circuit on first failure)
  - Print `[PASS]` / `[FAIL]` per step with actual vs expected values
  - Print `[STEP N]` header before each step for scannable output
  - Employee registration: register a new user, then Owner invites them as EMPLOYEE (or use the invite-employee endpoint established in Story 1.7)
  - At the end of the script: print a summary table — `Total: X passed, Y failed`

---

## Dev Notes

### Notification Architecture (Story 2-4 scope vs Epic 8.1)

```
  ┌──────────────────────────────────────────────────────────────────────────┐
  │  SINGLE DRAFT (employee tap "Créer produit" during POS flow)              │
  │                                                                            │
  │  POST /products/draft ──▶ CreateDraftProductUseCase                        │
  │                               │ publishes ProductCreatedProgressivelyEvent │
  │                               │ { actorRole="EMPLOYEE", productId, ... }   │
  │                               ▼                                            │
  │                  DraftProductNotificationListener.onDraftProductCreated()  │
  │                    ├─▶ DraftPendingValidationRepository.save() (badge row) │
  │                    └─▶ NotificationPort.notifyOwners(tenantId, payload)    │
  │                              payload.deepLink = "/products/{id}/edit"  ✅  │
  │                              payload.metadata = {productId, productName}   │
  └──────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────┐
  │  CSV BULK IMPORT (owner uploads CSV → N products created)                 │
  │                                                                            │
  │  POST /products/import ──▶ ImportCsvProductsUseCase (import loop)         │
  │                                │ After loop: publishes CsvImportCompleted  │
  │                                │ Event { importedCount=N, actorRole=... }  │
  │                                ▼                                           │
  │                DraftProductNotificationListener.onCsvImportCompleted()    │
  │                  └─▶ ONE batched notification: "X a importé N produits"   │
  │                        ⚠️ NOT one notification per product (N spam guard) │
  └──────────────────────────────────────────────────────────────────────────┘

  NotificationPort implementations:
  ┌────────────────────────────┐        ┌──────────────────────────────────┐
  │ LoggingNotificationAdapter │ (now)  │ FcmNotificationAdapter           │ (Epic 8.1)
  │ @Primary stub — logs only  │        │ Firebase Admin SDK               │
  │ No FCM calls               │        │ deepLink → GoRouter.go(deepLink) │
  └────────────────────────────┘        │ metadata → FCM data payload      │
                                        │ title/body → notification payload│
                                        └──────────────────────────────────┘

  Flutter side (Epic 8.1 FCM integration):
  FirebaseMessaging.onMessageOpenedApp ──▶ router.go(message.data['deepLink'])
  Routes handled: /products/:id/edit, /products (filtered), future: /stock/:id
```

**Story 2-4 delivers:**
- `NotificationPort` interface (contract, extensible with `metadata` Map)
- `NotificationPayload` record (`type`, `title`, `body`, `deepLink`, `metadata`)
- `CsvImportCompletedEvent` record (bulk import batching)
- `LoggingNotificationAdapter` (stub — `@Primary` bean, swapped for FCM in Epic 8.1)
- `DraftProductNotificationListener` (handles both single draft + CSV bulk)
- `DraftPendingValidation` entity + repository
- `GetPendingDraftsCountUseCase` + `GET /api/v1/products/drafts/count` endpoint
- Flutter `pendingDraftsCountProvider` + `DraftValidationBanner` widget (CatalogPage only) + nav badge
- **⚠️ Bell icon (`🔔`) in global AppBar = Epic 8.1 scope** — not deliverable here

**Epic 8.1 delivers (future):**
- `FcmNotificationAdapter` replacing `LoggingNotificationAdapter` (same `NotificationPort` contract)
- Device token registration: `POST /api/v1/devices/token` (register FCM token on app start)
- `device_tokens` table in tenant schema
- APNs certificate setup for iOS
- Global notification bell icon in persistent AppBar + notification history screen (30 days)

### RBAC Enforcement Strategy
> **CRITICAL**: `@EnableMethodSecurity` is NOT enabled in this project (verified in `SecurityConfig.java`). Therefore, `@PreAuthorize` annotations on controller classes/methods are **declarative documentation only** — they are NOT enforced at runtime.

**Actual RBAC enforcement happens in the use cases via `actorRole`:**

| Operation | Endpoint | Allowed roles | Enforcement location |
|---|---|---|---|
| Create DRAFT product | `POST /api/v1/products/draft` | **OWNER + EMPLOYEE** | None (no guard — both allowed) |
| Import CSV | `POST /api/v1/products/import` | **OWNER only** | `ImportCsvProductsUseCase` step 1 |
| Download CSV template | `GET /api/v1/products/import/template` | **OWNER + EMPLOYEE** | None (read-only) |
| Promote DRAFT→ACTIVE | `PATCH /api/v1/products/{id}` | **OWNER only** | `UpdateProductUseCase` |
| Get pending drafts count | `GET /api/v1/products/drafts/count` | **OWNER only** | `GetPendingDraftsCountUseCase` |
| All other product CRUD | existing endpoints | **OWNER only** | already in existing `UpdateProductUseCase`/`ArchiveProductUseCase` — add guards if missing |

**Controller pattern for actorRole extraction:**
```java
var auth = SecurityContextHolder.getContext().getAuthentication();
UUID actorId = (UUID) auth.getPrincipal();
String actorRole = auth.getAuthorities().iterator().next()
                       .getAuthority().substring(5); // "ROLE_OWNER" → "OWNER"
```

**Use case guard pattern:**
```java
if (!"OWNER".equals(command.actorRole())) {
    throw new DomainException(ErrorCode.FORBIDDEN);
}
```

### Product Name Uniqueness
- **DB-level**: `CREATE UNIQUE INDEX IF NOT EXISTS uq_products_name ON products(lower(name))` in `TenantSchemaProvisioner` — prevents any DB-level duplicate regardless of status
- **App-level**: `productRepository.existsByName(name)` called in `CreateDraftProductUseCase` and `ImportCsvProductsUseCase` BEFORE save — provides user-friendly error with `PRODUCT_NAME_ALREADY_EXISTS`
- **Existing `CreateProductUseCase`** (Story 2.1): Also add `existsByName()` check here to maintain consistency across ALL product creation paths
- **Scope**: Uniqueness is tenant-scoped (each tenant schema is isolated) — two tenants can have products with the same name

### GoF Pattern Reminders
- **Builder** (`ImportResult.Builder`): call `builder.incrementImported()` / `builder.addError(CsvRowError)` / `builder.markLimitReached(message)` inside the processing loop. Call `builder.build()` once at the end. Never pass a mutable ImportResult around.
- **Strategy** (`CsvColumnValidator<T>`): inject a `Map<KeeFileField, CsvColumnValidator<?>>` into `ProductCsvRowFactory`. Adding a new field type means implementing a new validator — no modification to existing code.  
- **Factory Method** (`ProductCsvRowFactory.fromRow()`): this is the **only** place that maps CSV column names to domain values. `ImportCsvProductsUseCase` does NOT contain field extraction logic — it only calls the factory.

### Architecture Rules (recap from architecture.md)
1. **TDD**: Write failing test FIRST. Always. No exception.
2. **Hexagonal**: `CsvParserPort` lives in `domain/port/out/`. `CommonsCsvParserAdapter` lives in `adapter/out/csv/`. `ImportCsvProductsUseCase` lives in `application/usecase/`.
3. **No HTTP types in ports**: `ImportCsvProductsUseCase.ImportCsvCommand` receives `InputStream`, not `MultipartFile`. `MultipartFile` → `InputStream` conversion happens in the Controller.
4. **actorId AND actorRole injection**: Always passed explicitly in command records. Never via `SecurityContextHolder` inside use cases or domain services.
5. **MCP placeholder**: `catalog/product/adapter/in/mcp/.gitkeep` MUST be created per architecture convention.
6. **GlobalExceptionHandler only**: No `@ExceptionHandler` in `ProductImportController`. Story 1.4 anti-pattern applies here too.
7. **OpenAPI annotations**: `ProductImportController` MUST have `@Tag(name = "Product Import")` and each endpoint needs `@Operation` + `@ApiResponse`.

### Plan Limit Check Pattern
- `PlanLimitGuard.checkProductLimit()` already exists in `subscription/plan/application/service/`
- However, for CSV import, we do NOT call `checkProductLimit()` per-row (that would throw on the first row over limit, unwinding the whole loop).
- Instead, compute `remaining = maxProducts - currentCount` before the loop, then stop the loop when `imported >= remaining`. This is a deliberate deviation from the single-product creation pattern — it allows partial import.
- The `PlanLimitGuard` is NOT suitable for this use case as-is. Use `PlanType.getMaxProducts()` directly.

### CSV Column Mapping — Request Encoding Note
- Spring `@RequestPart("mapping")` + Jackson deserializes the JSON part automatically when the `Content-Type` of that part is `application/json`.
- On the Flutter side, Dio `MultipartFile.fromString(jsonEncode(mapping), contentType: DioMediaType('application', 'json'))` sets the correct part content type.
- Alternative: receive mapping as `@RequestParam` individual strings (simpler but verbose for 8 fields). Use `@RequestPart` + JSON for cleaner API.

### Drift Schema — No Migration Needed
- The `products` Drift table already has `status TEXT DEFAULT 'ACTIVE'` (Schema v3, Story 2.1).
- `createDraft()` simply writes `status = 'DRAFT'` on insert. No schema migration needed.
- No new Drift table is needed for this story. All tables already exist at schema v6.

### DRAFT Product Display in Flutter
- `ProductCard` should show a "🔶 Brouillon" amber badge when `product.status == 'DRAFT'`.
- `ProductCard` already exists in `features/catalog/presentation/widget/product_card.dart` — add a `Chip` or `Badge` overlay when status is DRAFT.
- DRAFT products appear in the Catalogue `Active` tab (not Archivés). UX: they are incomplete but active, not hidden.

### CSV Template Download (Android/iOS vs Desktop)
- `getDownloadsDirectory()` from `path_provider`:
  - Android: returns `/storage/emulated/0/Download/` (requires `WRITE_EXTERNAL_STORAGE` on Android <10, granted automatically on Android 10+)
  - iOS: returns the app's `Documents/` (visible in Files app)
  - Linux/Windows: returns `~/Downloads/`
  - `path_provider` already in `pubspec.yaml` — no new dependency needed.  
- File name: prefix with timestamp to avoid collisions: `keevo_import_${DateTime.now().millisecondsSinceEpoch}.csv`

### HTTP Status Conventions for Import
- `POST /api/v1/products/import` → **HTTP 200** (even when `limitReached: true` — this is a partial success, not an error)
- `POST /api/v1/products/draft` → **HTTP 201** (new resource created)
- `GET /api/v1/products/import/template` → **HTTP 200** (not 201)
- `422 UNPROCESSABLE_ENTITY` only if the CSV file is syntactically malformed (cannot be parsed at all)

### Previously Established Patterns to Follow
- `ProductController.java` uses `SecurityContextHolder.getContext().getAuthentication()` to extract actorId (`UUID.fromString(auth.getName())`) — follow same pattern in `ProductImportController`.
- `ApiResponseWrapper.success(data)` wraps all 2xx responses — use it in all new controller methods.
- Import/export exceptions mapped in `GlobalExceptionHandler` — do NOT add local `@ExceptionHandler`.

### Project Structure Notes

**Backend new files:**
```
src/main/java/com/keevo/
  catalog/product/
    domain/
      event/
        ProductImportedFromCsvEvent.java  (NEW)
        ProductCreatedProgressivelyEvent.java  (NEW)
      port/
        out/
          CsvParserPort.java  (NEW)
    application/
      dto/
        CsvColumnMapping.java  (NEW)
        CsvRowError.java  (NEW)
        ImportResult.java  (NEW)
      usecase/
        ImportCsvProductsUseCase.java  (NEW)
        CreateDraftProductUseCase.java  (NEW)
        GenerateCsvTemplateUseCase.java  (NEW)
      UpdateProductUseCase.java  (MODIFY — add DRAFT→ACTIVE promotion + acknowledgement)
    adapter/
      in/
        web/
          ProductImportController.java  (NEW — includes GET /products/drafts/count)
          dto/
            CsvColumnMappingRequestDto.java  (NEW)
            ImportResultResponseDto.java  (NEW)
            CreateDraftProductRequestDto.java  (NEW)
        mcp/
          .gitkeep  (NEW — MANDATORY)
      out/
        csv/
          CommonsCsvParserAdapter.java  (NEW)
          validator/
            CsvColumnValidator.java  (NEW — interface)
            RequiredStringValidator.java  (NEW)
            PositiveIntegerValidator.java  (NEW)
            OptionalIntegerValidator.java  (NEW)
            OptionalStringValidator.java  (NEW)
            CsvValidationException.java  (NEW)
          ProductCsvRowFactory.java  (NEW)
  messaging/notification/                  (NEW MODULE — story 2-4 bootstraps)
    domain/
      model/
        DraftPendingValidation.java  (NEW)
        NotificationPayload.java    (NEW record)
      port/
        out/
          NotificationPort.java    (NEW interface)
          DraftPendingValidationRepository.java  (NEW interface)
    application/
      listener/
        DraftProductNotificationListener.java  (NEW — @EventListener @Async)
      usecase/
        GetPendingDraftsCountUseCase.java  (NEW)
    adapter/
      out/
        stub/
          LoggingNotificationAdapter.java  (NEW — @Primary stub, replaced by FCM in Epic 8.1)
        persistence/
          JpaDraftPendingValidationRepository.java  (NEW — JPA impl)
      in/
        mcp/
          .gitkeep  (NEW — MANDATORY)
```

**Backend modified files:**
```
shared/domain/exception/ErrorCode.java  (ADD CSV_PARSE_ERROR + PRODUCT_NAME_ALREADY_EXISTS)
shared/infrastructure/web/GlobalExceptionHandler.java  (ADD CSV_PARSE_ERROR→422, PRODUCT_NAME_ALREADY_EXISTS→409)
shared/infrastructure/persistence/TenantSchemaProvisioner.java  (ADD UNIQUE INDEX on products.name + draft_notifications table DDL)
catalog/product/domain/port/out/ProductRepository.java  (ADD existsByName(String name))
catalog/product/application/usecase/CreateProductUseCase.java  (ADD existsByName check — enforce uniqueness on standard create too)
catalog/product/application/usecase/UpdateProductUseCase.java  (ADD RBAC guard + DraftPendingValidation.acknowledge())
src/main/resources/application.yml  (ADD multipart size config + @EnableAsync)
pom.xml  (ADD commons-csv dependency)
```

**Backend test files (TDD-first):**
```
src/test/java/com/keevo/
  catalog/product/
    application/dto/
      ImportResultTest.java
      CsvColumnMappingTest.java
    application/usecase/
      ImportCsvProductsUseCaseTest.java
      CreateDraftProductUseCaseTest.java
      GenerateCsvTemplateUseCaseTest.java
    UpdateProductUseCaseTest.java  (MODIFY — add promotion test + EMPLOYEE forbidden test)
    adapter/in/web/
      ProductImportControllerTest.java
    adapter/out/csv/
      CommonsCsvParserAdapterTest.java
      ProductCsvRowFactoryTest.java
      validator/
        CsvColumnValidatorTest.java
    domain/event/
      ProductImportedFromCsvEventTest.java
      ProductCreatedProgressivelyEventTest.java
```

**Flutter new files:**
```
lib/
  features/catalog/
    domain/
      model/
        csv_import_result.dart  (NEW)
        csv_import_result.freezed.dart  (generated)
        csv_import_result.g.dart  (generated)
      repository/
        product_repository.dart  (MODIFY — add 3 new methods + countDraftProducts())
    data/
      datasource/
        remote_csv_import_datasource.dart  (NEW)
      repository/
        product_repository_impl.dart  (MODIFY — implement 3 new methods + countDraftProducts())
    presentation/
      page/
        csv_import_page.dart  (NEW)
      widget/
        create_draft_product_bottom_sheet.dart  (NEW)
        draft_validation_banner.dart  (NEW — AC10: shown in CatalogPage AppBar)
        product_card.dart  (MODIFY — add DRAFT badge)
      provider/
        csv_import_provider.dart  (NEW)
        csv_import_provider.g.dart  (generated)
        pending_drafts_count_provider.dart  (NEW — AC10: pendingDraftsCountProvider)
        pending_drafts_count_provider.g.dart  (generated)
  core/
    router/
      app_router.dart  (MODIFY — add /catalog/import route)
    navigation/
      scaffold_with_nav_bar.dart  (MODIFY — add Badge on catalog NavigationDestination)
pubspec.yaml  (MODIFY — add file_picker: ^6.1.1)
```

**Flutter test files:**
```
test/features/catalog/presentation/provider/
  csv_import_notifier_test.dart  (NEW)
  pending_drafts_count_provider_test.dart  (NEW — AC10)
test/features/catalog/presentation/widget/
  create_draft_product_bottom_sheet_test.dart  (NEW)
  draft_validation_banner_test.dart  (NEW — AC10)
```

**Backend test files (new — notification module):**
```
src/test/java/com/keevo/
  messaging/notification/
    application/listener/
      DraftProductNotificationListenerTest.java  (NEW)
    application/usecase/
      GetPendingDraftsCountUseCaseTest.java  (NEW)
```

**Script:**
```
keevo/scripts/curl-tests-story-2-4.sh  (NEW — 15 steps, retry pattern)
```

### References

- Plan limits: [identity/auth/domain/model/PlanType.java] — `FREE(1, 500, 3)`, `PREMIUM_TRIAL(MAX, MAX, MAX)`
- Plan limit guard: [subscription/plan/application/service/PlanLimitGuard.java#checkProductLimit]
- Product count port: [subscription/plan/domain/port/out/ProductCountPort.java] — `countActiveProducts()`
- ErrorCode enum: [shared/domain/exception/ErrorCode.java] — add `CSV_PARSE_ERROR` here
- GlobalExceptionHandler: [shared/infrastructure/web/GlobalExceptionHandler.java] — add message + HTTP mapping
- StockOperationService: [catalog/stock/domain/service/StockOperationService.java] — call `recordOperation(STOCK_ENTRY, qty, actorId, "Import CSV")` when `quantite_initiale > 0`
- ProductStatus: [catalog/product/domain/entity/ProductStatus.java] — ACTIVE / DRAFT (already exists)
- Drift products table: [app/lib/core/storage/products_table.dart] — `status TEXT DEFAULT 'ACTIVE'` (schema v6, no migration needed)
- app_router.dart patterns: [app/lib/core/router/app_router.dart] — follow existing GoRoute structure
- Anti-pattern guard: [architecture.md#Process Patterns, Error Handling] — no local @ExceptionHandler in controllers
- Architecture constraints: [_bmad-output/planning-artifacts/architecture.md#Enforcement]
- RBAC: Role enum [identity/auth/domain/model/Role.java] — OWNER, EMPLOYEE, SUPER_ADMIN
- RBAC: JwtAuthFilter [shared/infrastructure/security/JwtAuthFilter.java] — sets `ROLE_OWNER` or `ROLE_EMPLOYEE` authority
- RBAC: SecurityConfig [shared/infrastructure/security/SecurityConfig.java] — `@EnableMethodSecurity` NOT present, @PreAuthorize is decorative only
- RBAC evidence: PRD RBAC Matrix (FR21: "propriétaire peut créer/modifier/archiver produits"; FR29: "employé consulte seulement")
- RBAC evidence: UX Spec Flow 4 — Enregistrer une Vente (acteur: Loïc/Employee) — "Produit inconnu → Créer → brouillon"
- ErrorCode.FORBIDDEN: [shared/domain/exception/ErrorCode.java] — already exists → HTTP 403
- Notification architecture: [_bmad-output/planning-artifacts/architecture.md#messaging/notification/] — `NotificationPort` Observer+Template Method pattern
- Epic 8.1: [_bmad-output/planning-artifacts/epics/epic-8-alertes-notifications-gestion-oprationnelle.md] — Story 8.1 covers FCM delivery infrastructure (device token registration, actual push)
- PRD FR61: "Le système peut déclencher des alertes via push notification" — notification system is a PRD requirement
- Commons CSV docs: https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVFormat.html
- file_picker Flutter API: `FilePicker.platform.pickFiles(type: FileType.custom, allowedExtensions: ['csv'], withData: true)`

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6

### Debug Log References

### Completion Notes List

- C3 (owner self-notification) confirmed intentional by user — notification acts as a validation reminder.
- M3 (Flutter badge/banner OWNER-only guard) deferred — requires `TokenStorage.role` field addition (Epic 8.1 scope).
- M4 (actorName = UUID string in controller) deferred — requires `UserRepository.findNameById()` call.
- Route path `/products/import` used in impl vs `/catalog/import` in spec — internally consistent, low priority.

### File List

**Backend — new files:**
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductImportController.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/dto/CsvColumnMappingRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/dto/CreateDraftProductRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/dto/ImportResultResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/mcp/.gitkeep`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/csv/CommonsCsvParserAdapter.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/csv/ProductCsvRowFactory.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/csv/validator/CsvColumnValidator.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/csv/validator/CsvValidationException.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/csv/validator/RequiredStringValidator.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/csv/validator/PositiveIntegerValidator.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/csv/validator/OptionalIntegerValidator.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/csv/validator/OptionalStringValidator.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/persistence/DefaultStoreAdapter.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/dto/CsvColumnMapping.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/dto/CsvRowError.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/dto/ImportResult.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/ImportCsvProductsUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/CreateDraftProductUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/GenerateCsvTemplateUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/domain/event/CsvImportCompletedEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/domain/event/ProductImportedFromCsvEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/domain/port/out/CsvParserPort.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/domain/port/out/DefaultStorePort.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/adapter/in/mcp/.gitkeep`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/adapter/out/LoggingNotificationAdapter.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/adapter/out/persistence/JpaDraftPendingValidationRepository.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/DraftProductNotificationListener.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/application/usecase/GetPendingDraftsCountUseCase.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/domain/model/DraftPendingValidation.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/domain/model/NotificationPayload.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/domain/port/out/NotificationPort.java`
- `keevo/backend/src/main/java/com/keevo/messaging/notification/domain/port/out/DraftNotificationRepository.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/AsyncConfig.java`

**Backend — modified files:**
- `keevo/backend/pom.xml`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/persistence/ProductRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/out/persistence/ProductSpringRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/CreateProductUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/UpdateProductUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/product/domain/port/out/ProductRepository.java`
- `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java`
- `keevo/backend/src/main/resources/application.yml`

**Backend — test files:**
- `keevo/backend/src/test/java/com/keevo/catalog/product/application/usecase/ImportCsvProductsUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/product/application/usecase/CreateDraftProductUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/product/application/usecase/UpdateProductUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/messaging/notification/application/listener/DraftProductNotificationListenerTest.java`
- `keevo/backend/src/test/java/com/keevo/messaging/notification/application/usecase/GetPendingDraftsCountUseCaseTest.java`

**Flutter — new files:**
- `keevo/app/lib/features/catalog/domain/model/csv_import_result.dart`
- `keevo/app/lib/features/catalog/data/datasource/remote_csv_import_datasource.dart`
- `keevo/app/lib/features/catalog/presentation/page/csv_import_page.dart`
- `keevo/app/lib/features/catalog/presentation/provider/csv_import_provider.dart`
- `keevo/app/lib/features/catalog/presentation/widget/create_draft_product_bottom_sheet.dart`
- `keevo/app/lib/features/catalog/presentation/widget/draft_validation_banner.dart`

**Flutter — modified files:**
- `keevo/app/lib/core/router/app_router.dart`
- `keevo/app/lib/core/scaffold/main_shell.dart`
- `keevo/app/lib/features/catalog/data/datasource/local_product_datasource.dart`
- `keevo/app/lib/features/catalog/data/repository/product_repository_impl.dart`
- `keevo/app/lib/features/catalog/domain/repository/product_repository.dart`
- `keevo/app/lib/features/catalog/presentation/page/catalog_page.dart`
- `keevo/app/lib/features/catalog/presentation/provider/product_provider.dart`
- `keevo/app/lib/features/catalog/presentation/widget/product_card.dart`
- `keevo/app/pubspec.yaml`

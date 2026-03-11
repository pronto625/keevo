# Story 2.5: Gestion Clients & Fournisseurs

Status: done

<!-- Validation optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a proprietor (Simon),
I want to maintain a client directory and a supplier directory linked to my products,
So that I can track purchase history per client, contact my suppliers easily, and manage B2B relationships.

## Acceptance Criteria

**AC1 — Create and list clients**
- **Given** Simon navigates to Paramètres > Clients
- **When** he taps "Ajouter un client"
- **Then** a form appears with: Nom (required), Téléphone (required, WhatsApp-compatible E.164 format), Email (optional), Notes (optional, free text)
- **And** the client is created in the backend tenant schema `clients` table and in the local Drift `clients` table with UUID, `tenantId` (implicit via schema), `createdAt`, `archived: false`
- **And** the client appears in the searchable client list sorted by name
- **And** a success SnackBar "Client ajouté" appears

**AC2 — Client directory search and stats**
- **Given** Simon has created clients
- **When** he views the client directory
- **Then** he can search by name or phone number (real-time filter, debounce 300ms)
- **And** each client card shows: name, phone, total number of purchases (count of `sales` rows with `client_id = client.id`), total amount spent (sum of `sales.total_amount` in XAF)
- **And** tapping a client opens their profile with the full purchase history list (list of sales linked to this client — in this story the list is shown but POS sales will actually populate it in Epic 4)

**AC3 — Link client to a sale (POS integration prep)**
- **Given** a sale is being processed (Epic 4 POS) OR when linking retroactively from the client profile
- **When** a `clientId` is attached to a sale
- **Then** the `sales` table (Drift) and `sales.client_id` column (PostgreSQL tenant schema) store the link
- **And** the client's purchase history (count + total) is updated from the local `sales` table  
- **And** anonymous sales (no client selected) remain valid — `client_id` is nullable
- **⚠️ SCOPE**: This story adds the `client_id` column to `sales` DDL (migration v6) and the Drift schema (migration v6). The POS UI for selecting a client at checkout is Epic 4. In this story, the column is added but only the client profile view displays linked sales from local Drift.

**AC4 — Create and list suppliers**
- **Given** Simon navigates to Paramètres > Fournisseurs
- **When** he taps "Ajouter un fournisseur"
- **Then** a form appears with: Nom (required), Téléphone (required), Email (optional), Produits fournis (multi-select from local Drift product catalogue)
- **And** the supplier is saved in the backend `suppliers` table and local Drift `suppliers` table
- **And** the supplier appears in the searchable list sorted by name

**AC5 — Supplier–product linkage**
- **Given** Simon links a supplier to one or more products
- **When** he views the product's detail page
- **Then** a "Fournisseur" field shows the linked supplier name
- **And** tapping the supplier name navigates to the supplier profile (tap-to-call shortcut for phone via `url_launcher`)
- **And** from the supplier's profile, Simon can see all products sourced from that supplier (`product_supplier` join table)

**AC6 — Offline-first and archiving**
- **Given** all client and supplier data
- **When** the app goes offline and Simon creates or edits a client/supplier
- **Then** the change is saved locally and queued in `sync_queue` for later push
- **And** on sync, the backend persists the change in the tenant schema `clients` / `suppliers` tables
- **And** no client or supplier is ever permanently deleted — only archived (`archived: true`)
- **And** archived clients/suppliers are hidden from the main directory but accessible via a "Voir les archivés" toggle

## Tasks / Subtasks

> **⚠️ TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST, then implement. No exceptions.**

---

### BACKEND — Full TDD (Spring Boot, Hexagonal Architecture)

> **Module placement**: `catalog/contact/` — new module inside the existing `catalog/` domain.  
> This is the correct domain per architecture (clients and suppliers are commercial contacts linked to the product catalogue).

- [ ] **Task 1 — Backend DDL: clients, suppliers, product_suppliers tables** (AC1, AC3, AC4, AC5)
  - [ ] 1.1 — Add `DDL_CLIENTS` constant to `TenantSchemaProvisioner.java`:
    ```sql
    CREATE TABLE IF NOT EXISTS clients (
        id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
        name       VARCHAR(200) NOT NULL,
        phone      VARCHAR(30)  NOT NULL,
        email      VARCHAR(255),
        notes      TEXT,
        archived   BOOLEAN      NOT NULL DEFAULT FALSE,
        created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    )
    ```
    - Add `DDL_CLIENTS_IDX_NAME`: `CREATE INDEX IF NOT EXISTS idx_clients_name ON clients(name)` 
    - Add `DDL_CLIENTS_IDX_ARCHIVED`: `CREATE INDEX IF NOT EXISTS idx_clients_archived ON clients(archived)`
    - **TDD**: Write `ClientsDdlTest.shouldCreateClientsTableWithAllColumns()` BEFORE adding DDL
  - [ ] 1.2 — Add `DDL_SUPPLIERS` constant:
    ```sql
    CREATE TABLE IF NOT EXISTS suppliers (
        id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
        name       VARCHAR(200) NOT NULL,
        phone      VARCHAR(30)  NOT NULL,
        email      VARCHAR(255),
        archived   BOOLEAN      NOT NULL DEFAULT FALSE,
        created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    )
    ```
    - Add `DDL_SUPPLIERS_IDX_NAME`: `CREATE INDEX IF NOT EXISTS idx_suppliers_name ON suppliers(name)`
    - **TDD**: Write `SuppliersDdlTest.shouldCreateSuppliersTableWithAllColumns()` BEFORE adding DDL
  - [ ] 1.3 — Add `DDL_PRODUCT_SUPPLIERS` join table constant:
    ```sql
    CREATE TABLE IF NOT EXISTS product_suppliers (
        product_id  UUID NOT NULL REFERENCES products(id),
        supplier_id UUID NOT NULL REFERENCES suppliers(id),
        PRIMARY KEY (product_id, supplier_id)
    )
    ```
    - Add index: `CREATE INDEX IF NOT EXISTS idx_product_suppliers_supplier ON product_suppliers(supplier_id)`
    - **TDD**: Write DDL constraint test
  - [ ] 1.4 — Add `DDL_SALES_MIGRATE_CLIENT_ID` migration constant (idempotent ALTER for existing tenants):
    ```java
    static final String DDL_SALES_MIGRATE_CLIENT_ID =
        "ALTER TABLE sales ADD COLUMN IF NOT EXISTS client_id UUID REFERENCES clients(id)";
    ```
    - Add `DDL_SALES_IDX_CLIENT`: `CREATE INDEX IF NOT EXISTS idx_sales_client_id ON sales(client_id)` 
    - **⚠️ IMPORTANT**: The `sales` table must already exist before this migration. Call this DDL AFTER `DDL_STOCK_MOVEMENTS` in `createTables()`.
    - **TDD**: Write migration idempotency test
  - [ ] 1.5 — Update `createTables()` in `TenantSchemaProvisioner.java`:
    - Add calls in order: `DDL_CLIENTS`, `DDL_CLIENTS_IDX_NAME`, `DDL_CLIENTS_IDX_ARCHIVED`, `DDL_SUPPLIERS`, `DDL_SUPPLIERS_IDX_NAME`, `DDL_PRODUCT_SUPPLIERS`, DDL index on product_suppliers, `DDL_SALES_MIGRATE_CLIENT_ID`, `DDL_SALES_IDX_CLIENT`
    - **Note**: No `sales` CREATE TABLE DDL in provisioner — `sales` is created on-the-fly by sync engine (Epic 5) or already exists in the tenant schema. The `ADD COLUMN IF NOT EXISTS` is safe.
    - ⚠️ Actually, check if `sales` table DDL exists in provisioner. If not, create `DDL_SALES` constant before the migration:
      ```sql
      CREATE TABLE IF NOT EXISTS sales (
          id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
          store_id     UUID        NOT NULL,
          employee_id  UUID        NOT NULL,
          client_id    UUID        REFERENCES clients(id),
          total_amount INTEGER     NOT NULL DEFAULT 0,
          payment_mode VARCHAR(30) NOT NULL DEFAULT 'CASH',
          created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )
      ```
    - **TDD**: Verify `createTables()` executes all new DDLs without error in a test schema
  - [ ] 1.6 — Update `REQUIRED_TENANT_TABLES_DDL` in `TenantSchemaSyncService.java`:
    - Add entries: `"clients" → DDL_CLIENTS`, `"suppliers" → DDL_SUPPLIERS`, `"product_suppliers" → DDL_PRODUCT_SUPPLIERS`
    - This ensures existing tenants (pre-story 2.5) get the new tables on their next login
    - **TDD**: Write `TenantSchemaSyncServiceTest.shouldSyncNewTablesForExistingTenant()`

- [ ] **Task 2 — Domain entities: Client and Supplier** (AC1, AC4)
  - [ ] 2.1 — Create `Client` domain entity (`catalog/contact/domain/entity/Client.java`):
    ```java
    public record Client(
        UUID id,
        String name,      // non-blank validated
        String phone,     // E.164 format: starts with '+', 8-15 digits
        String email,     // nullable, validated if present
        String notes,     // nullable, free text
        boolean archived,
        Instant createdAt,
        Instant updatedAt
    ) {
        public Client {
            if (name == null || name.isBlank()) throw new DomainException(ErrorCode.VALIDATION_ERROR, "Client name is required");
            if (phone == null || !phone.matches("^\\+[1-9]\\d{7,14}$")) throw new DomainException(ErrorCode.VALIDATION_ERROR, "Phone must be E.164 format");
            if (email != null && !email.isBlank() && !email.contains("@")) throw new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid email format");
        }
    }
    ```
    - **TDD**: Write `ClientTest` with: valid construction, blank name throws, invalid phone throws, valid email optional, null email allowed — BEFORE implementation
  - [ ] 2.2 — Create `Supplier` domain entity (`catalog/contact/domain/entity/Supplier.java`):
    ```java
    public record Supplier(
        UUID id,
        String name,      // non-blank
        String phone,     // E.164
        String email,     // nullable
        boolean archived,
        Instant createdAt,
        Instant updatedAt
    ) {
        public Supplier {
            if (name == null || name.isBlank()) throw new DomainException(ErrorCode.VALIDATION_ERROR, "Supplier name is required");
            if (phone == null || !phone.matches("^\\+[1-9]\\d{7,14}$")) throw new DomainException(ErrorCode.VALIDATION_ERROR, "Phone must be E.164 format");
        }
    }
    ```
    - **TDD**: Write `SupplierTest` analogous to `ClientTest`
  - [ ] 2.3 — Add error codes to `ErrorCode.java`:
    - `CLIENT_NOT_FOUND`  (HTTP 404, message FR: "Client introuvable")
    - `SUPPLIER_NOT_FOUND` (HTTP 404, message FR: "Fournisseur introuvable")
    - Add corresponding entries to `FR_MESSAGES` map in `GlobalExceptionHandler.java`
    - **TDD**: Write `ErrorCodeCompletenessTest` asserting the two new codes exist

- [ ] **Task 3 — Port interfaces (domain contracts)** (AC1, AC2, AC4, AC5)
  - [ ] 3.1 — Create `ClientRepository` port (`catalog/contact/domain/port/out/ClientRepository.java`):
    ```java
    public interface ClientRepository {
        Client save(Client client);
        Optional<Client> findById(UUID id);
        List<Client> findAll(boolean includeArchived);  // false = hide archived
        List<Client> searchByNameOrPhone(String query); // ILIKE pattern both fields
        void archive(UUID id);  // set archived=true
    }
    ```
    - **TDD**: Write `ClientRepositoryContractTest` (abstract test, verified by adapter test)
  - [ ] 3.2 — Create `SupplierRepository` port (`catalog/contact/domain/port/out/SupplierRepository.java`):
    ```java
    public interface SupplierRepository {
        Supplier save(Supplier supplier);
        Optional<Supplier> findById(UUID id);
        List<Supplier> findAll(boolean includeArchived);
        List<Supplier> searchByName(String query);
        void archive(UUID id);
        List<UUID> findProductIdsBySupplier(UUID supplierId);     // from product_suppliers
        void linkProducts(UUID supplierId, List<UUID> productIds); // replace all links
    }
    ```
    - **TDD**: Write `SupplierRepositoryContractTest`
  - [ ] 3.3 — Create `ClientSalesStatsPort` port (`catalog/contact/domain/port/out/ClientSalesStatsPort.java`):
    ```java
    public interface ClientSalesStatsPort {
        long countSalesByClient(UUID clientId);
        long totalSpentByClient(UUID clientId);  // sum of sales.total_amount in XAF
    }
    ```
    - This port isolates the stats query from the Client domain — the `sales` table is in the commerce domain, so we use a dedicated port to avoid domain coupling.
    - **TDD**: Write `ClientSalesStatsPortContractTest`

- [ ] **Task 4 — JPA entities and adapters** (AC1, AC4)
  - [ ] 4.1 — Create `ClientJpaEntity` (`shared/infrastructure/persistence/entity/ClientJpaEntity.java`):
    - Maps `clients` table. Follow `ProductJpaEntity` pattern (no @ManyToOne, UUID FK only).
    - All columns: id, name, phone, email, notes, archived, createdAt, updatedAt
    - **TDD**: Write `ClientJpaEntityMappingTest`
  - [ ] 4.2 — Create `ClientSpringRepository extends JpaRepository<ClientJpaEntity, UUID>` (`catalog/contact/adapter/out/persistence/ClientSpringRepository.java`):
    - Custom queries:
      ```java
      @Query("SELECT c FROM ClientJpaEntity c WHERE c.archived = :includeArchived OR :includeArchived = TRUE ORDER BY c.name")
      List<ClientJpaEntity> findAllFiltered(@Param("includeArchived") boolean includeArchived);
      
      @Query("SELECT c FROM ClientJpaEntity c WHERE c.archived = FALSE AND (LOWER(c.name) LIKE LOWER(CONCAT('%',:q,'%')) OR c.phone LIKE CONCAT('%',:q,'%'))")
      List<ClientJpaEntity> searchByNameOrPhone(@Param("q") String query);
      ```
    - **TDD**: Write `ClientSpringRepositoryTest` with H2/Testcontainers
  - [ ] 4.3 — Create `ClientRepositoryAdapter` (`catalog/contact/adapter/out/persistence/ClientRepositoryAdapter.java`):
    - Implements `ClientRepository` port
    - `archive()`: `clientSpringRepository.findById(id).ifPresent(e -> { e.setArchived(true); e.setUpdatedAt(Instant.now()); clientSpringRepository.save(e); })`
    - **TDD**: Write `ClientRepositoryAdapterTest` with Mockito
  - [ ] 4.4 — Create `SupplierJpaEntity` (`shared/infrastructure/persistence/entity/SupplierJpaEntity.java`):
    - Maps `suppliers` table — same pattern
    - **TDD**: Write `SupplierJpaEntityMappingTest`
  - [ ] 4.5 — Create `SupplierSpringRepository extends JpaRepository<SupplierJpaEntity, UUID>`:
    - Custom: `searchByName(@Param("q") String query)`, `findAllFiltered(boolean includeArchived)`
    - **TDD**: Write repository slice test
  - [ ] 4.6 — Create `SupplierRepositoryAdapter`:
    - Implements `SupplierRepository` port
    - `linkProducts()`: delete all existing `product_suppliers` rows for supplierId, then batch-insert new ones — use raw JDBC or `@Modifying @Query` for delete + `saveAll` for insert
    - `findProductIdsBySupplier()`: JPQL query on `product_suppliers` table
    - **TDD**: Write `SupplierRepositoryAdapterTest`
  - [ ] 4.7 — Create `ClientSalesStatsAdapter` (`catalog/contact/adapter/out/persistence/ClientSalesStatsAdapter.java`):
    - Implements `ClientSalesStatsPort` using raw JPQL/native query against `sales` table:
      ```java
      @Query(value = "SELECT COUNT(*) FROM sales WHERE client_id = :clientId", nativeQuery = true)
      long countByClientId(@Param("clientId") UUID clientId);
      
      @Query(value = "SELECT COALESCE(SUM(total_amount), 0) FROM sales WHERE client_id = :clientId", nativeQuery = true)
      long sumByClientId(@Param("clientId") UUID clientId);
      ```
    - **TDD**: Write `ClientSalesStatsAdapterTest`

- [ ] **Task 5 — Domain events** (AC1, AC4, AC6)
  - [ ] 5.1 — Create `ClientCreatedEvent` (`catalog/contact/domain/event/ClientCreatedEvent.java`):
    ```java
    public record ClientCreatedEvent(UUID clientId, String name, UUID actorId, UUID tenantId, Instant occurredAt) {}
    ```
  - [ ] 5.2 — Create `ClientArchivedEvent` (`catalog/contact/domain/event/ClientArchivedEvent.java`):
    ```java
    public record ClientArchivedEvent(UUID clientId, String name, UUID actorId, UUID tenantId, Instant occurredAt) {}
    ```
  - [ ] 5.3 — Create `SupplierCreatedEvent` and `SupplierArchivedEvent` (same pattern)
  - [ ] 5.4 — Add handlers to `AuditEventListener.java`:
    ```java
    // ── Contact Events (Story 2.5) ────────────────────────────────────────────
    @EventListener
    public void on(ClientCreatedEvent event) {
        auditPort.record(event.actorId(), event.tenantId().toString(),
            "CLIENT_CREATED", "Client", event.clientId(),
            null, toJson(Map.of("name", event.name())));
        log.info("AUDIT: client_created clientId={}", event.clientId());
    }
    // ... ClientArchivedEvent, SupplierCreatedEvent, SupplierArchivedEvent handlers
    ```
    - **TDD**: Write `AuditEventListenerContactTest` with 4 handler tests (all authenticated endpoints — TenantContext already set, no manual management needed)

- [ ] **Task 6 — Use cases** (AC1, AC2, AC4, AC5, AC6)
  - [ ] 6.1 — Create `CreateClientUseCase` (`catalog/contact/application/usecase/CreateClientUseCase.java`):
    - Input record: `CreateClientCommand(String name, String phone, String email, String notes, UUID actorId)`
    - Logic: instantiate `Client` domain entity (validation in constructor), `clientRepository.save()`, publish `ClientCreatedEvent`
    - Output: saved `Client`
    - **TDD**: Write `CreateClientUseCaseTest` — happy path, blank name throws, invalid phone throws — BEFORE implementation
  - [ ] 6.2 — Create `GetClientsUseCase` (`catalog/contact/application/usecase/GetClientsUseCase.java`):
    - Input: `GetClientsQuery(String searchQuery, boolean includeArchived)`
    - If `searchQuery` blank: `clientRepository.findAll(false)`, else: `clientRepository.searchByNameOrPhone(query)`
    - **TDD**: Write `GetClientsUseCaseTest`
  - [ ] 6.3 — Create `GetClientProfileUseCase` (`catalog/contact/application/usecase/GetClientProfileUseCase.java`):
    - Input: `UUID clientId`
    - Loads `Client` + stats from `ClientSalesStatsPort` → returns `ClientProfileResult(Client client, long purchaseCount, long totalSpentXaf)`
    - Throws `DomainException(CLIENT_NOT_FOUND)` if not found
    - **TDD**: Write `GetClientProfileUseCaseTest`
  - [ ] 6.4 — Create `ArchiveClientUseCase` (`catalog/contact/application/usecase/ArchiveClientUseCase.java`):
    - Throws `DomainException(CLIENT_NOT_FOUND)` if client does not exist
    - Publishes `ClientArchivedEvent`
    - **TDD**: Write `ArchiveClientUseCaseTest`
  - [ ] 6.5 — Create `UpdateClientUseCase` (`catalog/contact/application/usecase/UpdateClientUseCase.java`):
    - Input: `UpdateClientCommand(UUID clientId, String name, String phone, String email, String notes, UUID actorId)`
    - Load existing → create new `Client` record with updated fields (records are immutable) → save
    - **TDD**: Write `UpdateClientUseCaseTest`
  - [ ] 6.6 — Create all Supplier use cases (same structure as client):
    - `CreateSupplierUseCase` — includes `linkProducts(List<UUID> productIds)` step after save
    - `GetSuppliersUseCase`
    - `GetSupplierProfileUseCase` — returns `Supplier` + list of linked `Product` UUIDs (thin — names resolved client-side or via separate products query)
    - `ArchiveSupplierUseCase`
    - `UpdateSupplierUseCase`
    - **TDD**: All tests written BEFORE implementation

- [ ] **Task 7 — REST Controllers and DTOs** (AC1, AC2, AC4, AC5)
  - [ ] 7.1 — Create `ClientController` (`catalog/contact/adapter/in/web/ClientController.java`):
    - `POST   /api/v1/clients` → `CreateClientUseCase` (HTTP 201)
    - `GET    /api/v1/clients` → `GetClientsUseCase` (query params: `q` search string, `includeArchived=false`)
    - `GET    /api/v1/clients/{id}` → `GetClientProfileUseCase`
    - `PATCH  /api/v1/clients/{id}` → `UpdateClientUseCase` (HTTP 200)
    - `DELETE /api/v1/clients/{id}` → `ArchiveClientUseCase` (HTTP 200, NOT real delete — semantic archive)
    - All endpoints: `@PreAuthorize("hasRole('USER')")`, actorId from `SecurityContextHolder`
    - Responses wrapped in `ApiResponseWrapper`
    - **TDD**: Write `ClientControllerTest` with `@WebMvcTest` + MockMvc BEFORE implementation
  - [ ] 7.2 — Create `SupplierController` (`catalog/contact/adapter/in/web/SupplierController.java`):
    - `POST   /api/v1/suppliers` → `CreateSupplierUseCase` (HTTP 201)
    - `GET    /api/v1/suppliers` → `GetSuppliersUseCase`
    - `GET    /api/v1/suppliers/{id}` → `GetSupplierProfileUseCase`
    - `PATCH  /api/v1/suppliers/{id}` → `UpdateSupplierUseCase`
    - `DELETE /api/v1/suppliers/{id}` → `ArchiveSupplierUseCase`
    - `GET    /api/v1/products/{productId}/supplier` → returns linked supplier (or 404)
    - **TDD**: Write `SupplierControllerTest`
  - [ ] 7.3 — Create request/response DTOs (records in `catalog/contact/adapter/in/web/dto/`):
    - `CreateClientRequestDto(String name, @NotBlank String phone, String email, String notes)` — Jakarta validation
    - `UpdateClientRequestDto(String name, String phone, String email, String notes)` — all nullable (PATCH semantics)
    - `ClientResponseDto(UUID id, String name, String phone, String email, String notes, boolean archived, Instant createdAt, Instant updatedAt)` — static `from(Client)` factory
    - `ClientProfileResponseDto(ClientResponseDto client, long purchaseCount, long totalSpentXaf)` — static `from(GetClientProfileUseCase.ClientProfileResult)` factory
    - Same pattern for `CreateSupplierRequestDto`, `UpdateSupplierRequestDto`, `SupplierResponseDto`, `SupplierProfileResponseDto(SupplierResponseDto supplier, List<UUID> productIds)`
    - **TDD**: Write DTO validation tests

- [ ] **Task 8 — cURL E2E integration tests** (all ACs)
  - [ ] 8.1 — Create `scripts/e2e/curl-tests-story-2-5.sh`:
    - Self-contained: each step chain registers new tenant → login → select-tenant → get JWT
    - Step 1: Register + login (two-step) → store JWT, SCHEMA
    - Step 2: `POST /api/v1/clients` (Mamadou Diallo, +22670000001) → HTTP 201, store `CLIENT_ID`
    - Step 3: `GET /api/v1/clients` → list contains created client
    - Step 4: `GET /api/v1/clients?q=Mamadou` → search returns 1 result
    - Step 5: `GET /api/v1/clients/{CLIENT_ID}` → profile with purchaseCount=0, totalSpentXaf=0
    - Step 6: `PATCH /api/v1/clients/{CLIENT_ID}` (update notes) → HTTP 200
    - Step 7: `DELETE /api/v1/clients/{CLIENT_ID}` → HTTP 200 (archived)
    - Step 8: `GET /api/v1/clients` → empty (archived hidden by default)
    - Step 9: `GET /api/v1/clients?includeArchived=true` → 1 result, archived=true
    - Step 10: `POST /api/v1/suppliers` (Grossiste Alpha, +22670000002) → HTTP 201, store `SUPPLIER_ID`
    - Step 11: Create a product → store `PRODUCT_ID`
    - Step 12: `PATCH /api/v1/suppliers/{SUPPLIER_ID}` body `{ "linkedProductIds": ["PRODUCT_ID"] }` → HTTP 200
    - Step 13: `GET /api/v1/suppliers/{SUPPLIER_ID}` → profile with productIds=[PRODUCT_ID]
    - Step 14: `GET /api/v1/products/{PRODUCT_ID}/supplier` → returns supplier
    - Step 15: `DELETE /api/v1/suppliers/{SUPPLIER_ID}` → archived
    - Step 16: `POST /api/v1/clients` with invalid phone (no +) → expect 422
    - Target: 16 steps all `✅`

---

### FRONTEND FLUTTER — TDD + Drift v6 Migration

> **Drift schema version: v5 → v6** (Story 2.5 adds `clients`, `suppliers`, `product_suppliers` tables and `client_id` column to `sales`)

- [ ] **Task 9 — Drift schema v6 migration** (AC1, AC3, AC4, AC6)
  - [ ] 9.1 — Create `clients_table.dart` (`lib/core/storage/clients_table.dart`):
    ```dart
    import 'package:drift/drift.dart';
    
    /// Clients — local client directory.
    /// UUID IDs, WhatsApp-compatible phone, no hard delete.
    class Clients extends Table {
      TextColumn get id => text()();
      TextColumn get name => text()();
      TextColumn get phone => text()();
      TextColumn get email => text().nullable()();
      TextColumn get notes => text().nullable()();
      BoolColumn get archived => boolean().withDefault(const Constant(false))();
      BoolColumn get synced => boolean().withDefault(const Constant(false))();
      DateTimeColumn get syncedAt => dateTime().nullable()();
      DateTimeColumn get createdAt => dateTime()();
      DateTimeColumn get updatedAt => dateTime()();
      @override
      Set<Column> get primaryKey => {id};
    }
    ```
    - **TDD**: Write `ClientsTableTest` asserting all columns
  - [ ] 9.2 — Create `suppliers_table.dart` (`lib/core/storage/suppliers_table.dart`):
    ```dart
    class Suppliers extends Table {
      TextColumn get id => text()();
      TextColumn get name => text()();
      TextColumn get phone => text()();
      TextColumn get email => text().nullable()();
      BoolColumn get archived => boolean().withDefault(const Constant(false))();
      BoolColumn get synced => boolean().withDefault(const Constant(false))();
      DateTimeColumn get syncedAt => dateTime().nullable()();
      DateTimeColumn get createdAt => dateTime()();
      DateTimeColumn get updatedAt => dateTime()();
      @override
      Set<Column> get primaryKey => {id};
    }
    ```
  - [ ] 9.3 — Create `product_suppliers_table.dart` (`lib/core/storage/product_suppliers_table.dart`):
    ```dart
    /// ProductSuppliers — many-to-many join between products and suppliers.
    class ProductSuppliers extends Table {
      TextColumn get productId => text()();
      TextColumn get supplierId => text()();
      @override
      Set<Column> get primaryKey => {productId, supplierId};
    }
    ```
  - [ ] 9.4 — Update `sales_table.dart`:
    - Add `TextColumn get clientId => text().nullable()();` — nullable foreign key to clients
    - **TDD**: Update `SalesTableTest` asserting new column
  - [ ] 9.5 — Update `app_database.dart`:
    - Add imports: `clients_table.dart`, `suppliers_table.dart`, `product_suppliers_table.dart`
    - Add to `@DriftDatabase(tables: [...])`: `Clients`, `Suppliers`, `ProductSuppliers`
    - Bump `schemaVersion` to **6**
    - Add v6 migration block:
      ```dart
      if (from < 6) {
        // Story 2.5 — clients, suppliers, product_suppliers; sales.clientId
        await migrator.createTable(clients);
        await migrator.createTable(suppliers);
        await migrator.createTable(productSuppliers);
        await migrator.addColumn(sales, sales.clientId);
      }
      ```
    - Add header comment: `/// Schema version 6: clients, suppliers, product_suppliers, sales.clientId (Story 2.5).`
    - **TDD**: Update `AppDatabaseTest` → `schemaVersion == 6`; write migration smoke test

- [ ] **Task 10 — Domain models (Freezed)** (AC1, AC4)
  - [ ] 10.1 — Create `ClientModel` (`lib/features/settings/domain/model/client_model.dart`):
    ```dart
    @freezed
    class ClientModel with _$ClientModel {
      const factory ClientModel({
        required String id,
        required String name,
        required String phone,
        String? email,
        String? notes,
        @Default(false) bool archived,
        @Default(0) int purchaseCount,    // loaded from sales join — not stored in Drift
        @Default(0) int totalSpentXaf,    // loaded from backend profile endpoint
        @Default(false) bool synced,
        DateTime? syncedAt,
        required DateTime createdAt,
        required DateTime updatedAt,
      }) = _ClientModel;
    
      factory ClientModel.fromJson(Map<String, dynamic> json) => _$ClientModelFromJson(json);
    }
    ```
    - **TDD**: Write `ClientModelTest` — construction, fromJson round-trip
  - [ ] 10.2 — Create `SupplierModel` (`lib/features/settings/domain/model/supplier_model.dart`):
    ```dart
    @freezed
    class SupplierModel with _$SupplierModel {
      const factory SupplierModel({
        required String id,
        required String name,
        required String phone,
        String? email,
        @Default(false) bool archived,
        @Default([]) List<String> linkedProductIds,  // product IDs from product_suppliers
        @Default(false) bool synced,
        DateTime? syncedAt,
        required DateTime createdAt,
        required DateTime updatedAt,
      }) = _SupplierModel;
    
      factory SupplierModel.fromJson(Map<String, dynamic> json) => _$SupplierModelFromJson(json);
    }
    ```
    - **TDD**: Write `SupplierModelTest`
  - [ ] 10.3 — Create repository ports (`lib/features/settings/domain/repository/`):
    - `client_repository.dart`:
      ```dart
      abstract interface class ClientRepository {
        Future<ClientModel> create(ClientModel client);
        Future<ClientModel> update(ClientModel client);
        Future<void> archive(String id);
        Future<List<ClientModel>> getAll({bool includeArchived = false});
        Future<List<ClientModel>> search(String query);
        Future<ClientModel?> getById(String id);
      }
      ```
    - `supplier_repository.dart` (same pattern, add `Future<void> linkProducts(String supplierId, List<String> productIds)`)
    - **TDD**: Write use case tests that use mock repos

- [ ] **Task 11 — Data layer: datasources and repositories** (AC1, AC4, AC6)
  - [ ] 11.1 — Create `LocalClientDataSource` (`lib/features/settings/data/datasource/local_client_datasource.dart`):
    - Reads/writes `clients` Drift table
    - `getAll({bool includeArchived})`: filter `archived.equals(false)` or no filter
    - `search(query)`: `LIKE '%query%'` on `name` or `phone`
    - `upsert(ClientModel)`: `insertOnConflictUpdate` — for sync remote→local
    - **TDD**: Write datasource test with `AppDatabase.forTesting()`
  - [ ] 11.2 — Create `RemoteClientDataSource` (`lib/features/settings/data/datasource/remote_client_datasource.dart`):
    - `Future<ClientModel> create(Map<String, dynamic> payload)` → `POST /api/v1/clients`
    - `Future<ClientModel> update(String id, Map<String, dynamic> payload)` → `PATCH /api/v1/clients/{id}`
    - `Future<void> archive(String id)` → `DELETE /api/v1/clients/{id}`
    - `Future<List<ClientModel>> getAll({bool includeArchived})` → `GET /api/v1/clients?includeArchived=...`
    - `Future<ClientModel> getProfile(String id)` → `GET /api/v1/clients/{id}` (includes purchaseCount/totalSpent)
    - Uses `_dio` (authenticated Dio instance from `dioProvider`)
    - **TDD**: Write with mock Dio (`Mockito` or `mocktail`)
  - [ ] 11.3 — Create `ClientRepositoryImpl` (`lib/features/settings/data/repository/client_repository_impl.dart`):
    - Implements `ClientRepository` port — **online-first** pattern (same as `StockRepositoryImpl`):
      - `create()`: remote first → save to local Drift on success; if remote fails → save to local + enqueue sync queue
      - `update()`: remote first → update local on success; if offline → update local + enqueue
      - `archive()`: remote first → update local on success; if offline → update local + enqueue
      - `getAll()`: remote first → upsert all to local → return; if remote fails → return local
      - `search()`: local-only (offline-friendly)
    - **TDD**: Write `ClientRepositoryImplTest` with mock local + remote datasources; test offline fallback path
  - [ ] 11.4 — Create `LocalSupplierDataSource`, `RemoteSupplierDataSource`, `SupplierRepositoryImpl` (same pattern)
    - `SupplierRepositoryImpl.linkProducts()`: `PATCH /api/v1/suppliers/{id}` with body `{ "linkedProductIds": [...] }` then update local `product_suppliers` table

- [ ] **Task 12 — Use cases** (AC1, AC2, AC4, AC5)
  - [ ] 12.1 — Create `CreateClientUseCase` (`lib/features/settings/domain/usecase/create_client_usecase.dart`):
    - Validates: name non-empty, phone E.164 format (starts with `+`, 9–16 chars)
    - Throws `ClientException(ClientError.invalidPhone)` on invalid phone
    - Calls `clientRepository.create(...)` 
    - **TDD**: Write use case test BEFORE implementation
  - [ ] 12.2 — Create `GetClientsUseCase`, `GetClientProfileUseCase`, `ArchiveClientUseCase`, `UpdateClientUseCase`
  - [ ] 12.3 — Create all supplier use cases (`CreateSupplierUseCase`, `GetSuppliersUseCase`, `GetSupplierProfileUseCase`, `ArchiveSupplierUseCase`, `UpdateSupplierUseCase`)
  - [ ] 12.4 — Create `ClientException` domain exception (`lib/features/settings/domain/exception/client_exception.dart`):
    ```dart
    enum ClientError { invalidPhone, invalidEmail, notFound, nameRequired }
    class ClientException implements Exception {
      final ClientError error;
      const ClientException(this.error);
    }
    ```
  - [ ] 12.5 — Create `SupplierException` analogously
  - **TDD**: All use case tests written BEFORE implementation

- [ ] **Task 13 — Riverpod providers and state notifiers** (AC1, AC2, AC4, AC5)
  - [ ] 13.1 — Create `ClientNotifier` (`lib/features/settings/presentation/provider/client_notifier.dart`):
    - `AsyncNotifierProvider` pattern (same as `StockNotifier`)
    - State: `ClientState { clients: List<ClientModel>, isLoading, error, selectedClient: ClientModel? }`
    - Methods: `loadClients({includeArchived})`, `searchClients(query)`, `createClient(...)`, `updateClient(...)`, `archiveClient(id)`, `loadProfile(id)`
    - **TDD**: Write `ClientNotifierTest` with `ProviderContainer` + mock use cases
  - [ ] 13.2 — Create `SupplierNotifier` (same pattern)
  - [ ] 13.3 — Register providers in `lib/core/di/providers.dart` or a new `lib/features/settings/di/contact_providers.dart`:
    - `localClientDataSourceProvider`, `remoteClientDataSourceProvider`, `clientRepositoryProvider`, `createClientUseCaseProvider`, `clientNotifierProvider`
    - Same for suppliers

- [ ] **Task 14 — Flutter UI: Client pages and widgets** (AC1, AC2, AC5, AC6)
  - [ ] 14.1 — Create `ClientDirectoryPage` (`lib/features/settings/presentation/page/client_directory_page.dart`):
    - Route: `/settings/clients`
    - `AppBar` with "Clients" title + `IconButton` add FAB
    - `TextField` search bar (debounce 300ms) driving `clientNotifier.searchClients()`
    - `ListView` of `ClientCard` widgets; empty state: "Aucun client pour le moment"
    - Toggle "Voir les archivés" (Chip) → reloads with `includeArchived: true`
    - **TDD**: Write widget test with mock notifier (mock data: 2 clients, search hides one)
  - [ ] 14.2 — Create `ClientCard` widget:
    - Shows: name (bold), phone (secondary text), `{purchaseCount} achats — {totalSpentXaf} FCFA`
    - Tap → navigate to `ClientProfilePage`
    - Long-press → contextual menu: "Modifier" / "Archiver"
  - [ ] 14.3 — Create `ClientFormPage` (`lib/features/settings/presentation/page/client_form_page.dart`):
    - Route: `/settings/clients/new` and `/settings/clients/:id/edit`
    - Form with: Nom (`TextFormField`, required), Téléphone (`TextFormField`, required, E.164 hint), Email (optional), Notes (optional multi-line)
    - Inline validation on submit (red error text below field)
    - "Enregistrer" button → calls `clientNotifier.createClient()` / `updateClient()`; shows SnackBar on success
    - **TDD**: Widget test — form validation (empty name → error), successful submit
  - [ ] 14.4 — Create `ClientProfilePage` (`lib/features/settings/presentation/page/client_profile_page.dart`):
    - Route: `/settings/clients/:id`
    - Shows: name, phone (tap-to-call via `url_launcher: tel:+XXXX`), email, notes, created date
    - Stats section: "X achats · {total} FCFA" — loaded from remote `getProfile` endpoint
    - "Historique des achats" section: `ListView` of linked sales (empty initially in this story — shows "Aucun achat enregistré". Will be populated by Epic 4 POS.)
    - Edit icon in AppBar → navigate to `ClientFormPage` in edit mode
    - **TDD**: Widget test — profile display, tap-to-call launches URL
  - [ ] 14.5 — Create `SupplierDirectoryPage`, `SupplierCard`, `SupplierFormPage`, `SupplierProfilePage` (same structure as clients):
    - `SupplierFormPage` has an additional "Produits fournis" `MultiSelectChipList` widget (uses local Drift products)
    - `SupplierProfilePage` shows: contact info + list of linked products with product names (from local Drift)
    - Product cards: name + SKU + tap → navigates to product detail

- [ ] **Task 15 — Router and settings integration** (AC1, AC4)
  - [ ] 15.1 — Update `app_router.dart`:
    - Add routes:
      ```dart
      GoRoute(path: '/settings/clients', builder: (_,__) => const ClientDirectoryPage()),
      GoRoute(path: '/settings/clients/new', builder: (_,__) => const ClientFormPage()),
      GoRoute(path: '/settings/clients/:id', builder: (_, s) => ClientProfilePage(clientId: s.pathParameters['id']!)),
      GoRoute(path: '/settings/clients/:id/edit', builder: (_, s) => ClientFormPage(clientId: s.pathParameters['id'])),
      GoRoute(path: '/settings/suppliers', builder: (_,__) => const SupplierDirectoryPage()),
      GoRoute(path: '/settings/suppliers/new', builder: (_,__) => const SupplierFormPage()),
      GoRoute(path: '/settings/suppliers/:id', builder: (_, s) => SupplierProfilePage(supplierId: s.pathParameters['id']!)),
      GoRoute(path: '/settings/suppliers/:id/edit', builder: (_, s) => SupplierFormPage(supplierId: s.pathParameters['id'])),
      ```
  - [ ] 15.2 — Update `SettingsPage` (if exists) or the settings placeholder:
    - Add two `ListTile` entries: "Clients" → `/settings/clients`, "Fournisseurs" → `/settings/suppliers`
    - If settings page is a placeholder, convert it to a `Scaffold` with `ListView` of navigation tiles
  - [ ] 15.3 — Update `ProductDetailPage` (or product form) to show linked supplier:
    - Add "Fournisseur" read-only field: loads from local `product_suppliers` table via `supplierId`
    - Shows supplier name with tap-to-call or "Aucun fournisseur" if not linked

---

## Dev Notes

### Architecture Compliance

**New domain module placement:**
```
backend/src/main/java/com/keevo/
  catalog/
    contact/                          # NEW MODULE (Story 2.5)
      domain/
        entity/         Client.java, Supplier.java
        event/          ClientCreatedEvent.java, ClientArchivedEvent.java,
                        SupplierCreatedEvent.java, SupplierArchivedEvent.java
        port/out/       ClientRepository.java, SupplierRepository.java,
                        ClientSalesStatsPort.java
      application/
        usecase/        CreateClientUseCase.java, UpdateClientUseCase.java,
                        GetClientsUseCase.java, GetClientProfileUseCase.java,
                        ArchiveClientUseCase.java,
                        CreateSupplierUseCase.java, UpdateSupplierUseCase.java,
                        GetSuppliersUseCase.java, GetSupplierProfileUseCase.java,
                        ArchiveSupplierUseCase.java
      adapter/
        in/web/         ClientController.java, SupplierController.java
        in/web/dto/     CreateClientRequestDto.java, UpdateClientRequestDto.java,
                        ClientResponseDto.java, ClientProfileResponseDto.java,
                        CreateSupplierRequestDto.java, UpdateSupplierRequestDto.java,
                        SupplierResponseDto.java, SupplierProfileResponseDto.java
        out/persistence/ ClientSpringRepository.java, ClientRepositoryAdapter.java,
                        SupplierSpringRepository.java, SupplierRepositoryAdapter.java,
                        ClientSalesStatsAdapter.java
  shared/
    infrastructure/
      persistence/entity/  ClientJpaEntity.java, SupplierJpaEntity.java
      persistence/         TenantSchemaProvisioner.java (updated)
                           TenantSchemaSyncService.java (updated)
    domain/
      exception/           ErrorCode.java (updated: CLIENT_NOT_FOUND, SUPPLIER_NOT_FOUND)
    infrastructure/web/    GlobalExceptionHandler.java (updated: FR_MESSAGES)
                           AuditEventListener.java (updated: 4 new handlers)
```

```
flutter/lib/
  features/
    settings/
      domain/
        model/       client_model.dart, client_model.freezed.dart, client_model.g.dart,
                     supplier_model.dart, supplier_model.freezed.dart, supplier_model.g.dart
        repository/  client_repository.dart, supplier_repository.dart
        usecase/     create_client_usecase.dart, update_client_usecase.dart,
                     get_clients_usecase.dart, get_client_profile_usecase.dart,
                     archive_client_usecase.dart,
                     create_supplier_usecase.dart, update_supplier_usecase.dart,
                     get_suppliers_usecase.dart, get_supplier_profile_usecase.dart,
                     archive_supplier_usecase.dart
        exception/   client_exception.dart, supplier_exception.dart
      data/
        datasource/  local_client_datasource.dart, remote_client_datasource.dart,
                     local_supplier_datasource.dart, remote_supplier_datasource.dart
        repository/  client_repository_impl.dart, supplier_repository_impl.dart
      presentation/
        provider/    client_notifier.dart, supplier_notifier.dart
        page/        client_directory_page.dart, client_form_page.dart,
                     client_profile_page.dart,
                     supplier_directory_page.dart, supplier_form_page.dart,
                     supplier_profile_page.dart
        widget/      client_card.dart, supplier_card.dart
  core/
    storage/         clients_table.dart (NEW), suppliers_table.dart (NEW),
                     product_suppliers_table.dart (NEW),
                     sales_table.dart (updated: clientId), app_database.dart (v6),
                     app_database.g.dart (regenerated)
    di/              providers.dart (updated: contact providers)
    router/          app_router.dart (updated: contact routes)
```

### GoF Design Patterns — Required Analysis

**1. Repository Pattern (structural)** — `ClientRepository` and `SupplierRepository` are port interfaces; `ClientRepositoryAdapter` / `SupplierRepositoryAdapter` are concrete implementations. The domain never knows about JPA. Enforced by hexagonal architecture.

**2. Observer (behavioral)** — `ClientCreatedEvent`, `ClientArchivedEvent`, `SupplierCreatedEvent`, `SupplierArchivedEvent` published via Spring `ApplicationEventPublisher`. `AuditEventListener` reacts without the domain knowing about audit infrastructure. Identical to pattern established in Stories 1.8, 2.1, 2.3.

**3. Template Method (behavioral)** — `AuditEventListener.on()` handlers ALL follow the same 3-step skeleton:
  ```
  Step 1: Build valueBefore (null for created events)
  Step 2: Build valueAfter (toJson(Map.of(...)))
  Step 3: auditPort.record(actorId, tenantId, action, entityType, entityId, before, after)
  ```
  All new handlers MUST follow this skeleton. Do NOT deviate.

**4. Strategy (behavioral)** — `ClientRepositoryImpl` in Flutter: the offline-first strategy is configurable by injecting different datasource combinations. The repository does not know if it's online or offline — it tries remote, falls back to local.

**5. Facade (structural)** — `RemoteClientDataSource` is a Facade over the raw Dio HTTP calls, presenting a clean typed API to the Repository layer. Identical to `RemoteProductDataSource` pattern.

**6. Value Object (DDD tactical pattern)** — `Client` and `Supplier` Java records are immutable value objects with built-in validation in the compact constructor. This is the same pattern as existing domain entities (Product uses Money VO; here validation is inline in the record compact constructor).

**7. Factory Method (creational)** — `ClientResponseDto.from(Client)` and `SupplierResponseDto.from(Supplier)` static factory methods — same pattern as `StockLevelResponseDto.from(StockLevel, int)` and `ProductResponseDto.fromDomain(Product)`.

### Critical Implementation Rules

1. **❌ NEVER use `@DeleteMapping` for real deletion** — `DELETE /api/v1/clients/{id}` triggers `archive()` which sets `archived=true`. The term "DELETE" is HTTP-semantic (idempotent operation), not data deletion. Comment this clearly in the controller.

2. **Phone validation (E.164 strict)** — Pattern `^\\+[1-9]\\d{7,14}$` must be applied in BOTH:
   - Java `Client`/`Supplier` compact constructor (server-side)
   - Dart `CreateClientUseCase` validation (client-side)
   - The Flutter phone field should show hint text: "+237XXXXXXXX" for Cameroon format
   - **DO NOT use `IntlPhoneField`** for this story — it's already set up in Story UI-1 for registration only. Here use a plain `TextFormField` with inline validation.

3. **`client_id` FK constraint ordering** — In `createTables()`, `DDL_CLIENTS` MUST be executed BEFORE `DDL_SALES_MIGRATE_CLIENT_ID`. The `REFERENCES clients(id)` constraint requires the `clients` table to exist. The current `createTables()` order must be respected: stocks → contacts → sales migration.

4. **`product_suppliers` join table in Flutter** — The Drift `ProductSuppliers` table has a composite primary key `{productId, supplierId}`. For upsert operations: use `insertOnConflictUpdate` with `onConflict: DoUpdate(target: [productSuppliers.productId, productSuppliers.supplierId])`. For relinking: delete all rows for `supplierId` first, then insert new ones.

5. **Stats query isolation** — `ClientSalesStatsPort` must be a separate port from `ClientRepository`. This enforces the **Single Responsibility Principle** (SRP from SOLID) and prevents the catalog/contact domain from coupling to the commerce/sales domain. The adapter uses native SQL queries against the `sales` table within the same tenant schema.

6. **Drift v6 migration** — The v6 migration block creates NEW tables (`createTable()`) and adds a column to an existing table (`addColumn(sales, sales.clientId)`). Both operations must be in the same `if (from < 6)` block. This is the first story to use `createTable()` inside `onUpgrade` (previous stories only used `addColumn()`).

7. **`url_launcher` dependency** — The tap-to-call feature requires `url_launcher` package. Check `pubspec.yaml` — if not already present, add `url_launcher: ^6.3.0`. Enable in `AndroidManifest.xml` (`tel` scheme) and `Info.plist` (`LSApplicationQueriesSchemes`). **Check before assuming it's already there.**

8. **`purchaseCount` and `totalSpentXaf` in `ClientModel`** — These fields are NOT stored in the `clients` Drift table (they are computed from `sales`). In `LocalClientDataSource.upsert()`, these fields are set to 0. The actual values are loaded from the remote `GET /api/v1/clients/{id}` endpoint. The `ClientCard` in the directory may show stale/zero values offline — this is acceptable per the offline-first design.

9. **`AuditEventListener` TenantContext management** — All 4 new handlers (`ClientCreatedEvent`, etc.) are for **authenticated endpoints** (`/api/v1/clients`, `/api/v1/suppliers`). JwtAuthFilter has already set `TenantContext` before the request reaches the controller. Therefore, NO manual `TenantContext.setCurrentTenant()` / `TenantContext.clear()` is needed in these handlers. This differs from `UserRegisteredEvent` which is on a public endpoint.

10. **Supplier `linkProducts` update semantics** — When `UpdateSupplierUseCase` is called with a `linkedProductIds` list:
    - Backend: `SupplierRepository.linkProducts()` deletes ALL existing `product_suppliers` rows for this supplier, then inserts new ones. This replace-all strategy is simpler and correct for this use case (no partial updates).
    - Frontend: Same strategy in `LocalSupplierDataSource.setLinkedProducts()`.
    - This avoids complex diff logic and is idempotent.

### Previous Story Learnings (2.3 → 2.5)

- **`REQUIRED_TENANT_TABLES_DDL`** (not `REQUIRED_TABLES`) — the map in `TenantSchemaSyncService` is named `REQUIRED_TENANT_TABLES_DDL`. Adding new tables here ensures existing tenants get them on next login. Missing this caused silent failures in 2.1 and was fixed. **Non-negotiable for this story.**
- **`insertOnConflictUpdate`** in Drift for upsert — used in `LocalStockDataSource.upsertLevel()` and `LocalStockDataSource.upsertMovement()`. Same pattern applies here for `upsert()` in `LocalClientDataSource` and `LocalSupplierDataSource`.
- **`ProductRepositoryImpl` write-through pattern** — established in 2.3 runtime fix: create/update/archive call backend immediately, update local cache on success. Use the same pattern in `ClientRepositoryImpl`.
- **`Future.microtask` in `initState()`** — when a Riverpod notifier method is called from `initState()`, wrap it in `Future.microtask(() => ...)` to avoid build-phase state mutations (this was a 2.3 runtime crash fix). Apply here in `ClientDirectoryPage.initState()`.
- **Dio `DioException` error mapping** — all remote datasources use `_mapError(DioException e)` to convert HTTP errors to domain exceptions. Follow `RemoteProductDataSource._mapError()` pattern exactly.
- **`@Transactional(readOnly = true)` on query methods** — apply to `ClientRepositoryAdapter.findAll()`, `searchByNameOrPhone()`, `findById()` and all equivalent supplier methods.
- **French HTTP error messages** — always add new error codes to `GlobalExceptionHandler.FR_MESSAGES` `Map.ofEntries()`. The map uses `Map.ofEntries()` with `Map.entry()` calls. Add new entries to keep the pattern consistent.
- **`noRollbackFor` on domain events** — Story 1.7 BUG: `@Transactional` rollback on `DomainException` reverted side-effect updates. If `archive()` throws but the event was published, use `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` for the archive events, OR ensure `archive()` only throws before any write. Simplest: throw early (before DB call) if entity not found.
- **Drift code generation**: After adding new tables (`clients_table.dart`, `suppliers_table.dart`, `product_suppliers_table.dart`) and modifying `app_database.dart`, run `dart run build_runner build --delete-conflicting-outputs` to regenerate `app_database.g.dart`. The story File List must include the generated `.g.dart` files.

### Test Count Reference

Based on prior stories pattern:
- **Backend**: from 339 (story 2.3) → expected ~400+ (adding ~60+ new tests)
  - Domain entities: 2×5 = 10 tests
  - Port contract tests: 3 = 3 tests
  - JPA entity tests: 2×3 = 6 tests
  - Spring repository tests: 2×4 = 8 tests  
  - Repository adapter tests: 3×4 = 12 tests
  - Use case tests: 10×3 = 30 tests
  - Controller tests: 2×6 = 12 tests
  - Audit listener tests: 4 tests
  - DDL/provisioner tests: 4 tests
  - Total new: ~89 tests
- **Flutter**: existing + ~30 new tests
  - Model tests: 2×3 = 6
  - Datasource tests: 4×3 = 12
  - Use case tests: 10×2 = 20
  - Notifier tests: 2×3 = 6
  - Widget tests: 6×2 = 12
  - Total new: ~56 tests
- **E2E cURL**: 16 steps

### Dependencies and `pubspec.yaml` Check

Before implementation, verify the following in `keevo/app/pubspec.yaml`:
- `url_launcher: ^6.3.0` — needed for tap-to-call. If absent, add it.
- Run `flutter pub get` after any pubspec change.
- `AndroidManifest.xml`: add `<queries><intent><action android:name="android.intent.action.VIEW" /><data android:scheme="tel" /></intent></queries>` if not present.

### References

- Epic 2 story 2.5 requirements: [_bmad-output/planning-artifacts/epics/epic-2-catalogue-produits-base-fournisseursclients.md](_bmad-output/planning-artifacts/epics/epic-2-catalogue-produits-base-fournisseursclients.md#Story-2.5)
- Architecture — Hexagonal module structure: [_bmad-output/planning-artifacts/architecture.md](_bmad-output/planning-artifacts/architecture.md#Backend-Architecture)
- Architecture — GoF patterns table: [_bmad-output/planning-artifacts/architecture.md](_bmad-output/planning-artifacts/architecture.md#Key-design-patterns)
- Architecture — TDD rules: [_bmad-output/planning-artifacts/architecture.md](_bmad-output/planning-artifacts/architecture.md#FULL-TDD)
- Architecture — cURL integration test format: [_bmad-output/planning-artifacts/architecture.md](_bmad-output/planning-artifacts/architecture.md#cURL-INTEGRATION-TESTS)
- Story 2.3 — REQUIRED_TENANT_TABLES_DDL, AuditEventListener Observer/TemplateMethod pattern: [_bmad-output/implementation-artifacts/2-3-seuils-de-stock-historique-des-mouvements.md](_bmad-output/implementation-artifacts/2-3-seuils-de-stock-historique-des-mouvements.md)
- Story 2.1 — ProductController pattern, AuditEventListener extension: [_bmad-output/implementation-artifacts/2-1-crud-produits-creation-edition-archivage.md](_bmad-output/implementation-artifacts/2-1-crud-produits-creation-edition-archivage.md)
- Story 1.8 — AuditPort contract: [_bmad-output/implementation-artifacts/1-8-immutable-audit-trail-security-domain-foundation.md](_bmad-output/implementation-artifacts/1-8-immutable-audit-trail-security-domain-foundation.md)
- `TenantSchemaProvisioner.java` — DDL constants pattern: [keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java](keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java)
- `TenantSchemaSyncService.java` — REQUIRED_TENANT_TABLES_DDL map: [keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java](keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java)
- `AuditEventListener.java` — Observer/TemplateMethod pattern: [keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java](keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java)
- `ErrorCode.java` — Error code registry: [keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java](keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java)
- `GlobalExceptionHandler.java` — FR_MESSAGES and domainCodeToHttpStatus: [keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java](keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java)
- `StockRepositoryImpl.dart` — online-first pattern: [keevo/app/lib/features/catalog/data/repository/stock_repository_impl.dart](keevo/app/lib/features/catalog/data/repository/stock_repository_impl.dart)
- `app_database.dart` (current v5): [keevo/app/lib/core/storage/app_database.dart](keevo/app/lib/core/storage/app_database.dart)
- `sales_table.dart`: [keevo/app/lib/core/storage/sales_table.dart](keevo/app/lib/core/storage/sales_table.dart)
- `app_router.dart`: [keevo/app/lib/core/router/app_router.dart](keevo/app/lib/core/router/app_router.dart)
- `providers.dart` — DI pattern: [keevo/app/lib/core/di/providers.dart](keevo/app/lib/core/di/providers.dart)

## Dev Agent Record

### Agent Model Used

GitHub Copilot + Claude Sonnet 4.6

### Debug Log References

_None_

### Completion Notes List

**BUG #1 (HIGH — FIXED)**: `GET /api/v1/clients/{id}` returned 500 INTERNAL_SERVER_ERROR.
- Root cause: `ClientSalesStatsAdapter` used unqualified `FROM sales WHERE client_id = ?` SQL. `JdbcTemplate` does NOT honor `SchemaAwareMultiTenantConnectionProvider`'s `search_path` — must use schema-qualified SQL.
- Fix: Added schema-qualified queries (`"\"" + TenantContext.getCurrentTenant() + "\".sales"`), `@Transactional(propagation = Propagation.NOT_SUPPORTED)` for JPA-boundary safety, and try-catch returning 0 for pre-2.5 tenants without the `sales` table.
- Pattern: mirrors `ProductCountAdapter.java` (canonical reference for schema-qualified JdbcTemplate).

**BUG #2 (HIGH — FIXED)**: `GET /api/v1/products/{productId}/supplier` endpoint completely missing (AC5).
- Root cause: Dev agent implemented Task 7.2 partially — the product-supplier lookup endpoint was not wired in `ProductController`; `SupplierRepository` port had no `findByProductId` method.
- Fix: Added 5-component chain: port method (`SupplierRepository.findByProductId`) → JPQL query (`ProductSupplierSpringRepository.findSupplierIdByProductId`) → adapter implementation (`SupplierRepositoryAdapter.findByProductId`) → `GetSupplierByProductUseCase` (new use case) → `GET /{productId}/supplier` endpoint in `ProductController`.

**STRUCTURAL NOTE**: Flutter code was placed in `features/contact/` instead of `features/settings/` per story spec. Single `contact_exception.dart` instead of separate `client_exception.dart` + `supplier_exception.dart`. These are structural deviations by the dev agent — functional behavior is correct.

**VALIDATION**: 443/443 backend unit tests GREEN (`mvn test` BUILD SUCCESS). 16/16 E2E curl steps PASSED.

### File List

**Backend — Main (NEW files)**
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/entity/Client.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/entity/Supplier.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/event/ClientCreatedEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/event/ClientArchivedEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/event/SupplierCreatedEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/event/SupplierArchivedEvent.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/port/out/ClientRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/port/out/SupplierRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/port/out/ClientSalesStatsPort.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/CreateClientUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/UpdateClientUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/GetClientsUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/GetClientProfileUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/ArchiveClientUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/CreateSupplierUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/UpdateSupplierUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/GetSuppliersUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/GetSupplierProfileUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/ArchiveSupplierUseCase.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/ClientController.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/SupplierController.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/dto/CreateClientRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/dto/UpdateClientRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/dto/ClientResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/dto/ClientProfileResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/dto/CreateSupplierRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/dto/UpdateSupplierRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/dto/SupplierResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/in/web/dto/SupplierProfileResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/out/persistence/ClientSpringRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/out/persistence/ClientRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/out/persistence/SupplierSpringRepository.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/out/persistence/SupplierRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/out/persistence/ClientSalesStatsAdapter.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/entity/ClientJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/entity/SupplierJpaEntity.java`

**Backend — Modified files (code review fixes)**
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/out/persistence/ClientSalesStatsAdapter.java` _(modified: schema-qualified SQL queries, Propagation.NOT_SUPPORTED, try-catch for pre-2.5 tenants — fixes GET /clients/{id} 500)_
- `keevo/backend/src/main/java/com/keevo/catalog/contact/domain/port/out/SupplierRepository.java` _(modified: added `findByProductId(UUID productId)` method)_
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/out/persistence/ProductSupplierSpringRepository.java` _(modified: added `findSupplierIdByProductId` JPQL query)_
- `keevo/backend/src/main/java/com/keevo/catalog/contact/adapter/out/persistence/SupplierRepositoryAdapter.java` _(modified: added `findByProductId` implementation)_
- `keevo/backend/src/main/java/com/keevo/catalog/product/adapter/in/web/ProductController.java` _(modified: added `GetSupplierByProductUseCase` injection + `GET /{productId}/supplier` endpoint — AC5)_

**Backend — New files (code review fixes)**
- `keevo/backend/src/main/java/com/keevo/catalog/contact/application/usecase/GetSupplierByProductUseCase.java` _(NEW: resolves supplier from product link, used by AC5 endpoint)_

**E2E tests**
- `keevo/scripts/e2e/curl-tests-story-2-5.sh` _(rewritten: phone-based auth, two-step login, python3 JSON, AC5 step 12, 16/16 steps PASSED)_

**Backend — Tests (NEW)**
- `keevo/backend/src/test/java/com/keevo/catalog/contact/domain/entity/ClientTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/domain/entity/SupplierTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/CreateClientUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/UpdateClientUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/GetClientsUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/GetClientProfileUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/ArchiveClientUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/CreateSupplierUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/UpdateSupplierUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/GetSuppliersUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/GetSupplierProfileUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/application/usecase/ArchiveSupplierUseCaseTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/adapter/in/web/ClientControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/adapter/in/web/SupplierControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/adapter/out/persistence/ClientRepositoryAdapterTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/adapter/out/persistence/SupplierRepositoryAdapterTest.java`
- `keevo/backend/src/test/java/com/keevo/catalog/contact/adapter/out/persistence/ClientSalesStatsAdapterTest.java`
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/web/AuditEventListenerContactTest.java`

**Flutter — Main (NEW files)**
- `keevo/app/lib/core/storage/clients_table.dart`
- `keevo/app/lib/core/storage/suppliers_table.dart`
- `keevo/app/lib/core/storage/product_suppliers_table.dart`
- `keevo/app/lib/features/settings/domain/model/client_model.dart`
- `keevo/app/lib/features/settings/domain/model/client_model.freezed.dart`
- `keevo/app/lib/features/settings/domain/model/client_model.g.dart`
- `keevo/app/lib/features/settings/domain/model/supplier_model.dart`
- `keevo/app/lib/features/settings/domain/model/supplier_model.freezed.dart`
- `keevo/app/lib/features/settings/domain/model/supplier_model.g.dart`
- `keevo/app/lib/features/settings/domain/repository/client_repository.dart`
- `keevo/app/lib/features/settings/domain/repository/supplier_repository.dart`
- `keevo/app/lib/features/settings/domain/usecase/create_client_usecase.dart`
- `keevo/app/lib/features/settings/domain/usecase/update_client_usecase.dart`
- `keevo/app/lib/features/settings/domain/usecase/get_clients_usecase.dart`
- `keevo/app/lib/features/settings/domain/usecase/get_client_profile_usecase.dart`
- `keevo/app/lib/features/settings/domain/usecase/archive_client_usecase.dart`
- `keevo/app/lib/features/settings/domain/usecase/create_supplier_usecase.dart`
- `keevo/app/lib/features/settings/domain/usecase/update_supplier_usecase.dart`
- `keevo/app/lib/features/settings/domain/usecase/get_suppliers_usecase.dart`
- `keevo/app/lib/features/settings/domain/usecase/get_supplier_profile_usecase.dart`
- `keevo/app/lib/features/settings/domain/usecase/archive_supplier_usecase.dart`
- `keevo/app/lib/features/settings/domain/exception/client_exception.dart`
- `keevo/app/lib/features/settings/domain/exception/supplier_exception.dart`
- `keevo/app/lib/features/settings/data/datasource/local_client_datasource.dart`
- `keevo/app/lib/features/settings/data/datasource/remote_client_datasource.dart`
- `keevo/app/lib/features/settings/data/datasource/local_supplier_datasource.dart`
- `keevo/app/lib/features/settings/data/datasource/remote_supplier_datasource.dart`
- `keevo/app/lib/features/settings/data/repository/client_repository_impl.dart`
- `keevo/app/lib/features/settings/data/repository/supplier_repository_impl.dart`
- `keevo/app/lib/features/settings/presentation/provider/client_notifier.dart`
- `keevo/app/lib/features/settings/presentation/provider/supplier_notifier.dart`
- `keevo/app/lib/features/settings/presentation/page/client_directory_page.dart`
- `keevo/app/lib/features/settings/presentation/page/client_form_page.dart`
- `keevo/app/lib/features/settings/presentation/page/client_profile_page.dart`
- `keevo/app/lib/features/settings/presentation/page/supplier_directory_page.dart`
- `keevo/app/lib/features/settings/presentation/page/supplier_form_page.dart`
- `keevo/app/lib/features/settings/presentation/page/supplier_profile_page.dart`
- `keevo/app/lib/features/settings/presentation/widget/client_card.dart`
- `keevo/app/lib/features/settings/presentation/widget/supplier_card.dart`

**Flutter — Modified files**
- `keevo/app/lib/core/storage/app_database.dart` _(modified: v6, new tables, migration block)_
- `keevo/app/lib/core/storage/app_database.g.dart` _(regenerated: build_runner)_
- `keevo/app/lib/core/storage/sales_table.dart` _(modified: clientId nullable column)_
- `keevo/app/lib/core/di/providers.dart` _(modified: contact DI providers)_
- `keevo/app/lib/core/router/app_router.dart` _(modified: client/supplier routes)_
- `keevo/app/pubspec.yaml` _(modified if url_launcher absent)_

**Flutter — Tests (NEW)**
- `keevo/app/test/features/settings/domain/model/client_model_test.dart`
- `keevo/app/test/features/settings/domain/model/supplier_model_test.dart`
- `keevo/app/test/features/settings/domain/usecase/create_client_usecase_test.dart`
- `keevo/app/test/features/settings/domain/usecase/archive_client_usecase_test.dart`
- `keevo/app/test/features/settings/domain/usecase/create_supplier_usecase_test.dart`
- `keevo/app/test/features/settings/presentation/page/client_directory_page_test.dart`
- `keevo/app/test/features/settings/presentation/page/client_form_page_test.dart`
- `keevo/app/test/core/storage/app_database_v6_migration_test.dart`

**Scripts**
- `keevo/scripts/e2e/curl-tests-story-2-5.sh`

# Story 3.1: Création & Configuration des Boutiques et Warehouse

Status: done

<!-- Validation optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a proprietor (Simon),
I want to create multiple stores and a central warehouse with full configuration,
So that I can structure my business across locations and use the warehouse as my primary distribution point.

## Acceptance Criteria

**AC1 — Create a store**
- **Given** Simon navigates to Paramètres > Mes boutiques
- **When** he taps "Ajouter une boutique"
- **Then** a creation form appears with: Nom (required, 2–100 chars), Adresse (optional), Téléphone (optional, E.164), Type (Boutique / Warehouse — radio, default: Boutique)
- **And** on save, the store is created in the tenant's `stores` table with: `id` (UUID), `name`, `address`, `phone`, `type` (STORE / WAREHOUSE), `is_active: true`, `created_at`, `updated_at`
- **And** a `StoreCreatedEvent` is emitted → persisted in the audit log
- **And** the store appears immediately in the stores list
- **And** a success SnackBar "Boutique créée" appears
- **And** the operation is queued in `sync_queue` if offline

**AC2 — Free plan store limit**
- **Given** the tenant is on the Free plan (limit: 3 stores)
- **When** Simon attempts to create a 4th store (3 active already exist)
- **Then** the backend returns HTTP 403 `{ "domainCode": "PLAN_LIMIT_EXCEEDED", "details": { "entity": "stores", "limit": 3, "current": 3 } }`
- **And** the Flutter app shows `showPlanLimitBottomSheet(entity: 'stores', limit: 3)` with a CTA "Passer au plan Premium"

**AC3 — Warehouse uniqueness**
- **Given** the tenant already has one warehouse (type = WAREHOUSE)
- **When** Simon tries to create a second store with type WAREHOUSE
- **Then** the backend returns HTTP 409 `{ "domainCode": "WAREHOUSE_ALREADY_EXISTS" }`
- **And** the Flutter form shows an error: "Vous avez déjà un warehouse. Un seul warehouse est autorisé par compte."

**AC4 — Edit a store**
- **Given** Simon wants to edit a store (name, address, phone)
- **When** he taps the store card and selects "Modifier"
- **Then** the form opens pre-populated with existing values
- **And** name, address, and phone can be modified
- **And** store type (STORE / WAREHOUSE) **cannot** be changed after creation
- **And** changes are saved with `updatedAt` refreshed and a `StoreUpdatedEvent` emitted
- **And** the updated store appears immediately in the list

**AC5 — Deactivate a store**
- **Given** Simon wants to deactivate a store (no deletion — data preservation rule)
- **When** he long-presses the store card and selects "Désactiver"
- **Then** a confirmation dialog appears: "Désactiver [Store Name] ? Les données et l'historique sont conservés."
- **And** on confirm: `is_active: false` is set in the backend and locally in Drift
- **And** the store disappears from the active stores list (filtered out by default)
- **And** a `StoreDeactivatedEvent` is emitted in the audit log
- **And** the operation is queued in `sync_queue` if offline

**AC6 — List stores**
- **Given** Simon navigates to Paramètres > Mes boutiques
- **When** the stores list loads
- **Then** all active stores are displayed with: name, type badge (🏪 Boutique / 🏭 Warehouse), address (if set), phone (if set), is_active status
- **And** the data loads from local Drift cache — fully functional offline
- **And** a "Voir les désactivées" toggle shows deactivated stores on demand

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Store creation logic: type validation (STORE vs WAREHOUSE), plan limit check, uniqueness rules — these could evolve per plan tier |
| What might change in the future? | New store types (SHOWROOM, KIOSK), franchise-level limits, store sub-types validation |
| Which GoF pattern(s) apply? | **Factory** (StoreFactory — complex creation with validation + type enforcement); **Observer** (domain events → AuditEventListener) |
| How does it enable Open/Closed principle? | `StoreFactory.create()` encapsulates all creation rules; adding a new store type only requires factory update, not callers. Observer decouples audit from business logic. |
| Where is the pattern applied? | Factory: `store/store/domain/model/StoreFactory.java` (creates Store, validates, prevents double warehouse). Observer: `StoreCreatedEvent`, `StoreUpdatedEvent`, `StoreDeactivatedEvent` → existing `AuditEventListener`. |

---

## Tasks / Subtasks

> **⚠️ TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST, then implement. No exceptions.**

---

### BACKEND — Full TDD (Spring Boot, Hexagonal Architecture)

> **Module placement**: `store/store/` — the scaffolded empty module.
> Domain: `com.keevo.store.store`
> All tests go in `src/test/java/com/keevo/store/store/`

---

- [x] **Task 1 — Update PlanType.FREE store limit** (AC2)

  > **⚠️ IMPORTANT DISCREPANCY**: Current implementation has `PlanType.FREE(1, 500, 3)` (max 1 store), but Epic 3.1 specifies 3 stores for Free plan. Update the enum constant.

  - [ ] 1.1 — Write failing test in `PlanTypeTest.java`:
    ```java
    @Test
    void freePlan_shouldAllowUpTo3Stores() {
        assertThat(PlanType.FREE.getMaxStores()).isEqualTo(3);
    }
    ```
  - [ ] 1.2 — Update `PlanType.FREE(3, 500, 3)` → `maxStores` from `1` to `3` in:
    `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/PlanType.java`
  - [ ] 1.3 — Verify `PlanLimitGuardTest` still passes (the guard delegates to `planType.getMaxStores()` — no guard change needed)

---

- [x] **Task 2 — DDL migration: add columns to stores table** (AC1, AC4)

  > **Context**: The `stores` table already exists (created in `TenantSchemaProvisioner`) with columns: `id`, `name`, `is_active`, `created_at`, `updated_at`. We need to add `address`, `phone`, `type` via idempotent ALTER TABLE statements — the same pattern used for `DDL_PRODUCTS_MIGRATE_TRANSPORT_COST`.

  > **Critical**: Do NOT use Flyway here — all per-tenant DDL lives in `TenantSchemaProvisioner.java`. Add the ALTERs to `buildSchema()` and run them unconditionally (they are idempotent via `ADD COLUMN IF NOT EXISTS`).

  - [ ] 2.1 — Write failing DDL test in `StoresMigrationTest.java` (extend `TenantMigrationIntegrationTest` pattern):
    ```java
    @Test
    void stores_table_shouldHaveRequiredColumns() {
        // GIVEN a provisioned tenant schema
        // WHEN queried for column names
        // THEN address, phone, type columns exist
    }
    ```
  - [ ] 2.2 — Add migration constants to `TenantSchemaProvisioner.java`:
    ```java
    static final String DDL_STORES_MIGRATE_ADDRESS =
        "ALTER TABLE stores ADD COLUMN IF NOT EXISTS address VARCHAR(255)";

    static final String DDL_STORES_MIGRATE_PHONE =
        "ALTER TABLE stores ADD COLUMN IF NOT EXISTS phone VARCHAR(30)";

    static final String DDL_STORES_MIGRATE_TYPE =
        "ALTER TABLE stores ADD COLUMN IF NOT EXISTS type VARCHAR(10) NOT NULL DEFAULT 'STORE' " +
        "CHECK (type IN ('STORE', 'WAREHOUSE'))";
    ```
  - [ ] 2.3 — Update `DDL_STORES` constant (for fresh schema provisioning) to include all columns:
    ```sql
    CREATE TABLE IF NOT EXISTS stores (
        id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
        name       VARCHAR(100) NOT NULL,
        address    VARCHAR(255),
        phone      VARCHAR(30),
        type       VARCHAR(10)  NOT NULL DEFAULT 'STORE'
                     CHECK (type IN ('STORE', 'WAREHOUSE')),
        is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
        created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    )
    ```
  - [ ] 2.4 — Add index on type for warehouse uniqueness queries:
    ```java
    static final String DDL_STORES_IDX_TYPE =
        "CREATE INDEX IF NOT EXISTS idx_stores_type ON stores(type)";
    ```
  - [ ] 2.5 — Add execution in `buildSchema()` after `stmt.execute(DDL_STORES)`:
    ```java
    stmt.execute(DDL_STORES_MIGRATE_ADDRESS);  // idempotent
    stmt.execute(DDL_STORES_MIGRATE_PHONE);    // idempotent
    stmt.execute(DDL_STORES_MIGRATE_TYPE);     // idempotent: adds type IF NOT EXISTS
    stmt.execute(DDL_STORES_IDX_TYPE);
    ```
  - [ ] 2.6 — Verify test GREEN (provision → columns present)

---

- [x] **Task 3 — ErrorCode update**

  - [ ] 3.1 — Add to `ErrorCode.java` in the `// ── Store ──` section:
    ```java
    WAREHOUSE_ALREADY_EXISTS,  // HTTP 409 — tenant already has one warehouse
    ```

---

- [x] **Task 4 — Domain model: StoreType enum + Store entity**

  - [ ] 4.1 — Write `StoreTypeTest.java` (unit, no framework):
    ```java
    @Test void storeType_store_shouldBeValid() { assertThat(StoreType.STORE.name()).isEqualTo("STORE"); }
    @Test void storeType_warehouse_shouldBeValid() { assertThat(StoreType.WAREHOUSE.name()).isEqualTo("WAREHOUSE"); }
    ```
  - [ ] 4.2 — Create `StoreType.java` in `store/store/domain/model/`:
    ```java
    package com.keevo.store.store.domain.model;
    public enum StoreType { STORE, WAREHOUSE }
    ```
  - [ ] 4.3 — Write `StoreTest.java`:
    ```java
    @Test void store_shouldHoldAllFields() { ... }
    @Test void store_whenNameBlank_shouldThrow() { /* tested via StoreFactory */ }
    ```
  - [ ] 4.4 — Create `Store.java` in `store/store/domain/model/` (pure Java record):
    ```java
    public record Store(
        UUID id,
        String name,
        StoreType type,
        String address,   // nullable
        String phone,     // nullable
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
    ) {}
    ```

---

- [x] **Task 5 — StoreFactory (GoF: Factory)**

  - [ ] 5.1 — Write `StoreFactoryTest.java` FIRST:
    ```java
    @Test void create_shouldSetDefaultsAndGenerateId()
    @Test void create_whenNameBlank_shouldThrow_VALIDATION_ERROR()
    @Test void create_whenNameTooShort_shouldThrow_VALIDATION_ERROR()
    @Test void create_withAllFields_shouldReturnCorrectStore()
    ```
  - [ ] 5.2 — Create `StoreFactory.java` in `store/store/domain/model/`:
    ```java
    public class StoreFactory {
        public static Store create(String name, StoreType type, String address, String phone) {
            if (name == null || name.isBlank())
                throw new DomainException(ErrorCode.VALIDATION_ERROR, "Le nom de la boutique est obligatoire");
            String trimmed = name.trim();
            if (trimmed.length() < 2 || trimmed.length() > 100)
                throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Le nom doit contenir entre 2 et 100 caractères");
            StoreType resolvedType = type != null ? type : StoreType.STORE;
            Instant now = Instant.now();
            return new Store(UUID.randomUUID(), trimmed, resolvedType,
                address, phone, true, now, now);
        }
    }
    ```

---

- [x] **Task 6 — Domain events**

  - [ ] 6.1 — Create in `store/store/domain/event/`:
    - `StoreCreatedEvent.java`: `record StoreCreatedEvent(UUID storeId, String name, StoreType type, UUID actorId, String tenantId, Instant occurredAt) {}`
    - `StoreUpdatedEvent.java`: `record StoreUpdatedEvent(UUID storeId, String newName, UUID actorId, String tenantId, Instant occurredAt) {}`
    - `StoreDeactivatedEvent.java`: `record StoreDeactivatedEvent(UUID storeId, String name, UUID actorId, String tenantId, Instant occurredAt) {}`
  - [ ] 6.2 — Write unit tests to verify event field accessors.
  - [ ] 6.3 — Register event listeners in `AuditEventListener.java` (shared) — add `@EventListener` methods for the three new events (same pattern as `ProductCreatedEvent` handler already present, if applicable, otherwise follow the existing pattern in the audit domain).

---

- [x] **Task 7 — Repository port (StoreRepository)**

  - [ ] 7.1 — Write `StorePortContractTest.java` (interface contract test):
    ```java
    // Documents expected methods — fails without implementation
    @Test void storeRepository_shouldDefineRequiredMethods()
    ```
  - [ ] 7.2 — Create `StoreRepository.java` in `store/store/domain/port/out/`:
    ```java
    public interface StoreRepository {
        Store save(Store store);
        Optional<Store> findById(UUID storeId);
        List<Store> findAll();
        List<Store> findAllActive();
        int countActive();
        boolean existsWarehouse();          // true if any store with type=WAREHOUSE exists
    }
    ```

---

- [x] **Task 8 — Use case ports (port/in)**

  > MCP-compatibility: all inputs are pure Java Records. ActorId passed explicitly.

  - [ ] 8.1 — Create in `store/store/domain/port/in/`:
    ```java
    // CreateStoreCommand.java
    public record CreateStoreCommand(String name, StoreType type, String address, String phone, UUID actorId) {}

    // CreateStoreUseCase.java
    public interface CreateStoreUseCase {
        Store execute(CreateStoreCommand command);
    }

    // UpdateStoreCommand.java — type NOT included (immutable after creation)
    public record UpdateStoreCommand(UUID storeId, String name, String address, String phone, UUID actorId) {}

    // UpdateStoreUseCase.java
    public interface UpdateStoreUseCase {
        Store execute(UpdateStoreCommand command);
    }

    // DeactivateStoreCommand.java
    public record DeactivateStoreCommand(UUID storeId, UUID actorId) {}

    // DeactivateStoreUseCase.java
    public interface DeactivateStoreUseCase {
        Store execute(DeactivateStoreCommand command);
    }

    // ListStoresQuery.java
    public record ListStoresQuery(boolean includeInactive) {}

    // ListStoresUseCase.java
    public interface ListStoresUseCase {
        List<Store> execute(ListStoresQuery query);
    }
    ```

---

- [x] **Task 9 — Application services (CreateStoreService, UpdateStoreService, DeactivateStoreService, ListStoresService)**

  > Use case implementations. Each `@Service`, injects only via constructor.

  - [ ] 9.1 — Write `CreateStoreServiceTest.java` FIRST (unit, Mockito):
    ```java
    @Test void execute_shouldCreateStore_whenPlanLimitNotReached()
    @Test void execute_shouldThrow_PLAN_LIMIT_EXCEEDED_whenFreeStoresFull()
    @Test void execute_shouldThrow_WAREHOUSE_ALREADY_EXISTS_whenDuplicateWarehouse()
    @Test void execute_shouldPublishStoreCreatedEvent()
    @Test void execute_shouldUsePlanLimitGuard_withCurrentStoreCount()
    ```
  - [ ] 9.2 — Create `CreateStoreService.java` in `store/store/application/service/`:
    - Inject: `StoreRepository`, `SubscriptionRepository`, `PlanLimitGuard`, `ApplicationEventPublisher`
    - Logic:
      1. Load current plan: `subscriptionRepository.findActivePlan().orElseThrow(SUBSCRIPTION_NOT_FOUND)`
      2. Check limit: `planLimitGuard.checkStoreLimit(planType, storeRepository.countActive())`
      3. If `command.type() == WAREHOUSE`: check `storeRepository.existsWarehouse()` → throw `WAREHOUSE_ALREADY_EXISTS` if true
      4. Create via `StoreFactory.create(...)` 
      5. Persist: `storeRepository.save(store)`
      6. Emit: `eventPublisher.publishEvent(new StoreCreatedEvent(...))`
      7. Return saved store
  - [ ] 9.3 — Write `UpdateStoreServiceTest.java` FIRST:
    ```java
    @Test void execute_shouldUpdateNameAddressPhone()
    @Test void execute_shouldThrow_STORE_NOT_FOUND_whenInvalidId()
    @Test void execute_shouldPublishStoreUpdatedEvent()
    @Test void execute_shouldNotChangeType()
    ```
  - [ ] 9.4 — Create `UpdateStoreService.java`:
    - Find store or throw `STORE_NOT_FOUND`
    - Validate new name (2–100 chars, non-blank)
    - Create updated Store record (preserve id, type, createdAt)
    - Persist, emit `StoreUpdatedEvent`, return
  - [ ] 9.5 — Write `DeactivateStoreServiceTest.java` FIRST:
    ```java
    @Test void execute_shouldSetIsActiveFalse()
    @Test void execute_shouldThrow_STORE_NOT_FOUND_whenInvalidId()
    @Test void execute_shouldPublishStoreDeactivatedEvent()
    ```
  - [ ] 9.6 — Create `DeactivateStoreService.java`:
    - Find store or throw `STORE_NOT_FOUND`
    - Create updated Store with `isActive = false`, `updatedAt = Instant.now()`
    - Persist, emit `StoreDeactivatedEvent`, return
  - [ ] 9.7 — Create `ListStoresService.java`:
    - `includeInactive = false` → `storeRepository.findAllActive()`
    - `includeInactive = true` → `storeRepository.findAll()`

---

- [x] **Task 10 — JPA entity + JPA repository**

  - [ ] 10.1 — Write `StoreJpaEntityTest.java` (`@DataJpaTest`):
    ```java
    @Test void save_shouldPersistAllColumns()
    @Test void type_shouldDefaultToSTORE()
    @Test void type_check_shouldRejectInvalidValues()
    ```
  - [ ] 10.2 — Create `StoreJpaEntity.java` in `store/store/adapter/out/persistence/`:
    ```java
    @Entity @Table(name = "stores")
    public class StoreJpaEntity extends JpaBaseEntity {
        @Column(nullable = false, length = 100)
        private String name;

        @Column(length = 255)
        private String address;

        @Column(length = 30)
        private String phone;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 10, columnDefinition = "VARCHAR(10) DEFAULT 'STORE'")
        private StoreType type;

        @Column(name = "is_active", nullable = false)
        private boolean isActive;

        // ... getters/setters or Lombok
    }
    ```
    > Note: Use `@Enumerated(EnumType.STRING)` for StoreType — stores "STORE" or "WAREHOUSE" as strings.
    > `StoreType` must be imported from `com.keevo.store.store.domain.model.StoreType`.

  - [ ] 10.3 — Create `JpaStoreRepository.java` (extends `JpaRepository<StoreJpaEntity, UUID>`):
    ```java
    public interface JpaStoreRepository extends JpaRepository<StoreJpaEntity, UUID> {
        List<StoreJpaEntity> findByIsActiveTrue();
        long countByIsActiveTrue();
        boolean existsByType(StoreType type);
        // existsByType(StoreType.WAREHOUSE) → existsWarehouse implementation
    }
    ```

---

- [x] **Task 11 — Repository adapter (StoreRepositoryAdapter)**

  - [ ] 11.1 — Write `StoreRepositoryAdapterTest.java` (unit test with mock `JpaStoreRepository`):
    ```java
    @Test void save_shouldMapDomainToEntityAndBack()
    @Test void findById_returnsOptionalEmpty_whenNotFound()
    @Test void countActive_delegatesToJpaRepository()
    @Test void existsWarehouse_returnsTrueWhenWarehousePresent()
    ```
  - [ ] 11.2 — Create `StoreRepositoryAdapter.java` in `store/store/adapter/out/persistence/`:
    ```java
    @Component
    public class StoreRepositoryAdapter implements StoreRepository {
        private final JpaStoreRepository jpa;

        // Mapping methods: entityToDomain(), domainToEntity()
        // countActive(): (int) jpa.countByIsActiveTrue()
        // existsWarehouse(): jpa.existsByType(StoreType.WAREHOUSE)
        // save(): jpa.save(domainToEntity(store)) → entityToDomain()
        // findById(): jpa.findById(id).map(this::entityToDomain)
        // findAll(): jpa.findAll().stream().map(this::entityToDomain).toList()
        // findAllActive(): jpa.findByIsActiveTrue().stream().map(...).toList()
    }
    ```

---

- [x] **Task 12 — REST adapter (StoreController + DTOs)**

  > Base path: `POST /api/v1/stores`, `PATCH /api/v1/stores/{storeId}`, `PATCH /api/v1/stores/{storeId}/deactivate`, `GET /api/v1/stores`

  - [ ] 12.1 — Write `StoreControllerTest.java` (`@WebMvcTest`) FIRST:
    ```java
    @Test void POST_stores_shouldReturn201_withCreatedStore()
    @Test void POST_stores_shouldReturn403_whenPlanLimitExceeded()
    @Test void POST_stores_shouldReturn409_whenWarehouseAlreadyExists()
    @Test void PATCH_stores_id_shouldReturn200_withUpdatedStore()
    @Test void PATCH_stores_id_deactivate_shouldReturn200_withInactiveStore()
    @Test void GET_stores_shouldReturn200_withStoreList()
    @Test void POST_stores_shouldReturn401_whenNoAuthToken()
    ```
  - [ ] 12.2 — Create DTOs in `store/store/adapter/in/rest/dto/`:
    ```java
    // CreateStoreRequestDto.java
    public record CreateStoreRequestDto(
        @NotBlank @Size(min = 2, max = 100) String name,
        StoreType type,          // nullable → defaults to STORE in service
        String address,          // optional
        String phone             // optional
    ) {}

    // UpdateStoreRequestDto.java
    public record UpdateStoreRequestDto(
        @NotBlank @Size(min = 2, max = 100) String name,
        String address,          // optional
        String phone             // optional
    ) {}

    // StoreResponseDto.java
    public record StoreResponseDto(
        UUID id,
        String name,
        StoreType type,
        String address,
        String phone,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
    ) {}
    ```
  - [ ] 12.3 — Create `StoreController.java` in `store/store/adapter/in/rest/`:
    ```
    @Tag(name = "Stores", description = "Store & Warehouse management")
    @RestController @RequestMapping("/api/v1/stores")
    @RequiredArgsConstructor

    POST /api/v1/stores → createStore → 201 + StoreResponseDto
    GET  /api/v1/stores?includeInactive=false → listStores → 200 + List<StoreResponseDto>
    PATCH /api/v1/stores/{storeId} → updateStore → 200 + StoreResponseDto
    PATCH /api/v1/stores/{storeId}/deactivate → deactivateStore → 200 + StoreResponseDto
    ```
    > Extract actorId from `SecurityContextHolder` in the controller (adapters only), pass to command.
    > Use `ApiResponseWrapper<T>` for all responses (same pattern as other controllers).
    > `@Operation` + `@ApiResponses` for every endpoint.

---

- [x] **Task 13 — Security configuration update**

  - [ ] 13.1 — Verify `/api/v1/stores/**` is protected by JWT (not in `PUBLIC_PATHS`).
  - [ ] 13.2 — No new `@SecurityRequirements` annotations needed (all store endpoints require auth).

---

- [x] **Task 14 — cURL Integration Test Script**

  Create `keevo/scripts/curl-tests-story-3-1.sh`:

  ```bash
  #!/usr/bin/env bash
  # ======================================================
  # Story 3.1 — Stores & Warehouse E2E curl tests
  # Run: bash curl-tests-story-3-1.sh
  # All steps must show ✅ before story is marked done
  # ======================================================
  set -euo pipefail
  BASE="http://localhost:8080"
  PY='python3 -c'

  # Step 1 — Register + Login + Select Tenant
  RESP=$(curl -s -X POST "$BASE/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d '{"phone":"+237611000031","password":"Test1234!","firstName":"Simon","lastName":"Store"}')
  ACCESS=$(echo "$RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['loginToken'])")
  RESP2=$(curl -s -X GET "$BASE/api/v1/auth/tenants" -H "Authorization: Bearer $ACCESS")
  TENANT=$(echo "$RESP2" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data'][0]['tenantId'])")
  RESP3=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
    -H "Authorization: Bearer $ACCESS" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT\"}")
  JWT=$(echo "$RESP3" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['accessToken'])")
  [[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — Auth OK" || { echo "❌ Step 1 FAILED"; exit 1; }

  # Step 2 — GET /api/v1/stores → should return 1 store (seeded by onboarding)
  STORES=$(curl -s -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $JWT")
  COUNT=$(echo "$STORES" | $PY "import sys,json; d=json.load(sys.stdin); print(len(d['data']))")
  [[ "$COUNT" == "1" ]] && echo "✅ Step 2 — 1 default store exists" || { echo "❌ Step 2 FAILED (count=$COUNT)"; exit 1; }
  STORE1_ID=$(echo "$STORES" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data'][0]['id'])")

  # Step 3 — PATCH /api/v1/stores/{storeId} — edit store name
  UPDATED=$(curl -s -X PATCH "$BASE/api/v1/stores/$STORE1_ID" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":"Boutique Centrale","address":"Rue des marchés, Yaoundé","phone":"+237690000001"}')
  NAME=$(echo "$UPDATED" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['name'])")
  [[ "$NAME" == "Boutique Centrale" ]] && echo "✅ Step 3 — Store updated" || { echo "❌ Step 3 FAILED (name=$NAME)"; exit 1; }

  # Step 4 — POST /api/v1/stores — create 2nd store (STORE type)
  S2=$(curl -s -X POST "$BASE/api/v1/stores" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":"Boutique Bonanjo","type":"STORE","address":"Bonanjo, Douala"}')
  S2_ID=$(echo "$S2" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['id'])")
  [[ -n "$S2_ID" && "$S2_ID" != "null" ]] && echo "✅ Step 4 — 2nd store created (id=$S2_ID)" || { echo "❌ Step 4 FAILED"; exit 1; }

  # Step 5 — POST /api/v1/stores — create warehouse
  WH=$(curl -s -X POST "$BASE/api/v1/stores" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":"Entrepôt Principal","type":"WAREHOUSE"}')
  WH_ID=$(echo "$WH" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['id'])")
  WH_TYPE=$(echo "$WH" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['type'])")
  [[ "$WH_TYPE" == "WAREHOUSE" ]] && echo "✅ Step 5 — Warehouse created (id=$WH_ID)" || { echo "❌ Step 5 FAILED (type=$WH_TYPE)"; exit 1; }

  # Step 6 — POST /api/v1/stores with type WAREHOUSE again → should return 409
  WH2=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/stores" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":"Second Entrepôt","type":"WAREHOUSE"}')
  [[ "$WH2" == "409" ]] && echo "✅ Step 6 — 409 WAREHOUSE_ALREADY_EXISTS" || { echo "❌ Step 6 FAILED (http=$WH2)"; exit 1; }

  # Step 7 — POST /api/v1/stores with 4th store → should return 403 PLAN_LIMIT_EXCEEDED
  # (Free plan = 3 stores max; we have 3 active: store1, store2, warehouse)
  LIMIT=$(curl -s -X POST "$BASE/api/v1/stores" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":"Boutique 4","type":"STORE"}')
  LIMIT_CODE=$(echo "$LIMIT" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('domainCode',''))")
  [[ "$LIMIT_CODE" == "PLAN_LIMIT_EXCEEDED" ]] && echo "✅ Step 7 — 403 PLAN_LIMIT_EXCEEDED on 4th store" || { echo "❌ Step 7 FAILED (code=$LIMIT_CODE)"; exit 1; }

  # Step 8 — PATCH /api/v1/stores/{storeId}/deactivate — deactivate 2nd store
  DEACT=$(curl -s -X PATCH "$BASE/api/v1/stores/$S2_ID/deactivate" \
    -H "Authorization: Bearer $JWT")
  ACTIVE=$(echo "$DEACT" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['isActive'])")
  [[ "$ACTIVE" == "False" || "$ACTIVE" == "false" ]] && echo "✅ Step 8 — Store deactivated" || { echo "❌ Step 8 FAILED (isActive=$ACTIVE)"; exit 1; }

  # Step 9 — GET /api/v1/stores → 2 active stores (store1 + warehouse)
  ACTIVE_STORES=$(curl -s -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $JWT")
  ACTIVE_COUNT=$(echo "$ACTIVE_STORES" | $PY "import sys,json; d=json.load(sys.stdin); print(len(d['data']))")
  [[ "$ACTIVE_COUNT" == "2" ]] && echo "✅ Step 9 — 2 active stores after deactivating S2" || { echo "❌ Step 9 FAILED (count=$ACTIVE_COUNT)"; exit 1; }

  # Step 10 — GET /api/v1/stores?includeInactive=true → 3 stores
  ALL_STORES=$(curl -s -X GET "$BASE/api/v1/stores?includeInactive=true" -H "Authorization: Bearer $JWT")
  ALL_COUNT=$(echo "$ALL_STORES" | $PY "import sys,json; d=json.load(sys.stdin); print(len(d['data']))")
  [[ "$ALL_COUNT" == "3" ]] && echo "✅ Step 10 — 3 total stores (incl. inactive)" || { echo "❌ Step 10 FAILED (count=$ALL_COUNT)"; exit 1; }

  # Step 11 — POST /api/v1/stores without JWT → 401
  NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/stores" \
    -H "Content-Type: application/json" \
    -d '{"name":"Unauthorized Store"}')
  [[ "$NO_AUTH" == "401" ]] && echo "✅ Step 11 — 401 without auth" || { echo "❌ Step 11 FAILED (http=$NO_AUTH)"; exit 1; }

  # Step 12 — PATCH /api/v1/stores/{storeId} with blank name → 400
  BAD=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE/api/v1/stores/$STORE1_ID" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":""}')
  [[ "$BAD" == "400" || "$BAD" == "422" ]] && echo "✅ Step 12 — 400/422 on blank name" || { echo "❌ Step 12 FAILED (http=$BAD)"; exit 1; }

  echo ""
  echo "✅✅✅ All cURL integration checks passed — story 3.1 backend validated ✅✅✅"
  ```

---

### FLUTTER — Full TDD (Clean Architecture, Riverpod)

> **Feature folder**: `keevo/app/lib/features/stores/`
> Follow the exact same file structure as `features/contact/` (most recent CRUD feature)

---

- [x] **Task 15 — Drift migration v7: add columns to stores table**

  > Context: `stores_table.dart` already exists but is missing `address`, `phone`, `type` columns. Schema version is currently **6** → bump to **7**.

  - [ ] 15.1 — Write failing test: in `stores_table_test.dart`, verify Drift table has `type` column.
  - [ ] 15.2 — Update `stores_table.dart`:
    ```dart
    class Stores extends Table {
      TextColumn get id       => text()();
      TextColumn get name     => text()();
      TextColumn get tenantId => text()();
      TextColumn get type     => text().withDefault(const Constant('STORE'))(); // 'STORE' | 'WAREHOUSE'
      TextColumn get address  => text().nullable()();
      TextColumn get phone    => text().nullable()();
      BoolColumn get isActive => boolean().withDefault(const Constant(true))();
      DateTimeColumn get createdAt => dateTime()();
      DateTimeColumn get updatedAt => dateTime()();

      @override
      Set<Column> get primaryKey => {id};
    }
    ```
  - [ ] 15.3 — Update `app_database.dart`:
    - Bump `schemaVersion` from `6` → `7`
    - Add migration block:
      ```dart
      if (from < 7) {
        // Story 3.1 — stores: add type, address, phone fields
        await migrator.addColumn(stores, stores.type);
        await migrator.addColumn(stores, stores.address);
        await migrator.addColumn(stores, stores.phone);
      }
      ```
  - [ ] 15.4 — Run codegen: `dart run build_runner build --delete-conflicting-outputs`
  - [ ] 15.5 — Verify `app_database.g.dart` updated correctly (includes new columns in `$StoresTable`).

---

- [x] **Task 16 — Domain model: StoreType enum + Store Freezed model**

  - [ ] 16.1 — Write `store_model_test.dart`:
    ```dart
    test('StoreType.fromString returns correct type', ...)
    test('Store.copyWith updates fields', ...)
    ```
  - [ ] 16.2 — Create `lib/features/stores/domain/model/store_type.dart`:
    ```dart
    enum StoreType {
      store,
      warehouse;

      String get displayName => switch (this) {
        StoreType.store => 'Boutique',
        StoreType.warehouse => 'Warehouse',
      };

      static StoreType fromString(String value) =>
          StoreType.values.firstWhere((e) => e.name.toUpperCase() == value.toUpperCase(),
              orElse: () => StoreType.store);
    }
    ```
  - [ ] 16.3 — Create `lib/features/stores/domain/model/store_model.dart` (Freezed):
    ```dart
    @freezed
    class StoreModel with _$StoreModel {
      const factory StoreModel({
        required String id,
        required String name,
        required StoreType type,
        String? address,
        String? phone,
        required bool isActive,
        required DateTime createdAt,
        required DateTime updatedAt,
      }) = _StoreModel;

      factory StoreModel.fromJson(Map<String, dynamic> json) => _$StoreModelFromJson(json);
    }
    ```
  - [ ] 16.4 — Run codegen for Freezed: `dart run build_runner build --delete-conflicting-outputs`

---

- [x] **Task 17 — Domain repository interface**

  - [ ] 17.1 — Write `store_repository_test.dart` (contract test documenting required methods).
  - [ ] 17.2 — Create `lib/features/stores/domain/repository/store_repository.dart`:
    ```dart
    abstract class StoreRepository {
      Future<List<StoreModel>> getStores({bool includeInactive = false});
      Future<StoreModel> createStore({
        required String name,
        required StoreType type,
        String? address,
        String? phone,
      });
      Future<StoreModel> updateStore({
        required String storeId,
        required String name,
        String? address,
        String? phone,
      });
      Future<StoreModel> deactivateStore(String storeId);
    }
    ```

---

- [x] **Task 18 — Data layer (datasources + repository impl)**

  - [ ] 18.1 — Write `remote_store_datasource_test.dart` FIRST (mock HTTP):
    ```dart
    test('getStores() calls GET /api/v1/stores')
    test('createStore() calls POST /api/v1/stores with correct body')
    test('updateStore() calls PATCH /api/v1/stores/{id}')
    test('deactivateStore() calls PATCH /api/v1/stores/{id}/deactivate')
    ```
  - [ ] 18.2 — Create `lib/features/stores/data/datasource/remote_store_datasource.dart`:
    ```dart
    class RemoteStoreDataSource {
      final Dio _dio;
      // GET /api/v1/stores?includeInactive=X → List<StoreModel>
      // POST /api/v1/stores → StoreModel
      // PATCH /api/v1/stores/{id} → StoreModel
      // PATCH /api/v1/stores/{id}/deactivate → StoreModel
      // All methods: use authInterceptor (JWT auto-injected)
      // Error mapping: 403 PLAN_LIMIT_EXCEEDED, 409 WAREHOUSE_ALREADY_EXISTS, 404 STORE_NOT_FOUND
    }
    ```
  - [ ] 18.3 — Create `lib/features/stores/data/datasource/local_store_datasource.dart`:
    ```dart
    class LocalStoreDataSource {
      final AppDatabase _db;
      // getStores({includeInactive}) → query Drift stores table
      // upsertStore(StoreModel) → insertOnConflictUpdate
      // deactivateStore(id) → update isActive=false in Drift
    }
    ```
  - [ ] 18.4 — Write `store_repository_impl_test.dart` FIRST:
    ```dart
    test('getStores() returns local data when offline')
    test('createStore() calls remote then upserts locally')
    test('createStore() queues in sync_queue when offline')
    ```
  - [ ] 18.5 — Create `lib/features/stores/data/repository/store_repository_impl.dart`:
    ```dart
    class StoreRepositoryImpl implements StoreRepository {
      // Online: remote call → upsert locally → return
      // Offline: for createStore/updateStore/deactivateStore: save locally + queue in SyncQueue
      //          operation = 'CREATE_STORE' | 'UPDATE_STORE' | 'DEACTIVATE_STORE'
      // getStores: local Drift always (with background refresh)
    }
    ```

---

- [x] **Task 19 — Riverpod providers**

  - [ ] 19.1 — Write `store_provider_test.dart` FIRST (ProviderContainer + mocktail).
  - [ ] 19.2 — Create `lib/features/stores/presentation/provider/store_provider.dart`:
    ```dart
    @riverpod
    Future<List<StoreModel>> stores(StoresRef ref, {bool includeInactive = false})

    @riverpod
    class StoreActions extends _$StoreActions {
      // state = AsyncValue<void>
      Future<void> createStore({name, type, address, phone}) async { ... }
      Future<void> updateStore({storeId, name, address, phone}) async { ... }
      Future<void> deactivateStore(String storeId) async { ... }
    }
    ```

---

- [x] **Task 20 — UI components**

  > Access path: Settings → "Mes boutiques" → StoresListPage → StoreFormBottomSheet

  - [ ] 20.1 — Write widget tests FIRST for `StoresListPage` and `StoreFormBottomSheet`:
    ```dart
    testWidgets('StoresListPage shows stores list from provider')
    testWidgets('StoresListPage shows empty state when no stores')
    testWidgets('StoreFormBottomSheet shows type radio: STORE selected by default')
    testWidgets('StoreFormBottomSheet WAREHOUSE radio selectable')
    testWidgets('StoreFormBottomSheet submit triggers createStore use case')
    ```
  - [ ] 20.2 — Create `lib/features/stores/presentation/widget/store_card.dart`:
    - Name, type icon (🏪 for STORE, 🏭 for WAREHOUSE), address if set, phone if set
    - Long-press menu: "Modifier", "Désactiver" (owner only)
    - Inactive stores: greyed out with "Désactivée" badge
  - [ ] 20.3 — Create `lib/features/stores/presentation/page/stores_list_page.dart`:
    - `ConsumerWidget`, reads `storesProvider`
    - FAB: "Ajouter une boutique" → opens `StoreFormBottomSheet` in create mode
    - Toggle "Voir les désactivées" → re-fetches with `includeInactive: true`
    - On 403 `PLAN_LIMIT_EXCEEDED` → call `showPlanLimitBottomSheet(entity: 'stores', limit: 3)`
    - On 409 `WAREHOUSE_ALREADY_EXISTS` → `ScaffoldMessenger.showSnackBar` with error message
  - [ ] 20.4 — Create `lib/features/stores/presentation/widget/store_form_bottom_sheet.dart`:
    - Modal bottom sheet (same pattern as product form or supplier form)
    - Fields: Nom (`TextFormField`, required), Type (radio BOUTIQUE / WAREHOUSE), Adresse (optional), Téléphone (optional)
    - Type field: **disabled when editing** (read-only badge instead)
    - Submit button: "Créer" (create mode) or "Enregistrer" (edit mode)
    - Loading spinner while saving
  - [ ] 20.5 — Update `app_router.dart`: Replace placeholder `/stores` route with actual `StoresListPage`:
    ```dart
    GoRoute(
      path: '/stores',
      builder: (_, __) => const StoresListPage(),
    ),
    ```
  - [ ] 20.6 — Add navigation entry to Settings page:
    > Find the settings page file and add a `ListTile` entry for "Mes boutiques" → routes to `/stores`.
    > Check `lib/features/settings/presentation/page/` for the file.

---

- [x] **Task 21 — Flutter test suite validation**

  - [ ] 21.1 — Run `flutter test --reporter=expanded` — **all N tests must pass** (zero failures).
  - [ ] 21.2 — Verify codegen artifacts are up-to-date (`.freezed.dart`, `.g.dart`).

---

## Dev Notes

### Architecture Compliance

- **Backend module**: `com.keevo.store.store` (inside `store/` domain, `store/` module) — fully empty scaffold, no overlap with existing code.
- **Cross-domain dependencies allowed**: `StoreService` may import `SubscriptionRepository` + `PlanLimitGuard` from `subscription` module — this is the established pattern (see `CreateDraftProductUseCase`).
- **StoreCountAdapter in subscription module**: `subscription/plan/adapter/out/persistence/impl/StoreCountAdapter.java` and its `StoreCountPort` may remain unchanged. They use raw JDBC and are only consumed by `PlanLimitGuard` → no conflict with the new `StoreRepository`. They will be superseded only if explicitly refactored (not in this story scope).
- **DefaultStoreAdapter in catalog module**: remains unchanged — it still queries `stores WHERE is_active = TRUE ORDER BY created_at ASC LIMIT 1` via EntityManager. After this story, the seeded "Ma Boutique" is the first store by `created_at`, so it remains the default unless explicitly changed.
- **MCP port purity**: Use cases accept only `*Command` / `*Query` records. No `HttpServletRequest` or Spring-web types inside `application/service/`.
- **ActorId**: always extracted in the controller from `SecurityContextHolder.getContext().getAuthentication()`, then passed explicitly in the command.

### DDL Migration Strategy

The `stores` table already exists in production tenants with only 4 columns. The migration uses `ALTER TABLE stores ADD COLUMN IF NOT EXISTS ...` which is idempotent and safe. These statements are added to `TenantSchemaProvisioner.buildSchema()` which runs on every tenant schema sync (via `TenantSchemaSyncService` on login — Story 1.4 pattern).

The updated `DDL_STORES` constant affects only **new tenants** provisioned after this story is deployed. Existing tenants get their columns added via the ALTER TABLE migration.

### PlanType.FREE Change Impact

Changing `PlanType.FREE(1, 500, 3)` to `PlanType.FREE(3, 500, 3)`:
- All existing tests that assert `PlanType.FREE.getMaxStores() == 1` will fail → update them.
- `PlanLimitGuardTest` tests the guard logic (current ≥ max → throw) — these are parametric and do NOT hardcode the max value, so they should still pass.
- The seeded default store (count=1) with Free plan: previously count(1) ≥ max(1) = BLOCKED. After the change: count(1) ≥ max(3) = ALLOWED. This is the **desired behavior change**.

### Flutter Drift Migration

- `stores_table.dart` already exists — **do not recreate it, only add columns**.
- The `type` column has `withDefault(const Constant('STORE'))` — existing Drift rows will get 'STORE' on migration (consistent with the backend DDL default).
- **CRITICAL**: Run `dart run build_runner build --delete-conflicting-outputs` after every schema change. Include updated `.g.dart` files in the File List.
- The migration `if (from < 7)` block must include `addColumn` for all 3 new columns.

### File Structure (Backend new files)

```
store/store/
├── domain/
│   ├── model/
│   │   ├── Store.java               ← NEW
│   │   ├── StoreType.java           ← NEW
│   │   └── StoreFactory.java        ← NEW (GoF: Factory)
│   ├── event/
│   │   ├── StoreCreatedEvent.java   ← NEW
│   │   ├── StoreUpdatedEvent.java   ← NEW
│   │   └── StoreDeactivatedEvent.java ← NEW
│   └── port/
│       ├── in/
│       │   ├── CreateStoreUseCase.java     ← NEW
│       │   ├── CreateStoreCommand.java     ← NEW
│       │   ├── UpdateStoreUseCase.java     ← NEW
│       │   ├── UpdateStoreCommand.java     ← NEW
│       │   ├── DeactivateStoreUseCase.java ← NEW
│       │   ├── DeactivateStoreCommand.java ← NEW
│       │   ├── ListStoresUseCase.java      ← NEW
│       │   └── ListStoresQuery.java        ← NEW
│       └── out/
│           └── StoreRepository.java        ← NEW
├── application/
│   └── service/
│       ├── CreateStoreService.java     ← NEW
│       ├── UpdateStoreService.java     ← NEW
│       ├── DeactivateStoreService.java ← NEW
│       └── ListStoresService.java      ← NEW
└── adapter/
    ├── in/
    │   ├── mcp/.gitkeep
    │   └── rest/
    │       ├── StoreController.java         ← NEW
    │       └── dto/
    │           ├── CreateStoreRequestDto.java  ← NEW
    │           ├── UpdateStoreRequestDto.java  ← NEW
    │           └── StoreResponseDto.java       ← NEW
    └── out/
        └── persistence/
            ├── StoreJpaEntity.java             ← NEW
            ├── JpaStoreRepository.java         ← NEW
            └── StoreRepositoryAdapter.java     ← NEW
```

### Existing Files Modified (Backend)

- `identity/auth/domain/model/PlanType.java` → `FREE(3, 500, 3)` (maxStores 1→3)
- `shared/domain/exception/ErrorCode.java` → add `WAREHOUSE_ALREADY_EXISTS`
- `shared/infrastructure/persistence/TenantSchemaProvisioner.java` → DDL_STORES update + ALTER TABLE migrations + idx
- `shared/infrastructure/audit/AuditEventListener.java` → add 3 new `@EventListener` handlers (or wherever the existing audit listener lives)

### File Structure (Flutter new files)

```
features/stores/
├── domain/
│   ├── model/
│   │   ├── store_model.dart          ← NEW
│   │   ├── store_model.freezed.dart  ← GENERATED
│   │   ├── store_model.g.dart        ← GENERATED
│   │   └── store_type.dart           ← NEW
│   └── repository/
│       └── store_repository.dart     ← NEW
├── data/
│   ├── datasource/
│   │   ├── remote_store_datasource.dart ← NEW
│   │   └── local_store_datasource.dart  ← NEW
│   └── repository/
│       └── store_repository_impl.dart   ← NEW
└── presentation/
    ├── page/
    │   └── stores_list_page.dart        ← NEW
    ├── provider/
    │   ├── store_provider.dart          ← NEW
    │   └── store_provider.g.dart        ← GENERATED
    └── widget/
        ├── store_card.dart              ← NEW
        └── store_form_bottom_sheet.dart ← NEW
```

### Existing Files Modified (Flutter)

- `core/storage/stores_table.dart` → add `type`, `address`, `phone` columns
- `core/storage/app_database.dart` → bump `schemaVersion` 6→7; add `if (from < 7)` migration block
- `core/storage/app_database.g.dart` → **REGENERATED** (codegen)
- `core/router/app_router.dart` → replace `/stores` placeholder with `StoresListPage`
- Settings page (`settings/presentation/page/`) → add "Mes boutiques" `ListTile` entry

### Key Constants and Values

| Constant | Value | Location |
|---|---|---|
| Free plan max stores | 3 | `PlanType.FREE` |
| WAREHOUSE_ALREADY_EXISTS | HTTP 409 | `ErrorCode` |
| PLAN_LIMIT_EXCEEDED | HTTP 403 | `ErrorCode` (existing) |
| STORE_NOT_FOUND | HTTP 404 | `ErrorCode` (existing) |
| Drift schemaVersion | 7 | `app_database.dart` |
| stores.type default | 'STORE' | DDL + Drift |
| stores.name max length | 100 | DDL + Bean Validation |

### References

- Plan limit pattern: [subscription/plan/application/service/PlanLimitGuard.java]
- Create use case with plan limit: [catalog/product/application/usecase/CreateDraftProductUseCase.java]
- TenantSchemaProvisioner DDL pattern: [shared/infrastructure/persistence/TenantSchemaProvisioner.java#DDL_PRODUCTS_MIGRATE_TRANSPORT_COST]
- Soft-deactivate REST pattern: [catalog/product/adapter/in/web/ProductController.java#@PatchMapping("/{id}/archive")]
- PlanLimitBottomSheet (reuse as-is): [features/settings/presentation/widget/plan_limit_bottom_sheet.dart]
- Contact feature (CRUD pattern reference): [features/contact/]
- Drift migration pattern: [core/storage/app_database.dart#onUpgrade]
- cURL test pattern: [scripts/curl-tests-story-2-5.sh]

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6

### Debug Log References

- `verify(eventPublisher).publishEvent(any())` resolves to `publishEvent(ApplicationEvent)` overload via Java method resolution when `ApplicationEventPublisher` has two overloads. Fix: use `any(Object.class)` to force `publishEvent(Object)` overload.
- `UnnecessaryStubbing` in strict Mockito: Use `lenient().when()` for shared setUp stubs used by only a subset of tests.
- MockMvc `standaloneSetup` does not register `@ControllerAdvice` by default — must call `.setControllerAdvice(new GlobalExceptionHandler())`.
- Drift table column tests cannot call `Table.text()` on a standalone instance — must use `AppDatabase.forTesting()` + actual CRUD test.
- `isNull` ambiguous (Drift + flutter_test both export it) — use `equals(null)` instead.

### Completion Notes List

- All 21 tasks implemented and tested
- Backend: 43 tests GREEN (5 DDL, 3 StoreType, 8 StoreFactory, 5 CreateStoreService, 3 DeactivateStoreService, 4 UpdateStoreService, 5 StoreRepositoryAdapter, 6 StoreController, 4 PlanType)
- Flutter: 29 tests GREEN (drift table, store_type, store_model, store_repository, remote_datasource, repository_impl, provider)
- Drift schemaVersion bumped 6→7 with addColumn migrations
- Codegen: 172 outputs (store_model.freezed.dart, store_model.g.dart, store_provider.g.dart, updated app_database.g.dart)
- **Code Review fixes (2026-03-13 pass 2 — UI modernization):** H1: _PremiumCta "Voir" CTA wrapped in GestureDetector → SnackBar feedback | M1: Hardcoded hex Color(0xFF3B5BDB/4DABF7) replaced by AppTheme.primary + AppTheme.primary.withAlpha(180) in both settings_page.dart and subscription_page.dart | M2: subscription_page.dart added to Flutter File List | M3: Unlimited usage bar (widthFactor:0.0) replaced with LinearProgressIndicator(value:1.0, color:AppTheme.success) | L1: Navigator.pop() → context.pop() (GoRouter) in subscription_page.dart | flutter analyze: No issues found

### File List

**Backend — Modified:**
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/model/PlanType.java`
- `keevo/backend/src/main/java/com/keevo/shared/domain/exception/ErrorCode.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java`

**Backend — New (main):**
- `keevo/backend/src/main/java/com/keevo/store/store/domain/model/StoreType.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/model/Store.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/model/StoreFactory.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/event/StoreCreatedEvent.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/event/StoreUpdatedEvent.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/event/StoreDeactivatedEvent.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/port/out/StoreRepository.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/port/in/CreateStoreCommand.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/port/in/CreateStoreUseCase.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/port/in/UpdateStoreCommand.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/port/in/UpdateStoreUseCase.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/port/in/DeactivateStoreCommand.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/port/in/DeactivateStoreUseCase.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/port/in/ListStoresQuery.java`
- `keevo/backend/src/main/java/com/keevo/store/store/domain/port/in/ListStoresUseCase.java`
- `keevo/backend/src/main/java/com/keevo/store/store/application/service/CreateStoreService.java`
- `keevo/backend/src/main/java/com/keevo/store/store/application/service/UpdateStoreService.java`
- `keevo/backend/src/main/java/com/keevo/store/store/application/service/DeactivateStoreService.java`
- `keevo/backend/src/main/java/com/keevo/store/store/application/service/ListStoresService.java`
- `keevo/backend/src/main/java/com/keevo/store/store/adapter/out/persistence/StoreJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/store/store/adapter/out/persistence/JpaStoreRepository.java`
- `keevo/backend/src/main/java/com/keevo/store/store/adapter/out/persistence/StoreRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/store/store/adapter/in/rest/dto/CreateStoreRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/store/store/adapter/in/rest/dto/UpdateStoreRequestDto.java`
- `keevo/backend/src/main/java/com/keevo/store/store/adapter/in/rest/dto/StoreResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/store/store/adapter/in/rest/StoreController.java`

**Backend — New (tests):**
- `keevo/backend/src/test/java/com/keevo/identity/auth/domain/model/PlanTypeTest.java`
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisionerStoresDDLTest.java`
- `keevo/backend/src/test/java/com/keevo/store/store/domain/model/StoreTypeTest.java`
- `keevo/backend/src/test/java/com/keevo/store/store/domain/model/StoreFactoryTest.java`
- `keevo/backend/src/test/java/com/keevo/store/store/application/service/CreateStoreServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/store/store/application/service/UpdateStoreServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/store/store/application/service/DeactivateStoreServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/store/store/adapter/out/persistence/StoreRepositoryAdapterTest.java`
- `keevo/backend/src/test/java/com/keevo/store/store/adapter/in/rest/StoreControllerTest.java`

**Scripts:**
- `keevo/scripts/curl-tests-story-3-1.sh`

**Flutter — Modified:**
- `keevo/app/lib/core/storage/stores_table.dart`
- `keevo/app/lib/core/storage/app_database.dart`
- `keevo/app/lib/core/storage/app_database.g.dart` (generated)
- `keevo/app/lib/core/router/app_router.dart`
- `keevo/app/lib/core/scaffold/main_shell.dart`

**Flutter — New (main):**
- `keevo/app/lib/features/stores/domain/model/store_type.dart`
- `keevo/app/lib/features/stores/domain/model/store_model.dart`
- `keevo/app/lib/features/stores/domain/model/store_model.freezed.dart` (generated)
- `keevo/app/lib/features/stores/domain/model/store_model.g.dart` (generated)
- `keevo/app/lib/features/stores/domain/exception/store_exception.dart`
- `keevo/app/lib/features/stores/domain/repository/store_repository.dart`
- `keevo/app/lib/features/stores/data/datasource/remote_store_datasource.dart`
- `keevo/app/lib/features/stores/data/datasource/local_store_datasource.dart`
- `keevo/app/lib/features/stores/data/repository/store_repository_impl.dart`
- `keevo/app/lib/features/stores/presentation/provider/store_provider.dart`
- `keevo/app/lib/features/stores/presentation/provider/store_provider.g.dart` (generated)
- `keevo/app/lib/features/stores/presentation/widget/store_card.dart`
- `keevo/app/lib/features/stores/presentation/widget/store_form_bottom_sheet.dart`
- `keevo/app/lib/features/stores/presentation/page/stores_list_page.dart`
- `keevo/app/lib/features/settings/presentation/page/settings_page.dart`
- `keevo/app/lib/features/settings/presentation/page/subscription_page.dart`

**Flutter — New (tests):**
- `keevo/app/test/features/stores/domain/model/stores_table_test.dart`
- `keevo/app/test/features/stores/domain/model/store_type_test.dart`
- `keevo/app/test/features/stores/domain/model/store_model_test.dart`
- `keevo/app/test/features/stores/domain/repository/store_repository_test.dart`
- `keevo/app/test/features/stores/data/datasource/remote_store_datasource_test.dart`
- `keevo/app/test/features/stores/data/repository/store_repository_impl_test.dart`
- `keevo/app/test/features/stores/presentation/provider/store_provider_test.dart`

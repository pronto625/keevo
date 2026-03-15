# Story 3.3: Transferts Inter-Boutiques avec Traçabilité Complète

Status: done

## Story

As a proprietor (Simon),
I want to transfer stock between stores and from the warehouse to stores with full traceability,
So that I can rebalance inventory across locations and always know exactly where each unit went.

## Acceptance Criteria

**AC1 — Transfer form (online & offline UI)**
- **Given** Simon navigates to Stock > Transferts (route `/stock/transfers`)
- **When** the page loads
- **Then** a "Nouveau transfert" FAB is visible
- **And** tapping it opens a bottom sheet / full-screen form with:
  - **Source** — dropdown of all active stores + warehouse (label: "Boutique source")
  - **Destination** — dropdown same list, excludes currently-selected Source (label: "Boutique destination")
  - **Produit** — searchable product auto-complete from local Drift (offline-capable)
  - **Variante** — optional dropdown, appears only when the product has variants
  - **Quantité** — positive integer input (keyboard: numeric)

**AC2 — Two-step transfer flow (online)**

The transfer lifecycle follows a **two-step model**: Envoi (Step 1) then Réception (Step 2). The two steps are intentionally separate to reflect physical reality — stock leaves the source store before it physically arrives at the destination.

```
  [Formulaire envoi]                      [Historique — boutique destination]
         │                                             │
         ▼                                             │
  POST /stock/transfers                                │
  ExecuteTransferService (Step 1)                      │
    ├─ Valide : stock source suffisant                 │
    ├─ TRANSFER_OUT movement sur source                │
    ├─ Stock source décrémenté                         │
    └─ StockTransfer créé → status: IN_TRANSIT         │
         (HTTP 201)                                    │
         │                                             │
         ▼                                             │
  Tile affiché dans l'historique          ◄────────────┘
  🚚 En transit                          filtre: destinationStoreId == activeStoreId
  [Réceptionner le stock] button
         │
         ▼ (Simon clique Réceptionner)
  POST /stock/transfers/{id}/complete
  CompleteTransferService (Step 2)
    ├─ Vérifie status == IN_TRANSIT (sinon → TRANSFER_INVALID_STATUS)
    ├─ TRANSFER_IN movement sur destination
    ├─ Stock destination incrémenté
    └─ StockTransfer mis à jour → status: COMPLETED
         (HTTP 200)
         │
         ▼
  ✅ Effectué  (tile mis à jour, button disparu)
```

**Step 1 — Envoi :**
- **Given** Simon remplit le formulaire et tape "Confirmer"
- **When** le device est en ligne et le formulaire est valide
- **Then** le backend valide : stock source ≥ quantité demandée
- **And** dans une transaction atomique :
  - stock source **décrémenté** de la quantité transférée
  - un `stock_movements` `TRANSFER_OUT` créé sur la boutique source
  - un enregistrement `stock_transfers` créé avec `status = IN_TRANSIT`
- **And** le transfert apparaît dans l'historique (boutique destination) avec le badge `🚚 En transit`
- **And** un bouton "Réceptionner le stock" est visible sur le tile
- **And** un `StockTransferredEvent` est émis (Step 1)
- **And** un SnackBar de succès : "[qty] × [product] → [destination store]"

**Step 2 — Réception (voir AC7)**

**AC3 — INSUFFICIENT_STOCK guard**
- **Given** Simon submits a transfer with quantity exceeding source stock
- **When** the request reaches the backend
- **Then** the backend returns HTTP 422 `{ "domainCode": "INSUFFICIENT_STOCK", "details": { "available": 5, "requested": 10 } }`
- **And** the Flutter form shows an inline validation error under the quantity field: "Stock insuffisant — disponible : X unités"

**AC4 — Offline transfer (local-first)**
- **Given** the device has no network when Simon confirms a transfer
- **When** the form is submitted
- **Then** the transfer is validated locally against the Drift `stock_levels` data
- **And** if local stock is sufficient: source and destination `stock_levels` rows are updated locally immediately
- **And** a `STOCK_TRANSFER` entry is added to `sync_queue` with the full transfer payload as JSON
- **And** a `stock_transfers` local record is created with `status = 'PENDING_SYNC'`
- **And** the transfer shows in history with a "En attente de sync ⏳" badge
- **And** on next reconnection, the sync engine pushes the queue entry; server re-validates and either commits (clears `synced = true`) or returns conflict

**AC5 — Transfer history**
- **Given** Simon navigates to Stock > Historique transferts (tab or sub-page of `/stock/transfers`)
- **When** the page loads
- **Then** all transfers are listed in reverse chronological order with:
  - Date + time
  - Source → Destination (store names)
  - Product name + variant (if applicable)
  - Quantity pill
  - Status badge (voir tableau ci-dessous)
  - Bouton "Réceptionner le stock" si statut `IN_TRANSIT` (visible uniquement sur les tiles de la boutique destination active)
- **And** the list loads from local Drift cache — offline-capable
- **And** a filter bar allows filtering by status chip
- **And** when online, the list is refreshed from the server in the background
- **And** if `activeStoreId` is set, only transfers where `destinationStoreId == activeStoreId` are shown (transferts entrants de la boutique active)

**Tableau des statuts :**

| Statut | Badge | Couleur | Action disponible |
|--------|-------|---------|-------------------|
| `IN_TRANSIT` | 🚚 En transit | Teal | Bouton "Réceptionner le stock" |
| `COMPLETED` | ✅ Effectué | Vert | Aucune |
| `PENDING_SYNC` | ⏳ En attente de sync | Orange | Aucune (sera synced à la reconnexion) |
| `CONFLICT` | ❌ Conflit | Rouge | À résoudre manuellement (Epic 5) |

**AC7 — Réception du transfert (Step 2, online)**
- **Given** Simon voit un transfert `IN_TRANSIT` dans l'historique de sa boutique
- **When** il tape "Réceptionner le stock"
- **Then** le backend valide que le transfert est bien `IN_TRANSIT` (sinon : `TRANSFER_INVALID_STATUS`)
- **And** dans une transaction atomique :
  - stock destination **incrémenté** de la quantité transférée
  - un `stock_movements` `TRANSFER_IN` créé sur la boutique destination
  - `status` du transfert mis à jour → `COMPLETED`
- **And** un SnackBar de succès : "Stock réceptionné : [qty]× [product]"
- **And** le tile se met à jour → badge `✅ Effectué`, bouton "Réceptionner" disparaît
- **And** toutes les vues stock/catalogue se rafraîchissent immédiatement (cache invalidation cascade)
- **If** le transfert était déjà `COMPLETED` (action concurrente sur une autre session) :
  - Le backend renvoie `TRANSFER_INVALID_STATUS`
  - L'UI rafraîchit silencieusement l'historique (pas de SnackBar d'erreur)
  - Le tile disparaît (il n'est plus `IN_TRANSIT`)
- **If** le device est hors ligne : la réception est **bloquée** (`StateError` → l'UI affiche un message d'erreur — la réception nécessite une connexion active)

---

**AC6 — Source ≠ Destination validation**
- **Given** Simon selects the same store as both source and destination
- **When** the form is validated client-side
- **Then** the form shows an error: "Source et destination doivent être différentes"
- **And** the backend also returns HTTP 422 `{ "domainCode": "VALIDATION_ERROR", "field": "destinationStoreId", "message": "Source and destination must differ" }` if somehow bypassed

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Transfer validation rules may evolve (e.g., reservation locks, multi-step approvals for large quantities) |
| What might change in the future? | Conflict resolution strategy on offline re-sync (currently: server re-validates and rejects if quantity changed); multi-product batch transfers |
| Which GoF pattern(s) apply? | **Command** (TransferStockCommand encapsulates the operation as a value object passed through use case → domain service → repository; enables offline queuing); **Observer** (StockTransferredEvent → AuditEventListener + sync notifications); **Strategy** (TransferValidationStrategy — single-store vs. cross-tenant validation extensible without changing use case) |
| How does it enable Open/Closed principle? | `ExecuteTransferUseCase` is closed — validation delegates to `TransferValidationStrategy` (open for extension: add employee-only-view-restriction later without touching the use case). `StockTransferredEvent` decouples audit from domain. |
| Where is the pattern applied? | Command: `TransferStockCommand` record; Observer: `StockTransferredEvent` via `ApplicationEventPublisher`; Strategy: `TransferValidationStrategy` interface + `DefaultTransferValidationStrategy` impl |

---

## Tasks / Subtasks

> **⚠️ TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST, then implement. No exceptions.**

---

### BACKEND — Full TDD (Spring Boot, Hexagonal Architecture)

> **Module placement**: `catalog/stock/` — reuses existing stock infrastructure (`StockOperationService`, `StockLevelRepository`, `StockMovementRepository`).
> New transfer domain lives in `catalog/stock/domain/` (transfer sub-domain within stock module).
> All new tests → `src/test/java/com/keevo/catalog/stock/`

---

- [ ] **Task 1 — New domain model: StockTransfer aggregate + event**

  - [ ] 1.1 — Write `StockTransferTest.java` FIRST (unit, pure Java):
    ```java
    @Test void transfer_shouldRequirePositiveQuantity()
    @Test void transfer_shouldHoldAllFields()
    @Test void transfer_shouldRejectSameSourceAndDestination()
    ```
  - [ ] 1.2 — Create `StockTransfer.java` in `catalog/stock/domain/model/`:
    ```java
    package com.keevo.catalog.stock.domain.model;

    import java.time.Instant;
    import java.util.UUID;

    /**
     * StockTransfer — aggregate root for a stock transfer operation.
     *
     * <p>Immutable after creation. Carries the "transfer header" persisted in
     * stock_transfers table alongside the two stock_movements audit entries.
     *
     * <p>GoF: Command (value object carrying the transfer data).
     * Story 3.3.
     */
    public class StockTransfer {

        public enum TransferStatus { COMPLETED, PENDING_SYNC, CONFLICT }

        private final UUID id;
        private final UUID sourceStoreId;
        private final UUID destinationStoreId;
        private final UUID productId;
        private final UUID variantId;       // nullable
        private final int quantity;
        private final UUID actorId;
        private final Instant occurredAt;
        private final TransferStatus status;
        private final String notes;         // nullable

        public StockTransfer(UUID id, UUID sourceStoreId, UUID destinationStoreId,
                             UUID productId, UUID variantId, int quantity,
                             UUID actorId, Instant occurredAt,
                             TransferStatus status, String notes) {
            if (id == null)                  throw new IllegalArgumentException("id cannot be null");
            if (sourceStoreId == null)       throw new IllegalArgumentException("sourceStoreId cannot be null");
            if (destinationStoreId == null)  throw new IllegalArgumentException("destinationStoreId cannot be null");
            if (productId == null)           throw new IllegalArgumentException("productId cannot be null");
            if (actorId == null)             throw new IllegalArgumentException("actorId cannot be null");
            if (quantity <= 0)               throw new IllegalArgumentException("quantity must be positive");
            if (sourceStoreId.equals(destinationStoreId))
                throw new IllegalArgumentException("sourceStoreId and destinationStoreId must differ");
            this.id                 = id;
            this.sourceStoreId      = sourceStoreId;
            this.destinationStoreId = destinationStoreId;
            this.productId          = productId;
            this.variantId          = variantId;
            this.quantity           = quantity;
            this.actorId            = actorId;
            this.occurredAt         = occurredAt != null ? occurredAt : Instant.now();
            this.status             = status != null ? status : TransferStatus.COMPLETED;
            this.notes              = notes;
        }

        public UUID getId()                    { return id; }
        public UUID getSourceStoreId()         { return sourceStoreId; }
        public UUID getDestinationStoreId()    { return destinationStoreId; }
        public UUID getProductId()             { return productId; }
        public UUID getVariantId()             { return variantId; }
        public int  getQuantity()              { return quantity; }
        public UUID getActorId()               { return actorId; }
        public Instant getOccurredAt()         { return occurredAt; }
        public TransferStatus getStatus()      { return status; }
        public String getNotes()               { return notes; }
    }
    ```

  - [ ] 1.3 — Create `StockTransferredEvent.java` in `catalog/stock/domain/event/`:
    ```java
    package com.keevo.catalog.stock.domain.event;

    import java.time.Instant;
    import java.util.UUID;

    /**
     * StockTransferredEvent — domain event published after a successful transfer.
     *
     * <p>GoF: Observer — AuditEventListener reacts to this event to write the
     * audit log entry without coupling the domain service to the audit module.
     * Story 3.3.
     */
    public record StockTransferredEvent(
        UUID transferId,
        UUID sourceStoreId,
        UUID destinationStoreId,
        UUID productId,
        UUID variantId,       // nullable
        int quantity,
        UUID actorId,
        String tenantId,
        Instant occurredAt
    ) {}
    ```

---

- [ ] **Task 2 — new ErrorCode entries**

  - [ ] 2.1 — Add to `ErrorCode.java` if not present (check for `STORE_NOT_ACTIVE`, `SAME_SOURCE_DESTINATION`):
    ```java
    // ── Transfer (Story 3.3) ──────────────────────────────────────────────
    STORE_NOT_ACTIVE,           // HTTP 422 — source or destination store is deactivated
    SAME_SOURCE_DESTINATION,    // HTTP 422 — sourceStoreId == destinationStoreId
    ```
    > If `STORE_NOT_FOUND` already covers inactive stores → reuse it and only add `SAME_SOURCE_DESTINATION`.

---

- [ ] **Task 3 — Ports: TransferStockCommand + use case interfaces**

  - [ ] 3.1 — Create `TransferStockCommand.java` in `catalog/stock/domain/port/in/`:
    ```java
    package com.keevo.catalog.stock.domain.port.in;

    import java.util.UUID;

    /**
     * TransferStockCommand — input value object for the transfer use case.
     *
     * <p>GoF: Command pattern — encapsulates the transfer intent.
     * MCP-ready: pure Java record, no HttpServletRequest or Spring types.
     * Story 3.3.
     */
    public record TransferStockCommand(
        UUID sourceStoreId,
        UUID destinationStoreId,
        UUID productId,
        UUID variantId,      // null for simple products
        int  quantity,       // must be > 0
        UUID actorId,
        String notes         // nullable
    ) {}
    ```

  - [ ] 3.2 — Create `TransferStockUseCase.java` in `catalog/stock/domain/port/in/`:
    ```java
    import com.keevo.catalog.stock.domain.model.StockTransfer;

    public interface TransferStockUseCase {
        StockTransfer execute(TransferStockCommand command);
    }
    ```

  - [ ] 3.3 — Create `GetTransferHistoryQuery.java` in `catalog/stock/domain/port/in/`:
    ```java
    /**
     * @param sourceStoreId      optional filter
     * @param destinationStoreId optional filter
     * @param from               optional — transfers at or after this timestamp
     * @param to                 optional — transfers at or before this timestamp
     * @param page               0-based
     * @param size               page size (default 25)
     */
    public record GetTransferHistoryQuery(
        UUID sourceStoreId,
        UUID destinationStoreId,
        Instant from,
        Instant to,
        int page,
        int size
    ) {}
    ```

  - [ ] 3.4 — Create `GetTransferHistoryUseCase.java` in `catalog/stock/domain/port/in/`:
    ```java
    import org.springframework.data.domain.Page;

    public interface GetTransferHistoryUseCase {
        Page<StockTransfer> execute(GetTransferHistoryQuery query);
    }
    ```

---

- [ ] **Task 4 — Out port: StockTransferRepository**

  - [ ] 4.1 — Write `StockTransferRepositoryContractTest.java` (contract, JUnit 5):
    ```java
    @Test void repository_shouldDefineSave()
    @Test void repository_shouldDefineFindByFilters()
    ```
  - [ ] 4.2 — Create `StockTransferRepository.java` in `catalog/stock/domain/port/out/`:
    ```java
    package com.keevo.catalog.stock.domain.port.out;

    import com.keevo.catalog.stock.domain.model.StockTransfer;
    import org.springframework.data.domain.Page;
    import org.springframework.data.domain.Pageable;

    import java.time.Instant;
    import java.util.UUID;

    /**
     * StockTransferRepository — out port for stock transfer persistence.
     *
     * <p>Persists the transfer header (stock_transfers table). The two
     * stock_movements records are written by StockOperationService.
     * Story 3.3.
     */
    public interface StockTransferRepository {

        StockTransfer save(StockTransfer transfer);

        Page<StockTransfer> findByFilters(
            UUID sourceStoreId,
            UUID destinationStoreId,
            Instant from,
            Instant to,
            Pageable pageable
        );
    }
    ```

---

- [ ] **Task 5 — Strategy: TransferValidationStrategy**

  - [ ] 5.1 — Write `TransferValidationStrategyTest.java` FIRST:
    ```java
    @Test void defaultStrategy_shouldThrow_whenQuantityExceedsStock()
    @Test void defaultStrategy_shouldThrow_whenSourceStoreInactive()
    @Test void defaultStrategy_shouldThrow_whenDestinationStoreInactive()
    @Test void defaultStrategy_shouldThrow_whenSameSourceAndDestination()
    @Test void defaultStrategy_shouldPass_whenAllValid()
    ```
  - [ ] 5.2 — Create `TransferValidationStrategy.java` in `catalog/stock/domain/service/`:
    ```java
    package com.keevo.catalog.stock.domain.service;

    import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;

    /**
     * TransferValidationStrategy — GoF Strategy.
     * Validates a transfer command before execution.
     * Closed for modification, open for extension (e.g., employee-scoped validation).
     * Story 3.3.
     */
    public interface TransferValidationStrategy {
        /**
         * Validate the command. Throws {@link com.keevo.shared.domain.exception.DomainException}
         * on any rule violation.
         * @param command the transfer to validate
         * @param availableSourceQty current stock level at source
         */
        void validate(TransferStockCommand command, int availableSourceQty);
    }
    ```
  - [ ] 5.3 — Create `DefaultTransferValidationStrategy.java` in `catalog/stock/domain/service/`:
    ```java
    package com.keevo.catalog.stock.domain.service;

    import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;
    import com.keevo.shared.domain.exception.DomainException;
    import com.keevo.shared.domain.exception.ErrorCode;
    import org.springframework.stereotype.Component;

    /**
     * DefaultTransferValidationStrategy — owner-level transfer validation.
     *
     * <p>Rules:
     * 1. sourceStoreId != destinationStoreId
     * 2. quantity > 0  (already enforced by TransferStockCommand domain invariant)
     * 3. availableSourceQty >= quantity  (INSUFFICIENT_STOCK)
     *
     * <p>Store active status is checked upstream in ExecuteTransferService
     * (uses StoreRepository.findById → asserts isActive).
     *
     * Story 3.3.
     */
    @Component
    public class DefaultTransferValidationStrategy implements TransferValidationStrategy {

        @Override
        public void validate(TransferStockCommand command, int availableSourceQty) {
            if (command.sourceStoreId().equals(command.destinationStoreId())) {
                throw new DomainException(ErrorCode.SAME_SOURCE_DESTINATION,
                    "sourceStoreId and destinationStoreId must differ");
            }
            if (command.quantity() <= 0) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "quantity must be positive, got: " + command.quantity());
            }
            if (availableSourceQty < command.quantity()) {
                throw new DomainException(ErrorCode.INSUFFICIENT_STOCK,
                    "available=" + availableSourceQty + " requested=" + command.quantity(),
                    java.util.Map.of(
                        "available", availableSourceQty,
                        "requested", command.quantity()
                    ));
            }
        }
    }
    ```

---

- [ ] **Task 6 — Application service: ExecuteTransferService**

  - [ ] 6.1 — Write `ExecuteTransferServiceTest.java` FIRST (unit, Mockito):
    ```java
    @Test void execute_shouldDecrement_sourceStock()
    @Test void execute_shouldIncrement_destinationStock()
    @Test void execute_shouldPersistTransferRecord()
    @Test void execute_shouldPublishStockTransferredEvent()
    @Test void execute_shouldCreateTwoMovementRecords_TRANSFER_OUT_and_TRANSFER_IN()
    @Test void execute_shouldThrow_INSUFFICIENT_STOCK_whenSourceQtyTooLow()
    @Test void execute_shouldThrow_STORE_NOT_FOUND_whenSourceStoreDoesNotExist()
    @Test void execute_shouldThrow_STORE_NOT_FOUND_whenDestinationStoreDoesNotExist()
    @Test void execute_shouldThrow_SAME_SOURCE_DESTINATION_whenStoreIdsMatch()
    ```
  - [ ] 6.2 — Create `ExecuteTransferService.java` in `catalog/stock/application/usecase/`:
    ```java
    package com.keevo.catalog.stock.application.usecase;

    import com.keevo.catalog.stock.domain.entity.MovementType;
    import com.keevo.catalog.stock.domain.event.StockTransferredEvent;
    import com.keevo.catalog.stock.domain.model.StockTransfer;
    import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;
    import com.keevo.catalog.stock.domain.port.in.TransferStockUseCase;
    import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
    import com.keevo.catalog.stock.domain.port.out.StockTransferRepository;
    import com.keevo.catalog.stock.domain.service.StockOperationService;
    import com.keevo.catalog.stock.domain.service.TransferValidationStrategy;
    import com.keevo.shared.domain.exception.DomainException;
    import com.keevo.shared.domain.exception.ErrorCode;
    import com.keevo.shared.infrastructure.persistence.TenantContext;
    import com.keevo.store.store.domain.port.out.StoreRepository;
    import org.springframework.context.ApplicationEventPublisher;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;

    import java.time.Instant;
    import java.util.UUID;

    /**
     * ExecuteTransferService — orchestrates the stock transfer use case.
     *
     * <p>Steps (all atomic — single @Transactional):
     * 1. Load source + destination stores; assert active
     * 2. Load current stock level at source
     * 3. Delegate validation to TransferValidationStrategy (GoF: Strategy)
     * 4. Call StockOperationService.recordOperation(TRANSFER_OUT) on source
     * 5. Call StockOperationService.recordOperation(TRANSFER_IN) on destination
     * 6. Persist StockTransfer header record
     * 7. Publish StockTransferredEvent (GoF: Observer)
     *
     * <p>GoF: Strategy for validation, Observer for event.
     * Story 3.3.
     */
    @Service
    public class ExecuteTransferService implements TransferStockUseCase {

        private final StoreRepository storeRepository;
        private final StockLevelRepository stockLevelRepository;
        private final StockOperationService stockOperationService;
        private final StockTransferRepository transferRepository;
        private final TransferValidationStrategy validationStrategy;
        private final ApplicationEventPublisher eventPublisher;

        public ExecuteTransferService(
                StoreRepository storeRepository,
                StockLevelRepository stockLevelRepository,
                StockOperationService stockOperationService,
                StockTransferRepository transferRepository,
                TransferValidationStrategy validationStrategy,
                ApplicationEventPublisher eventPublisher) {
            this.storeRepository       = storeRepository;
            this.stockLevelRepository  = stockLevelRepository;
            this.stockOperationService = stockOperationService;
            this.transferRepository    = transferRepository;
            this.validationStrategy    = validationStrategy;
            this.eventPublisher        = eventPublisher;
        }

        @Override
        @Transactional
        public StockTransfer execute(TransferStockCommand command) {
            // 1. Validate stores exist and are active
            var source = storeRepository.findById(command.sourceStoreId())
                .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND,
                    "Source store not found: " + command.sourceStoreId()));
            if (!source.isActive()) {
                throw new DomainException(ErrorCode.STORE_NOT_ACTIVE,
                    "Source store is deactivated: " + command.sourceStoreId());
            }
            var destination = storeRepository.findById(command.destinationStoreId())
                .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND,
                    "Destination store not found: " + command.destinationStoreId()));
            if (!destination.isActive()) {
                throw new DomainException(ErrorCode.STORE_NOT_ACTIVE,
                    "Destination store is deactivated: " + command.destinationStoreId());
            }

            // 2. Load source stock level
            int availableQty = (command.variantId() != null
                ? stockLevelRepository.findByProductVariantAndStore(
                    command.productId(), command.variantId(), command.sourceStoreId())
                : stockLevelRepository.findByProductAndStore(
                    command.productId(), command.sourceStoreId()))
                .map(sl -> sl.getQuantity())
                .orElse(0);

            // 3. Validate (Strategy)
            validationStrategy.validate(command, availableQty);

            // 4. TRANSFER_OUT from source
            stockOperationService.recordOperation(
                command.productId(), command.variantId(), command.sourceStoreId(),
                MovementType.TRANSFER_OUT, -command.quantity(),
                command.actorId(), "Transfert → " + destination.getName());

            // 5. TRANSFER_IN to destination
            stockOperationService.recordOperation(
                command.productId(), command.variantId(), command.destinationStoreId(),
                MovementType.TRANSFER_IN, command.quantity(),
                command.actorId(), "Transfert ← " + source.getName());

            // 6. Persist transfer header
            var now = Instant.now();
            var transfer = new StockTransfer(
                UUID.randomUUID(),
                command.sourceStoreId(), command.destinationStoreId(),
                command.productId(), command.variantId(),
                command.quantity(), command.actorId(), now,
                StockTransfer.TransferStatus.COMPLETED,
                command.notes()
            );
            var saved = transferRepository.save(transfer);

            // 7. Publish event (Observer)
            eventPublisher.publishEvent(new StockTransferredEvent(
                saved.getId(),
                command.sourceStoreId(), command.destinationStoreId(),
                command.productId(), command.variantId(),
                command.quantity(), command.actorId(),
                TenantContext.getCurrentTenant(), now
            ));

            return saved;
        }
    }
    ```

  - [ ] 6.3 — Write `GetTransferHistoryServiceTest.java` FIRST:
    ```java
    @Test void execute_shouldDelegateToRepository_withCorrectFilters()
    @Test void execute_shouldDefaultToPage0Size25_whenNotSpecified()
    ```
  - [ ] 6.4 — Create `GetTransferHistoryService.java` in `catalog/stock/application/usecase/`:
    ```java
    @Service
    public class GetTransferHistoryService implements GetTransferHistoryUseCase {

        private final StockTransferRepository transferRepository;

        public GetTransferHistoryService(StockTransferRepository transferRepository) {
            this.transferRepository = transferRepository;
        }

        @Override
        @Transactional(readOnly = true)
        public Page<StockTransfer> execute(GetTransferHistoryQuery query) {
            Pageable pageable = PageRequest.of(
                query.page(), query.size(),
                Sort.by(Sort.Direction.DESC, "occurredAt")
            );
            return transferRepository.findByFilters(
                query.sourceStoreId(), query.destinationStoreId(),
                query.from(), query.to(), pageable
            );
        }
    }
    ```

---

- [ ] **Task 7 — DDL: stock_transfers table in TenantSchemaProvisioner**

  - [ ] 7.1 — Write `TenantSchemaProvisionerTransferTest.java` (integration test asserting `stock_transfers` table exists after provisioning):
    ```java
    @Test void provision_shouldCreate_stock_transfers_table()
    ```
  - [ ] 7.2 — Add DDL constant and execute in `TenantSchemaProvisioner.java`:
    ```java
    // ── Stock transfers (Story 3.3) ──────────────────────────────────────────
    static final String DDL_STOCK_TRANSFERS = """
            CREATE TABLE IF NOT EXISTS stock_transfers (
                id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                source_store_id      UUID        NOT NULL REFERENCES stores(id),
                destination_store_id UUID        NOT NULL REFERENCES stores(id),
                product_id           UUID        NOT NULL REFERENCES products(id),
                variant_id           UUID,
                quantity             INTEGER     NOT NULL CHECK (quantity > 0),
                actor_id             UUID        NOT NULL,
                occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                status               VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'
                                         CHECK (status IN ('COMPLETED','PENDING_SYNC','CONFLICT')),
                notes                TEXT
            )""";
    static final String DDL_STOCK_TRANSFERS_IDX_SOURCE =
        "CREATE INDEX IF NOT EXISTS idx_stock_transfers_source ON stock_transfers(source_store_id, occurred_at DESC)";
    static final String DDL_STOCK_TRANSFERS_IDX_DEST =
        "CREATE INDEX IF NOT EXISTS idx_stock_transfers_dest ON stock_transfers(destination_store_id, occurred_at DESC)";
    static final String DDL_STOCK_TRANSFERS_IDX_PRODUCT =
        "CREATE INDEX IF NOT EXISTS idx_stock_transfers_product ON stock_transfers(product_id, occurred_at DESC)";
    ```
    Execute in `provisionTenantSchema()` right after the `stock_movements` block:
    ```java
    stmt.execute(DDL_STOCK_TRANSFERS);
    stmt.execute(DDL_STOCK_TRANSFERS_IDX_SOURCE);
    stmt.execute(DDL_STOCK_TRANSFERS_IDX_DEST);
    stmt.execute(DDL_STOCK_TRANSFERS_IDX_PRODUCT);
    ```

---

- [ ] **Task 8 — JPA entity + Spring repository + adapter**

  - [ ] 8.1 — Create `StockTransferJpaEntity.java` in `shared/infrastructure/persistence/entity/`:
    ```java
    package com.keevo.shared.infrastructure.persistence.entity;

    import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
    import jakarta.persistence.*;
    import java.time.Instant;
    import java.util.UUID;

    /**
     * StockTransferJpaEntity — JPA mapping for stock_transfers table.
     * Story 3.3.
     */
    @Entity
    @Table(name = "stock_transfers")
    public class StockTransferJpaEntity {

        @Id
        @Column(name = "id", columnDefinition = "UUID")
        private UUID id;

        @Column(name = "source_store_id", nullable = false, columnDefinition = "UUID")
        private UUID sourceStoreId;

        @Column(name = "destination_store_id", nullable = false, columnDefinition = "UUID")
        private UUID destinationStoreId;

        @Column(name = "product_id", nullable = false, columnDefinition = "UUID")
        private UUID productId;

        @Column(name = "variant_id", columnDefinition = "UUID")
        private UUID variantId;

        @Column(name = "quantity", nullable = false)
        private Integer quantity;

        @Column(name = "actor_id", nullable = false, columnDefinition = "UUID")
        private UUID actorId;

        @Column(name = "occurred_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
        private Instant occurredAt;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false, length = 20)
        private TransferStatus status;

        @Column(name = "notes", columnDefinition = "TEXT")
        private String notes;

        public StockTransferJpaEntity() {}

        // All-args constructor + getters + JPA setters — follow exact same pattern
        // as StockMovementJpaEntity (see shared/infrastructure/persistence/entity/).
        // (Standard boilerplate — not repeated here for brevity.)
    }
    ```

  - [ ] 8.2 — Create `StockTransferSpringRepository.java` in `catalog/stock/adapter/out/persistence/`:
    ```java
    import org.springframework.data.jpa.repository.JpaRepository;
    import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
    import com.keevo.shared.infrastructure.persistence.entity.StockTransferJpaEntity;
    import java.util.UUID;

    public interface StockTransferSpringRepository
        extends JpaRepository<StockTransferJpaEntity, UUID>,
                JpaSpecificationExecutor<StockTransferJpaEntity> {}
    ```

  - [ ] 8.3 — Write `StockTransferRepositoryAdapterTest.java` (`@DataJpaTest`):
    ```java
    @Test void save_shouldPersistTransfer_withCorrectFields()
    @Test void findByFilters_shouldFilterBySourceStore()
    @Test void findByFilters_shouldFilterByDestinationStore()
    @Test void findByFilters_shouldFilterByDateRange()
    @Test void findByFilters_shouldOrderByOccurredAtDesc()
    ```
  - [ ] 8.4 — Create `StockTransferRepositoryAdapter.java` in `catalog/stock/adapter/out/persistence/`:
    ```java
    package com.keevo.catalog.stock.adapter.out.persistence;

    import com.keevo.catalog.stock.domain.model.StockTransfer;
    import com.keevo.catalog.stock.domain.port.out.StockTransferRepository;
    import com.keevo.shared.infrastructure.persistence.entity.StockTransferJpaEntity;
    import jakarta.persistence.criteria.Predicate;
    import org.springframework.data.domain.*;
    import org.springframework.data.jpa.domain.Specification;
    import org.springframework.stereotype.Component;
    import org.springframework.transaction.annotation.Transactional;

    import java.time.Instant;
    import java.util.*;

    /**
     * StockTransferRepositoryAdapter — JPA implementation.
     * Uses JpaSpecificationExecutor for optional filter combination.
     * GoF: Adapter pattern. Story 3.3.
     */
    @Component
    public class StockTransferRepositoryAdapter implements StockTransferRepository {

        private final StockTransferSpringRepository springRepository;

        public StockTransferRepositoryAdapter(StockTransferSpringRepository springRepository) {
            this.springRepository = springRepository;
        }

        @Override
        @Transactional
        public StockTransfer save(StockTransfer transfer) {
            var entity = toJpaEntity(transfer);
            return toDomain(springRepository.save(entity));
        }

        @Override
        @Transactional(readOnly = true)
        public Page<StockTransfer> findByFilters(
                UUID sourceStoreId, UUID destinationStoreId,
                Instant from, Instant to, Pageable pageable) {
            Specification<StockTransferJpaEntity> spec = buildSpec(
                sourceStoreId, destinationStoreId, from, to);
            return springRepository.findAll(spec, pageable).map(this::toDomain);
        }

        private Specification<StockTransferJpaEntity> buildSpec(
                UUID sourceStoreId, UUID destinationStoreId,
                Instant from, Instant to) {
            return (root, query, cb) -> {
                List<Predicate> predicates = new ArrayList<>();
                if (sourceStoreId != null)
                    predicates.add(cb.equal(root.get("sourceStoreId"), sourceStoreId));
                if (destinationStoreId != null)
                    predicates.add(cb.equal(root.get("destinationStoreId"), destinationStoreId));
                if (from != null)
                    predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
                if (to != null)
                    predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
                return cb.and(predicates.toArray(new Predicate[0]));
            };
        }

        private StockTransferJpaEntity toJpaEntity(StockTransfer t) {
            var entity = new StockTransferJpaEntity();
            entity.setId(t.getId());
            entity.setSourceStoreId(t.getSourceStoreId());
            entity.setDestinationStoreId(t.getDestinationStoreId());
            entity.setProductId(t.getProductId());
            entity.setVariantId(t.getVariantId());
            entity.setQuantity(t.getQuantity());
            entity.setActorId(t.getActorId());
            entity.setOccurredAt(t.getOccurredAt());
            entity.setStatus(t.getStatus());
            entity.setNotes(t.getNotes());
            return entity;
        }

        private StockTransfer toDomain(StockTransferJpaEntity e) {
            return new StockTransfer(
                e.getId(), e.getSourceStoreId(), e.getDestinationStoreId(),
                e.getProductId(), e.getVariantId(), e.getQuantity(),
                e.getActorId(), e.getOccurredAt(), e.getStatus(), e.getNotes()
            );
        }
    }
    ```

---

- [ ] **Task 9 — REST adapter: DTOs + TransferController**

  - [ ] 9.1 — Create `TransferStockRequestDto.java` in `catalog/stock/adapter/in/web/dto/`:
    ```java
    package com.keevo.catalog.stock.adapter.in.web.dto;

    import jakarta.validation.constraints.*;
    import java.util.UUID;

    /**
     * TransferStockRequestDto — REST request body for POST /api/v1/stock/transfers.
     * Story 3.3.
     */
    public record TransferStockRequestDto(
        @NotNull UUID sourceStoreId,
        @NotNull UUID destinationStoreId,
        @NotNull UUID productId,
        UUID variantId,                         // nullable
        @Min(1) int quantity,
        String notes                            // nullable
    ) {}
    ```

  - [ ] 9.2 — Create `StockTransferResponseDto.java` in `catalog/stock/adapter/in/web/dto/`:
    ```java
    package com.keevo.catalog.stock.adapter.in.web.dto;

    import com.keevo.catalog.stock.domain.model.StockTransfer;
    import java.time.Instant;
    import java.util.UUID;

    /**
     * StockTransferResponseDto — REST response for a stock transfer.
     * Story 3.3.
     */
    public record StockTransferResponseDto(
        UUID id,
        UUID sourceStoreId,
        UUID destinationStoreId,
        UUID productId,
        UUID variantId,
        int quantity,
        UUID actorId,
        Instant occurredAt,
        String status,   // "COMPLETED" | "PENDING_SYNC" | "CONFLICT"
        String notes
    ) {
        public static StockTransferResponseDto from(StockTransfer t) {
            return new StockTransferResponseDto(
                t.getId(), t.getSourceStoreId(), t.getDestinationStoreId(),
                t.getProductId(), t.getVariantId(), t.getQuantity(),
                t.getActorId(), t.getOccurredAt(), t.getStatus().name(), t.getNotes()
            );
        }
    }
    ```

  - [ ] 9.3 — Write `StockTransferControllerTest.java` (`@WebMvcTest`) FIRST:
    ```java
    @Test void POST_stock_transfers_shouldReturn201_withValidBody()
    @Test void POST_stock_transfers_shouldReturn401_withoutAuth()
    @Test void POST_stock_transfers_shouldReturn422_whenQuantityZero()
    @Test void POST_stock_transfers_shouldReturn422_whenSourceEqualsDestination()  // mocked from service
    @Test void POST_stock_transfers_shouldReturn422_whenInsufficientStock()        // mocked from service
    @Test void GET_stock_transfers_history_shouldReturn200_withPagedResults()
    @Test void GET_stock_transfers_history_shouldApply_sourceStoreFilter()
    ```
  - [ ] 9.4 — Create `StockTransferController.java` in `catalog/stock/adapter/in/web/`:
    ```java
    package com.keevo.catalog.stock.adapter.in.web;

    import com.keevo.catalog.stock.adapter.in.web.dto.StockTransferResponseDto;
    import com.keevo.catalog.stock.adapter.in.web.dto.TransferStockRequestDto;
    import com.keevo.catalog.stock.application.usecase.ExecuteTransferService;
    import com.keevo.catalog.stock.application.usecase.GetTransferHistoryService;
    import com.keevo.catalog.stock.domain.port.in.GetTransferHistoryQuery;
    import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;
    import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
    import io.swagger.v3.oas.annotations.Operation;
    import io.swagger.v3.oas.annotations.tags.Tag;
    import jakarta.validation.Valid;
    import org.springframework.data.domain.Page;
    import org.springframework.format.annotation.DateTimeFormat;
    import org.springframework.http.HttpStatus;
    import org.springframework.http.ResponseEntity;
    import org.springframework.security.access.prepost.PreAuthorize;
    import org.springframework.security.core.context.SecurityContextHolder;
    import org.springframework.web.bind.annotation.*;

    import java.time.Instant;
    import java.util.UUID;

    /**
     * StockTransferController — REST API for inter-store stock transfers.
     *
     * <pre>
     * POST /api/v1/stock/transfers                  → execute transfer
     * GET  /api/v1/stock/transfers?source=&dest=&from=&to=&page=&size=  → paginated history
     * </pre>
     *
     * Story 3.3.
     */
    @Tag(name = "Stock Transfers", description = "Inter-store stock transfer management")
    @RestController
    @RequestMapping("/api/v1/stock/transfers")
    @PreAuthorize("hasRole('USER')")
    public class StockTransferController {

        private final ExecuteTransferService executeTransferService;
        private final GetTransferHistoryService getTransferHistoryService;

        public StockTransferController(ExecuteTransferService executeTransferService,
                                       GetTransferHistoryService getTransferHistoryService) {
            this.executeTransferService    = executeTransferService;
            this.getTransferHistoryService = getTransferHistoryService;
        }

        @Operation(summary = "Execute a stock transfer between two stores")
        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public ResponseEntity<ApiResponseWrapper<StockTransferResponseDto>> transfer(
                @Valid @RequestBody TransferStockRequestDto dto) {

            UUID actorId = UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName());

            var command = new TransferStockCommand(
                dto.sourceStoreId(), dto.destinationStoreId(),
                dto.productId(), dto.variantId(),
                dto.quantity(), actorId, dto.notes()
            );
            var result = executeTransferService.execute(command);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(StockTransferResponseDto.from(result)));
        }

        @Operation(summary = "Get paginated transfer history with optional filters")
        @GetMapping
        public ResponseEntity<ApiResponseWrapper<Page<StockTransferResponseDto>>> getHistory(
                @RequestParam(required = false) UUID source,
                @RequestParam(required = false) UUID dest,
                @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                @RequestParam(defaultValue = "0")  int page,
                @RequestParam(defaultValue = "25") int size) {

            if (size < 1 || size > 100) size = 25;

            var query = new GetTransferHistoryQuery(source, dest, from, to, page, size);
            Page<StockTransferResponseDto> result =
                getTransferHistoryService.execute(query)
                    .map(StockTransferResponseDto::from);
            return ResponseEntity.ok(ApiResponseWrapper.ok(result));
        }
    }
    ```

    > **Security**: `actorId` extracted from `SecurityContextHolder` JWT principal (UUID string).
    > UUID path/query params are type-safe. `size` clamped to [1,100]. No user-string SQL concatenation.

---

- [ ] **Task 10 — cURL Integration Test Script**

  Create `keevo/scripts/curl-tests-story-3-3.sh`:

  ```bash
  #!/usr/bin/env bash
  # ======================================================
  # Story 3.3 — Transferts Inter-Boutiques avec Traçabilité
  # cURL E2E integration tests
  # Run: bash curl-tests-story-3-3.sh
  # All steps must show ✅ before story is marked done
  # ======================================================
  set -euo pipefail
  BASE="http://localhost:8080"
  PY='python3 -c'

  # ── Step 1 — Auth ────────────────────────────────────────────────────────────
  RESP=$(curl -s -X POST "$BASE/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d '{"phone":"+237611000033","password":"Test1234!","firstName":"Simon","lastName":"Transfer"}')
  LOGIN_TOKEN=$(echo "$RESP" | $PY "import sys,json; print(json.load(sys.stdin)['data']['loginToken'])")
  TENANTS=$(curl -s -X GET "$BASE/api/v1/auth/tenants" -H "Authorization: Bearer $LOGIN_TOKEN")
  TENANT_ID=$(echo "$TENANTS" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['tenantId'])")
  SEL=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
    -H "Authorization: Bearer $LOGIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\"}")
  JWT=$(echo "$SEL" | $PY "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
  [[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — Auth OK" || { echo "❌ Step 1 FAILED"; exit 1; }

  # ── Step 2 — Onboarding (creates default store) ──────────────────────────────
  curl -s -X POST "$BASE/api/v1/onboarding/complete" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"sectorType":"GENERAL","shopName":"Boutique Transfert","city":"Douala"}' > /dev/null
  echo "✅ Step 2 — Onboarding OK"

  # ── Step 3 — Create a second store (destination) ─────────────────────────────
  STORES=$(curl -s -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $JWT")
  STORE1_ID=$(echo "$STORES" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")
  [[ -n "$STORE1_ID" && "$STORE1_ID" != "null" ]] && echo "✅ Step 3a — Source store found (id=$STORE1_ID)" || { echo "❌ Step 3a FAILED"; exit 1; }

  S2=$(curl -s -X POST "$BASE/api/v1/stores" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":"Boutique Bonanjo","type":"STORE"}')
  STORE2_ID=$(echo "$S2" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
  [[ -n "$STORE2_ID" && "$STORE2_ID" != "null" ]] && echo "✅ Step 3b — Destination store created (id=$STORE2_ID)" || { echo "❌ Step 3b FAILED"; exit 1; }

  # ── Step 4 — Create product and add stock to source store ─────────────────────
  PROD=$(curl -s -X POST "$BASE/api/v1/products" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"name":"Savon Azur","price":500,"buyPrice":300,"stockQuantity":0,"categoryId":null}')
  PROD_ID=$(echo "$PROD" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
  [[ -n "$PROD_ID" && "$PROD_ID" != "null" ]] && echo "✅ Step 4a — Product created (id=$PROD_ID)" || { echo "❌ Step 4a FAILED"; exit 1; }

  SE=$(curl -s -X POST "$BASE/api/v1/products/$PROD_ID/stock/entry" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"storeId\":\"$STORE1_ID\",\"quantity\":20,\"notes\":\"Stock initial\"}")
  QTY=$(echo "$SE" | $PY "import sys,json; print(json.load(sys.stdin)['data']['quantityAfter'])")
  [[ "$QTY" == "20" ]] && echo "✅ Step 4b — Stock entry: 20 units in source store" || { echo "❌ Step 4b FAILED (qty=$QTY)"; exit 1; }

  # ── Step 5 — Execute a transfer: 8 units from store1 → store2 ────────────────
  TRANSFER=$(curl -s -X POST "$BASE/api/v1/stock/transfers" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"sourceStoreId\":\"$STORE1_ID\",\"destinationStoreId\":\"$STORE2_ID\",\"productId\":\"$PROD_ID\",\"quantity\":8,\"notes\":\"Transfert test\"}")
  HTTP_STATUS=$(echo "$TRANSFER" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('status','200'))")
  TRANSFER_ID=$(echo "$TRANSFER" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
  TRANSFER_STATUS=$(echo "$TRANSFER" | $PY "import sys,json; print(json.load(sys.stdin)['data']['status'])")
  [[ -n "$TRANSFER_ID" && "$TRANSFER_ID" != "null" ]] && echo "✅ Step 5a — Transfer created (id=$TRANSFER_ID)" || { echo "❌ Step 5a FAILED"; exit 1; }
  [[ "$TRANSFER_STATUS" == "COMPLETED" ]] && echo "✅ Step 5b — Transfer status = COMPLETED" || { echo "❌ Step 5b FAILED (status=$TRANSFER_STATUS)"; exit 1; }

  # ── Step 6 — Verify source stock decremented by 8 (20 - 8 = 12) ──────────────
  SRC_STOCK=$(curl -s -X GET "$BASE/api/v1/products/$PROD_ID/stock" -H "Authorization: Bearer $JWT")
  SRC_QTY=$(echo "$SRC_STOCK" | $PY "
import sys,json
levels = json.load(sys.stdin)['data']
src = next((l for l in levels if l['storeId'] == '$STORE1_ID'), None)
print(src['quantity'] if src else -1)
")
  [[ "$SRC_QTY" == "12" ]] && echo "✅ Step 6 — Source stock: 20 - 8 = 12 ✓" || { echo "❌ Step 6 FAILED (srcQty=$SRC_QTY, expected 12)"; exit 1; }

  # ── Step 7 — Verify destination stock incremented by 8 (0 + 8 = 8) ───────────
  DEST_QTY=$(echo "$SRC_STOCK" | $PY "
import sys,json
levels = json.load(sys.stdin)['data']
dst = next((l for l in levels if l['storeId'] == '$STORE2_ID'), None)
print(dst['quantity'] if dst else -1)
")
  [[ "$DEST_QTY" == "8" ]] && echo "✅ Step 7 — Destination stock: 0 + 8 = 8 ✓" || { echo "❌ Step 7 FAILED (dstQty=$DEST_QTY, expected 8)"; exit 1; }

  # ── Step 8 — Verify two stock_movements created (TRANSFER_OUT + TRANSFER_IN) ──
  HIST=$(curl -s -X GET "$BASE/api/v1/products/$PROD_ID/stock/history?page=0&size=10" \
    -H "Authorization: Bearer $JWT")
  TYPES=$(echo "$HIST" | $PY "
import sys,json
content = json.load(sys.stdin)['data']['content']
types = sorted(set(m['movementType'] for m in content if m['movementType'].startswith('TRANSFER')))
print(','.join(types))
")
  [[ "$TYPES" == "TRANSFER_IN,TRANSFER_OUT" ]] && echo "✅ Step 8 — Two movement records: TRANSFER_IN + TRANSFER_OUT" || { echo "❌ Step 8 FAILED (types=$TYPES)"; exit 1; }

  # ── Step 9 — Transfer history endpoint ───────────────────────────────────────
  HIST2=$(curl -s -X GET "$BASE/api/v1/stock/transfers?page=0&size=25" \
    -H "Authorization: Bearer $JWT")
  TOTAL=$(echo "$HIST2" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalElements'])")
  [[ "$TOTAL" -ge 1 ]] && echo "✅ Step 9 — Transfer history: totalElements >= 1 (total=$TOTAL)" || { echo "❌ Step 9 FAILED (total=$TOTAL)"; exit 1; }

  # ── Step 10 — Filter by sourceStoreId ─────────────────────────────────────────
  HIST_SRC=$(curl -s -X GET "$BASE/api/v1/stock/transfers?source=$STORE1_ID&page=0&size=25" \
    -H "Authorization: Bearer $JWT")
  TOTAL_SRC=$(echo "$HIST_SRC" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalElements'])")
  [[ "$TOTAL_SRC" -ge 1 ]] && echo "✅ Step 10 — Filtered by source: $TOTAL_SRC result(s)" || { echo "❌ Step 10 FAILED (total=$TOTAL_SRC)"; exit 1; }

  # ── Step 11 — INSUFFICIENT_STOCK: transfer 100 units (only 12 remain) ─────────
  OVTX=$(curl -s -X POST "$BASE/api/v1/stock/transfers" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"sourceStoreId\":\"$STORE1_ID\",\"destinationStoreId\":\"$STORE2_ID\",\"productId\":\"$PROD_ID\",\"quantity\":100}")
  DOMAIN_CODE=$(echo "$OVTX" | $PY "import sys,json; print(json.load(sys.stdin).get('domainCode',''))")
  [[ "$DOMAIN_CODE" == "INSUFFICIENT_STOCK" ]] && echo "✅ Step 11 — INSUFFICIENT_STOCK returned correctly" || { echo "❌ Step 11 FAILED (code=$DOMAIN_CODE)"; exit 1; }

  # ── Step 12 — SAME_SOURCE_DESTINATION: same store for source and dest ──────────
  SAME=$(curl -s -X POST "$BASE/api/v1/stock/transfers" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"sourceStoreId\":\"$STORE1_ID\",\"destinationStoreId\":\"$STORE1_ID\",\"productId\":\"$PROD_ID\",\"quantity\":1}")
  ERR_CODE=$(echo "$SAME" | $PY "import sys,json; print(json.load(sys.stdin).get('domainCode',''))")
  [[ "$ERR_CODE" == "SAME_SOURCE_DESTINATION" || "$ERR_CODE" == "VALIDATION_ERROR" ]] && echo "✅ Step 12 — Same-store error returned" || { echo "❌ Step 12 FAILED (code=$ERR_CODE)"; exit 1; }

  # ── Step 13 — Unauthenticated access returns 401 ──────────────────────────────
  HTTP_401=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/stock/transfers")
  [[ "$HTTP_401" == "401" ]] && echo "✅ Step 13 — 401 on unauthenticated GET /stock/transfers" || { echo "❌ Step 13 FAILED (code=$HTTP_401)"; exit 1; }

  echo ""
  echo "✅✅✅ All cURL integration checks passed — Story 3.3 backend validated ✅✅✅"
  ```

---

### FLUTTER — Full TDD (Clean Architecture + Riverpod)

> **Feature location**: `lib/features/inventory/` — extends existing multi-store inventory feature (same as Story 3.2).
> New sub-feature: `lib/features/inventory/` → add `transfer/` sub-feature directory, following the catalog/domain/data/presentation split.

---

- [ ] **Task 11 — Domain model: StockTransferModel**

  - [ ] 11.1 — Write test FIRST in `test/features/inventory/domain/model/stock_transfer_model_test.dart`:
    ```dart
    test('StockTransferModel.fromJson should parse all fields correctly')
    test('StockTransferModel.isPendingSync should return true when status is PENDING_SYNC')
    test('StockTransferModel.isCompleted should return true when status is COMPLETED')
    ```
  - [ ] 11.2 — Create `lib/features/inventory/domain/model/stock_transfer_model.dart`:
    ```dart
    import 'package:freezed_annotation/freezed_annotation.dart';

    part 'stock_transfer_model.freezed.dart';
    part 'stock_transfer_model.g.dart';

    /// StockTransferModel — transfer record.
    /// Mirrors StockTransfer backend domain model.
    /// Story 3.3. GoF: Command value object.
    @freezed
    class StockTransferModel with _$StockTransferModel {
      const factory StockTransferModel({
        required String id,
        required String sourceStoreId,
        required String destinationStoreId,
        required String productId,
        String? variantId,
        required int quantity,
        required String actorId,
        required DateTime occurredAt,
        required String status,     // 'COMPLETED' | 'PENDING_SYNC' | 'CONFLICT'
        String? notes,
        // Denormalized display fields (populated by UI layer from local Drift)
        @Default('') String sourceStoreName,
        @Default('') String destinationStoreName,
        @Default('') String productName,
        @Default('') String variantLabel,
      }) = _StockTransferModel;

      factory StockTransferModel.fromJson(Map<String, dynamic> json) =>
          _$StockTransferModelFromJson(json);
    }

    extension StockTransferModelX on StockTransferModel {
      bool get isPendingSync => status == 'PENDING_SYNC';
      bool get isCompleted   => status == 'COMPLETED';
      bool get isConflict    => status == 'CONFLICT';
    }
    ```
    Run: `dart run build_runner build --delete-conflicting-outputs`

---

- [ ] **Task 12 — Domain: StockTransferRepository interface + usecase**

  - [ ] 12.1 — Create `lib/features/inventory/domain/repository/stock_transfer_repository.dart`:
    ```dart
    import '../model/stock_transfer_model.dart';

    /// StockTransferRepository — domain port for transfer operations.
    /// Story 3.3.
    abstract class StockTransferRepository {
      /// Execute a transfer. Returns the created transfer record.
      /// Throws [InsufficientStockException] if source quantity < requested.
      Future<StockTransferModel> executeTransfer({
        required String sourceStoreId,
        required String destinationStoreId,
        required String productId,
        String? variantId,
        required int quantity,
        String? notes,
      });

      /// Paginated transfer history with optional filters.
      Future<List<StockTransferModel>> getHistory({
        String? sourceStoreId,
        String? destinationStoreId,
        DateTime? from,
        DateTime? to,
        int page = 0,
        int size = 25,
      });
    }
    ```

  - [ ] 12.2 — Create `lib/features/inventory/domain/usecase/execute_transfer_usecase.dart`:
    ```dart
    import '../model/stock_transfer_model.dart';
    import '../repository/stock_transfer_repository.dart';

    class ExecuteTransferUseCase {
      final StockTransferRepository _repository;
      ExecuteTransferUseCase(this._repository);

      Future<StockTransferModel> execute({
        required String sourceStoreId,
        required String destinationStoreId,
        required String productId,
        String? variantId,
        required int quantity,
        String? notes,
      }) {
        if (sourceStoreId == destinationStoreId) {
          throw ArgumentError('sourceStoreId and destinationStoreId must differ');
        }
        if (quantity <= 0) {
          throw ArgumentError('quantity must be positive');
        }
        return _repository.executeTransfer(
          sourceStoreId: sourceStoreId,
          destinationStoreId: destinationStoreId,
          productId: productId,
          variantId: variantId,
          quantity: quantity,
          notes: notes,
        );
      }
    }
    ```

  - [ ] 12.3 — Write tests FIRST in `test/features/inventory/domain/usecase/execute_transfer_usecase_test.dart`:
    ```dart
    test('throws ArgumentError when sourceStoreId == destinationStoreId')
    test('throws ArgumentError when quantity <= 0')
    test('calls repository.executeTransfer with correct params when valid')
    test('returns StockTransferModel from repository on success')
    ```

---

- [ ] **Task 13 — Data layer: Drift table + local/remote datasources**

  - [ ] 13.1 — Create `lib/core/storage/stock_transfers_table.dart`:
    ```dart
    import 'package:drift/drift.dart';

    /// StockTransfers — Drift table for local transfer records.
    /// Schema version 8. Story 3.3.
    class StockTransfers extends Table {
      TextColumn get id                  => text()();
      TextColumn get sourceStoreId       => text()();
      TextColumn get destinationStoreId  => text()();
      TextColumn get productId           => text()();
      TextColumn get variantId           => text().nullable()();
      IntColumn  get quantity            => integer()();
      TextColumn get actorId             => text()();
      DateTimeColumn get occurredAt      => dateTime()();
      TextColumn get status              => text().withDefault(const Constant('COMPLETED'))();
      TextColumn get notes               => text().nullable()();
      // Denormalized display fields for offline history rendering
      TextColumn get sourceStoreName     => text().withDefault(const Constant(''))();
      TextColumn get destinationStoreName => text().withDefault(const Constant(''))();
      TextColumn get productName         => text().withDefault(const Constant(''))();

      @override
      Set<Column> get primaryKey => {id};
    }
    ```

  - [ ] 13.2 — Update `app_database.dart`:
    - Import `stock_transfers_table.dart`
    - Add `StockTransfers` to `@DriftDatabase(tables: [...])`
    - Bump `schemaVersion` to `8`
    - Add migration `if (from < 8)` → `await migrator.createTable(stockTransfers);`
    ```dart
    /// Schema version 8: stock_transfers table added (Story 3.3).
    int get schemaVersion => 8;
    ...
    if (from < 8) {
      await migrator.createTable(stockTransfers);
    }
    ```
    - Run `dart run build_runner build --delete-conflicting-outputs`

  - [ ] 13.3 — Write test FIRST in `test/features/inventory/data/datasource/local_stock_transfer_datasource_test.dart`:
    ```dart
    test('insertTransfer should persist to Drift')
    test('getHistory should return transfers in reverse chronological order')
    test('getHistory should filter by sourceStoreId when provided')
    test('updateLocalStock should decrement source and increment destination')
    ```
  - [ ] 13.4 — Create `lib/features/inventory/data/datasource/local_stock_transfer_datasource.dart`:
    ```dart
    import 'package:drift/drift.dart';
    import '../../../../core/storage/app_database.dart';
    import '../../domain/model/stock_transfer_model.dart';

    /// LocalStockTransferDataSource — Drift-backed offline transfer operations.
    ///
    /// Handles:
    /// - Persisting transfer records locally
    /// - Updating stock_levels atomically (decrement source, increment destination)
    /// - Storing pending transfers in sync_queue
    /// Story 3.3.
    class LocalStockTransferDataSource {
      final AppDatabase _db;
      LocalStockTransferDataSource(this._db);

      /// Insert a transfer record locally and update stock levels.
      /// Called when: (a) offline transfer confirmed, (b) server response upsert.
      Future<StockTransferModel> upsertTransfer(StockTransferModel model) async {
        await _db.into(_db.stockTransfers).insertOnConflictUpdate(
          StockTransfersCompanion.insert(
            id: model.id,
            sourceStoreId: model.sourceStoreId,
            destinationStoreId: model.destinationStoreId,
            productId: model.productId,
            variantId: Value(model.variantId),
            quantity: model.quantity,
            actorId: model.actorId,
            occurredAt: model.occurredAt,
            status: Value(model.status),
            notes: Value(model.notes),
            sourceStoreName: Value(model.sourceStoreName),
            destinationStoreName: Value(model.destinationStoreName),
            productName: Value(model.productName),
          ),
        );
        return model;
      }

      /// Update local Drift stock_levels for offline transfer.
      /// Decrement source, increment destination atomically in a transaction.
      Future<void> applyLocalStockChange({
        required String productId,
        String? variantId,
        required String sourceStoreId,
        required String destinationStoreId,
        required int quantity,
      }) async {
        await _db.transaction(() async {
          // Decrement source
          await (_db.update(_db.stockLevels)
            ..where((sl) => sl.productId.equals(productId) &
                            sl.storeId.equals(sourceStoreId)))
            .write(StockLevelsCompanion.custom(
              quantity: _db.stockLevels.quantity - Variable(quantity),
              updatedAt: Variable(DateTime.now()),
            ));
          // Increment destination (upsert: create row at 0 if not exists, then add)
          await _db.customUpdate(
            '''
            INSERT INTO stock_levels (id, product_id, variant_id, store_id, quantity, minimum_threshold, updated_at)
            VALUES (lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
                    substr(lower(hex(randomblob(2))),2) || '-' ||
                    substr('89ab', abs(random()) % 4 + 1, 1) ||
                    substr(lower(hex(randomblob(2))),2) || '-' || lower(hex(randomblob(6))),
                    ?, ?, ?, ?, 0, ?)
            ON CONFLICT(product_id, store_id) DO UPDATE
               SET quantity = quantity + ?, updated_at = ?
            ''',
            variables: [
              Variable(productId), Variable(variantId),
              Variable(destinationStoreId), Variable(quantity),
              Variable(DateTime.now()),
              Variable(quantity), Variable(DateTime.now()),
            ],
            updates: {_db.stockLevels},
          );
        });
      }

      Future<List<StockTransferModel>> getHistory({
        String? sourceStoreId,
        String? destinationStoreId,
        DateTime? from,
        DateTime? to,
        int page = 0,
        int size = 25,
      }) async {
        var query = _db.select(_db.stockTransfers);
        query.orderBy([(t) => OrderingTerm.desc(t.occurredAt)]);
        if (sourceStoreId != null)
          query.where((t) => t.sourceStoreId.equals(sourceStoreId));
        if (destinationStoreId != null)
          query.where((t) => t.destinationStoreId.equals(destinationStoreId));
        if (from != null)
          query.where((t) => t.occurredAt.isBiggerOrEqualValue(from));
        if (to != null)
          query.where((t) => t.occurredAt.isSmallerOrEqualValue(to));
        query.limit(size, offset: page * size);
        final rows = await query.get();
        return rows.map(_rowToModel).toList();
      }

      StockTransferModel _rowToModel(StockTransfer row) => StockTransferModel(
        id: row.id,
        sourceStoreId: row.sourceStoreId,
        destinationStoreId: row.destinationStoreId,
        productId: row.productId,
        variantId: row.variantId,
        quantity: row.quantity,
        actorId: row.actorId,
        occurredAt: row.occurredAt,
        status: row.status,
        notes: row.notes,
        sourceStoreName: row.sourceStoreName,
        destinationStoreName: row.destinationStoreName,
        productName: row.productName,
      );
    }
    ```

  - [ ] 13.5 — Write test FIRST in `test/features/inventory/data/datasource/remote_stock_transfer_datasource_test.dart`:
    ```dart
    test('executeTransfer should POST to /api/v1/stock/transfers')
    test('executeTransfer should throw InsufficientStockException on 422 INSUFFICIENT_STOCK')
    test('getHistory should GET /api/v1/stock/transfers with correct query params')
    ```
  - [ ] 13.6 — Create `lib/features/inventory/data/datasource/remote_stock_transfer_datasource.dart`:
    ```dart
    import 'package:dio/dio.dart';
    import '../../domain/model/stock_transfer_model.dart';

    class RemoteStockTransferDataSource {
      final Dio _dio;
      RemoteStockTransferDataSource({required Dio dio}) : _dio = dio;

      Future<StockTransferModel> executeTransfer({
        required String sourceStoreId,
        required String destinationStoreId,
        required String productId,
        String? variantId,
        required int quantity,
        String? notes,
      }) async {
        final payload = {
          'sourceStoreId': sourceStoreId,
          'destinationStoreId': destinationStoreId,
          'productId': productId,
          if (variantId != null) 'variantId': variantId,
          'quantity': quantity,
          if (notes != null) 'notes': notes,
        };
        final resp = await _dio.post<Map<String, dynamic>>(
          '/api/v1/stock/transfers',
          data: payload,
        );
        return StockTransferModel.fromJson(
            resp.data!['data'] as Map<String, dynamic>);
      }

      Future<List<StockTransferModel>> getHistory({
        String? sourceStoreId,
        String? destinationStoreId,
        DateTime? from,
        DateTime? to,
        int page = 0,
        int size = 25,
      }) async {
        final resp = await _dio.get<Map<String, dynamic>>(
          '/api/v1/stock/transfers',
          queryParameters: {
            if (sourceStoreId != null) 'source': sourceStoreId,
            if (destinationStoreId != null) 'dest': destinationStoreId,
            if (from != null) 'from': from.toUtc().toIso8601String(),
            if (to != null) 'to': to.toUtc().toIso8601String(),
            'page': page,
            'size': size,
          },
        );
        final pageData = resp.data!['data'] as Map<String, dynamic>;
        final List<dynamic> content = pageData['content'] as List;
        return content
            .map((e) => StockTransferModel.fromJson(e as Map<String, dynamic>))
            .toList();
      }
    }
    ```

  - [ ] 13.7 — Write test FIRST in `test/features/inventory/data/repository/stock_transfer_repository_impl_test.dart`:
    ```dart
    test('executeTransfer online: calls remote, then upserts locally, returns model')
    test('executeTransfer offline: updates local stock, queues to sync_queue, returns PENDING_SYNC model')
    test('getHistory: returns local data when offline')
    test('getHistory: merges remote into local when online')
    ```
  - [ ] 13.8 — Create `lib/features/inventory/data/repository/stock_transfer_repository_impl.dart`:
    ```dart
    import 'package:dio/dio.dart';
    import 'package:uuid/uuid.dart';
    import 'dart:convert';

    import '../../../../core/network/connectivity_provider.dart';
    import '../../../../core/storage/app_database.dart';
    import '../../domain/model/stock_transfer_model.dart';
    import '../../domain/repository/stock_transfer_repository.dart';
    import '../datasource/local_stock_transfer_datasource.dart';
    import '../datasource/remote_stock_transfer_datasource.dart';

    /// StockTransferRepositoryImpl — offline-first transfer repository.
    ///
    /// Online:  POST to server → upsert locally → return COMPLETED model.
    /// Offline: validate local stock → update local stock_levels → queue to sync_queue
    ///          → return PENDING_SYNC model.
    ///
    /// Story 3.3.
    class StockTransferRepositoryImpl implements StockTransferRepository {
      final LocalStockTransferDataSource _local;
      final RemoteStockTransferDataSource _remote;
      final AppDatabase _db;
      final bool Function() _isOnline; // injected connectivity check

      StockTransferRepositoryImpl({
        required LocalStockTransferDataSource local,
        required RemoteStockTransferDataSource remote,
        required AppDatabase db,
        required bool Function() isOnline,
      })  : _local = local,
            _remote = remote,
            _db = db,
            _isOnline = isOnline;

      @override
      Future<StockTransferModel> executeTransfer({
        required String sourceStoreId,
        required String destinationStoreId,
        required String productId,
        String? variantId,
        required int quantity,
        String? notes,
      }) async {
        if (_isOnline()) {
          // Online path: delegate to server
          final model = await _remote.executeTransfer(
            sourceStoreId: sourceStoreId,
            destinationStoreId: destinationStoreId,
            productId: productId,
            variantId: variantId,
            quantity: quantity,
            notes: notes,
          );
          // Write-through cache
          await _local.upsertTransfer(model);
          await _local.applyLocalStockChange(
            productId: productId,
            variantId: variantId,
            sourceStoreId: sourceStoreId,
            destinationStoreId: destinationStoreId,
            quantity: quantity,
          );
          return model;
        } else {
          // Offline path: local validation + optimistic update
          final srcStock = await _getLocalStock(productId, variantId, sourceStoreId);
          if (srcStock < quantity) {
            throw DioException(
              requestOptions: RequestOptions(path: ''),
              type: DioExceptionType.unknown,
              message: 'INSUFFICIENT_STOCK:available=$srcStock:requested=$quantity',
            );
          }
          await _local.applyLocalStockChange(
            productId: productId,
            variantId: variantId,
            sourceStoreId: sourceStoreId,
            destinationStoreId: destinationStoreId,
            quantity: quantity,
          );
          final id = const Uuid().v4();
          final model = StockTransferModel(
            id: id,
            sourceStoreId: sourceStoreId,
            destinationStoreId: destinationStoreId,
            productId: productId,
            variantId: variantId,
            quantity: quantity,
            actorId: '',   // actorId resolved from auth state in provider
            occurredAt: DateTime.now(),
            status: 'PENDING_SYNC',
            notes: notes,
          );
          await _local.upsertTransfer(model);
          // Enqueue for sync
          await _db.into(_db.syncQueue).insert(SyncQueueCompanion.insert(
            id: id,
            operation: 'STOCK_TRANSFER',
            payload: jsonEncode({
              'sourceStoreId': sourceStoreId,
              'destinationStoreId': destinationStoreId,
              'productId': productId,
              if (variantId != null) 'variantId': variantId,
              'quantity': quantity,
              if (notes != null) 'notes': notes,
            }),
            createdAt: DateTime.now(),
          ));
          return model;
        }
      }

      @override
      Future<List<StockTransferModel>> getHistory({
        String? sourceStoreId,
        String? destinationStoreId,
        DateTime? from,
        DateTime? to,
        int page = 0,
        int size = 25,
      }) async {
        if (_isOnline()) {
          try {
            final remoteList = await _remote.getHistory(
              sourceStoreId: sourceStoreId,
              destinationStoreId: destinationStoreId,
              from: from, to: to, page: page, size: size,
            );
            for (final t in remoteList) {
              await _local.upsertTransfer(t);
            }
          } catch (_) {
            // Fallback to local on network error
          }
        }
        return _local.getHistory(
          sourceStoreId: sourceStoreId,
          destinationStoreId: destinationStoreId,
          from: from, to: to, page: page, size: size,
        );
      }

      Future<int> _getLocalStock(
          String productId, String? variantId, String storeId) async {
        final query = _db.select(_db.stockLevels);
        query
          ..where((sl) =>
              sl.productId.equals(productId) & sl.storeId.equals(storeId));
        final rows = await query.get();
        return rows.isEmpty ? 0 : rows.first.quantity;
      }
    }
    ```

---

- [ ] **Task 14 — Presentation: providers**

  - [ ] 14.1 — Write test FIRST in `test/features/inventory/presentation/provider/stock_transfer_provider_test.dart`:
    ```dart
    test('executeTransfer_notifier: emits loading then success state')
    test('executeTransfer_notifier: emits error state on INSUFFICIENT_STOCK')
    test('transferHistoryProvider: returns list from repository')
    ```
  - [ ] 14.2 — Create `lib/features/inventory/presentation/provider/stock_transfer_provider.dart`:
    ```dart
    import 'package:flutter_riverpod/flutter_riverpod.dart';
    import 'package:riverpod_annotation/riverpod_annotation.dart';

    import '../../../../core/di/providers.dart';
    import '../../../../core/network/connectivity_provider.dart';
    import '../../data/datasource/local_stock_transfer_datasource.dart';
    import '../../data/datasource/remote_stock_transfer_datasource.dart';
    import '../../data/repository/stock_transfer_repository_impl.dart';
    import '../../domain/model/stock_transfer_model.dart';
    import '../../domain/repository/stock_transfer_repository.dart';

    part 'stock_transfer_provider.g.dart';

    // ── Infrastructure ───────────────────────────────────────────────────────────

    final localStockTransferDsProvider =
        Provider<LocalStockTransferDataSource>((ref) {
      final db = ref.watch(appDatabaseProvider);
      return LocalStockTransferDataSource(db);
    });

    final remoteStockTransferDsProvider =
        Provider<RemoteStockTransferDataSource>((ref) {
      final dio = ref.watch(dioProvider);
      return RemoteStockTransferDataSource(dio: dio);
    });

    final stockTransferRepositoryProvider =
        Provider<StockTransferRepository>((ref) {
      final connectivity = ref.watch(connectivityProvider);
      return StockTransferRepositoryImpl(
        local:    ref.watch(localStockTransferDsProvider),
        remote:   ref.watch(remoteStockTransferDsProvider),
        db:       ref.watch(appDatabaseProvider),
        isOnline: () => connectivity.isOnline,
      );
    });

    // ── Transfer history ─────────────────────────────────────────────────────────

    @riverpod
    Future<List<StockTransferModel>> transferHistory(
        TransferHistoryRef ref, {
        String? sourceStoreId,
        String? destinationStoreId,
      }) {
      return ref.watch(stockTransferRepositoryProvider).getHistory(
        sourceStoreId: sourceStoreId,
        destinationStoreId: destinationStoreId,
      );
    }

    // ── Execute transfer (AsyncNotifier) ─────────────────────────────────────────

    @riverpod
    class ExecuteTransferNotifier extends _$ExecuteTransferNotifier {
      @override
      AsyncValue<StockTransferModel?> build() => const AsyncValue.data(null);

      Future<StockTransferModel?> execute({
        required String sourceStoreId,
        required String destinationStoreId,
        required String productId,
        String? variantId,
        required int quantity,
        String? notes,
      }) async {
        state = const AsyncValue.loading();
        state = await AsyncValue.guard(() =>
          ref.read(stockTransferRepositoryProvider).executeTransfer(
            sourceStoreId: sourceStoreId,
            destinationStoreId: destinationStoreId,
            productId: productId,
            variantId: variantId,
            quantity: quantity,
            notes: notes,
          ),
        );
        // On success, invalidate overview and history so they refresh
        if (state.hasValue && !state.hasError) {
          ref.invalidate(globalStockOverviewProvider);
          ref.invalidate(transferHistoryProvider);
        }
        return state.valueOrNull;
      }
    }
    ```
    Run: `dart run build_runner build --delete-conflicting-outputs`

---

- [ ] **Task 15 — Presentation: TransferFormBottomSheet (AC1, AC3)**

  - [ ] 15.1 — Write widget test FIRST in `test/features/inventory/presentation/widget/transfer_form_bottom_sheet_test.dart`:
    ```dart
    // Setup: GoogleFonts.config.allowRuntimeFetching = false in setUpAll()
    testWidgets('shows source, destination, product, quantity fields')
    testWidgets('Confirmer button is disabled when form is empty')
    testWidgets('shows inline error when source == destination is selected')
    testWidgets('shows inline error when quantity is 0')
    testWidgets('calls onTransferSubmitted with correct params on valid submit')
    ```
  - [ ] 15.2 — Create `lib/features/inventory/presentation/widget/transfer_form_bottom_sheet.dart`:
    ```dart
    /// TransferFormBottomSheet — modal form for executing a stock transfer.
    ///
    /// Shows: source dropdown, destination dropdown (excludes source),
    /// product search autocomplete, optional variant dropdown, quantity field.
    ///
    /// Story 3.3.
    ```
    Implementation notes:
    - Use `showModalBottomSheet` or `DraggableScrollableSheet` — full-screen on compact, sheet on expanded
    - Source and destination dropdowns use `StoreStockSummaryModel` list from `globalStockOverviewProvider`
    - Product search uses `LocalProductDataSource` for offline Drift-backed autocomplete
    - **Inline validation**: "Source et destination doivent être différentes" / "Stock insuffisant — disponible : X unités" (from `DioException` message parsing)
    - On submit: call `executeTransferNotifier.execute(...)`, watch state, show SnackBar on success

---

- [ ] **Task 16 — Presentation: TransferHistoryPage (AC5)**

  - [ ] 16.1 — Write widget test FIRST in `test/features/inventory/presentation/page/transfer_history_page_test.dart`:
    ```dart
    // GoogleFonts.config.allowRuntimeFetching = false in setUpAll()
    testWidgets('shows loading indicator while fetching history')
    testWidgets('shows list of transfers with source→destination, product, qty')
    testWidgets('shows PENDING_SYNC badge in amber for pending transfers')
    testWidgets('shows COMPLETED badge in green for completed transfers')
    testWidgets('shows empty state when history is empty')
    ```
  - [ ] 16.2 — Create `lib/features/inventory/presentation/page/transfer_history_page.dart`:
    ```dart
    /// TransferHistoryPage — paginated transfer history with filter bar.
    ///
    /// Route: /stock/transfers (tab within Stock module or pushed from FAB).
    /// Story 3.3.
    ```
    Implementation notes:
    - `ConsumerStatefulWidget` — watches `transferHistoryProvider`
    - Status badge: `✅ Effectué` (green), `⏳ En attente de sync` (amber), `❌ Conflit` (red)
    - Filter chip row: "Toutes boutiques" / "Source: X" / "Destination: Y" — tapping opens bottom sheet with store picker
    - Uses `ListView.builder` with `RefreshIndicator` (pull-to-refresh invalidates provider)
    - Empty state: illustration + "Aucun transfert pour le moment"

---

- [ ] **Task 17 — Router + NavigationBar integration**

  - [ ] 17.1 — Update `app_router.dart` — add route for `/stock/transfers`:
    ```dart
    GoRoute(
      path: '/stock/transfers',
      builder: (_, __) => const TransferHistoryPage(),
    ),
    ```
  - [ ] 17.2 — Update `GlobalStockOverviewPage` or `MainShell` — add "Nouveau transfert" FAB or navigation button in the Stock module.

    > Per UX Flow 6: entry point is `Stock > Transferts`. The GlobalStockOverviewPage app bar should gain a transfer icon (🔄) or a dedicated "Transferts" tab/segment.
    > Minimal approach: add an icon button in `GlobalStockOverviewPage` AppBar that navigates to `/stock/transfers`.

---

## Dev Notes

### Architecture compliance — critical rules

1. **No cross-module JPA entity exports**: `ExecuteTransferService` imports `StoreRepository` port (not `StoreJpaEntity`), `StockLevelRepository` port (not `StockLevelJpaEntity`). Never import JPA entities from other modules.
2. **TenantContext is ThreadLocal**: All JPA and JDBC operations run in the correct tenant schema automatically. No manual schema prefix needed in SQL.
3. **StockOperationService is the single entry point** for all stock mutations — do NOT directly update `stock_levels` from `ExecuteTransferService`. Always go through `StockOperationService.recordOperation()`.
4. **`@Transactional` on ExecuteTransferService.execute()**: The TRANSFER_OUT, TRANSFER_IN, and stock_transfers INSERT are a single database transaction. If any step fails, all roll back.
5. **DomainException `details` map**: the `INSUFFICIENT_STOCK` error must include `available` and `requested` keys for the Flutter client to display the inline error. See `DefaultTransferValidationStrategy`.
6. **SAME_SOURCE_DESTINATION** new error code: add to `ErrorCode.java` before `ExecuteTransferService`. The Spring `GlobalExceptionHandler` maps all `DomainException` to appropriate HTTP status codes; `SAME_SOURCE_DESTINATION` → HTTP 422.
7. **GlobalExceptionHandler HTTP mapping**: verify `STORE_NOT_ACTIVE` and `SAME_SOURCE_DESTINATION` are mapped. Add entries if missing:
   ```java
   case STORE_NOT_ACTIVE         -> HttpStatus.UNPROCESSABLE_ENTITY;
   case SAME_SOURCE_DESTINATION  -> HttpStatus.UNPROCESSABLE_ENTITY;
   ```
8. **`StockTransferredEvent` audit wiring**: the `AuditEventListener` (Story 1.8) reacts to all domain events. Add a `@EventListener(StockTransferredEvent.class)` handler if not auto-detected, or verify the existing listener handles it.

### Flutter offline-first rules

1. **Drift schema version 8**: bump `schemaVersion` to 8 in `app_database.dart`, add the migration block. Run `build_runner` after.
2. **`applyLocalStockChange` atomicity**: wrapped in `_db.transaction()` — Drift guarantees an SQLite transaction. The `insertOnConflictUpdate` pattern for destination stock is safe.
3. **Connectivity check**: use existing `connectivityProvider` from Story 1.5. Do not introduce a new connectivity abstraction.
4. **`sync_queue` payload contract**: the `STOCK_TRANSFER` operation payload must be a JSON object matching `TransferStockRequestDto` fields exactly so the future sync engine can replay it server-side.
5. **`invalidate` on success**: after a successful transfer (online or offline), `ref.invalidate(globalStockOverviewProvider)` and `ref.invalidate(transferHistoryProvider)` to force UI refresh.
6. **`build_runner`**: after creating/modifying any `@freezed` model, always run:
   ```bash
   cd keevo/app && dart run build_runner build --delete-conflicting-outputs
   ```

### Database schema notes

- New backend table: `stock_transfers` in each tenant schema (provisioned by `TenantSchemaProvisioner`).
- No breaking change to existing tables (`stock_levels`, `stock_movements` already support `TRANSFER_IN`/`TRANSFER_OUT`).
- `stock_transfers.actor_id` stores `UUID` — same pattern as `stock_movements.actor_id`.
- For the existing `stock_movements` table: `TRANSFER_IN` and `TRANSFER_OUT` values were already defined in `MovementType` enum (Story 2.3) and in the CHECK constraint DDL. No DDL change needed there.

### UX alignment

- **UX Flow 6**: `Stock > Transferts → Boutique source → Boutique destination → Produits + quantités → Confirmer → Stock source réduit → Stock destination augmenté → Tracé dans historique`
- **Route**: `/stock/transfers` (history page with FAB), form is a bottom sheet pushed on FAB tap
- **Entry point**: Add a "Transferts" icon (🔄) button in `GlobalStockOverviewPage` AppBar OR as a segment in the Stock module nav.
- **Success SnackBar**: "Transfert effectué : [qty] × [product] → [destination store]" — green, 4 seconds
- **Offline badge**: "⏳ En attente de sync" — amber `Chip` in `TransferHistoryPage`
- **Status colors**: COMPLETED → `Colors.green`, PENDING_SYNC → `Colors.amber`, CONFLICT → `Colors.red`

### Project Structure Notes

**Backend new files:**
- `catalog/stock/domain/model/StockTransfer.java`
- `catalog/stock/domain/event/StockTransferredEvent.java`
- `catalog/stock/domain/port/in/TransferStockCommand.java`
- `catalog/stock/domain/port/in/TransferStockUseCase.java`
- `catalog/stock/domain/port/in/GetTransferHistoryQuery.java`
- `catalog/stock/domain/port/in/GetTransferHistoryUseCase.java`
- `catalog/stock/domain/port/out/StockTransferRepository.java`
- `catalog/stock/domain/service/TransferValidationStrategy.java`
- `catalog/stock/domain/service/DefaultTransferValidationStrategy.java`
- `catalog/stock/application/usecase/ExecuteTransferService.java`
- `catalog/stock/application/usecase/GetTransferHistoryService.java`
- `shared/infrastructure/persistence/entity/StockTransferJpaEntity.java`
- `catalog/stock/adapter/out/persistence/StockTransferSpringRepository.java`
- `catalog/stock/adapter/out/persistence/StockTransferRepositoryAdapter.java`
- `catalog/stock/adapter/in/web/dto/TransferStockRequestDto.java`
- `catalog/stock/adapter/in/web/dto/StockTransferResponseDto.java`
- `catalog/stock/adapter/in/web/StockTransferController.java`

**Backend modified files:**
- `shared/domain/exception/ErrorCode.java` — add `STORE_NOT_ACTIVE`, `SAME_SOURCE_DESTINATION`
- `shared/infrastructure/persistence/TenantSchemaProvisioner.java` — add `stock_transfers` DDL
- `shared/infrastructure/web/GlobalExceptionHandler.java` — map new error codes if needed

**Backend new test files:**
- `catalog/stock/domain/model/StockTransferTest.java`
- `catalog/stock/domain/service/TransferValidationStrategyTest.java`
- `catalog/stock/domain/port/out/StockTransferRepositoryContractTest.java`
- `catalog/stock/application/usecase/ExecuteTransferServiceTest.java`
- `catalog/stock/application/usecase/GetTransferHistoryServiceTest.java`
- `catalog/stock/adapter/out/persistence/StockTransferRepositoryAdapterTest.java`
- `catalog/stock/adapter/in/web/StockTransferControllerTest.java`

**Flutter new files:**
- `lib/features/inventory/domain/model/stock_transfer_model.dart` + `.freezed.dart` + `.g.dart`
- `lib/features/inventory/domain/repository/stock_transfer_repository.dart`
- `lib/features/inventory/domain/usecase/execute_transfer_usecase.dart`
- `lib/core/storage/stock_transfers_table.dart`
- `lib/features/inventory/data/datasource/local_stock_transfer_datasource.dart`
- `lib/features/inventory/data/datasource/remote_stock_transfer_datasource.dart`
- `lib/features/inventory/data/repository/stock_transfer_repository_impl.dart`
- `lib/features/inventory/presentation/provider/stock_transfer_provider.dart` + `.g.dart`
- `lib/features/inventory/presentation/widget/transfer_form_bottom_sheet.dart`
- `lib/features/inventory/presentation/page/transfer_history_page.dart`

**Flutter modified files:**
- `lib/core/storage/app_database.dart` — add `StockTransfers` table, bump to schema v8
- `lib/core/router/app_router.dart` — add `/stock/transfers` route
- `lib/features/inventory/presentation/page/global_stock_overview_page.dart` — add transfer FAB/icon

**Test files (Flutter):**
- `test/features/inventory/domain/model/stock_transfer_model_test.dart`
- `test/features/inventory/domain/usecase/execute_transfer_usecase_test.dart`
- `test/features/inventory/data/datasource/local_stock_transfer_datasource_test.dart`
- `test/features/inventory/data/datasource/remote_stock_transfer_datasource_test.dart`
- `test/features/inventory/data/repository/stock_transfer_repository_impl_test.dart`
- `test/features/inventory/presentation/provider/stock_transfer_provider_test.dart`
- `test/features/inventory/presentation/widget/transfer_form_bottom_sheet_test.dart`
- `test/features/inventory/presentation/page/transfer_history_page_test.dart`

**Script:**
- `keevo/scripts/curl-tests-story-3-3.sh`

### References

- [Epic 3.3 — Acceptance Criteria](../_bmad-output/planning-artifacts/epics/epic-3-gestion-multi-boutiques-stock-quipe.md#story-33)
- [UX Flow 6 — Transfert Stock Inter-Boutiques](../_bmad-output/planning-artifacts/ux-design-specification.md#flow-6)
- [Architecture — Backend Hexagonal Layout](../_bmad-output/planning-artifacts/architecture.md#backend-architecture)
- [Architecture — GoF Mandatory Analysis](../_bmad-output/planning-artifacts/architecture.md#gof-design-pattern-analysis)
- [Architecture — TDD Strict Rules](../_bmad-output/planning-artifacts/architecture.md#full-tdd)
- Previous story: [3-2-vue-centralisee-des-stocks-multi-boutiques.md](3-2-vue-centralisee-des-stocks-multi-boutiques.md) — StockOperationService, MultiStoreStockRepositoryAdapter, GlobalStockOverviewPage patterns
- Existing `StockOperationService` — single entry point for all stock mutations (TRANSFER_OUT/TRANSFER_IN)
- Existing `StockLevelRepository`, `StockMovementRepository` ports — reused unchanged
- Existing `StoreRepository` port — reused for store validation
- Existing `MovementType.TRANSFER_IN`, `MovementType.TRANSFER_OUT` — already defined, no change needed
- Existing `ErrorCode.INSUFFICIENT_STOCK`, `ErrorCode.STORE_NOT_FOUND` — reused
- Drift `SyncQueueTable` — `operation: 'STOCK_TRANSFER'`, `payload: JSON`

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6

### Debug Log References

### Completion Notes List

### File List

**Backend (production):**
- `src/main/java/com/keevo/catalog/stock/domain/model/StockTransfer.java`
- `src/main/java/com/keevo/catalog/stock/domain/event/StockTransferredEvent.java`
- `src/main/java/com/keevo/catalog/stock/domain/port/in/TransferStockCommand.java`
- `src/main/java/com/keevo/catalog/stock/domain/port/in/TransferStockUseCase.java`
- `src/main/java/com/keevo/catalog/stock/domain/port/in/GetTransferHistoryQuery.java`
- `src/main/java/com/keevo/catalog/stock/domain/port/in/GetTransferHistoryUseCase.java`
- `src/main/java/com/keevo/catalog/stock/domain/port/out/StockTransferRepository.java`
- `src/main/java/com/keevo/catalog/stock/domain/service/TransferValidationStrategy.java`
- `src/main/java/com/keevo/catalog/stock/domain/service/DefaultTransferValidationStrategy.java`
- `src/main/java/com/keevo/catalog/stock/application/usecase/ExecuteTransferService.java`
- `src/main/java/com/keevo/catalog/stock/application/usecase/GetTransferHistoryService.java`
- `src/main/java/com/keevo/shared/infrastructure/persistence/entity/StockTransferJpaEntity.java`
- `src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockTransferSpringRepository.java`
- `src/main/java/com/keevo/catalog/stock/adapter/out/persistence/StockTransferRepositoryAdapter.java`
- `src/main/java/com/keevo/catalog/stock/adapter/in/web/dto/TransferStockRequestDto.java`
- `src/main/java/com/keevo/catalog/stock/adapter/in/web/dto/StockTransferResponseDto.java`
- `src/main/java/com/keevo/catalog/stock/adapter/in/web/StockTransferController.java`
- `src/main/java/com/keevo/shared/domain/exception/ErrorCode.java` *(modified)*
- `src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` *(modified)*

**Backend (tests):**
- `src/test/java/com/keevo/catalog/stock/domain/model/StockTransferTest.java`
- `src/test/java/com/keevo/catalog/stock/domain/service/TransferValidationStrategyTest.java`
- `src/test/java/com/keevo/catalog/stock/domain/port/out/StockTransferRepositoryContractTest.java`
- `src/test/java/com/keevo/catalog/stock/application/usecase/ExecuteTransferServiceTest.java`
- `src/test/java/com/keevo/catalog/stock/application/usecase/GetTransferHistoryServiceTest.java`
- `src/test/java/com/keevo/catalog/stock/adapter/out/persistence/StockTransferRepositoryAdapterTest.java`
- `src/test/java/com/keevo/catalog/stock/adapter/in/web/StockTransferControllerTest.java`

**Flutter (production):**
- `lib/features/inventory/domain/model/stock_transfer_model.dart`
- `lib/features/inventory/domain/model/stock_transfer_model.freezed.dart` *(generated)*
- `lib/features/inventory/domain/model/stock_transfer_model.g.dart` *(generated)*
- `lib/features/inventory/domain/repository/stock_transfer_repository.dart`
- `lib/features/inventory/domain/usecase/execute_transfer_usecase.dart`
- `lib/core/storage/stock_transfers_table.dart`
- `lib/features/inventory/data/datasource/local_stock_transfer_datasource.dart`
- `lib/features/inventory/data/datasource/remote_stock_transfer_datasource.dart`
- `lib/features/inventory/data/repository/stock_transfer_repository_impl.dart`
- `lib/features/inventory/presentation/provider/stock_transfer_provider.dart`
- `lib/features/inventory/presentation/provider/stock_transfer_provider.g.dart` *(generated)*
- `lib/features/inventory/presentation/widget/transfer_form_bottom_sheet.dart`
- `lib/features/inventory/presentation/page/transfer_history_page.dart`
- `lib/core/storage/app_database.dart` *(modified — schema v8)*
- `lib/core/storage/app_database.g.dart` *(regenerated)*
- `lib/core/router/app_router.dart` *(modified)*
- `lib/features/inventory/presentation/page/global_stock_overview_page.dart` *(modified — transfer entry point)*

**Flutter (tests):**
- `test/features/inventory/domain/model/stock_transfer_model_test.dart`
- `test/features/inventory/domain/usecase/execute_transfer_usecase_test.dart`
- `test/features/inventory/data/datasource/local_stock_transfer_datasource_test.dart`
- `test/features/inventory/data/datasource/remote_stock_transfer_datasource_test.dart`
- `test/features/inventory/data/repository/stock_transfer_repository_impl_test.dart`
- `test/features/inventory/presentation/provider/stock_transfer_provider_test.dart`
- `test/features/inventory/presentation/widget/transfer_form_bottom_sheet_test.dart`
- `test/features/inventory/presentation/page/transfer_history_page_test.dart`

**Scripts:**
- `keevo/scripts/curl-tests-story-3-3.sh`

---

## Architecture as Implemented — Flutter (2026-03-15)

> Section de référence post-livraison. Documente l'architecture finale telle que codée, incluant les décisions prises pendant l'implémentation et les correctifs appliqués après la livraison initiale.

---

### 1. Architecture générale du module transfert Flutter

```
Presentation Layer
  TransferHistoryPage (ConsumerStatefulWidget)
    └─ _FilterBar            (StatelessWidget) — chips status × filtre boutique active
    └─ _TransferTile         (ConsumerStatefulWidget) — reçoit 1 transfert, action Réceptionner
    └─ showTransferFormBottomSheet() → _TransferFormBottomSheet (ConsumerStatefulWidget)

Provider Layer
  transferHistoryProvider       (@riverpod AutoDispose — liste enrichie)
  stockTransferRepositoryProvider (Provider keepAlive — repository singleton)
  ExecuteTransferNotifier       (@riverpod AutoDispose — non utilisé par l'UI, réservé tests/CLI)
  CompleteTransferNotifier      (@riverpod AutoDispose — non utilisé par l'UI, réservé tests/CLI)

Data Layer
  StockTransferRepositoryImpl   (offline-first: remote-first online, local-first offline)
  RemoteStockTransferDataSource (Dio → /api/v1/stock/transfers)
  LocalStockTransferDataSource  (Drift — stock_transfers + stock_levels)
```

---

### 2. `_TransferFormBottomSheet` — Dual-mode form

Le formulaire opère en **deux modes** déterminés par le paramètre `destinationStores` :

| Mode | Déclencheur | Champs affichés |
|------|------------|-----------------|
| **Self-load** (`_selfLoadMode = true`) | FAB "Nouveau transfert" (destinationStores vide) | Produit (dropdown) + Boutique source (dropdown) + Boutique destination (dropdown) |
| **Contextuel** (`_selfLoadMode = false`) | Bouton depuis la fiche stock produit (destinationStores pré-rempli) | Info row source + Info row produit + Boutique destination (dropdown) |

**Exclusion source=destination :** Dans les deux modes, la liste destination est filtrée :
```dart
final destinationStores = allStores.where((s) => s.id != _effectiveSourceId).toList();
```

**Validation :** `_formKey.validate()` + guards null sur `_selectedDestination`, `_selectedSource` (self-load), `_selectedProduct` (self-load) avant tout appel réseau.

**Gestion `INSUFFICIENT_STOCK` (AC3) :** Le `domainCode` du backend est inspecté ; si `INSUFFICIENT_STOCK`, l'erreur est affichée **inline sous le champ quantité** (pas dans la bannière globale).

---

### 3. `_submit()` — Flux d'envoi de transfert (Step 1)

> Correspond à AC2 Step 1. Le backend renvoie `201 IN_TRANSIT`. Le tile apparaît immédiatement dans l'historique avec le bouton "Réceptionner".

**Pattern adopté :** appel direct au repository (bypass du notifier AutoDispose).

```dart
// ✅ Pattern retenu — stable
final transfer = await ref
    .read(stockTransferRepositoryProvider)  // keepAlive provider → jamais disposed
    .executeTransfer(sourceStoreId: ..., destinationStoreId: ..., productId: ..., quantity: ...);
```

**Pourquoi pas le notifier :**
`ExecuteTransferNotifier` est `@riverpod` (AutoDispose). Quand `ref.invalidate()` est appelé après la réponse, Riverpod déclenche un rebuild qui dispose le notifier avant que `state = result` ne soit exécuté → `Bad state: Future already completed`.

**Séquence complète :**
1. Guard `_isSubmitting` → spinner
2. `_formKey.currentState!.validate()` → inline errors
3. `stockTransferRepositoryProvider.executeTransfer(...)` → await
4. `ref.invalidate(×6 providers)` → cache bust cascade
5. `Navigator.pop(transfer.id)` + SnackBar succès
6. `DioException catch` → `INSUFFICIENT_STOCK` inline sur champ qty / autres → bannière
7. `finally setState(() => _isSubmitting = false)`

---

### 4. `_receive()` — Flux de réception de transfert (Step 2)

> Correspond à AC7. Le bouton "Réceptionner le stock" n'est visible que sur les tiles `IN_TRANSIT`. Le backend renvoie `200 COMPLETED`.

**Pattern adopté :** même bypass repository + guard `_isReceiving` contre double-tap.

```dart
// Guard double-tap (ConsumerStatefulWidget local state)
if (_isReceiving) return;
setState(() => _isReceiving = true);

await ref.read(stockTransferRepositoryProvider).completeTransfer(widget.transfer.id);
// ... invalidations ...
finally { if (mounted) setState(() => _isReceiving = false); }
```

**Gestion `TRANSFER_INVALID_STATUS` :** Le transfert peut déjà être `COMPLETED` par une autre session. Dans ce cas le backend répond 500 + `domainCode: TRANSFER_INVALID_STATUS`. On invalide silencieusement le cache (le tile disparaît, pas de SnackBar d'erreur) :
```dart
if (domainCode == 'TRANSFER_INVALID_STATUS') {
  ref.invalidate(transferHistoryProvider);
  return;
}
```

---

### 5. `TransferHistoryPage` — Double filtrage

L'historique applique deux filtres indépendants en cascade :

```
transferHistoryProvider(page: 0, pageSize: 20)
        ↓ données brutes (toutes boutiques, tous statuts)
1. Filtre boutique active (store filter)
   activeStoreId == null → aucun filtre (vue "Toutes les boutiques")
   activeStoreId != null → WHERE destinationStoreId == activeStoreId
        ↓ transferts entrants de la boutique active
2. Filtre statut (chips _FilterBar)
   null → tous statuts
   'IN_TRANSIT' | 'COMPLETED' | 'PENDING_SYNC' | 'CONFLICT' → WHERE status == selected
        ↓
   ListView des _TransferTile filtrés
```

**Rationale du filtre destination-only :** Quand Simon est dans la boutique Cosmos, il ne voit que les transferts **entrants** (stock à réceptionner). Les transferts sortants relèvent de la boutique source. Une vue "Toutes boutiques" (activeStoreId = null) affiche tout sans restriction.

**Pull-to-refresh :** `RefreshIndicator → ref.invalidate(transferHistoryProvider)` — recharge depuis le remote.

---

### 6. `transferHistoryProvider` — Dénormalisation des noms

Le provider enrichit chaque `StockTransferModel` avec les noms de boutique et de produit (display fields) :

```dart
@riverpod
Future<List<StockTransferModel>> transferHistory(TransferHistoryRef ref, {...}) async {
  final transfers = await ref.watch(stockTransferRepositoryProvider).getHistory(...);
  // ref.READ (non watch) — évite AutoDispose race mid-await
  final stores   = await ref.read(storeListNotifierProvider.future);
  final products = await ref.read(productListForPickerProvider.future);
  final storeMap   = {for (final s in stores)   s.id: s.name};
  final productMap = {for (final p in products) p.id: p.name};
  return transfers.map((t) => t.copyWith(
    sourceStoreName:      t.sourceStoreName.isNotEmpty ? t.sourceStoreName : storeMap[t.sourceStoreId] ?? '',
    destinationStoreName: t.destinationStoreName.isNotEmpty ? t.destinationStoreName : storeMap[t.destinationStoreId] ?? '',
    productName:          t.productName.isNotEmpty ? t.productName : productMap[t.productId] ?? '',
  )).toList();
}
```

**Règle `ref.read` vs `ref.watch` dans les providers `@riverpod` async :** utiliser `ref.read` pour les futures secondaires dans un corps async afin d'éviter le race condition AutoDispose.

---

### 7. `StockTransferRepositoryImpl` — Stratégie offline-first

> API backend : `POST /api/v1/stock/transfers` (Step 1) et `POST /api/v1/stock/transfers/{id}/complete` (Step 2).

#### `executeTransfer` — Step 1

```
Online:
  1. remote.executeTransfer(...)         → StockTransferModel (IN_TRANSIT)
  2. local.saveTransfer(transfer)        → cache Drift stock_transfers
  3. local.applyLocalStockChange(...)    → write-through: Drift stock_levels
                                           (décrémente source, incrémente destination)

Offline:
  1. local.getLocalStock(productId, storeId=source) → int available
  2. if available < quantity → throw StateError('INSUFFICIENT_STOCK:...')
  3. local.applyLocalStockChange(...)    → Drift stock_levels mis à jour immédiatement
  4. db.into(syncQueue).insert(...)      → opération='STOCK_TRANSFER', payload JSON
  5. local.saveTransfer(PENDING_SYNC)    → visible dans historique avec badge ⏳
```

#### `completeTransfer` — Step 2

```
Online:  remote.completeTransfer(id)  →  POST /stock/transfers/{id}/complete
         → StockTransferModel (COMPLETED)
         sauvegarde local (status=COMPLETED)
         Note: local stock_levels destination N'EST PAS mis à jour ici.
               write-through local appliqué seulement à executeTransfer (step 1).
               Le next full sync harmonise via storeStockDetailProvider invalidé.
Offline: throw StateError('La réception nécessite une connexion active.')
         → UI affiche un message d'erreur, pas de SnackBar silencieux.
```

#### `getHistory`

```
Online:  remote.getHistory(...) → saveTransfer(chaque item) → retourne liste remote
         Fallback: si remote échoue → local.getHistory(...) (offline recovery)
Offline: local.getHistory(...) directement
```

---

### 8. `LocalStockTransferDataSource.applyLocalStockChange` — UPDATE-then-INSERT

La table Drift `StockLevels` a `primaryKey => {id}` uniquement — pas de `UNIQUE(product_id, store_id)`. SQLite refuse donc `ON CONFLICT(product_id, store_id) DO UPDATE`. Pattern retenu :

```dart
await _db.transaction(() async {
  // Source : décrémentation directe (la ligne existe toujours pour une source valide)
  await _db.customUpdate(
    'UPDATE stock_levels SET quantity = quantity - ?, updated_at = ? WHERE product_id = ? AND store_id = ?',
    variables: [qty, now, productId, sourceStoreId], updates: {_db.stockLevels},
  );
  // Destination : UPDATE d'abord, INSERT si aucune ligne
  final updated = await _db.customUpdate(
    'UPDATE stock_levels SET quantity = quantity + ?, updated_at = ? WHERE product_id = ? AND store_id = ?',
    variables: [qty, now, productId, destinationStoreId], updates: {_db.stockLevels},
  );
  if (updated == 0) {
    // Pas encore de stock pour ce produit dans cette boutique → créer la ligne
    await _db.into(_db.stockLevels).insert(StockLevelsCompanion.insert(
      id: 'sl-${productId.substring(0,8)}-${destinationStoreId.substring(0,8)}',
      productId: productId, storeId: destinationStoreId, quantity: quantity, updatedAt: now,
    ));
  }
});
```

---

### 9. Invalidation du cache — Cascade complète

Déclenchée dans `_submit()` (après executeTransfer) **et** dans `_receive()` (après completeTransfer) :

| Provider invalidé | Type | Impact |
|---|---|---|
| `transferHistoryProvider` | AutoDispose Future (family) | Recharge la liste des transferts |
| `globalStockOverviewProvider` | AutoDispose Future | Recharge la vue centralisée multi-boutiques |
| `storeStockDetailProvider` | AutoDispose Future **Family** | Recharge le détail stock de **toutes** les boutiques |
| `productListProvider` | AutoDispose Future | Recharge le catalogue produits |
| `productListForPickerProvider` | AutoDispose Future | Recharge le picker produit dans le formulaire |
| `stockNotifierProvider` | AsyncNotifier **Family** | Recharge le stock de **tous** les produits |

`ref.invalidate(familyProvider)` invalide toutes les instances de la famille en un seul appel — pas besoin de connaître les clés.

---

### 10. Palette de couleurs — UX spec "Indigo Sky"

Toutes les occurrences de `cs.tertiary`/`cs.tertiaryContainer` du module transfert ont été remplacées par la palette définie dans `app_theme.dart` :

| Rôle | Valeur hex | Utilisation |
|---|---|---|
| `colorPrimary` | `#3B5BDB` | Indigo royal — header gradient début, icônes, focus border, FAB |
| `primaryGradientEnd` | `#4DABF7` | Bleu ciel — header gradient fin |
| `colorSecondary` | `#FF6B6B` | Corail — accent secondaire |
| Dark `primary` | `#4DABF7` | Bleu ciel en mode sombre |

Gradient header (historique **et** formulaire) : `[cs.primary, Color(0xFF4DABF7)]`.

---

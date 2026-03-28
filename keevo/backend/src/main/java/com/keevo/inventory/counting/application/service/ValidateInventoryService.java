package com.keevo.inventory.counting.application.service;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.inventory.counting.domain.event.InventoryValidatedEvent;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.ValidateInventoryResult;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryCommand;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryUseCase;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ValidateInventoryService — Template Method: fixed sequence of steps.
 * 1. Load session (guard: must be IN_PROGRESS)
 * 2. Load all counts
 * 3. For each gap: call StockOperationService.recordOperation() (ADJUSTMENT)
 * 4. Validate session (State transition)
 * 5. Save session
 * 6. Publish InventoryValidatedEvent
 *
 * @Transactional ensures atomicity — all adjustments or rollback ALL.
 * Story 6.4.
 */
@Service
public class ValidateInventoryService implements ValidateInventoryUseCase {

    private final InventorySessionRepository sessionRepo;
    private final InventoryCountRepository countRepo;
    private final StockOperationService stockOperationService;
    private final StockLevelRepository stockLevelRepo;
    private final ApplicationEventPublisher eventPublisher;

    public ValidateInventoryService(InventorySessionRepository sessionRepo,
                                     InventoryCountRepository countRepo,
                                     StockOperationService stockOperationService,
                                     StockLevelRepository stockLevelRepo,
                                     ApplicationEventPublisher eventPublisher) {
        this.sessionRepo = sessionRepo;
        this.countRepo = countRepo;
        this.stockOperationService = stockOperationService;
        this.stockLevelRepo = stockLevelRepo;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public ValidateInventoryResult execute(ValidateInventoryCommand command) {
        // Step 1: Load session
        InventorySession session = sessionRepo.findById(command.sessionId())
                .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND,
                        "Session not found: " + command.sessionId()));

        // Guard: session must be IN_PROGRESS
        if (!session.getStatus().canValidate()) {
            throw new DomainException(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS,
                    "Cannot validate session in status: " + session.getStatus());
        }

        // Step 2: Load all counts for the session
        List<InventoryCount> counts = countRepo.findBySessionId(session.getId());

        // Step 3: Apply adjustments for products with gap ≠ 0
        int adjustmentsApplied = 0;
        for (InventoryCount count : counts) {
            if (!count.isCounted()) continue; // skip uncounted

            int ecart = count.getEcart(); // physical - theoretical (gap detection only)
            if (ecart == 0) continue;      // AC3: skip concordant products

            // Load CURRENT stock level for accurate delta (not theoretical snapshot)
            int currentQty = loadCurrentStockQuantity(
                    count.getProductId(), count.getVariantId(), session.getStoreId());
            int delta = count.getPhysical() - currentQty;
            if (delta == 0) continue; // stock already at physical (rare: concurrent adjustment)

            // Reuse StockOperationService — DO NOT reinvent stock mutation
            stockOperationService.recordOperation(
                    count.getProductId(),
                    count.getVariantId(),
                    session.getStoreId(),
                    MovementType.ADJUSTMENT,
                    delta,
                    command.actorId(),
                    "INVENTORY:" + session.getId()
            );
            adjustmentsApplied++;
        }

        // Step 4: Validate session (State pattern → VALIDATED + completedAt)
        session.validate();

        // Step 5: Save session
        sessionRepo.save(session);

        // Step 6: Publish validation event
        String tenantId = TenantContext.getCurrentTenant();
        eventPublisher.publishEvent(new InventoryValidatedEvent(
                session.getId(), command.actorId(), adjustmentsApplied,
                tenantId, Instant.now()
        ));

        return new ValidateInventoryResult(
                session.getId(), adjustmentsApplied,
                session.getStatus(), session.getCompletedAt()
        );
    }

    private int loadCurrentStockQuantity(UUID productId, UUID variantId, UUID storeId) {
        if (variantId != null) {
            return stockLevelRepo.findByProductVariantAndStore(productId, variantId, storeId)
                    .map(sl -> sl.getQuantity()).orElse(0);
        }
        return stockLevelRepo.findByProductAndStore(productId, storeId)
                .map(sl -> sl.getQuantity()).orElse(0);
    }
}

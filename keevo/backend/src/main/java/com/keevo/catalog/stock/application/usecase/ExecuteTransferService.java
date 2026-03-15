package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.event.StockTransferredEvent;
import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
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
 * ExecuteTransferService — orchestrates Step 1 of the two-step transfer flow.
 *
 * <p>Steps (all atomic — single @Transactional):
 * 1. Assert source store exists
 * 2. Assert destination store exists
 * 3. Load current stock level at source (0 if none)
 * 4. Delegate validation to TransferValidationStrategy (GoF: Strategy)
 * 5. Call StockOperationService.recordOperation(TRANSFER_OUT) on source
 * 6. Persist StockTransfer header with status IN_TRANSIT
 * 7. Publish StockTransferredEvent (GoF: Observer)
 *
 * Step 2 (receiving / completing) is handled by CompleteTransferService.
 * Stock at the destination is “locked in transit” until completion.
 *
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
        this.storeRepository     = storeRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.stockOperationService = stockOperationService;
        this.transferRepository  = transferRepository;
        this.validationStrategy  = validationStrategy;
        this.eventPublisher      = eventPublisher;
    }

    @Override
    @Transactional
    public StockTransfer execute(TransferStockCommand command) {
        // 1. Assert source store exists
        storeRepository.findById(command.sourceStoreId())
            .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND,
                "Source store not found: " + command.sourceStoreId()));

        // 2. Assert destination store exists (unless same — validation will catch it)
        if (!command.sourceStoreId().equals(command.destinationStoreId())) {
            storeRepository.findById(command.destinationStoreId())
                .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND,
                    "Destination store not found: " + command.destinationStoreId()));
        }

        // 3. Load current source stock level
        int sourceQty = 0;
        if (command.variantId() != null) {
            sourceQty = stockLevelRepository
                .findByProductVariantAndStore(command.productId(), command.variantId(), command.sourceStoreId())
                .map(l -> l.getQuantity())
                .orElse(0);
        } else {
            sourceQty = stockLevelRepository
                .findByProductAndStore(command.productId(), command.sourceStoreId())
                .map(l -> l.getQuantity())
                .orElse(0);
        }

        // 4. Validate (GoF: Strategy) — throws DomainException on any violation
        validationStrategy.validate(command, sourceQty);

        // 5. TRANSFER_OUT on source (negative delta — stock leaves source immediately)
        stockOperationService.recordOperation(
            command.productId(), command.variantId(), command.sourceStoreId(),
            MovementType.TRANSFER_OUT, -command.quantity(),
            command.actorId(), command.notes());

        // 6. Persist transfer header with IN_TRANSIT status
        //    Destination stock will be credited when the transfer is received (Step 2).
        var now      = Instant.now();
        var transfer = new StockTransfer(
            UUID.randomUUID(),
            command.sourceStoreId(),
            command.destinationStoreId(),
            command.productId(),
            command.variantId(),
            command.quantity(),
            command.actorId(),
            now,
            TransferStatus.IN_TRANSIT,
            command.notes()
        );
        var saved = transferRepository.save(transfer);

        // 7. Publish event (GoF: Observer)
        eventPublisher.publishEvent(new StockTransferredEvent(
            saved.getId(),
            command.sourceStoreId(),
            command.destinationStoreId(),
            command.productId(),
            command.variantId(),
            command.quantity(),
            command.actorId(),
            TenantContext.getCurrentTenant(),
            now
        ));

        return saved;
    }
}

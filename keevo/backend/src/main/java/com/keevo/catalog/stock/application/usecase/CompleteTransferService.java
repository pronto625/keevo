package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.event.StockTransferredEvent;
import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import com.keevo.catalog.stock.domain.port.in.CompleteTransferCommand;
import com.keevo.catalog.stock.domain.port.in.CompleteTransferUseCase;
import com.keevo.catalog.stock.domain.port.out.StockTransferRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * CompleteTransferService — orchestrates Step 2 of the two-step transfer flow.
 *
 * <p>Steps (all atomic — single @Transactional):
 * 1. Find the transfer by ID
 * 2. Assert its status is IN_TRANSIT (reject if already COMPLETED / CONFLICT)
 * 3. Call StockOperationService.recordOperation(TRANSFER_IN) on destination
 * 4. Persist updated transfer header with status COMPLETED
 * 5. Publish StockTransferredEvent (GoF: Observer)
 *
 * Story 3.3.
 */
@Service
public class CompleteTransferService implements CompleteTransferUseCase {

    private final StockTransferRepository transferRepository;
    private final StockOperationService stockOperationService;
    private final ApplicationEventPublisher eventPublisher;

    public CompleteTransferService(
            StockTransferRepository transferRepository,
            StockOperationService stockOperationService,
            ApplicationEventPublisher eventPublisher) {
        this.transferRepository   = transferRepository;
        this.stockOperationService = stockOperationService;
        this.eventPublisher       = eventPublisher;
    }

    @Override
    @Transactional
    public StockTransfer execute(CompleteTransferCommand command) {
        // 1. Fetch the transfer
        var transfer = transferRepository.findById(command.transferId())
                .orElseThrow(() -> new DomainException(ErrorCode.TRANSFER_NOT_FOUND,
                        "Transfer not found: " + command.transferId()));

        // 2. Validate status — only IN_TRANSIT transfers can be received
        if (transfer.getStatus() != TransferStatus.IN_TRANSIT) {
            throw new DomainException(ErrorCode.TRANSFER_INVALID_STATUS,
                    "Transfer " + command.transferId() + " has status "
                    + transfer.getStatus() + " — expected IN_TRANSIT");
        }

        // 3. TRANSFER_IN on destination (positive delta — goods received)
        stockOperationService.recordOperation(
                transfer.getProductId(), transfer.getVariantId(),
                transfer.getDestinationStoreId(),
                MovementType.TRANSFER_IN, transfer.getQuantity(),
                command.actorId(), transfer.getNotes());

        // 4. Persist updated status
        var completed = transferRepository.save(transfer.withStatus(TransferStatus.COMPLETED));

        // 5. Publish event (GoF: Observer — notifies audit listener)
        var now = Instant.now();
        eventPublisher.publishEvent(new StockTransferredEvent(
                completed.getId(),
                completed.getSourceStoreId(),
                completed.getDestinationStoreId(),
                completed.getProductId(),
                completed.getVariantId(),
                completed.getQuantity(),
                command.actorId(),
                TenantContext.getCurrentTenant(),
                now
        ));

        return completed;
    }
}

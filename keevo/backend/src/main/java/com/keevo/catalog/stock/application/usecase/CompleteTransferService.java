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
 * 2. ATOMIC status transition IN_TRANSIT → COMPLETED via conditional UPDATE
 *    (Option A — idempotent: exactly one concurrent caller wins)
 * 3. If already COMPLETED → return existing (idempotent, no double credit)
 * 4. Call StockOperationService.recordOperation(TRANSFER_IN) on destination
 * 5. Re-fetch completed transfer (version incremented by atomic UPDATE)
 * 6. Publish StockTransferredEvent (GoF: Observer)
 *
 * Story 3.3 + v1s-13-1 (B-CRIT-1 fix).
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
        // 1. Fetch the transfer (for data, not for status gating — transitionStatus is the gate)
        var transfer = transferRepository.findById(command.transferId())
                .orElseThrow(() -> new DomainException(ErrorCode.TRANSFER_NOT_FOUND,
                        "Transfer not found: " + command.transferId()));

        // 1.5 Story v1s-12-9 — EMPLOYEE must be assigned to the destination store (FR36).
        //        OWNER (assignedStoreId == null) is never scoped.
        if (command.assignedStoreId() != null
                && !command.assignedStoreId().equals(transfer.getDestinationStoreId())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "EMPLOYEE cannot complete a transfer for another store");
        }

        // 2. ATOMIC transition IN_TRANSIT → COMPLETED (Option A — conditional UPDATE)
        //    Two concurrent calls: exactly one returns true, the other false.
        boolean transitioned = transferRepository.transitionStatus(
                transfer.getId(), TransferStatus.IN_TRANSIT, TransferStatus.COMPLETED);

        if (!transitioned) {
            // Re-fetch to check current status for idempotent behavior
            var current = transferRepository.findById(command.transferId())
                    .orElseThrow(() -> new DomainException(ErrorCode.TRANSFER_NOT_FOUND,
                            "Transfer not found: " + command.transferId()));
            if (current.getStatus() == TransferStatus.COMPLETED) {
                // IDEMPOTENT — already completed, return existing (no double credit, no re-event)
                return current;
            }
            throw new DomainException(ErrorCode.TRANSFER_INVALID_STATUS,
                    "Transfer " + command.transferId() + " has status "
                    + current.getStatus() + " — expected IN_TRANSIT");
        }

        // 3. TRANSFER_IN on destination (positive delta — goods received)
        stockOperationService.recordOperation(
                transfer.getProductId(), transfer.getVariantId(),
                transfer.getDestinationStoreId(),
                MovementType.TRANSFER_IN, transfer.getQuantity(),
                command.actorId(), transfer.getNotes());

        // 4. Re-fetch completed transfer (version was incremented by the atomic UPDATE)
        var completed = transferRepository.findById(command.transferId())
                .orElseThrow(() -> new DomainException(ErrorCode.TRANSFER_NOT_FOUND,
                        "Transfer disappeared after completion: " + command.transferId()));

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

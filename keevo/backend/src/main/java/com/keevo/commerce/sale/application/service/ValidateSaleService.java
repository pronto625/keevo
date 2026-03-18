package com.keevo.commerce.sale.application.service;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.GetPendingSalesUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ValidateSaleService — handles manual validation, cancellation, and listing of pending sales.
 * Story 4.3 AC6, AC9, AC10.
 */
@Service
@Transactional
public class ValidateSaleService implements ValidateSaleUseCase, CancelPendingSaleUseCase, GetPendingSalesUseCase {

    private final SaleRepository saleRepository;
    private final StockLevelRepository stockLevelRepository;
    private final StockOperationService stockOperationService;
    private final ApplicationEventPublisher eventPublisher;

    public ValidateSaleService(SaleRepository saleRepository,
                               StockLevelRepository stockLevelRepository,
                               StockOperationService stockOperationService,
                               ApplicationEventPublisher eventPublisher) {
        this.saleRepository = saleRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.stockOperationService = stockOperationService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void validateSale(ValidateSaleCommand command) {
        // Step 0: Apply product-ID remappings (draft → promoted) before loading
        if (command.productIdRemappings() != null && !command.productIdRemappings().isEmpty()) {
            saleRepository.remapItemProductIds(command.saleId(), command.productIdRemappings());
        }

        Sale sale = loadPendingSale(command.saleId());
        validateJustification(command.justification());

        // Step 1: Record initial stock entries for newly promoted draft products.
        // Uses sale.getStoreId() as the authoritative store to avoid storeId mismatch
        // between Flutter local DB and backend (JWT-assigned store).
        if (command.initialStockEntries() != null && !command.initialStockEntries().isEmpty()) {
            UUID actorId = command.actorId();
            for (var entry : command.initialStockEntries().entrySet()) {
                UUID productId = entry.getKey();
                int qty = entry.getValue();
                if (qty > 0) {
                    stockOperationService.recordOperation(
                            productId, null, sale.getStoreId(),
                            MovementType.STOCK_ENTRY, qty,
                            actorId, "Stock initial à la validation du brouillon");
                }
            }
        }

        String tenantId = TenantContext.getCurrentTenant();
        Instant now = Instant.now();

        List<UUID> forcedProducts = new ArrayList<>();

        // Decrement stock for each item — force to 0 if insufficient
        for (SaleItem item : sale.getItems()) {
            int available = stockLevelRepository.findByProductAndStore(item.getProductId(), sale.getStoreId())
                    .map(sl -> sl.getQuantity())
                    .orElse(0);

            int effectiveDecrement = Math.min(available, item.getQuantity());

            if (available < item.getQuantity()) {
                // Force stock to 0
                forcedProducts.add(item.getProductId());
                eventPublisher.publishEvent(new StockForcedZeroEvent(
                        item.getProductId(), sale.getStoreId(),
                        item.getQuantity(), available,
                        command.actorId(), tenantId, now));
            }

            if (effectiveDecrement > 0) {
                stockOperationService.recordOperation(
                        item.getProductId(),
                        item.getVariantId(),
                        sale.getStoreId(),
                        MovementType.SALE,
                        -effectiveDecrement,
                        command.actorId(),
                        "Validation manuelle vente " + sale.getId()
                );
            }
        }

        saleRepository.updateStatus(sale.getId(), SaleStatus.COMPLETED);

        eventPublisher.publishEvent(new SaleManuallyValidatedEvent(
                sale.getId(), command.actorId(), command.justification(),
                forcedProducts, tenantId, now));

        eventPublisher.publishEvent(new SaleCompletedEvent(
                sale.getId(), command.actorId(), tenantId, sale.getStoreId(),
                sale.getTotalAmount(), sale.getDiscountAmount(), "[]", now));
    }

    @Override
    public void cancelPendingSale(CancelPendingSaleCommand command) {
        Sale sale = loadPendingSale(command.saleId());
        validateJustification(command.justification());

        saleRepository.updateStatus(sale.getId(), SaleStatus.CANCELLED);

        String tenantId = TenantContext.getCurrentTenant();
        eventPublisher.publishEvent(new SaleCancelledEvent(
                sale.getId(), command.actorId(), command.justification(),
                tenantId, Instant.now()));
    }

    @Override
    public List<Sale> getPendingSales() {
        return saleRepository.findByStatus(SaleStatus.PENDING_VALIDATION);
    }

    private Sale loadPendingSale(UUID saleId) {
        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new DomainException(ErrorCode.SALE_NOT_FOUND,
                        "Sale not found: " + saleId));
        if (sale.getStatus() != SaleStatus.PENDING_VALIDATION) {
            throw new DomainException(ErrorCode.SALE_NOT_PENDING,
                    "Sale " + saleId + " is not pending validation (status=" + sale.getStatus() + ")");
        }
        return sale;
    }

    private void validateJustification(String justification) {
        if (justification != null && !justification.isBlank() && justification.trim().length() < 10) {
            throw new DomainException(ErrorCode.JUSTIFICATION_TOO_SHORT,
                    "Justification must be at least 10 characters");
        }
    }
}

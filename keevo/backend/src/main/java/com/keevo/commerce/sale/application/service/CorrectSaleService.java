package com.keevo.commerce.sale.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.inventory.stock.domain.entity.MovementType;
import com.keevo.inventory.stock.domain.service.StockOperationService;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CorrectSaleUseCase;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.DeterministicIds;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CorrectSaleService — corrects item quantities on a COMPLETED sale, with proportional
 * stock adjustment and total recalculation. Story v1s-13-5 (AC6-AC7).
 *
 * <p>Quantity-only scope (Décision D4): no price change, no line add/remove.
 */
@Service
@Transactional
public class CorrectSaleService implements CorrectSaleUseCase {

    private final SaleRepository saleRepository;
    private final StockOperationService stockOperationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public CorrectSaleService(SaleRepository saleRepository,
                              StockOperationService stockOperationService,
                              ApplicationEventPublisher eventPublisher,
                              ObjectMapper objectMapper) {
        this.saleRepository = saleRepository;
        this.stockOperationService = stockOperationService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void correctSale(CorrectSaleCommand command) {
        Sale sale = saleRepository.findById(command.saleId())
                .orElseThrow(() -> new DomainException(ErrorCode.SALE_NOT_FOUND,
                        "Sale not found: " + command.saleId()));

        if (sale.getStatus() != SaleStatus.COMPLETED) {
            throw new DomainException(ErrorCode.SALE_NOT_COMPLETED,
                    "Sale " + sale.getId() + " is not completed (status=" + sale.getStatus() + ")");
        }

        enforceEmployeeStoreScope(command.assignedStoreId(), sale.getStoreId());

        Map<UUID, Integer> itemQuantities = command.itemQuantities();
        if (itemQuantities == null || itemQuantities.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "itemQuantities is required and must not be empty");
        }

        JustificationPolicy.requireStrict(command.justification());

        String beforeSnapshot = serializeItems(sale.getItems(), sale.getId());

        List<SaleItem> correctedItems = new ArrayList<>();
        for (SaleItem item : sale.getItems()) {
            Integer newQty = itemQuantities.get(item.getId());
            if (newQty == null) {
                // Unknown/unaffected item id — no-op, keep as-is.
                correctedItems.add(item);
                continue;
            }
            if (newQty <= 0) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR,
                        "New quantity must be positive, got: " + newQty);
            }
            if (newQty == item.getQuantity()) {
                correctedItems.add(item);
                continue;
            }
            correctedItems.add(new SaleItem(
                    item.getId(), item.getSaleId(), item.getProductId(), item.getVariantId(),
                    item.getProductName(), item.getCatalogueUnitPrice(), item.getAppliedUnitPrice(),
                    newQty));
        }

        int newSubtotal = correctedItems.stream().mapToInt(SaleItem::getSubtotal).sum();
        // Same rule as the Sale constructor, checked before any stock movement is written.
        if (sale.getDiscountAmount() > newSubtotal) {
            throw new DomainException(ErrorCode.DISCOUNT_EXCEEDS_SUBTOTAL,
                    "Discount (" + sale.getDiscountAmount() + ") exceeds corrected subtotal (" + newSubtotal + ")");
        }
        int newTotal = newSubtotal - sale.getDiscountAmount();

        for (int i = 0; i < correctedItems.size(); i++) {
            SaleItem before = sale.getItems().get(i);
            SaleItem after = correctedItems.get(i);
            if (after.getQuantity() == before.getQuantity()) continue;
            // Decrease → restore stock (positive delta); increase → decrement (negative delta).
            // v1s-13-13: id derived from the app's correctionId + item id (same on the device),
            // so the pull does not add a second ADJUSTMENT row; null (older app) → random id.
            UUID movementId = command.correctionId() != null
                    ? DeterministicIds.saleCorrectionMovementId(command.correctionId(), before.getId())
                    : null;
            stockOperationService.recordOperation(
                    before.getProductId(), before.getVariantId(), sale.getStoreId(),
                    MovementType.ADJUSTMENT, -(after.getQuantity() - before.getQuantity()),
                    command.actorId(), "Correction vente " + sale.getId(),
                    movementId, false);
        }

        Sale correctedSale = new Sale(sale.getId(), sale.getStoreId(), sale.getEmployeeId(),
                sale.getClientId(), sale.getPaymentMode(), newTotal, sale.getDiscountAmount(),
                SaleStatus.COMPLETED, sale.getOccurredAt(), sale.getCreatedAt(), correctedItems,
                sale.getVersion(), sale.getMobileMoneyRef());

        // Story v1s-13-10: version-checked save — a concurrent cancel/correction that committed
        // since the read makes it throw OptimisticLockingFailureException (→ 409), rolling back
        // the ADJUSTMENT movements above.
        saleRepository.save(correctedSale);

        String tenantId = TenantContext.getCurrentTenant();
        eventPublisher.publishEvent(new SaleCorrectedEvent(
                sale.getId(), command.actorId(), command.justification(),
                beforeSnapshot, serializeItems(correctedItems, sale.getId()), tenantId, Instant.now(),
                sale.getStoreId()));
    }

    private String serializeItems(List<SaleItem> items, UUID saleId) {
        return SaleAuditSnapshot.serialize(objectMapper, items, saleId);
    }

    private void enforceEmployeeStoreScope(UUID assignedStoreId, UUID saleStoreId) {
        // OWNER flow passes null assignedStoreId and is unrestricted.
        if (assignedStoreId != null && !assignedStoreId.equals(saleStoreId)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Employee cannot correct sale outside assigned store");
        }
    }
}

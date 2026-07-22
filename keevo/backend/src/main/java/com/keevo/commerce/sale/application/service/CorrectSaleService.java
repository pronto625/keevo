package com.keevo.commerce.sale.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CorrectSaleUseCase;
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

        String beforeSnapshot = serializeItems(sale.getItems());

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
            // Decrease → restore stock (positive delta); increase → decrement (negative delta).
            stockOperationService.recordOperation(
                    item.getProductId(), item.getVariantId(), sale.getStoreId(),
                    MovementType.ADJUSTMENT, -(newQty - item.getQuantity()),
                    command.actorId(), "Correction vente " + sale.getId());
            correctedItems.add(new SaleItem(
                    item.getId(), item.getSaleId(), item.getProductId(), item.getVariantId(),
                    item.getProductName(), item.getCatalogueUnitPrice(), item.getAppliedUnitPrice(),
                    newQty));
        }

        int newSubtotal = correctedItems.stream().mapToInt(SaleItem::getSubtotal).sum();
        int newTotal = newSubtotal - sale.getDiscountAmount();

        Sale correctedSale = new Sale(sale.getId(), sale.getStoreId(), sale.getEmployeeId(),
                sale.getClientId(), sale.getPaymentMode(), newTotal, sale.getDiscountAmount(),
                SaleStatus.COMPLETED, sale.getOccurredAt(), sale.getCreatedAt(), correctedItems);

        saleRepository.save(correctedSale);

        String tenantId = TenantContext.getCurrentTenant();
        eventPublisher.publishEvent(new SaleCorrectedEvent(
                sale.getId(), command.actorId(), command.justification(),
                beforeSnapshot, serializeItems(correctedItems), tenantId, Instant.now()));
    }

    private String serializeItems(List<SaleItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private void enforceEmployeeStoreScope(UUID assignedStoreId, UUID saleStoreId) {
        // OWNER flow passes null assignedStoreId and is unrestricted.
        if (assignedStoreId != null && !assignedStoreId.equals(saleStoreId)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Employee cannot correct sale outside assigned store");
        }
    }
}

package com.keevo.commerce.sale.application.service;

import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockMovementInput;
import com.keevo.inventory.api.StockMovementKind;
import com.keevo.inventory.api.StockQueryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.domain.event.SalePriceOverriddenEvent;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.event.SaleCompletedEvent;
import com.keevo.commerce.sale.domain.model.SaleFactory;
import com.keevo.commerce.sale.domain.event.SalePendingValidationEvent;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * RecordSaleService — application service implementing RecordSaleUseCase.
 *
 * <p>GoF Command: receives RecordSaleCommand, delegates to SaleFactory.
 * <p>GoF Observer: publishes SaleCompletedEvent for AuditEventListener.
 * <p>Hexagonal: imports ONLY from domain/ and shared/ — zero Spring Data/JPA.
 */
@Service
@Transactional
public class RecordSaleService implements RecordSaleUseCase {

    private final SaleRepository saleRepository;
    private final StockQueryPort stockQuery;
    private final StockCommandPort stockCommand;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public RecordSaleService(SaleRepository saleRepository,
                             StockQueryPort stockQuery,
                             StockCommandPort stockCommand,
                             ApplicationEventPublisher eventPublisher,
                             ObjectMapper objectMapper) {
        this.saleRepository = saleRepository;
        this.stockQuery = stockQuery;
        this.stockCommand = stockCommand;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void recordSale(RecordSaleCommand command) {
        // Idempotency guard — duplicate saleId is a no-op
        if (saleRepository.existsById(command.saleId())) {
            return;
        }

        SaleStatus effectiveStatus = command.requestedStatus() != null
                ? command.requestedStatus() : SaleStatus.COMPLETED;
        boolean isPending = effectiveStatus == SaleStatus.PENDING_VALIDATION;

        if (!isPending && !command.allowNegativeStock()) {
            // Stock availability check for each item (only for COMPLETED sales)
            for (var item : command.items()) {
                int available = getAvailableStock(item.productId(), item.variantId(), command.storeId());
                if (available < item.quantity()) {
                    throw new DomainException(ErrorCode.INSUFFICIENT_STOCK,
                            "Stock insuffisant pour " + item.productName()
                                    + ": disponible=" + available + " demandé=" + item.quantity());
                }
            }
        }

        // Create Sale aggregate via factory
        Sale sale = SaleFactory.from(command, effectiveStatus);

        // Persist sale + items (single transaction)
        saleRepository.save(sale);

        if (!isPending) {
            // Decrement stock for each item (only for COMPLETED sales)
            // v1s-13-8: the SALE movement id is the (client-generated) sale item id on both
            // sides, so the device's pull upserts its own movement row instead of adding a
            // second one. itemId null → server-generated.
            for (var item : command.items()) {
                stockCommand.recordMovement(new StockMovementInput(
                        item.productId(),
                        item.variantId(),
                        command.storeId(),
                        StockMovementKind.SALE,
                        -item.quantity(),
                        command.actorId(),
                        "Vente " + command.saleId(),
                        item.itemId(),
                        command.allowNegativeStock()
                ));
            }

            // Story 4.2 — Publish SalePriceOverriddenEvent for each overridden item
            for (var item : command.items()) {
                if (item.catalogueUnitPrice() != item.appliedUnitPrice()) {
                    eventPublisher.publishEvent(new SalePriceOverriddenEvent(
                            item.productId(),
                            sale.getId(),
                            item.productName(),
                            item.catalogueUnitPrice(),
                            item.appliedUnitPrice(),
                            command.actorId(),
                            TenantContext.getCurrentTenant(),
                            Instant.now()
                    ));
                }
            }

            // Publish SaleCompletedEvent
            eventPublisher.publishEvent(new SaleCompletedEvent(
                    sale.getId(),
                    command.actorId(),
                    TenantContext.getCurrentTenant(),
                    command.storeId(),
                    sale.getTotalAmount(),
                    sale.getDiscountAmount(),
                    serializeItems(command),
                    sale.getOccurredAt()
            ));
        } else {
            // PENDING_VALIDATION: publish SalePendingValidationEvent, no stock operations
            var draftProductIds = command.items().stream()
                    .map(RecordSaleUseCase.SaleItemCommand::productId)
                    .toList();
            eventPublisher.publishEvent(new SalePendingValidationEvent(
                    sale.getId(),
                    command.storeId(),
                    command.actorId(),
                    draftProductIds,
                    sale.getTotalAmount(),
                    TenantContext.getCurrentTenant(),
                    sale.getOccurredAt()
            ));
        }
    }

    private int getAvailableStock(UUID productId, UUID variantId, UUID storeId) {
        return stockQuery.availableQuantity(productId, variantId, storeId);
    }

    private String serializeItems(RecordSaleCommand command) {
        return SaleAuditSnapshot.serialize(objectMapper, command.items(), command.saleId());
    }
}

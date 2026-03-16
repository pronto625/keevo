package com.keevo.commerce.sale.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleCompletedEvent;
import com.keevo.commerce.sale.domain.model.SaleFactory;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final StockLevelRepository stockLevelRepository;
    private final StockOperationService stockOperationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public RecordSaleService(SaleRepository saleRepository,
                             StockLevelRepository stockLevelRepository,
                             StockOperationService stockOperationService,
                             ApplicationEventPublisher eventPublisher,
                             ObjectMapper objectMapper) {
        this.saleRepository = saleRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.stockOperationService = stockOperationService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void recordSale(RecordSaleCommand command) {
        // Idempotency guard — duplicate saleId is a no-op
        if (saleRepository.existsById(command.saleId())) {
            return;
        }

        // Stock availability check for each item
        for (var item : command.items()) {
            int available = getAvailableStock(item.productId(), item.variantId(), command.storeId());
            if (available < item.quantity()) {
                throw new DomainException(ErrorCode.INSUFFICIENT_STOCK,
                        "Stock insuffisant pour " + item.productName()
                                + ": disponible=" + available + " demandé=" + item.quantity());
            }
        }

        // Create Sale aggregate via factory
        Sale sale = SaleFactory.from(command);

        // Persist sale + items (single transaction)
        saleRepository.save(sale);

        // Decrement stock for each item
        for (var item : command.items()) {
            stockOperationService.recordOperation(
                    item.productId(),
                    item.variantId(),
                    command.storeId(),
                    MovementType.SALE,
                    -item.quantity(),
                    command.actorId(),
                    "Vente " + command.saleId()
            );
        }

        // Publish event (Observer pattern — AuditEventListener subscribes)
        eventPublisher.publishEvent(new SaleCompletedEvent(
                sale.getId(),
                command.actorId(),
                TenantContext.getCurrentTenant(),
                command.storeId(),
                sale.getTotalAmount(),
                serializeItems(command),
                sale.getOccurredAt()
        ));
    }

    private int getAvailableStock(UUID productId, UUID variantId, UUID storeId) {
        if (variantId != null) {
            return stockLevelRepository.findByProductVariantAndStore(productId, variantId, storeId)
                    .map(StockLevel::getQuantity)
                    .orElse(0);
        }
        return stockLevelRepository.findByProductAndStore(productId, storeId)
                .map(StockLevel::getQuantity)
                .orElse(0);
    }

    private String serializeItems(RecordSaleCommand command) {
        try {
            return objectMapper.writeValueAsString(command.items());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}

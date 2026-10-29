package com.keevo.commerce.sale.application.service;

import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockMovementInput;
import com.keevo.inventory.api.StockMovementKind;
import com.keevo.inventory.api.StockQueryPort;
import com.keevo.shared.event.catalog.product.ProductActivatedEvent;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.shared.event.catalog.sale.*;
import com.keevo.commerce.api.*;
import com.keevo.commerce.sale.domain.port.out.ProductStatusPort;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SaleValidationCascadeService — checks and auto-validates pending sales
 * when products transition from DRAFT to ACTIVE.
 * Story 4.3 AC7, AC8.
 */
@Service
@Transactional
public class SaleValidationCascadeService {

    private static final Logger log = LoggerFactory.getLogger(SaleValidationCascadeService.class);

    private final SaleRepository saleRepository;
    private final ProductStatusPort productStatusPort;
    private final StockQueryPort stockQuery;
    private final StockCommandPort stockCommand;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public SaleValidationCascadeService(SaleRepository saleRepository,
                                        ProductStatusPort productStatusPort,
                                        StockQueryPort stockQuery,
                                        StockCommandPort stockCommand,
                                        ApplicationEventPublisher eventPublisher,
                                        ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.saleRepository = saleRepository;
        this.productStatusPort = productStatusPort;
        this.stockQuery = stockQuery;
        this.stockCommand = stockCommand;
        this.eventPublisher = eventPublisher;
    }

    public void onProductActivated(UUID productId, UUID actorId) {
        List<Sale> pendingSales = saleRepository.findPendingByProductId(productId);
        String tenantId = TenantContext.getCurrentTenant();
        for (Sale sale : pendingSales) {
            attemptCascadeValidation(sale, productId, actorId, tenantId);
        }
    }

    @EventListener
    public void handleProductActivated(ProductActivatedEvent event) {
        onProductActivated(event.productId(), event.actorId());
    }

    public void onStockChanged(UUID productId, UUID storeId, UUID actorId) {
        List<Sale> pendingSales = saleRepository.findPendingByProductId(productId);
        String tenantId = TenantContext.getCurrentTenant();
        for (Sale sale : pendingSales) {
            attemptCascadeValidation(sale, productId, actorId, tenantId);
        }
    }

    private boolean attemptCascadeValidation(Sale sale, UUID triggerProductId, UUID actorId, String tenantId) {
        // Check all products in the sale are ACTIVE
        for (SaleItem item : sale.getItems()) {
            String status = productStatusPort.getProductStatus(item.getProductId());
            if (!"ACTIVE".equals(status)) {
                return false;
            }
        }

        // Check stock for ALL products (including the trigger product), on the level that is
        // decremented below — the item's variant, null for a simple product (Story 12.2c).
        for (SaleItem item : sale.getItems()) {
            int available = stockQuery.availableQuantity(tenantId, item.getProductId(), item.getVariantId(), sale.getStoreId());
            if (available <= 0) {
                log.warn("Vente #{} en attente : le produit '{}' n'a pas de stock",
                        sale.getId().toString().substring(0, 8), item.getProductName());
                return false;
            }
        }

        // All conditions met — claim PENDING_VALIDATION→COMPLETED atomically (Story v1s-13-10).
        // Another actor that already settled the sale wins: skip it, never fail the activation.
        if (!saleRepository.updateStatusIfCurrent(
                sale.getId(), SaleStatus.PENDING_VALIDATION, SaleStatus.COMPLETED)) {
            log.debug("Cascade skipped for sale {}: no longer pending validation", sale.getId());
            return false;
        }

        // Decrement stock for ALL items
        for (SaleItem item : sale.getItems()) {
            stockCommand.recordMovement(tenantId, new StockMovementInput(
                    item.getProductId(),
                    item.getVariantId(),
                    sale.getStoreId(),
                    StockMovementKind.SALE,
                    -item.getQuantity(),
                    actorId,
                    "Vente auto-validée " + sale.getId(),
                    // Story v1s-13-10: sale item id = SALE movement id (same on the app).
                    item.getId(),
                    false
            ));
        }
        Instant now = Instant.now();

        eventPublisher.publishEvent(new SaleCompletedEvent(
                sale.getId(), actorId, tenantId, sale.getStoreId(),
                sale.getTotalAmount(), sale.getDiscountAmount(),
                SaleAuditSnapshot.serialize(objectMapper, sale.getItems(), sale.getId()), now));

        eventPublisher.publishEvent(new SaleAutoValidatedEvent(
                sale.getId(), triggerProductId, actorId, tenantId, now, sale.getStoreId()));

        return true;
    }
}

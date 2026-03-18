package com.keevo.commerce.sale.application.service;

import com.keevo.catalog.product.domain.event.ProductActivatedEvent;
import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.catalog.stock.domain.service.StockOperationService;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.out.ProductStatusPort;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
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
    private final StockLevelRepository stockLevelRepository;
    private final StockOperationService stockOperationService;
    private final ApplicationEventPublisher eventPublisher;

    public SaleValidationCascadeService(SaleRepository saleRepository,
                                        ProductStatusPort productStatusPort,
                                        StockLevelRepository stockLevelRepository,
                                        StockOperationService stockOperationService,
                                        ApplicationEventPublisher eventPublisher) {
        this.saleRepository = saleRepository;
        this.productStatusPort = productStatusPort;
        this.stockLevelRepository = stockLevelRepository;
        this.stockOperationService = stockOperationService;
        this.eventPublisher = eventPublisher;
    }

    public void onProductActivated(UUID productId, UUID actorId) {
        List<Sale> pendingSales = saleRepository.findPendingByProductId(productId);
        for (Sale sale : pendingSales) {
            attemptCascadeValidation(sale, productId, actorId);
        }
    }

    @EventListener
    public void handleProductActivated(ProductActivatedEvent event) {
        onProductActivated(event.productId(), event.actorId());
    }

    public void onStockChanged(UUID productId, UUID storeId, UUID actorId) {
        List<Sale> pendingSales = saleRepository.findPendingByProductId(productId);
        for (Sale sale : pendingSales) {
            attemptCascadeValidation(sale, productId, actorId);
        }
    }

    private boolean attemptCascadeValidation(Sale sale, UUID triggerProductId, UUID actorId) {
        // Check all products in the sale are ACTIVE
        for (SaleItem item : sale.getItems()) {
            String status = productStatusPort.getProductStatus(item.getProductId());
            if (!"ACTIVE".equals(status)) {
                return false;
            }
        }

        // Check stock for ALL products (including the trigger product)
        for (SaleItem item : sale.getItems()) {
            int available = stockLevelRepository.findByProductAndStore(item.getProductId(), sale.getStoreId())
                    .map(sl -> sl.getQuantity())
                    .orElse(0);
            if (available <= 0) {
                log.warn("Vente #{} en attente : le produit '{}' n'a pas de stock",
                        sale.getId().toString().substring(0, 8), item.getProductName());
                return false;
            }
        }

        // All conditions met — validate sale
        saleRepository.updateStatus(sale.getId(), SaleStatus.COMPLETED);

        // Decrement stock for ALL items
        for (SaleItem item : sale.getItems()) {
            stockOperationService.recordOperation(
                    item.getProductId(),
                    item.getVariantId(),
                    sale.getStoreId(),
                    MovementType.SALE,
                    -item.getQuantity(),
                    actorId,
                    "Vente auto-validée " + sale.getId()
            );
        }

        String tenantId = TenantContext.getCurrentTenant();
        Instant now = Instant.now();

        eventPublisher.publishEvent(new SaleCompletedEvent(
                sale.getId(), actorId, tenantId, sale.getStoreId(),
                sale.getTotalAmount(), sale.getDiscountAmount(), "[]", now));

        eventPublisher.publishEvent(new SaleAutoValidatedEvent(
                sale.getId(), triggerProductId, actorId, tenantId, now));

        return true;
    }
}

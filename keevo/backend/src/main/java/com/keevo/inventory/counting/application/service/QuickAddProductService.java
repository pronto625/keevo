package com.keevo.inventory.counting.application.service;

import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.event.ProductCreatedEvent;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.counting.domain.event.InventoryCountSavedEvent;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.model.QuickAddProductResult;
import com.keevo.inventory.counting.domain.port.in.QuickAddProductCommand;
import com.keevo.inventory.counting.domain.port.in.QuickAddProductUseCase;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * QuickAddProductService — Facade pattern.
 * Coordinates 3 repositories (Product, StockLevel, InventoryCount) behind a single execute() call.
 * All writes are atomic via @Transactional.
 * Story 6.2.a.
 */
@Service
public class QuickAddProductService implements QuickAddProductUseCase {

    private static final Logger log = LoggerFactory.getLogger(QuickAddProductService.class);

    private final InventorySessionRepository sessionRepository;
    private final ProductRepository productRepository;
    private final StockLevelRepository stockLevelRepository;
    private final InventoryCountRepository countRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final StoreRepository storeRepository; // v1s-15-9: ProductCreatedEvent.storeName

    public QuickAddProductService(InventorySessionRepository sessionRepository,
                                  ProductRepository productRepository,
                                  StockLevelRepository stockLevelRepository,
                                  InventoryCountRepository countRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  StoreRepository storeRepository) {
        this.sessionRepository = sessionRepository;
        this.productRepository = productRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.countRepository = countRepository;
        this.eventPublisher = eventPublisher;
        this.storeRepository = storeRepository;
    }

    @Override
    @Transactional
    public QuickAddProductResult execute(QuickAddProductCommand cmd) {
        // 1. Validate session exists and is IN_PROGRESS
        InventorySession session = sessionRepository.findById(cmd.sessionId())
                .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND,
                        "Session d'inventaire introuvable : " + cmd.sessionId()));

        if (session.getStatus() != InventorySessionStatus.IN_PROGRESS) {
            throw new DomainException(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS,
                    "Cette session d'inventaire n'est pas en cours");
        }

        // 2. Deduplication check (case-insensitive via repository)
        String trimmedName = cmd.name().trim();
        if (productRepository.existsByName(trimmedName)) {
            throw new DomainException(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS,
                    "Un produit avec le nom '" + trimmedName + "' existe déjà");
        }

        // 3. Create Product
        String sku = generateSku();
        Instant now = Instant.now();
        int price = cmd.sellingPrice() != null ? cmd.sellingPrice() : 0;
        Product product = new Product(
                UUID.randomUUID(), trimmedName, null, sku,
                cmd.categoryId(), price, 0, 0,
                cmd.physicalQty(), false, ProductStatus.ACTIVE,
                0, now, now
        );
        Product savedProduct = productRepository.save(product);

        // 4. Create StockLevel
        StockLevel stockLevel = new StockLevel(
                UUID.randomUUID(), savedProduct.getId(), null,
                session.getStoreId(), cmd.physicalQty(), now
        );
        StockLevel savedStock = stockLevelRepository.save(stockLevel);

        // 5. Create InventoryCount
        InventoryCount count = InventoryCount.create(
                cmd.sessionId(), savedProduct.getId(), null,
                trimmedName, null,
                0, cmd.physicalQty(), cmd.actorId()
        );
        InventoryCount savedCount = countRepository.save(count);

        // 6. Publish events
        // Story 14.10: actorRole/actorName from the authenticated actor.
        // v1s-15-9: CurrentActor (label never a raw UUID) + storeName from the session's store.
        String actorRole = null;
        String actorName = null;
        String storeName = null;
        try {
            if (CurrentActor.authentication() != null) {
                actorRole = CurrentActor.role();
                actorName = CurrentActor.actorLabel();
            }
        } catch (Exception e) {
            log.warn("[QUICKADD] Actor-context extraction failed for actorId={}: {}", cmd.actorId(), e.getMessage());
        }
        if (session.getStoreId() != null) {
            try {
                storeName = storeRepository.findById(session.getStoreId())
                        .map(s -> s.name())
                        .orElse(null);
            } catch (Exception e) {
                log.warn("[QUICKADD] Store-name lookup failed for storeId={}: {}", session.getStoreId(), e.getMessage());
            }
        }
        eventPublisher.publishEvent(new ProductCreatedEvent(
                savedProduct.getId(), savedProduct.getName(), savedProduct.getSku(),
                TenantContext.getCurrentTenant(), cmd.actorId(),
                actorRole, actorName, storeName,
                now
        ));
        eventPublisher.publishEvent(new InventoryCountSavedEvent(
                savedCount.getId(), cmd.sessionId(), savedProduct.getId(), null,
                0, cmd.physicalQty(), savedCount.getEcart(),
                cmd.actorId(), TenantContext.getCurrentTenant(), now,
                session.getStoreId()
        ));

        return new QuickAddProductResult(savedProduct, savedStock, savedCount);
    }

    private String generateSku() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder("KEV-");
            for (int i = 0; i < 6; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            String sku = sb.toString();
            if (productRepository.findBySku(sku).isEmpty()) {
                return sku;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR, "Impossible de générer un SKU unique après 5 tentatives");
    }
}

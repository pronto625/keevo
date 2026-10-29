package com.keevo.inventory.counting.application.service;

import com.keevo.store.api.StoreQueryPort;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.api.RegisterProductInput;
import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.counting.domain.event.InventoryCountSavedEvent;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.model.QuickAddProductResult;
import com.keevo.inventory.counting.domain.port.in.QuickAddProductCommand;
import com.keevo.inventory.counting.domain.port.in.QuickAddProductUseCase;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.api.PlanLimitPort;
import com.keevo.subscription.api.ProductCountPort;
import com.keevo.subscription.api.SubscriptionQueryPort;
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
    // 12.2a: catalog reached through its public API (+ its events named interface).
    private final ProductQueryPort productQuery;
    private final ProductCommandPort productCommand;
    private final StockLevelRepository stockLevelRepository;
    private final InventoryCountRepository countRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final StoreQueryPort storeQuery; // v1s-15-9: ProductCreatedEvent.storeName
    // v1s-14-13: FREE plan product limit (same rule as CreateProductUseCase)
    private final ProductCountPort productCountPort;
    private final SubscriptionQueryPort subscriptionQuery;
    private final PlanLimitPort planLimit;
    private final TransactionLockPort transactionLock;

    public QuickAddProductService(InventorySessionRepository sessionRepository,
                                  ProductQueryPort productQuery,
                                  ProductCommandPort productCommand,
                                  StockLevelRepository stockLevelRepository,
                                  InventoryCountRepository countRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  StoreQueryPort storeQuery,
                                  ProductCountPort productCountPort,
                                  SubscriptionQueryPort subscriptionQuery,
                                  PlanLimitPort planLimit,
                                  TransactionLockPort transactionLock) {
        this.sessionRepository = sessionRepository;
        this.productQuery = productQuery;
        this.productCommand = productCommand;
        this.stockLevelRepository = stockLevelRepository;
        this.countRepository = countRepository;
        this.eventPublisher = eventPublisher;
        this.storeQuery = storeQuery;
        this.productCountPort = productCountPort;
        this.subscriptionQuery = subscriptionQuery;
        this.planLimit = planLimit;
        this.transactionLock = transactionLock;
    }

    @Override
    @Transactional
    public QuickAddProductResult execute(QuickAddProductCommand cmd) {
        // v1s-14-13: same per-tenant advisory lock as CreateProductUseCase, first, so the name
        // check and the plan-limit count → save cannot interleave with a concurrent create.
        transactionLock.lock("product-limit:" + TenantContext.getCurrentTenant());

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
        if (productQuery.existsByName(trimmedName)) {
            throw new DomainException(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS,
                    "Un produit avec le nom '" + trimmedName + "' existe déjà");
        }

        // 2b. Plan limit check (v1s-14-13) — after the name check, as in CreateProductUseCase,
        // so the « produit existant » dedup still wins at the cap; before any write.
        int currentCount = productCountPort.countActiveProducts(TenantContext.getCurrentTenant());
        var plan = subscriptionQuery.findCurrentPlan(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif"));
        planLimit.checkProductLimit(plan, currentCount);

        // 3. Create Product
        String sku = generateSku();
        Instant now = Instant.now();
        int price = cmd.sellingPrice() != null ? cmd.sellingPrice() : 0;
        // J18: the product starts with NO stock. The counted quantity is recorded as the
        // session count (theoretical 0 → physical N) and applied ONCE, with its movement, by
        // the inventory validation. Creating the stock here as well doubled it (3 → 6).
        // Actor context for the catalog's ProductCreatedEvent
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
                storeName = storeQuery.findById(TenantContext.getCurrentTenant(), session.getStoreId())
                        .map(s -> s.name())
                        .orElse(null);
            } catch (Exception e) {
                log.warn("[QUICKADD] Store-name lookup failed for storeId={}: {}", session.getStoreId(), e.getMessage());
            }
        }
        ProductView savedProduct = productCommand.register(new RegisterProductInput(
                UUID.randomUUID(), trimmedName, sku, cmd.categoryId(), price, now,
                cmd.actorId(), actorRole, actorName, storeName));

        // 4. Create StockLevel
        StockLevel stockLevel = new StockLevel(
                UUID.randomUUID(), savedProduct.id(), null,
                session.getStoreId(), 0, now
        );
        StockLevel savedStock = stockLevelRepository.save(stockLevel);

        // 5. Create InventoryCount
        InventoryCount count = InventoryCount.create(
                cmd.sessionId(), savedProduct.id(), null,
                trimmedName, null,
                0, cmd.physicalQty(), cmd.actorId()
        );
        InventoryCount savedCount = countRepository.save(count);

        // 6. Publish events (ProductCreatedEvent is published by the catalog's register, 12.2b)
        eventPublisher.publishEvent(new InventoryCountSavedEvent(
                savedCount.getId(), cmd.sessionId(), savedProduct.id(), null,
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
            if (!productQuery.existsBySku(sku)) {
                return sku;
            }
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR, "Impossible de générer un SKU unique après 5 tentatives");
    }
}

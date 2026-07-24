package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.event.ProductCreatedEvent;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.port.out.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CreateProductUseCase — Business logic for creating products
 * 
 * Handles:
 * - Input validation
 * - Auto-SKU generation (KEV- + 6 random alphanumeric)
 * - SKU uniqueness validation
 * - Product creation with defaults
 * - Event publishing for audit trail
 */
@Service
public class CreateProductUseCase {

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductCountPort productCountPort;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanLimitGuard planLimitGuard;

    public CreateProductUseCase(ProductRepository productRepository,
                               ApplicationEventPublisher eventPublisher,
                               ProductCountPort productCountPort,
                               SubscriptionRepository subscriptionRepository,
                               PlanLimitGuard planLimitGuard) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
        this.productCountPort = productCountPort;
        this.subscriptionRepository = subscriptionRepository;
        this.planLimitGuard = planLimitGuard;
    }

    /**
     * DTO for create product request
     */
    public record CreateProductDto(
        String name,
        String description, 
        String sku,
        UUID categoryId,
        Integer price,
        Integer buyPrice,
        Integer transportCost,
        Integer stockQuantity,
        UUID actorId,  // Added for audit trail
        Integer minimumThreshold,  // Story 2.4: CSV import can set initial threshold
        String status,  // Story 4.3: POS draft creation — "DRAFT" or null (defaults to ACTIVE)
        String actorRole,  // Story 14.10: "OWNER" or "EMPLOYEE" — for notification dispatch
        String actorName,  // Story 14.10: display name for notifications
        String storeName   // Story 14.10: employee's assigned store name (nullable for OWNER)
    ) {
        /** Backward-compatible constructor without minimumThreshold + status. */
        public CreateProductDto(
                String name, String description, String sku, UUID categoryId,
                Integer price, Integer buyPrice, Integer transportCost,
                Integer stockQuantity, UUID actorId) {
            this(name, description, sku, categoryId, price, buyPrice, transportCost,
                 stockQuantity, actorId, 0, null, null, null, null);
        }
        /** Backward-compatible constructor without status. */
        public CreateProductDto(
                String name, String description, String sku, UUID categoryId,
                Integer price, Integer buyPrice, Integer transportCost,
                Integer stockQuantity, UUID actorId, Integer minimumThreshold) {
            this(name, description, sku, categoryId, price, buyPrice, transportCost,
                 stockQuantity, actorId, minimumThreshold, null, null, null, null);
        }
    }

    /**
     * Execute product creation
     * 
     * @param dto product creation data
     * @return created product
     * @throws IllegalArgumentException if validation fails
     */
    public Product execute(CreateProductDto dto) {
        // Validate input
        validateInput(dto);

        // Name uniqueness check (AC8 — Story 2.4)
        if (productRepository.existsByName(dto.name())) {
            throw new DomainException(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS,
                    "Un produit avec le nom '" + dto.name() + "' existe déjà dans votre catalogue");
        }

        // Generate or validate SKU
        String sku = dto.sku() != null ? dto.sku() : generateSku();
        validateSkuUniqueness(sku);

        // Create product with defaults
        var now = Instant.now();
        var product = new Product(
            UUID.randomUUID(),
            dto.name().trim(),
            dto.description(),
            sku,
            dto.categoryId(),
            dto.price(),
            dto.buyPrice(),
            dto.transportCost() != null ? dto.transportCost() : 0,
            dto.stockQuantity(),
            false, // archived = false (default)
            "DRAFT".equalsIgnoreCase(dto.status()) ? ProductStatus.DRAFT : ProductStatus.ACTIVE,
            dto.minimumThreshold() != null ? dto.minimumThreshold() : 0,
            now, // createdAt
            now  // updatedAt
        );

        // Plan limit check — all roles (mirrors CreateDraftProductUseCase AC3)
        int currentCount = productCountPort.countActiveProducts();
        var planType = subscriptionRepository.findActivePlan()
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif"))
                .getPlanType();
        planLimitGuard.checkProductLimit(planType, currentCount);

        // Save product
        var saved = productRepository.save(product);

        // Publish domain event for audit trail
        String tenantId = TenantContext.getCurrentTenant();
        eventPublisher.publishEvent(new ProductCreatedEvent(
            saved.getId(),
            saved.getName(),
            saved.getSku(),
            tenantId,
            dto.actorId(),
            dto.actorRole(),
            dto.actorName(),
            dto.storeName(),
            Instant.now()
        ));
        
        return saved;
    }

    // ── Private helper methods ────────────────────────────────────────────

    private void validateInput(CreateProductDto dto) {
        if (dto.name() == null || dto.name().trim().isEmpty()) {
            throw new IllegalArgumentException("Product name cannot be null or empty");
        }
    }

    private void validateSkuUniqueness(String sku) {
        if (productRepository.findBySku(sku).isPresent()) {
            throw new IllegalArgumentException("Product with SKU '" + sku + "' already exists");
        }
    }

    private String generateSku() {
        // Generate KEV- + 6 random alphanumeric characters (uppercase)
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder("KEV-");
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return sb.toString();
    }
}
package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.event.ProductActivatedEvent;
import com.keevo.catalog.product.domain.event.ProductUpdatedEvent;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * UpdateProductUseCase — Business logic for updating products.
 *
 * <p>Story 2.4 (AC6): if the product is currently in DRAFT status and the caller
 * is an OWNER, the status is promoted to ACTIVE. Only OWNERs may promote a draft.
 * The corresponding {@link com.keevo.messaging.notification.domain.model.DraftPendingValidation}
 * record is acknowledged so it disappears from the badge counter.
 */
@Service
public class UpdateProductUseCase {

    private final ProductRepository           productRepository;
    private final DraftNotificationRepository draftRepository;
    private final ApplicationEventPublisher   eventPublisher;
    private final ObjectMapper                objectMapper;

    public UpdateProductUseCase(ProductRepository productRepository,
                                DraftNotificationRepository draftRepository,
                                ApplicationEventPublisher eventPublisher,
                                ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.draftRepository   = draftRepository;
        this.eventPublisher    = eventPublisher;
        this.objectMapper      = objectMapper;
    }

    /**
     * DTO for update product request.
     *
     * <p>{@code actorRole}: "OWNER" or "EMPLOYEE". Required for DRAFT→ACTIVE guard (Story 2.4).
     * Use the existing 10-arg constructor from controllers; the new field defaults to "OWNER"
     * via the backward-compatible constructor.
     */
    public record UpdateProductDto(
        UUID    id,
        String  name,
        String  description,
        String  sku,
        UUID    categoryId,
        Integer price,
        Integer buyPrice,
        Integer transportCost,
        Integer stockQuantity,
        UUID    actorId,
        String  actorRole     // "OWNER" or "EMPLOYEE" — Story 2.4 draft promotion guard
    ) {
        /** Backward-compatible constructor: actorRole defaults to "OWNER". */
        public UpdateProductDto(UUID id, String name, String description, String sku,
                                UUID categoryId, Integer price, Integer buyPrice,
                                Integer transportCost, Integer stockQuantity, UUID actorId) {
            this(id, name, description, sku, categoryId, price, buyPrice,
                 transportCost, stockQuantity, actorId, "OWNER");
        }
    }

    public Product execute(UpdateProductDto dto) {
        // Find existing product
        var existing = productRepository.findById(dto.id())
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + dto.id()));

        // Validate input
        if (dto.name() == null || dto.name().trim().isEmpty()) {
            throw new IllegalArgumentException("Product name cannot be null or empty");
        }

        // Story 2.4 (AC6): DRAFT → ACTIVE promotion requires OWNER role
        boolean isPromotion = existing.getStatus() == ProductStatus.DRAFT;
        if (isPromotion && !"OWNER".equals(dto.actorRole())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut valider un produit en brouillon");
        }

        // Resolve SKU: keep existing if not provided
        String resolvedSku = (dto.sku() != null) ? dto.sku() : existing.getSku();

        // Validate SKU uniqueness (only if SKU actually changed)
        if (!existing.getSku().equals(resolvedSku)) {
            if (productRepository.findBySku(resolvedSku).isPresent()) {
                throw new IllegalArgumentException("Product with SKU '" + resolvedSku + "' already exists");
            }
        }

        // Resolve status: DRAFT → ACTIVE when owner updates; otherwise preserve
        ProductStatus resolvedStatus = isPromotion ? ProductStatus.ACTIVE : existing.getStatus();

        // Create updated product (null-safe: preserve existing values if not provided)
        var updated = new Product(
            existing.getId(),
            dto.name().trim(),
            dto.description(),
            resolvedSku,
            dto.categoryId() != null ? dto.categoryId() : existing.getCategoryId(),
            dto.price() != null ? dto.price() : existing.getPriceValue(),
            dto.buyPrice() != null ? dto.buyPrice() : existing.getBuyPriceValue(),
            dto.transportCost() != null ? dto.transportCost() : existing.getTransportCostValue(),
            dto.stockQuantity() != null ? dto.stockQuantity() : existing.getStockQuantity(),
            existing.getArchived(),
            resolvedStatus,
            existing.getMinimumThreshold(),
            existing.getCreatedAt(),
            Instant.now() // updatedAt refreshed
        );

        // Save and publish event
        var saved = productRepository.save(updated);
        String tenantId = TenantContext.getCurrentTenant();

        // Acknowledge draft notification so the badge counter decrements (AC6)
        if (isPromotion) {
            draftRepository.findByProductId(saved.getId()).ifPresent(draft -> {
                draft.acknowledge();
                draftRepository.saveUpdated(draft);
            });

            // Story 4.3 — publish ProductActivatedEvent for cascade auto-validation
            eventPublisher.publishEvent(new ProductActivatedEvent(
                    saved.getId(), dto.actorId(), tenantId, Instant.now()));
        }

        // Publish domain event for audit trail with valueBefore/valueAfter
        String valueBefore = toJson(existing);
        String valueAfter = toJson(saved);
        
        eventPublisher.publishEvent(new ProductUpdatedEvent(
            saved.getId(),
            saved.getName(),
            valueBefore,
            valueAfter,
            tenantId,
            dto.actorId(),
            Instant.now()
        ));
        
        return saved;
    }

    /**
     * Serialize Product to JSON for audit trail.
     * Returns "{}" on error — audit is best-effort.
     */
    private String toJson(Product product) {
        try {
            // Use HashMap to allow null values (Map.of disallows null)
            var map = new java.util.HashMap<String, Object>();
            map.put("id", product.getId());
            map.put("name", product.getName());
            map.put("description", product.getDescription());
            map.put("sku", product.getSku());
            map.put("categoryId", product.getCategoryId());
            map.put("archived", product.getArchived());
            map.put("status", product.getStatus());
            map.put("createdAt", product.getCreatedAt());
            map.put("updatedAt", product.getUpdatedAt());
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
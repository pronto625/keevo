package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.event.ProductUpdatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * UpdateProductUseCase — Business logic for updating products
 */
@Service
public class UpdateProductUseCase {

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public UpdateProductUseCase(ProductRepository productRepository, 
                               ApplicationEventPublisher eventPublisher,
                               ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * DTO for update product request
     */
    public record UpdateProductDto(
        UUID id,
        String name,
        String description,
        String sku,
        UUID categoryId,
        Integer price,
        Integer buyPrice,
        Integer transportCost,
        Integer stockQuantity,
        UUID actorId  // Added for audit trail
    ) {}

    public Product execute(UpdateProductDto dto) {
        // Find existing product
        var existing = productRepository.findById(dto.id())
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + dto.id()));

        // Validate input
        if (dto.name() == null || dto.name().trim().isEmpty()) {
            throw new IllegalArgumentException("Product name cannot be null or empty");
        }

        // Resolve SKU: keep existing if not provided
        String resolvedSku = (dto.sku() != null) ? dto.sku() : existing.getSku();

        // Validate SKU uniqueness (only if SKU actually changed)
        if (!existing.getSku().equals(resolvedSku)) {
            if (productRepository.findBySku(resolvedSku).isPresent()) {
                throw new IllegalArgumentException("Product with SKU '" + resolvedSku + "' already exists");
            }
        }

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
            existing.getStatus(),
            existing.getCreatedAt(),
            Instant.now() // updatedAt refreshed
        );

        // Save and publish event
        var saved = productRepository.save(updated);
        
        // Publish domain event for audit trail with valueBefore/valueAfter
        String tenantId = TenantContext.getCurrentTenant();
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
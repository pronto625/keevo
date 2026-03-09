package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.event.ProductCreatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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

    public CreateProductUseCase(ProductRepository productRepository, 
                               ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
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
        Integer stockQuantity,
        UUID actorId  // Added for audit trail
    ) {}

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
            dto.stockQuantity(),
            false, // archived = false (default)
            ProductStatus.ACTIVE, // status = ACTIVE (default for catalogue products)
            now, // createdAt
            now  // updatedAt
        );

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
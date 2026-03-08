package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.event.ProductArchivedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;

import java.util.UUID;

/**
 * ArchiveProductUseCase — Business logic for archiving products (soft delete)
 */
@Service
public class ArchiveProductUseCase {

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ArchiveProductUseCase(ProductRepository productRepository, 
                                ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * DTO for archive product request
     */
    public record ArchiveProductDto(
        UUID productId,
        UUID actorId  // Added for audit trail
    ) {}

    public void execute(ArchiveProductDto dto) {
        // Validate product exists
        var product = productRepository.findById(dto.productId())
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + dto.productId()));

        // Archive product (soft delete)
        productRepository.archive(dto.productId());
        
        // Publish domain event for audit trail
        String tenantId = TenantContext.getCurrentTenant();
        eventPublisher.publishEvent(new ProductArchivedEvent(
            product.getId(),
            product.getName(),
            product.getSku(),
            tenantId,
            dto.actorId(),
            Instant.now()
        ));
        
        // Note: No return value needed for archive operation
    }
}
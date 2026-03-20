package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.event.ProductUnarchivedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * UnarchiveProductUseCase — Business logic for restoring an archived product.
 */
@Service
public class UnarchiveProductUseCase {

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UnarchiveProductUseCase(ProductRepository productRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
    }

    public record UnarchiveProductDto(
        UUID productId,
        UUID actorId
    ) {}

    public void execute(UnarchiveProductDto dto) {
        var product = productRepository.findById(dto.productId())
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + dto.productId()));

        productRepository.unarchive(dto.productId());

        String tenantId = TenantContext.getCurrentTenant();
        eventPublisher.publishEvent(new ProductUnarchivedEvent(
            product.getId(),
            product.getName(),
            product.getSku(),
            tenantId,
            dto.actorId(),
            Instant.now()
        ));
    }
}

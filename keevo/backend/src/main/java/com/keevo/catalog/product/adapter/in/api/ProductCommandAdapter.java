package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.CreateDraftProductInput;
import com.keevo.catalog.api.CreateProductInput;
import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.api.RegisterProductInput;
import com.keevo.catalog.api.UpdateProductInput;
import com.keevo.catalog.product.application.usecase.ArchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateDraftProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.UnarchiveProductUseCase;
import com.keevo.catalog.product.application.usecase.UpdateProductUseCase;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.event.ProductCreatedEvent;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implements the published {@link ProductCommandPort} by delegating to the existing catalog use
 * cases and repository (Story 12.2a). Business rules, events and transactions stay in the
 * delegates; this adapter only maps API records to internal commands.
 */
@Component
class ProductCommandAdapter implements ProductCommandPort {

    private final CreateProductUseCase createProduct;
    private final UpdateProductUseCase updateProduct;
    private final ArchiveProductUseCase archiveProduct;
    private final UnarchiveProductUseCase unarchiveProduct;
    private final CreateDraftProductUseCase createDraftProduct;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    ProductCommandAdapter(CreateProductUseCase createProduct,
                          UpdateProductUseCase updateProduct,
                          ArchiveProductUseCase archiveProduct,
                          UnarchiveProductUseCase unarchiveProduct,
                          CreateDraftProductUseCase createDraftProduct,
                          ProductRepository productRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.createProduct = createProduct;
        this.updateProduct = updateProduct;
        this.archiveProduct = archiveProduct;
        this.unarchiveProduct = unarchiveProduct;
        this.createDraftProduct = createDraftProduct;
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public ProductView create(String tenantSchema, CreateProductInput in) {
        TenantContext.requireCurrent(tenantSchema);
        Product created = createProduct.execute(new CreateProductUseCase.CreateProductDto(
                in.name(), in.description(), in.sku(), in.categoryId(),
                in.price(), in.buyPrice(), in.transportCost(), in.stockQuantity(),
                in.actorId(), 0, null, null, null, null, in.id()));
        return ProductViews.of(created);
    }

    @Override
    public ProductView update(String tenantSchema, UpdateProductInput in) {
        TenantContext.requireCurrent(tenantSchema);
        Product updated = updateProduct.execute(new UpdateProductUseCase.UpdateProductDto(
                in.productId(), in.name(), in.description(), in.sku(), in.categoryId(),
                in.price(), in.buyPrice(), in.transportCost(), in.stockQuantity(),
                in.actorId(), in.actorRole()));
        return ProductViews.of(updated);
    }

    @Override
    public void archive(String tenantSchema, UUID productId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        archiveProduct.execute(new ArchiveProductUseCase.ArchiveProductDto(productId, actorId));
    }

    @Override
    public void unarchive(String tenantSchema, UUID productId, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        unarchiveProduct.execute(new UnarchiveProductUseCase.UnarchiveProductDto(productId, actorId));
    }

    @Override
    public ProductView createDraft(String tenantSchema, CreateDraftProductInput in) {
        TenantContext.requireCurrent(tenantSchema);
        Product draft = createDraftProduct.execute(new CreateDraftProductUseCase.CreateDraftCommand(
                in.id(), in.name(), in.description(), in.categoryId(),
                in.price(), in.buyPrice(), in.transportCost(),
                in.actorId(), in.actorRole(), in.actorName()));
        return ProductViews.of(draft);
    }

    @Override
    public ProductView register(String tenantSchema, RegisterProductInput in) {
        TenantContext.requireCurrent(tenantSchema);
        Product product = new Product(
                in.id(), in.name(), null, in.sku(),
                in.categoryId(), in.price(), 0, 0,
                0, false, ProductStatus.ACTIVE,
                0, in.createdAt(), in.createdAt());
        Product saved = productRepository.save(product);
        // 12.2b: the catalog owns and publishes its product-created event (same payload as before)
        eventPublisher.publishEvent(new ProductCreatedEvent(
                saved.getId(), saved.getName(), saved.getSku(),
                TenantContext.getCurrentTenant(), in.actorId(),
                in.actorRole(), in.actorName(), in.storeName(),
                in.createdAt()));
        return ProductViews.of(saved);
    }

    @Override
    public ProductView changeMinimumThreshold(String tenantSchema, UUID productId, int threshold) {
        TenantContext.requireCurrent(tenantSchema);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));
        return ProductViews.of(productRepository.save(product.withMinimumThreshold(threshold)));
    }
}

package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the published {@link ProductQueryPort} by delegating to the catalog's product
 * repository (Story 12.2a). No transaction of its own: it joins the caller's.
 */
@Component
class ProductQueryAdapter implements ProductQueryPort {

    private final ProductRepository productRepository;

    ProductQueryAdapter(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public Optional<ProductView> findById(String tenantSchema, UUID productId) {
        TenantContext.requireCurrent(tenantSchema);
        return productRepository.findById(productId).map(ProductViews::of);
    }

    @Override
    public Optional<ProductView> findByName(String tenantSchema, String name) {
        TenantContext.requireCurrent(tenantSchema);
        return productRepository.findByName(name).map(ProductViews::of);
    }

    @Override
    public boolean existsByName(String tenantSchema, String name) {
        TenantContext.requireCurrent(tenantSchema);
        return productRepository.existsByName(name);
    }

    @Override
    public boolean existsBySku(String tenantSchema, String sku) {
        TenantContext.requireCurrent(tenantSchema);
        return productRepository.findBySku(sku).isPresent();
    }

    @Override
    public List<ProductView> findAllActive(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return productRepository.findAllActive().stream().map(ProductViews::of).toList();
    }

    @Override
    public List<ProductView> findAllByIds(String tenantSchema, List<UUID> productIds) {
        TenantContext.requireCurrent(tenantSchema);
        return productRepository.findAllByIds(productIds).stream().map(ProductViews::of).toList();
    }

    @Override
    public List<ProductView> findByCategoryIds(String tenantSchema, List<UUID> categoryIds) {
        TenantContext.requireCurrent(tenantSchema);
        return productRepository.findByCategoryIds(categoryIds).stream().map(ProductViews::of).toList();
    }

    @Override
    public Map<UUID, String> findPhotoUrlsByIds(String tenantSchema, List<UUID> productIds) {
        TenantContext.requireCurrent(tenantSchema);
        return productRepository.findPhotoUrlsByIds(productIds);
    }

    @Override
    public Optional<String> findStatusName(String tenantSchema, UUID productId) {
        TenantContext.requireCurrent(tenantSchema);
        return productRepository.findById(productId).map(product -> product.getStatus().name());
    }
}

package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
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
    public Optional<ProductView> findById(UUID productId) {
        return productRepository.findById(productId).map(ProductViews::of);
    }

    @Override
    public Optional<ProductView> findByName(String name) {
        return productRepository.findByName(name).map(ProductViews::of);
    }

    @Override
    public boolean existsByName(String name) {
        return productRepository.existsByName(name);
    }

    @Override
    public boolean existsBySku(String sku) {
        return productRepository.findBySku(sku).isPresent();
    }

    @Override
    public List<ProductView> findAllActive() {
        return productRepository.findAllActive().stream().map(ProductViews::of).toList();
    }

    @Override
    public List<ProductView> findAllByIds(List<UUID> productIds) {
        return productRepository.findAllByIds(productIds).stream().map(ProductViews::of).toList();
    }

    @Override
    public List<ProductView> findByCategoryIds(List<UUID> categoryIds) {
        return productRepository.findByCategoryIds(categoryIds).stream().map(ProductViews::of).toList();
    }

    @Override
    public Map<UUID, String> findPhotoUrlsByIds(List<UUID> productIds) {
        return productRepository.findPhotoUrlsByIds(productIds);
    }

    @Override
    public Optional<String> findStatusName(UUID productId) {
        return productRepository.findById(productId).map(product -> product.getStatus().name());
    }
}

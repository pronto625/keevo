package com.keevo.catalog.product.adapter.out.persistence;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.infrastructure.persistence.entity.ProductJpaEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/**
 * ProductRepositoryAdapter — JPA implementation of ProductRepository port
 * 
 * Handles tenant isolation via existing TenantContext and per-tenant schemas.
 * Converts between domain Product and ProductJpaEntity.
 */
@Component
public class ProductRepositoryAdapter implements ProductRepository {

    private final ProductSpringRepository springRepository;

    public ProductRepositoryAdapter(ProductSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public Product save(Product product) {
        var jpaEntity = toJpaEntity(product);
        var saved = springRepository.save(jpaEntity);
        return toDomain(saved);
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return springRepository.findById(id)
                .map(this::toDomain);
    }

    @Override
    public List<Product> findAll() {
        return springRepository.findAll().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Product> findAllActive() {
        return springRepository.findAllActive().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return springRepository.findBySku(sku)
                .map(this::toDomain);
    }

    @Override
    public void archive(UUID id) {
        springRepository.findById(id).ifPresent(product -> {
            product.setArchived(true);
            product.setUpdatedAt(Instant.now());
            springRepository.save(product);
        });
    }

    // ── Conversion methods ─────────────────────────────────────────────────────

    private ProductJpaEntity toJpaEntity(Product product) {
        return new ProductJpaEntity(
            product.getId(),
            product.getName(),
            product.getDescription(),
            product.getSku(),
            product.getCategoryId(),
            product.getPriceValue(),
            product.getBuyPriceValue(),
            product.getTransportCostValue(),
            product.getStockQuantity(),
            product.getMinimumThreshold(),
            null, // photoUrl - future story
            product.getArchived(),
            product.getStatus(),
            product.getCreatedAt(),
            product.getUpdatedAt()
        );
    }

    private Product toDomain(ProductJpaEntity entity) {
        return new Product(
            entity.getId(),
            entity.getName(),
            entity.getDescription(),
            entity.getSku(),
            entity.getCategoryId(),
            entity.getPrice(),
            entity.getBuyPrice(),
            entity.getTransportCost(),
            entity.getStockQuantity(),
            entity.getArchived(),
            entity.getStatus(),
            entity.getMinimumThreshold(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
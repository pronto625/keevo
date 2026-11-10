package com.keevo.catalog.product.adapter.out.persistence;

import com.keevo.catalog.product.adapter.out.persistence.entity.ProductJpaEntity;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
    public List<Product> searchActive(String search, int limit) {
        org.springframework.data.domain.Pageable page = org.springframework.data.domain.PageRequest.of(0,
                Math.max(1, limit));
        List<ProductJpaEntity> rows = search == null || search.isBlank()
                ? springRepository.findActiveByName(page)
                : springRepository.searchActive(containsPattern(search.strip()), page);
        return rows.stream().map(this::toDomain).toList();
    }

    /** {@code %search%}, lower-cased, with the user's {@code \ % _} escaped (literal). */
    static String containsPattern(String search) {
        String escaped = search.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return springRepository.findBySku(sku)
                .map(this::toDomain);
    }

    @Override
    public Optional<Product> findByName(String name) {
        return springRepository.findByNameIgnoreCase(name == null ? "" : name.trim())
                .map(this::toDomain);
    }

    @Override
    public boolean existsByName(String name) {
        return springRepository.existsByNameIgnoreCase(name == null ? "" : name.trim());
    }

    @Override
    public void archive(UUID id) {
        springRepository.findById(id).ifPresent(product -> {
            product.setArchived(true);
            product.setUpdatedAt(Instant.now());
            springRepository.save(product);
        });
    }

    @Override
    public void unarchive(UUID id) {
        springRepository.findById(id).ifPresent(product -> {
            product.setArchived(false);
            product.setUpdatedAt(Instant.now());
            springRepository.save(product);
        });
    }

    @Override
    public List<Product> findAllByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return springRepository.findAllByIds(ids).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Product> findByCategoryIds(List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return List.of();
        return springRepository.findByCategoryIds(categoryIds).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Map<UUID, String> findPhotoUrlsByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return springRepository.findPhotoUrlsByIds(ids).stream()
                .filter(row -> row[1] != null)
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> (String) row[1],
                        (a, b) -> a));
    }

    @Override
    public Optional<String> findPhotoUrl(UUID id) {
        return Optional.ofNullable(findPhotoUrlsByIds(List.of(id)).get(id));
    }

    @Override
    public boolean updatePhotoUrl(UUID id, String photoUrl, Instant updatedAt) {
        return springRepository.updatePhotoUrl(id, photoUrl, updatedAt) > 0;
    }

    @Override
    public boolean updateTrackStock(UUID id, boolean tracked, Instant updatedAt) {
        return springRepository.updateTrackStockIfDifferent(id, tracked, updatedAt) > 0;
    }

    // ── Story 20.1 ───────────────────────────────────────────────────────────────

    @Override
    public List<Product> lockForMerge(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return springRepository.lockAllByIdsOrdered(ids).stream().map(this::toDomain).toList();
    }

    @Override
    public void markMerged(UUID sourceId, UUID targetId, Instant now) {
        springRepository.markMerged(sourceId, targetId, now);
    }

    @Override
    public int repointMerged(UUID fromId, UUID toId, Instant now) {
        return springRepository.repointMerged(fromId, toId, now);
    }

    @Override
    public void updateBarcode(UUID id, String barcode, String productKey, Instant now) {
        springRepository.updateBarcode(id, barcode, productKey, now);
    }

    @Override
    public Map<UUID, UUID> findMergeTargets(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return springRepository.findMergeTargets(ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (UUID) row[1], (a, b) -> a));
    }

    @Override
    public List<Product> findByBarcodeOrKey(String code, String key, int limit) {
        return springRepository.findByBarcodeOrKey(code, key,
                        org.springframework.data.domain.PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    // ── Conversion methods ─────────────────────────────────────────────────────

    private ProductJpaEntity toJpaEntity(Product product) {
        var entity = new ProductJpaEntity(
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
            null, // photoUrl — not updatable through save (see ProductJpaEntity / updatePhotoUrl)
            product.getArchived(),
            product.getStatus(),
            product.getCreatedAt(),
            product.getUpdatedAt()
        );
        entity.setTrackStock(product.isTrackStock()); // Story 17.12
        entity.setBarcode(product.getBarcode());         // Story 20.1
        entity.setProductKey(product.getProductKey());   // Story 20.1 — computed when name / barcode change
        entity.setPriceManual(product.isPriceManual());  // Story 20.1
        // mergedIntoId: not insertable/updatable through a save (merge only)
        return entity;
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
            entity.getTrackStock(), // Story 17.12 — null (legacy row) → tracked
            entity.getBarcode(),
            entity.getProductKey(), // stored key, not recomputed on read
            Boolean.TRUE.equals(entity.getPriceManual()),
            entity.getMergedIntoId(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
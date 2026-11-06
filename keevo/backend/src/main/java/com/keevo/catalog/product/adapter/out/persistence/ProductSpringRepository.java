package com.keevo.catalog.product.adapter.out.persistence;

import com.keevo.catalog.product.adapter.out.persistence.entity.ProductJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ProductSpringRepository — Spring Data JPA repository for products
 * 
 * Handles tenant isolation automatically via per-tenant schema context
 */
@Repository
public interface ProductSpringRepository extends JpaRepository<ProductJpaEntity, UUID> {
    
    /**
     * Find all non-archived products
     */
    @Query("SELECT p FROM ProductJpaEntity p WHERE p.archived = false")
    List<ProductJpaEntity> findAllActive();
    
    /**
     * Find product by SKU (unique per tenant)
     */
    Optional<ProductJpaEntity> findBySku(String sku);

    /**
     * Check name uniqueness (case-insensitive) — Story 2.4.
     * Uses LOWER() functional index on products(lower(name)).
     */
    @Query("SELECT COUNT(p) > 0 FROM ProductJpaEntity p WHERE LOWER(p.name) = LOWER(:name)")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    /** At most one row: uq_products_name is unique on lower(name). */
    @Query("SELECT p FROM ProductJpaEntity p WHERE LOWER(p.name) = LOWER(:name)")
    Optional<ProductJpaEntity> findByNameIgnoreCase(@Param("name") String name);

    @Query("SELECT p FROM ProductJpaEntity p WHERE p.id IN :ids")
    List<ProductJpaEntity> findAllByIds(@Param("ids") List<UUID> ids);

    @Query("SELECT p FROM ProductJpaEntity p WHERE p.categoryId IN :categoryIds AND p.archived = false")
    List<ProductJpaEntity> findByCategoryIds(@Param("categoryIds") List<UUID> categoryIds);

    @Query("SELECT p.id, p.photoUrl FROM ProductJpaEntity p WHERE p.id IN :ids")
    List<Object[]> findPhotoUrlsByIds(@Param("ids") List<UUID> ids);

    /**
     * Sets (or clears) the product photo and bumps {@code updated_at} so the sync pull sends the
     * new photo URL to the other devices. The only write of {@code photo_url} (the column is not
     * updatable through a product save).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductJpaEntity p SET p.photoUrl = :photoUrl, p.updatedAt = :updatedAt WHERE p.id = :id")
    int updatePhotoUrl(@Param("id") UUID id, @Param("photoUrl") String photoUrl,
                       @Param("updatedAt") Instant updatedAt);

    /**
     * Story 17.12 — conditional switch of the stock tracking: writes only when the value differs, so two
     * concurrent identical requests change it once (the second one re-evaluates the WHERE after the first commits
     * and updates 0 rows). Bumps {@code updated_at} for the pull.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductJpaEntity p SET p.trackStock = :tracked, p.updatedAt = :updatedAt "
            + "WHERE p.id = :id AND p.trackStock <> :tracked")
    int updateTrackStockIfDifferent(@Param("id") UUID id, @Param("tracked") boolean tracked,
                                    @Param("updatedAt") Instant updatedAt);

    // ── Story 20.1 — merge, redirection, barcode lookup ─────────────────────────────────────────

    /** Both merge rows, locked ({@code FOR UPDATE}) in id order: two crossed merges cannot deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductJpaEntity p WHERE p.id IN :ids ORDER BY p.id")
    List<ProductJpaEntity> lockAllByIdsOrdered(@Param("ids") Collection<UUID> ids);

    /** The merged source: archived, pointing at its target, bumped for the pull. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductJpaEntity p SET p.archived = true, p.mergedIntoId = :targetId, p.updatedAt = :now "
            + "WHERE p.id = :sourceId")
    int markMerged(@Param("sourceId") UUID sourceId, @Param("targetId") UUID targetId, @Param("now") Instant now);

    /** Products merged into {@code fromId} now point at {@code toId}: chains stay flat (one hop). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductJpaEntity p SET p.mergedIntoId = :toId, p.updatedAt = :now WHERE p.mergedIntoId = :fromId")
    int repointMerged(@Param("fromId") UUID fromId, @Param("toId") UUID toId, @Param("now") Instant now);

    /** Sets the barcode and its recomputed key (merge: the target inherits the source barcode). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductJpaEntity p SET p.barcode = :barcode, p.productKey = :productKey, p.updatedAt = :now "
            + "WHERE p.id = :id")
    int updateBarcode(@Param("id") UUID id, @Param("barcode") String barcode,
                      @Param("productKey") String productKey, @Param("now") Instant now);

    /** (id, merged_into_id) of the given products that were merged — one indexed query for a whole batch. */
    @Query("SELECT p.id, p.mergedIntoId FROM ProductJpaEntity p WHERE p.id IN :ids AND p.mergedIntoId IS NOT NULL")
    List<Object[]> findMergeTargets(@Param("ids") Collection<UUID> ids);

    /**
     * Products carrying this code as barcode or as barcode key ({@code bc:<code>}), merged ones included: direct
     * (not merged) and visible ones first. Served by the partial indexes idx_products_barcode / idx_products_product_key.
     */
    @Query("SELECT p FROM ProductJpaEntity p WHERE p.barcode = :code OR p.productKey = :key "
            + "ORDER BY CASE WHEN p.mergedIntoId IS NULL THEN 0 ELSE 1 END, p.archived, p.updatedAt DESC")
    List<ProductJpaEntity> findByBarcodeOrKey(@Param("code") String code, @Param("key") String key,
                                              Pageable page);
}

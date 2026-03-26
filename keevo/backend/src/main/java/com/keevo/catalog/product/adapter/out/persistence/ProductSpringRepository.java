package com.keevo.catalog.product.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.entity.ProductJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    @Query("SELECT p FROM ProductJpaEntity p WHERE p.id IN :ids")
    List<ProductJpaEntity> findAllByIds(@Param("ids") List<UUID> ids);

    @Query("SELECT p FROM ProductJpaEntity p WHERE p.categoryId IN :categoryIds AND p.archived = false")
    List<ProductJpaEntity> findByCategoryIds(@Param("categoryIds") List<UUID> categoryIds);

    @Query("SELECT p.id, p.photoUrl FROM ProductJpaEntity p WHERE p.id IN :ids")
    List<Object[]> findPhotoUrlsByIds(@Param("ids") List<UUID> ids);
}
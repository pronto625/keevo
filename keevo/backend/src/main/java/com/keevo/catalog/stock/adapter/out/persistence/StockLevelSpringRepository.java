package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.entity.StockLevelJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/**
 * StockLevelSpringRepository — Spring Data JPA repository for stock_levels.
 */
public interface StockLevelSpringRepository extends JpaRepository<StockLevelJpaEntity, UUID> {

    @Query("SELECT s FROM StockLevelJpaEntity s WHERE s.productId = :productId AND s.storeId = :storeId AND s.variantId IS NULL")
    Optional<StockLevelJpaEntity> findByProductAndStore(
            @Param("productId") UUID productId,
            @Param("storeId") UUID storeId);

    @Query("SELECT s FROM StockLevelJpaEntity s WHERE s.productId = :productId AND s.variantId = :variantId AND s.storeId = :storeId")
    Optional<StockLevelJpaEntity> findByProductVariantAndStore(
            @Param("productId") UUID productId,
            @Param("variantId") UUID variantId,
            @Param("storeId") UUID storeId);

    @Query("SELECT s FROM StockLevelJpaEntity s WHERE s.productId = :productId")
    List<StockLevelJpaEntity> findAllByProduct(@Param("productId") UUID productId);
}

package com.keevo.catalog.stock.domain.port.out;

import com.keevo.catalog.stock.domain.entity.StockLevel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * StockLevelRepository — port (interface) for stock level persistence.
 *
 * <p>GoF: Repository pattern separates domain from infrastructure.
 * Implementation lives in adapter/out/persistence.
 */
public interface StockLevelRepository {

    /** Find stock level for a simple product (no variant) in a store. */
    Optional<StockLevel> findByProductAndStore(UUID productId, UUID storeId);

    /** Find stock level for a specific variant in a store. */
    Optional<StockLevel> findByProductVariantAndStore(UUID productId, UUID variantId, UUID storeId);

    /** Persist (create or update) a stock level. */
    StockLevel save(StockLevel level);

    /** Retrieve all stock levels for a product across all stores. */
    List<StockLevel> findAllByProduct(UUID productId);

    /** Find all stock levels for a store (used by inventory counting). */
    List<StockLevel> findAllByStoreId(UUID storeId);
}

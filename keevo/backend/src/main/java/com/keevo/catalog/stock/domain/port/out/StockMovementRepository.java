package com.keevo.catalog.stock.domain.port.out;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

/**
 * StockMovementRepository — port for stock movement audit trail persistence.
 */
public interface StockMovementRepository {

    /** Persist a new stock movement record (immutable — no update). */
    StockMovement save(StockMovement movement);

    /**
     * Paginated history for a product with optional filters.
     *
     * @param productId    required
     * @param movementType optional filter
     * @param from         optional — movements at or after this timestamp
     * @param to           optional — movements at or before this timestamp
     * @param storeId      optional filter
     * @param pageable     pagination and sorting
     */
    Page<StockMovement> findByProductId(UUID productId, MovementType movementType,
                                        Instant from, Instant to, UUID storeId, Pageable pageable);
}

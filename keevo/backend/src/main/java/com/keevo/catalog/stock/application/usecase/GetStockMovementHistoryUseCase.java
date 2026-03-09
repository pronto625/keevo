package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.port.out.StockMovementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * GetStockMovementHistoryUseCase — paginated, filtered history of stock movements.
 *
 * <p>All filters are optional; only productId is required.
 * Results are sorted by createdAt DESC (most recent first).
 *
 * Story 2.3.
 */
@Service
public class GetStockMovementHistoryUseCase {

    private final StockMovementRepository stockMovementRepository;

    public GetStockMovementHistoryUseCase(StockMovementRepository stockMovementRepository) {
        this.stockMovementRepository = stockMovementRepository;
    }

    /**
     * @param productId    required — the product whose history to fetch
     * @param movementType optional filter
     * @param from         optional — inclusive start timestamp
     * @param to           optional — inclusive end timestamp
     * @param storeId      optional filter
     * @param page         0-based page number (default 0)
     * @param size         page size (default 20, max 100)
     * @return page of StockMovement records
     */
    @Transactional(readOnly = true)
    public Page<StockMovement> execute(UUID productId, MovementType movementType,
                                       Instant from, Instant to, UUID storeId,
                                       int page, int size) {
        int clampedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(page, clampedSize,
                Sort.by(Sort.Direction.DESC, "occurredAt"));

        return stockMovementRepository.findByProductId(
                productId, movementType, from, to, storeId, pageable);
    }
}

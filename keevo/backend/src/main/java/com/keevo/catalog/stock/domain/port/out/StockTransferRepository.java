package com.keevo.catalog.stock.domain.port.out;

import com.keevo.catalog.stock.domain.model.StockTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

/**
 * StockTransferRepository — out port for stock transfer persistence.
 *
 * <p>Persists the transfer header (stock_transfers table). The two
 * stock_movements records are written by StockOperationService.
 * Story 3.3.
 */
public interface StockTransferRepository {

    StockTransfer save(StockTransfer transfer);

    java.util.Optional<StockTransfer> findById(UUID id);

    Page<StockTransfer> findByFilters(
        UUID sourceStoreId,
        UUID destinationStoreId,
        Instant from,
        Instant to,
        Pageable pageable
    );
}

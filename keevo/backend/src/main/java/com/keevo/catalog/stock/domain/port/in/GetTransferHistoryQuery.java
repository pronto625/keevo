package com.keevo.catalog.stock.domain.port.in;

import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * GetTransferHistoryQuery — query object for paginated transfer history.
 *
 * @param sourceStoreId      optional filter
 * @param destinationStoreId optional filter
 * @param from               optional — transfers at or after this timestamp
 * @param to                 optional — transfers at or before this timestamp
 * @param page               0-based page number
 * @param size               page size (default 25)
 * @param storeId            optional — transfers where this store is source OR destination
 * @param status             optional — only transfers in this status
 * Story 3.3; v1s-16-23 (storeId, status).
 */
public record GetTransferHistoryQuery(
    UUID sourceStoreId,
    UUID destinationStoreId,
    Instant from,
    Instant to,
    int page,
    int size,
    UUID storeId,
    TransferStatus status
) {

    /** Pre-v1s-16-23 arity — no store/status filter. */
    public GetTransferHistoryQuery(UUID sourceStoreId, UUID destinationStoreId,
                                   Instant from, Instant to, int page, int size) {
        this(sourceStoreId, destinationStoreId, from, to, page, size, null, null);
    }
}

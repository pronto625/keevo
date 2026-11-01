package com.keevo.inventory.transfer.domain.port.in;

import com.keevo.inventory.transfer.domain.model.StockTransfer.TransferStatus;

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
 * @param scopeStoreId       actor scope — the EMPLOYEE's assigned store ({@code null} for OWNER):
 *                           only transfers where it is source OR destination, ANDed with the filters
 * Story 3.3; v1s-16-23 (storeId, status); 16.10 (scopeStoreId).
 */
public record GetTransferHistoryQuery(
    UUID sourceStoreId,
    UUID destinationStoreId,
    Instant from,
    Instant to,
    int page,
    int size,
    UUID storeId,
    TransferStatus status,
    UUID scopeStoreId
) {
}

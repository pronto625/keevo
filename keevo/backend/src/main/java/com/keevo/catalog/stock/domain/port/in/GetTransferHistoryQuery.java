package com.keevo.catalog.stock.domain.port.in;

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
 * Story 3.3.
 */
public record GetTransferHistoryQuery(
    UUID sourceStoreId,
    UUID destinationStoreId,
    Instant from,
    Instant to,
    int page,
    int size
) {}

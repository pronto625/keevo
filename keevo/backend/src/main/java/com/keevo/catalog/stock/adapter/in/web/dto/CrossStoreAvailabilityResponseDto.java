package com.keevo.catalog.stock.adapter.in.web.dto;

import com.keevo.catalog.stock.domain.model.CrossStoreAvailabilityEntry;
import com.keevo.catalog.stock.domain.port.in.GetCrossStoreAvailabilityUseCase;
import com.keevo.store.store.domain.model.StoreType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CrossStoreAvailabilityResponseDto — response envelope for the cross-store availability endpoint.
 *
 * <p>Wraps product metadata and per-store stock entries. {@code refreshedAt} reflects
 * the query timestamp (same for all entries in one request).
 *
 * Story 3.4.
 */
public record CrossStoreAvailabilityResponseDto(
        UUID          productId,
        String        productName,
        List<Entry>   entries,
        Instant       refreshedAt
) {
    public record Entry(
            UUID      storeId,
            String    storeName,
            StoreType storeType,
            int       quantity,
            int       minimumThreshold,
            boolean   isLow
    ) {
        public static Entry from(CrossStoreAvailabilityEntry e) {
            return new Entry(
                    e.storeId(), e.storeName(), e.storeType(),
                    e.quantity(), e.minimumThreshold(), e.isLow()
            );
        }
    }

    public static CrossStoreAvailabilityResponseDto from(GetCrossStoreAvailabilityUseCase.Result result) {
        Instant refreshedAt = result.entries().isEmpty()
                ? Instant.now()
                : result.entries().get(0).refreshedAt();
        return new CrossStoreAvailabilityResponseDto(
                result.product().getId(),
                result.product().getName(),
                result.entries().stream().map(Entry::from).toList(),
                refreshedAt
        );
    }
}

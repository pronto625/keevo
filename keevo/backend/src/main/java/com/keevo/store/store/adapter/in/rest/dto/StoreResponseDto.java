package com.keevo.store.store.adapter.in.rest.dto;

import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;

import java.time.Instant;
import java.util.UUID;

/**
 * StoreResponseDto — Response body for store endpoints.
 * Story 3.1.
 */
public record StoreResponseDto(
        UUID id,
        String name,
        StoreType type,
        String address,
        String phone,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
    public static StoreResponseDto fromDomain(Store store) {
        return new StoreResponseDto(
                store.id(),
                store.name(),
                store.type(),
                store.address(),
                store.phone(),
                store.isActive(),
                store.createdAt(),
                store.updatedAt()
        );
    }
}

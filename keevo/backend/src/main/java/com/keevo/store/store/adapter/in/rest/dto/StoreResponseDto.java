package com.keevo.store.store.adapter.in.rest.dto;

import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreLogoUrls;
import com.keevo.store.api.StoreType;

import java.time.Instant;
import java.util.UUID;

/**
 * StoreResponseDto — Response body for store endpoints.
 * Story 3.1. Story 19.12 — additive Bref fields: {@code category} (key), {@code description}, {@code logoUrl}
 * ({@code /api/v1/stores/{id}/logo?v=<token>} when a logo is stored, {@link StoreLogoUrls}, else null — never the disk path), {@code niu},
 * {@code rccm}, {@code country} (never null, {@code CM} by default).
 */
public record StoreResponseDto(
        UUID id,
        String name,
        StoreType type,
        String address,
        String phone,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt,
        String category,
        String description,
        String logoUrl,
        String niu,
        String rccm,
        String country
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
                store.updatedAt(),
                store.category() == null ? null : store.category().key(),
                store.description(),
                logoUrl(store),
                store.niu(),
                store.rccm(),
                store.country()
        );
    }

    /** The stable API path of the store logo, {@code null} when none. */
    public static String logoUrl(Store store) {
        return StoreLogoUrls.of(store.id(), store.logoPath());
    }
}

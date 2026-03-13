package com.keevo.store.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Store — Immutable domain model for a store or warehouse.
 *
 * <p>Pure Java record — no framework dependencies.
 * Story 3.1 — Store & Warehouse management.
 */
public record Store(
        UUID id,
        String name,
        StoreType type,
        String address,     // nullable
        String phone,       // nullable
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {}

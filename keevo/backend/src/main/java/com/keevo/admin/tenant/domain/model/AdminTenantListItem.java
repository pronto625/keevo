package com.keevo.admin.tenant.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * AdminTenantListItem — read-only projection of a tenant for the Super Admin list view.
 *
 * <p>Pure Java record — no Spring/JPA dependency.
 * Loaded via JdbcTemplate from the public schema.
 *
 * <p>plan: "FREE" | "PAID" (PREMIUM + PREMIUM_TRIAL both map to "PAID" at the DTO layer)
 * <p>status: "ACTIVE" | "DELETION_PENDING" | "SUSPENDED"
 */
public record AdminTenantListItem(
        UUID id,
        String code,
        String name,
        String ownerPhone,
        String plan,
        String status,
        Instant registeredAt,
        Instant lastActivityAt,
        int storeCount,
        int employeeCount,
        Instant deletionScheduledAt
) {}

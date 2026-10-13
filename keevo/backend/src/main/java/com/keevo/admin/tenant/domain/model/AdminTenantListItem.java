package com.keevo.admin.tenant.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * AdminTenantListItem — read-only projection of a tenant for the Super Admin list view.
 *
 * <p>Pure Java record — no Spring/JPA dependency.
 * Loaded via JdbcTemplate from the public schema.
 *
 * <p>plan: "FREE" | "PREMIUM_TRIAL" | "PREMIUM" — {@code public.tenants.plan_type}, kept in step with the
 * tenant-schema subscription since Story 9-2 (labelled in French by the dashboard). The list filter
 * takes "FREE" | "PREMIUM_TRIAL" | "PREMIUM" and still the legacy "PAID" (PREMIUM + PREMIUM_TRIAL).
 * <p>status: "ACTIVE" | "DELETION_PENDING" | "SUSPENDED"
 * <p>ownerName: the owner's first + last name when an {@code employees} row exists for them, else
 * « Propriétaire » (spec 9-1 completion). {@code code} stays internal: it is never serialized.
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
        Instant deletionScheduledAt,
        String ownerName
) {

    /** Item whose owner name is not resolved yet (« Propriétaire »). */
    public AdminTenantListItem(UUID id, String code, String name, String ownerPhone, String plan,
                               String status, Instant registeredAt, Instant lastActivityAt,
                               int storeCount, int employeeCount, Instant deletionScheduledAt) {
        this(id, code, name, ownerPhone, plan, status, registeredAt, lastActivityAt,
                storeCount, employeeCount, deletionScheduledAt, com.keevo.admin.AdminLabels.OWNER);
    }
}

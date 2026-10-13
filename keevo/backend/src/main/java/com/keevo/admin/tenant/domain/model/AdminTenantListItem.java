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
 * still takes "PAID" (PREMIUM + PREMIUM_TRIAL) or "FREE".
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

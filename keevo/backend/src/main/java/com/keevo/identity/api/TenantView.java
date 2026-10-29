package com.keevo.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant (row of {@code public.tenants}) as seen by other modules (Story 12.2d).
 *
 * @param code                public tenant code ({@code KV-XXXXXX})
 * @param schemaName          tenant schema ({@code kv_xxxxxx})
 * @param deletionScheduledAt null unless a deletion is pending
 */
public record TenantView(
        UUID id,
        String code,
        String schemaName,
        String name,
        TenantStatus status,
        PlanType planType,
        Instant createdAt,
        Instant deletionScheduledAt
) {
    /** True for an {@link TenantStatus#ACTIVE} tenant. */
    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}

package com.keevo.shared.event.catalog.plan;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.event.catalog.TenantScopedEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * PlanDowngradedEvent — the super admin moved a tenant back to the FREE plan (Story 9-2).
 *
 * @param actorId      super admin user id
 * @param tenantId     tenant UUID
 * @param schemaName   tenant schema (owner recipient lookup)
 * @param previousPlan plan before the downgrade
 * @param reason       optional free-text reason given by the admin
 * @param occurredAt   downgrade time
 */
public record PlanDowngradedEvent(
        UUID actorId,
        UUID tenantId,
        String schemaName,
        PlanType previousPlan,
        String reason,
        Instant occurredAt
) implements TenantScopedEvent {

    /** Story 12.4 — tenant key of the module-listener binding: the schema, not the {@code UUID tenantId}. */
    @Override
    public String tenantSchema() {
        return schemaName;
    }
}

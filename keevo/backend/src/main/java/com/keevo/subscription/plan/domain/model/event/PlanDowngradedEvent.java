package com.keevo.subscription.plan.domain.model.event;

import com.keevo.identity.auth.domain.model.PlanType;

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
) {}

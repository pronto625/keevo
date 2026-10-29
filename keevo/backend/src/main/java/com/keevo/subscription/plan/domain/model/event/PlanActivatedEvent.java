package com.keevo.subscription.plan.domain.model.event;

import com.keevo.identity.api.PlanType;

import java.time.Instant;
import java.util.UUID;

/**
 * PlanActivatedEvent — the super admin activated Premium for a tenant after an off-platform
 * payment (Story 9-2). Published inside the activation transaction; the owner WhatsApp message
 * is sent after commit.
 *
 * @param actorId      super admin user id
 * @param tenantId     tenant UUID ({@code public.tenants.id})
 * @param schemaName   tenant schema (owner recipient lookup)
 * @param previousPlan plan before the activation
 * @param expiresAt    new Premium expiry (end of the chosen day, WAT)
 * @param amountXaf    recorded payment amount
 * @param occurredAt   activation time
 */
public record PlanActivatedEvent(
        UUID actorId,
        UUID tenantId,
        String schemaName,
        PlanType previousPlan,
        Instant expiresAt,
        long amountXaf,
        Instant occurredAt
) {}

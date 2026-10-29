package com.keevo.subscription.plan.domain.model.event;

import com.keevo.identity.api.PlanType;

import java.time.Instant;
import java.util.UUID;

/**
 * PlanExpiredEvent — the nightly expiry job downgraded an expired PREMIUM / PREMIUM_TRIAL
 * subscription to FREE (Story 9-2; replaces the former {@code SubscriptionDowngradedEvent} TODO).
 * One event per downgraded tenant, published inside that tenant's downgrade transaction.
 *
 * @param tenantId     tenant UUID
 * @param schemaName   tenant schema (owner recipient lookup)
 * @param previousPlan expired plan (PREMIUM or PREMIUM_TRIAL)
 * @param occurredAt   downgrade time
 */
public record PlanExpiredEvent(
        UUID tenantId,
        String schemaName,
        PlanType previousPlan,
        Instant occurredAt
) {}

package com.keevo.subscription.plan.domain.port.in;

import com.keevo.identity.auth.domain.model.PlanType;

import java.time.Instant;

/**
 * ActivatePlanCommand — Input record for admin plan activation/upgrade.
 */
public record ActivatePlanCommand(
        String actorId,
        String targetTenantId,
        PlanType newPlan,
        Instant expiresAt    // null for FREE plan; required for PREMIUM
) {}

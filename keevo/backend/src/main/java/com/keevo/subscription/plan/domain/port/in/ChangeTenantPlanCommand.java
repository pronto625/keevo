package com.keevo.subscription.plan.domain.port.in;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.subscription.plan.domain.model.PlanChangeCause;

import java.time.Instant;
import java.util.UUID;

/**
 * ChangeTenantPlanCommand — set a tenant's plan from an admin context (Story 9-2).
 *
 * @param tenantId   tenant UUID ({@code public.tenants.id})
 * @param schemaName tenant schema, already validated by the caller
 * @param newPlan    target plan; its limits are applied
 * @param expiresAt  new expiry, null for FREE
 * @param cause      recorded in {@code tenant_plan_events}
 * @param actorId    super admin id, null for a system change
 * @param reason     optional free text
 */
public record ChangeTenantPlanCommand(
        UUID tenantId,
        String schemaName,
        PlanType newPlan,
        Instant expiresAt,
        PlanChangeCause cause,
        UUID actorId,
        String reason
) {}

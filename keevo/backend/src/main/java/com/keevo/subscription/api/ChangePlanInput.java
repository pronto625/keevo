package com.keevo.subscription.api;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant plan change from an admin flow (Story 9-2, published in 12.2d).
 *
 * @param tenantId   tenant UUID ({@code public.tenants.id})
 * @param tenantSchema tenant schema ({@code kv_xxxxxx}), already validated by the caller
 * @param newPlan    target plan; its limits are applied
 * @param expiresAt  new expiry, null for FREE
 * @param cause      recorded in {@code tenant_plan_events}
 * @param actorId    super admin id, null for a system change
 * @param reason     optional free text
 */
public record ChangePlanInput(
        UUID tenantId,
        String tenantSchema,
        PlanType newPlan,
        Instant expiresAt,
        PlanChangeCause cause,
        UUID actorId,
        String reason
) {
    public ChangePlanInput {
        if (tenantId == null || tenantSchema == null || tenantSchema.isBlank() || newPlan == null || cause == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Tenant, schema, plan and cause are required");
        }
    }
}

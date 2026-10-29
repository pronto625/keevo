package com.keevo.subscription.api;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Super-admin plan activation (legacy {@code /admin/subscriptions/{tenantId}/activate}, Story 12.2d).
 *
 * @param actorId      super admin id
 * @param tenantSchema tenant schema name ({@code kv_xxxxxx})
 * @param expiresAt    null for FREE; required for PREMIUM
 */
public record ActivatePlanInput(UUID actorId, String tenantSchema, PlanType newPlan, Instant expiresAt) {
    public ActivatePlanInput {
        if (tenantSchema == null || tenantSchema.isBlank() || newPlan == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Tenant and plan are required");
        }
    }
}

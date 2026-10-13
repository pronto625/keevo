package com.keevo.admin.operations.adapter.in.rest.dto;

import com.keevo.admin.operations.domain.model.InactiveTenant;

import java.time.Instant;

/** Contract with keevo/dashboard/src/types/operations.ts (InactiveTenant). */
public record InactiveTenantDto(
        String tenantId,
        String tenantName,
        String plan,
        Instant lastActivityAt,
        Long daysSinceLastActivity
) {
    public static InactiveTenantDto from(InactiveTenant m) {
        return new InactiveTenantDto(
                m.tenantId(), m.tenantName(), m.plan(), m.lastActivityAt(), m.daysSinceLastActivity());
    }
}

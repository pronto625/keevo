package com.keevo.admin.operations.adapter.in.rest.dto;

import com.keevo.admin.operations.domain.model.TenantActivity;

import java.time.Instant;

/** Contract with keevo/dashboard/src/types/operations.ts (TenantActivityRow).
 * Story 20.13: the count is the number of revenue documents (POS sales + counted invoices, 20.9 source),
 * not sales only — label it « Documents », never « Ventes ».
 */
public record TenantActivityDto(
        String tenantId,
        String tenantName,
        String plan,
        long gmv,
        long orderCount,
        Instant lastActivityAt,
        int storeCount
) {
    public static TenantActivityDto from(TenantActivity m) {
        return new TenantActivityDto(
                m.tenantId(), m.tenantName(), m.plan(), m.gmv(), m.orderCount(), m.lastActivityAt(), m.storeCount());
    }
}

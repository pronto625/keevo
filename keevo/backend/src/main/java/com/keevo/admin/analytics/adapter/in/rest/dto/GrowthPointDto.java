package com.keevo.admin.analytics.adapter.in.rest.dto;

import com.keevo.admin.analytics.domain.model.GrowthPoint;

/** Contract with keevo/dashboard/src/types/analytics.ts (GrowthPoint).
 * Story 20.13: the count is the number of revenue documents (POS sales + counted invoices, 20.9 source),
 * not sales only — label it « Documents », never « Ventes ».
 */
public record GrowthPointDto(String month, long newTenants, long cumulativeTenants, long salesCount, long gmv,
                             long mau) {
    public static GrowthPointDto from(GrowthPoint p) {
        return new GrowthPointDto(p.month(), p.newTenants(), p.cumulativeTenants(), p.salesCount(), p.gmv(), p.mau());
    }
}

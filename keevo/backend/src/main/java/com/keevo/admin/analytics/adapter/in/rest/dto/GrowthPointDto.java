package com.keevo.admin.analytics.adapter.in.rest.dto;

import com.keevo.admin.analytics.domain.model.GrowthPoint;

/** Contract with keevo/dashboard/src/types/analytics.ts (GrowthPoint). */
public record GrowthPointDto(String month, long newTenants, long cumulativeTenants, long salesCount, long gmv,
                             long mau) {
    public static GrowthPointDto from(GrowthPoint p) {
        return new GrowthPointDto(p.month(), p.newTenants(), p.cumulativeTenants(), p.salesCount(), p.gmv(), p.mau());
    }
}

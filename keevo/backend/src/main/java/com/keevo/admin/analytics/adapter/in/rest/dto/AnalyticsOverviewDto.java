package com.keevo.admin.analytics.adapter.in.rest.dto;

import com.keevo.admin.analytics.domain.model.AnalyticsOverview;

/** Contract with keevo/dashboard/src/types/analytics.ts (AnalyticsOverview). */
public record AnalyticsOverviewDto(
        long dau,
        long mau,
        Double dauMauRatio,
        long totalTenants,
        long totalStores,
        long activeStores,
        long totalProducts,
        long totalCompletedSales,
        long totalGmv
) {
    public static AnalyticsOverviewDto from(AnalyticsOverview m) {
        return new AnalyticsOverviewDto(m.dau(), m.mau(), m.dauMauRatio(), m.totalTenants(), m.totalStores(),
                m.activeStores(), m.totalProducts(), m.totalCompletedSales(), m.totalGmv());
    }
}

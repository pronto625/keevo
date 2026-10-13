package com.keevo.admin.analytics.domain.model;

/**
 * Platform KPIs (Story 9-4). Amounts in XAF.
 *
 * @param dau                 distinct active users today (WAT)
 * @param mau                 distinct active users over the last 30 days (today included)
 * @param dauMauRatio         dau / mau (0..1), null when mau = 0
 * @param totalTenants        non-DELETED tenants, KV-ADMIN excluded
 * @param totalStores         active stores of type STORE
 * @param activeStores        active STORE stores with ≥ 1 completed sale in the last 30 days
 * @param totalProducts       non-archived products, all tenant schemas
 * @param totalCompletedSales completed sales, all time
 * @param totalGmv            sum of completed sales, all time
 */
public record AnalyticsOverview(
        long dau,
        long mau,
        Double dauMauRatio,
        long totalTenants,
        long totalStores,
        long activeStores,
        long totalProducts,
        long totalCompletedSales,
        long totalGmv
) {}

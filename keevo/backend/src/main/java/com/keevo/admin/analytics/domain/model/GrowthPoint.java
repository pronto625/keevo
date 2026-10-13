package com.keevo.admin.analytics.domain.model;

/**
 * One calendar month (WAT) of the growth curves (Story 9-4).
 *
 * @param month             {@code YYYY-MM}
 * @param newTenants        tenants created during the month
 * @param cumulativeTenants tenants created up to the end of the month
 * @param salesCount        completed sales of the month
 * @param gmv               completed sales amount of the month (XAF)
 * @param mau               distinct active users during the month
 */
public record GrowthPoint(String month, long newTenants, long cumulativeTenants, long salesCount, long gmv, long mau) {}

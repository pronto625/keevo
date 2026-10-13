package com.keevo.admin.operations.domain.model;

/**
 * Platform-wide operational KPIs over a period (Story 9.7).
 *
 * @param totalGmv           sum of {@code total_amount} of COMPLETED sales, all tenants (XAF)
 * @param totalOrders        number of those sales
 * @param activeTenantsCount tenants with at least one COMPLETED sale in the period
 * @param newTenantsCount    tenants created in the period
 * @param periodLabel        French label of the period (e.g. « 7 derniers jours »)
 */
public record OperationsOverview(
        long totalGmv,
        long totalOrders,
        int activeTenantsCount,
        int newTenantsCount,
        String periodLabel
) {}

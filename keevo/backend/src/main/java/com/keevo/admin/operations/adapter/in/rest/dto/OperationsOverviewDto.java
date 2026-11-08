package com.keevo.admin.operations.adapter.in.rest.dto;

import com.keevo.admin.operations.domain.model.OperationsOverview;

/** Contract with keevo/dashboard/src/types/operations.ts (AdminOperationsOverview).
 * Story 20.13: the count is the number of revenue documents (POS sales + counted invoices, 20.9 source),
 * not sales only — label it « Documents », never « Ventes ».
 */
public record OperationsOverviewDto(
        long totalGmv,
        long totalOrders,
        int activeTenantsCount,
        int newTenantsCount,
        String periodLabel
) {
    public static OperationsOverviewDto from(OperationsOverview m) {
        return new OperationsOverviewDto(
                m.totalGmv(), m.totalOrders(), m.activeTenantsCount(), m.newTenantsCount(), m.periodLabel());
    }
}

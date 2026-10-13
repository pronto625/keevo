package com.keevo.admin.revenue.adapter.in.rest.dto;

import com.keevo.admin.revenue.domain.model.ChurnReport;

import java.util.List;

/** Contract with keevo/dashboard/src/types/revenue.ts (ChurnReport). {@code churnRate} is 0..1. */
public record ChurnReportDto(String month, int churnedCount, int payingAtMonthStart, double churnRate,
                             List<ChurnedTenantDto> tenants) {
    public static ChurnReportDto from(ChurnReport m) {
        return new ChurnReportDto(m.month().toString(), m.tenants().size(), m.payingAtMonthStart(), m.churnRate(),
                m.tenants().stream().map(ChurnedTenantDto::from).toList());
    }
}

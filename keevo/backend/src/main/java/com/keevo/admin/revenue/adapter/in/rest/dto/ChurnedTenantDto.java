package com.keevo.admin.revenue.adapter.in.rest.dto;

import com.keevo.admin.revenue.domain.model.ChurnedTenant;

/**
 * Contract with keevo/dashboard/src/types/revenue.ts (ChurnedTenant). No tenant id: the screen shows the
 * business name only (« Sans nom » when unnamed).
 */
public record ChurnedTenantDto(String tenantName, String planEndDate, Double tenureMonths, long lastGmv30d) {

    public static final String UNNAMED = "Sans nom";

    public static ChurnedTenantDto from(ChurnedTenant m) {
        String name = m.tenantName() == null || m.tenantName().isBlank() ? UNNAMED : m.tenantName();
        return new ChurnedTenantDto(name, m.planEndDate().toString(), m.tenureMonths(), m.lastGmv30d());
    }
}

package com.keevo.admin.revenue.adapter.in.rest.dto;

import com.keevo.admin.revenue.domain.model.RevenueTrendPoint;

/** Contract with keevo/dashboard/src/types/revenue.ts (RevenueTrendPoint). {@code month} is {@code YYYY-MM}. */
public record RevenueTrendPointDto(String month, long mrr, int payingTenants, int newPayingTenants, int churnedTenants) {
    public static RevenueTrendPointDto from(RevenueTrendPoint m) {
        return new RevenueTrendPointDto(m.month().toString(), m.mrr(), m.payingTenants(),
                m.newPayingTenants(), m.churnedTenants());
    }
}

package com.keevo.admin.revenue.adapter.in.rest.dto;

import com.keevo.admin.revenue.domain.model.RevenueSummary;

/** Contract with keevo/dashboard/src/types/revenue.ts (RevenueSummary). Dates are ISO yyyy-MM-dd, rates 0..1. */
public record RevenueSummaryDto(
        String from,
        String to,
        long mrr,
        long arr,
        long mrrPreviousMonth,
        Double mrrTrendPercent,
        int payingTenants,
        int freeTenants,
        int trialTenants,
        int premiumTenants,
        int premiumWithoutPayment,
        int newPayingTenants,
        int churnedTenants,
        int churnBase,
        double churnRate,
        int convertedTenants,
        int trialsEnded,
        double conversionRate,
        long revenueCollected
) {
    public static RevenueSummaryDto from(RevenueSummary m) {
        return new RevenueSummaryDto(
                m.from().toString(), m.to().toString(),
                m.mrr(), m.arr(), m.mrrPreviousMonth(), m.mrrTrendPercent(),
                m.payingTenants(), m.freeTenants(), m.trialTenants(),
                m.premiumTenants(), m.premiumWithoutPayment(),
                m.newPayingTenants(), m.churnedTenants(), m.churnBase(), m.churnRate(),
                m.convertedTenants(), m.trialsEnded(), m.conversionRate(),
                m.revenueCollected());
    }
}

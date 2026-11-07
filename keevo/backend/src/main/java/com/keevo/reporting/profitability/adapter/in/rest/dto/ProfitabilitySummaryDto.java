package com.keevo.reporting.profitability.adapter.in.rest.dto;

import com.keevo.reporting.profitability.domain.model.ProfitabilitySummary;

/**
 * Story 20.9 — REST response of {@code GET /api/v1/reporting/profitability/summary}: the period's revenue (same
 * figure as the dashboard), the margin of the lines of catalogue products, and the revenue of the lines without
 * product, whose margin is unknown (« Marge inconnue »).
 */
public record ProfitabilitySummaryDto(
        long totalRevenue,
        long revenueWithKnownMargin,
        long costOfKnownMargin,
        long grossMarginXaf,
        double marginPercent,
        long revenueWithUnknownMargin,
        int unknownMarginLines
) {
    public static ProfitabilitySummaryDto from(ProfitabilitySummary s) {
        return new ProfitabilitySummaryDto(s.totalRevenue(), s.revenueWithKnownMargin(), s.costOfKnownMargin(),
                s.grossMarginXaf(), s.marginPercent(), s.revenueWithUnknownMargin(), s.unknownMarginLines());
    }
}

package com.keevo.commerce.sale.adapter.in.rest.dto;

import com.keevo.commerce.sale.domain.model.DayClosureSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * DayClosureSummaryDto — Response DTO for day closure summary.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
public record DayClosureSummaryDto(
        int totalSales,
        int totalRevenue,
        String topProductId,
        String topProductName,
        int topProductQty,
        int cashAmount,
        int momoAmount,
        int pendingSalesCount,
        int pendingSalesTotal
) {
    public static DayClosureSummaryDto fromDomain(DayClosureSummary summary) {
        return new DayClosureSummaryDto(
                summary.totalSales(),
                summary.totalRevenue(),
                summary.topProductId(),
                summary.topProductName(),
                summary.topProductQty(),
                summary.cashAmount(),
                summary.momoAmount(),
                summary.pendingSalesCount(),
                summary.pendingSalesTotal()
        );
    }
}

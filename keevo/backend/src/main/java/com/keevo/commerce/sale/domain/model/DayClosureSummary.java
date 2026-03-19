package com.keevo.commerce.sale.domain.model;

/**
 * DayClosureSummary — Value object containing day closure aggregates.
 * Pure Java record — no framework imports.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * @param totalSales        Count of COMPLETED sales (excludes PENDING_VALIDATION and CANCELLED)
 * @param totalRevenue      Sum of totalAmount for COMPLETED sales (XAF integer)
 * @param topProductId      Product ID with highest total quantity sold (nullable if no sales)
 * @param topProductName    Name of top product (nullable)
 * @param topProductQty     Total quantity sold of top product
 * @param cashAmount        Sum of COMPLETED sales paid in CASH
 * @param momoAmount        Sum of COMPLETED sales paid in MOBILE_MONEY
 * @param pendingSalesCount Count of PENDING_VALIDATION sales (shown separately in report)
 * @param pendingSalesTotal Sum of totalAmount for PENDING_VALIDATION sales
 */
public record DayClosureSummary(
        int totalSales,
        int totalRevenue,
        String topProductId,
        String topProductName,
        int topProductQty,
        int cashAmount,
        int momoAmount,
        int pendingSalesCount,
        int pendingSalesTotal
) {}

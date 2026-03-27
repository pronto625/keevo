package com.keevo.inventory.counting.domain.model;

/**
 * InventoryGapSummary — aggregate totals for the gap report.
 *
 * <p>Pure Java record — no framework deps.
 * Story 6.3 — Gap Analysis Report.
 */
public record InventoryGapSummary(
        int totalCounted,
        int totalConcordant,
        int totalSurplus,
        int totalShortage,
        long totalSurplusValueXaf,
        long totalShortageValueXaf
) {}

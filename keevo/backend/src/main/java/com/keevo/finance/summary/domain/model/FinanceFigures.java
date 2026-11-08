package com.keevo.finance.summary.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.11 — the raw figures of a period and of its comparison period, as read in SQL (one grouped query per side),
 * before Bref's math. Amounts unrounded.
 */
public final class FinanceFigures {

    private FinanceFigures() {
    }

    /**
     * Revenue totals: {@code ttc} VAT included, its {@code tax}, the {@code paid} part, {@code count} documents
     * (sales + invoices).
     */
    public record RevenueTotals(BigDecimal ttc, BigDecimal tax, BigDecimal paid, long count) {
        public static final RevenueTotals ZERO = new RevenueTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    /**
     * Revenue of the period ({@code totals}) and of the comparison period ({@code previous}); revenue excl. VAT per
     * bucket key and per store; {@code excludingTaxWithoutStore}: the revenue excl. VAT of the documents without store
     * (0 when a store is selected).
     */
    public record RevenueBreakdown(RevenueTotals totals, RevenueTotals previous,
                                   Map<String, BigDecimal> excludingTaxByBucket,
                                   Map<UUID, BigDecimal> excludingTaxByStore, BigDecimal excludingTaxWithoutStore) {
        public static final RevenueBreakdown EMPTY = new RevenueBreakdown(RevenueTotals.ZERO, RevenueTotals.ZERO,
                Map.of(), Map.of(), BigDecimal.ZERO);
    }

    public record ExpenseTotals(BigDecimal total, long count) {
        public static final ExpenseTotals ZERO = new ExpenseTotals(BigDecimal.ZERO, 0);
    }

    public record CategoryAmount(String category, String categoryLabel, BigDecimal amount) {
    }

    /** Expenses of the period and of the comparison period; per category (amount desc), per bucket key, per store. */
    public record ExpenseBreakdown(ExpenseTotals totals, ExpenseTotals previous, List<CategoryAmount> byCategory,
                                   Map<String, BigDecimal> byBucket, Map<UUID, BigDecimal> byStore) {
        public static final ExpenseBreakdown EMPTY = new ExpenseBreakdown(ExpenseTotals.ZERO, ExpenseTotals.ZERO,
                List.of(), Map.of(), Map.of());
    }
}

package com.keevo.finance.summary.domain.model;

import com.keevo.finance.summary.domain.model.FinanceMath.Delta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Story 20.11 — Bref's {@code FinanceSummary} ({@code api-bref/src/finance/finance.types.ts}) with Keevo's names for
 * the store ({@code storeId}, {@code storeName}, {@code byStore}) and the French label of each category. Amounts in
 * XAF, rounded to the cent like Bref.
 */
public record FinanceSummary(
        PeriodInfo period,
        Revenue revenue,
        Expenses expenses,
        Result result,
        Comparison comparison,
        List<CategoryShare> byCategory,
        List<SeriesPoint> series,
        List<StoreResult> byStore
) {

    /** {@code granularity}: {@code day} or {@code month}. */
    public record PeriodInfo(LocalDate startDate, LocalDate endDate, long days, String granularity) {
    }

    /**
     * {@code ttc}: revenue VAT included (20.9 source); {@code tax}: its VAT; {@code excludingTax} = ttc − tax, the base of
     * the result; {@code paid} / {@code unpaid}: the settled part and the rest; {@code invoiceCount}: sales + invoices.
     */
    public record Revenue(BigDecimal ttc, BigDecimal tax, BigDecimal excludingTax, BigDecimal paid, BigDecimal unpaid,
                          long invoiceCount) {
    }

    public record Expenses(BigDecimal total, long count, BigDecimal avgPerDay) {
    }

    /** {@code net} = revenue excl. VAT − expenses; percentages {@code null} when the revenue excl. VAT is 0. */
    public record Result(BigDecimal net, BigDecimal marginPct, BigDecimal expenseRatioPct) {
    }

    public record Comparison(Range previousPeriod, Delta revenue, Delta expenses, Delta result) {
    }

    public record Range(LocalDate startDate, LocalDate endDate) {
    }

    public record CategoryShare(String category, String categoryLabel, BigDecimal amount, BigDecimal sharePct) {
    }

    /** {@code key}: {@code yyyy-MM-dd} (granularity day) or {@code yyyy-MM} (month). */
    public record SeriesPoint(String key, BigDecimal revenue, BigDecimal expenses, BigDecimal result) {
    }

    /** {@code storeName}: the store's display name ({@code null} when unknown). */
    public record StoreResult(UUID storeId, String storeName, BigDecimal revenue, BigDecimal expenses,
                              BigDecimal result) {
    }
}

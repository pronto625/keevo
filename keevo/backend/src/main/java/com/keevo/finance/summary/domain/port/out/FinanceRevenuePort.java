package com.keevo.finance.summary.domain.port.out;

import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueBreakdown;
import com.keevo.finance.summary.domain.model.FinanceMath.Granularity;
import com.keevo.finance.summary.domain.model.FinanceMath.Period;

import java.util.UUID;

/**
 * Story 20.11 — the revenue the finance summary reads: the single revenue source of 20.9 (sales ∪ invoices − credit
 * notes, each counted once), on civil dates in WAT (FUS-NFR9), bounds included. {@code storeId} null = every store
 * (the documents without store in their own figure); a store = the store-scoped source, as everywhere in reporting.
 */
public interface FinanceRevenuePort {

    /**
     * One grouped query over {@code [comparison.start, period.end]} (the comparison period ends the day before
     * {@code period} starts): totals of both periods, revenue excl. VAT per bucket of {@code granularity} and, without
     * a store, per store.
     *
     * @param tenantSchema the schema of the current tenant context
     */
    RevenueBreakdown breakdown(String tenantSchema, Period period, Period comparison, UUID storeId,
                               Granularity granularity);
}

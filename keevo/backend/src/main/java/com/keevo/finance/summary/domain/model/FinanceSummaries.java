package com.keevo.finance.summary.domain.model;

import com.keevo.finance.summary.domain.model.FinanceFigures.ExpenseBreakdown;
import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueBreakdown;
import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueTotals;
import com.keevo.finance.summary.domain.model.FinanceMath.Granularity;
import com.keevo.finance.summary.domain.model.FinanceMath.Period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.keevo.finance.summary.domain.model.FinanceMath.computeDelta;
import static com.keevo.finance.summary.domain.model.FinanceMath.dec;
import static com.keevo.finance.summary.domain.model.FinanceMath.pct;
import static com.keevo.finance.summary.domain.model.FinanceMath.round2;

/**
 * Story 20.11 — Bref's {@code FinanceService.getSummary} assembly ({@code api-bref/src/finance/finance.service.ts}) on
 * figures already read: pure, O(buckets + categories + stores), on doubles like JavaScript ({@code Number(...)} of the
 * SQL sums). Same formulas, same rounding points: revenue = round2(ttc − tax), net = round2(revenue − expenses),
 * deltas on the rounded revenue and net and on the raw expenses.
 */
public final class FinanceSummaries {

    /** Label of the {@code byStore} row of the revenue without store (documents, until they carry one — 20-14). */
    public static final String WITHOUT_STORE = "Sans boutique";

    private FinanceSummaries() {
    }

    /**
     * @param previous   the comparison period (calendar month / year for {@code period=}, the equal-length window
     *                   before otherwise)
     * @param withStores whether {@code byStore} is filled (Bref: only without a store filter)
     * @param storeNames display names of the stores of {@code byStore}
     */
    public static FinanceSummary assemble(Period period, Period previous, RevenueBreakdown revenue,
                                          ExpenseBreakdown expenses, boolean withStores, Map<UUID, String> storeNames) {
        long days = FinanceMath.daysInclusive(period.startDate(), period.endDate());
        Granularity granularity = FinanceMath.pickGranularity(period.startDate(), period.endDate());
        RevenueTotals current = revenue.totals();
        RevenueTotals before = revenue.previous();
        double ttc = d(current.ttc());
        double expenseTotal = d(expenses.totals().total());
        double previousExpenses = d(expenses.previous().total());

        double excludingTax = round2(ttc - d(current.tax()));
        double net = round2(excludingTax - expenseTotal);
        double prevExcludingTax = round2(d(before.ttc()) - d(before.tax()));
        double prevNet = round2(prevExcludingTax - previousExpenses);

        List<FinanceSummary.CategoryShare> byCategory = expenses.byCategory().stream().map(c -> {
            double amount = round2(d(c.amount()));
            return new FinanceSummary.CategoryShare(c.category(), c.categoryLabel(), dec(amount),
                    pct(amount, expenseTotal));
        }).toList();

        List<FinanceSummary.SeriesPoint> series = FinanceMath.bucketKeys(period.startDate(), period.endDate(),
                granularity).stream().map(key -> {
                    double r = round2(d(revenue.excludingTaxByBucket().get(key)));
                    double e = round2(d(expenses.byBucket().get(key)));
                    return new FinanceSummary.SeriesPoint(key, dec(r), dec(e), dec(round2(r - e)));
                }).toList();

        List<FinanceSummary.StoreResult> byStore = List.of();
        if (withStores) {
            Set<UUID> ids = new LinkedHashSet<>(revenue.excludingTaxByStore().keySet());
            ids.addAll(expenses.byStore().keySet());
            List<FinanceSummary.StoreResult> rows = new ArrayList<>();
            for (UUID id : ids) {
                double r = round2(d(revenue.excludingTaxByStore().get(id)));
                double e = round2(d(expenses.byStore().get(id)));
                rows.add(new FinanceSummary.StoreResult(id, storeNames.get(id), dec(r), dec(e), dec(round2(r - e))));
            }
            double withoutStore = round2(d(revenue.excludingTaxWithoutStore()));
            if (withoutStore != 0) { // so that the rows add up to the total
                rows.add(new FinanceSummary.StoreResult(null, WITHOUT_STORE, dec(withoutStore), dec(0),
                        dec(withoutStore)));
            }
            rows.sort(Comparator.comparing(FinanceSummary.StoreResult::result).reversed()
                    .thenComparing(s -> s.storeName() == null ? "" : s.storeName())
                    .thenComparing(s -> s.storeId() == null ? "" : s.storeId().toString()));
            byStore = List.copyOf(rows);
        }

        return new FinanceSummary(
                new FinanceSummary.PeriodInfo(period.startDate(), period.endDate(), days, granularity.key()),
                new FinanceSummary.Revenue(dec(round2(ttc)), dec(round2(d(current.tax()))), dec(excludingTax),
                        dec(round2(d(current.paid()))), dec(round2(ttc - d(current.paid()))), current.count()),
                new FinanceSummary.Expenses(dec(round2(expenseTotal)), expenses.totals().count(),
                        dec(round2(expenseTotal / days))),
                new FinanceSummary.Result(dec(net), pct(net, excludingTax), pct(expenseTotal, excludingTax)),
                new FinanceSummary.Comparison(new FinanceSummary.Range(previous.startDate(), previous.endDate()),
                        computeDelta(excludingTax, prevExcludingTax),
                        computeDelta(expenseTotal, previousExpenses),
                        computeDelta(net, prevNet)),
                byCategory, series, byStore);
    }

    /** {@code Number(x)} of a SQL sum; absent = 0. */
    private static double d(BigDecimal n) {
        return n == null ? 0 : n.doubleValue();
    }
}

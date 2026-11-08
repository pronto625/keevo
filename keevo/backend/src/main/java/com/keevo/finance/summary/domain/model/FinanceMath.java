package com.keevo.finance.summary.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Story 20.11 — Bref's {@code api-bref/src/finance/finance-math.ts}, function for function. Every case of
 * {@code finance-math.spec.ts} is in the shared contract {@code keevo/contracts/finance-math-cases.json}. The arithmetic
 * is done on IEEE doubles like JavaScript ({@code Math.round(n * 100) / 100}; Java's {@link Math#round(double)} has the
 * same half-toward-+∞ rule), so the cents match Bref's even on binary artefacts (2.675 → 2.68, 1.005 → 1). Dates are
 * civil dates (Bref computes them in UTC on {@code yyyy-MM-dd} strings — the same thing on {@link LocalDate}).
 */
public final class FinanceMath {

    /** Bref: by day up to 45 days (a month), by month beyond (a quarter, a year). */
    public static final int MAX_DAILY_DAYS = 45;

    /** Bucket size of the series: {@code day} ({@code yyyy-MM-dd}) or {@code month} ({@code yyyy-MM}). */
    public enum Granularity {
        DAY("day"), MONTH("month");

        private final String key;

        Granularity(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** A period of civil dates, bounds included. */
    public record Period(LocalDate startDate, LocalDate endDate) {
    }

    /**
     * Bref's {@code Delta}: the previous value, the difference and its percentage ({@code null} when the previous value
     * is 0 — undefined).
     */
    public record Delta(BigDecimal previous, BigDecimal delta, BigDecimal deltaPct) {
    }

    private FinanceMath() {
    }

    /** Number of calendar days, bounds included. */
    public static long daysInclusive(LocalDate startDate, LocalDate endDate) {
        return ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /** The period of the same length just before {@code [startDate, endDate]}. */
    public static Period previousPeriod(LocalDate startDate, LocalDate endDate) {
        long days = daysInclusive(startDate, endDate);
        LocalDate prevEnd = startDate.minusDays(1);
        return new Period(prevEnd.minusDays(days - 1), prevEnd);
    }

    public static Granularity pickGranularity(LocalDate startDate, LocalDate endDate) {
        return daysInclusive(startDate, endDate) <= MAX_DAILY_DAYS ? Granularity.DAY : Granularity.MONTH;
    }

    /** Every bucket key of the period, those without activity included. */
    public static List<String> bucketKeys(LocalDate startDate, LocalDate endDate, Granularity granularity) {
        List<String> keys = new ArrayList<>();
        if (granularity == Granularity.DAY) {
            for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
                keys.add(d.toString());
            }
            return keys;
        }
        YearMonth end = YearMonth.from(endDate);
        YearMonth month = YearMonth.from(startDate);
        while (true) { // Bref: the start month always, then up to the end month
            keys.add(month.toString());
            if (!month.isBefore(end)) {
                return keys;
            }
            month = month.plusMonths(1);
        }
    }

    /** Bref's {@code round2}: {@code Math.round(n * 100) / 100} on doubles. */
    public static double round2(double n) {
        return Math.round(n * 100) / 100.0;
    }

    public static Delta computeDelta(double current, double previous) {
        return new Delta(dec(round2(previous)), dec(round2(current - previous)),
                previous == 0 ? null : dec(round2(((current - previous) / Math.abs(previous)) * 100)));
    }

    /** Percentage of {@code part} in {@code whole}, {@code null} when {@code whole} is 0. */
    public static BigDecimal pct(double part, double whole) {
        return whole == 0 ? null : dec(round2((part / whole) * 100));
    }

    /** A double as the response's decimal: 15000.0 → 15000, 12.5 → 12.5, never an exponent. */
    public static BigDecimal dec(double n) {
        BigDecimal stripped = BigDecimal.valueOf(n == 0 ? 0 : n).stripTrailingZeros(); // -0.0 → 0
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}

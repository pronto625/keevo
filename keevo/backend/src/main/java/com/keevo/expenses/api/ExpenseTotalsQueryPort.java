package com.keevo.expenses.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Story 20.11 — totals of the live (not deleted) expenses of a period and of its comparison period, for the finance
 * summary. Dates are the expenses' civil dates ({@code expense_date}), bounds included; {@code storeId} null = every
 * store. A tenant that never used the expenses module (no table) reads zeros — nothing is provisioned.
 */
public interface ExpenseTotalsQueryPort {

    /** Size of a series bucket: a calendar day ({@code yyyy-MM-dd}) or a month ({@code yyyy-MM}). */
    enum Bucket { DAY, MONTH }

    /** Sum and number of expenses. */
    record Totals(BigDecimal amount, long count) {
        public static final Totals ZERO = new Totals(BigDecimal.ZERO, 0);
    }

    /** Total of one category: Bref's key and its French label (« Loyer »…). */
    record CategoryTotal(String category, String categoryLabel, BigDecimal amount) {
    }

    /** Total of one bucket ({@code yyyy-MM-dd} or {@code yyyy-MM}); buckets without expense are absent. */
    record BucketTotal(String key, BigDecimal amount) {
    }

    /** Total of one store. */
    record StoreTotal(UUID storeId, BigDecimal amount) {
    }

    /**
     * Everything the summary needs, in <b>one</b> grouped query: the totals of the period and of the comparison period,
     * and for the period: per category (amount desc, then key), per bucket and — without a store filter — per store.
     */
    record Breakdown(Totals totals, Totals previous, List<CategoryTotal> byCategory, List<BucketTotal> byBucket,
                     List<StoreTotal> byStore) {
        public static final Breakdown EMPTY = new Breakdown(Totals.ZERO, Totals.ZERO, List.of(), List.of(), List.of());
    }

    /**
     * One grouped query over {@code [previousFrom, to]} (and {@code storeId} when given): {@code previousFrom} starts the
     * comparison period, which ends the day before {@code from}.
     */
    Breakdown breakdown(String tenantSchema, LocalDate previousFrom, LocalDate from, LocalDate to, UUID storeId,
                        Bucket bucket);
}

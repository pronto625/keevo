package com.keevo.expenses.expense.domain.model;

import java.util.Arrays;
import java.util.List;

/**
 * Story 20.10 — the list orders of Bref ({@code ExpenseSortBy}), each a total order on key columns all in the same
 * direction, so the next page is one row-value comparison on an index ({@code db/modules/expenses/V1}):
 * <ul>
 *   <li>{@code recent} (default): date, creation time, id — newest first (Bref: date DESC, createdAt DESC);</li>
 *   <li>{@code oldest}: the same, oldest first;</li>
 *   <li>{@code amount_desc}: amount, date, id — largest first (Bref: amount DESC, date DESC);</li>
 *   <li>{@code amount_asc}: the same, smallest first (ties by oldest date: one index serves both amount orders).</li>
 * </ul>
 * An unknown or absent value is {@code recent}, as in Bref.
 */
public enum ExpenseSort {
    RECENT("recent", Keys.BY_DATE, true),
    OLDEST("oldest", Keys.BY_DATE, false),
    AMOUNT_DESC("amount_desc", Keys.BY_AMOUNT, true),
    AMOUNT_ASC("amount_asc", Keys.BY_AMOUNT, false);

    /** The key columns of an order. */
    public enum Keys {
        /** {@code expense_date, created_at, id}. */
        BY_DATE(List.of("expense_date", "created_at", "id")),
        /** {@code amount, expense_date, id}. */
        BY_AMOUNT(List.of("amount", "expense_date", "id"));

        private final List<String> columns;

        Keys(List<String> columns) {
            this.columns = columns;
        }

        public List<String> columns() {
            return columns;
        }
    }

    private final String key;
    private final Keys keys;
    private final boolean descending;

    ExpenseSort(String key, Keys keys, boolean descending) {
        this.key = key;
        this.keys = keys;
        this.descending = descending;
    }

    public String key() {
        return key;
    }

    public Keys keys() {
        return keys;
    }

    public boolean descending() {
        return descending;
    }

    /** Bref fallback: anything else than the four keys is {@link #RECENT}. */
    public static ExpenseSort fromKey(String key) {
        return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst().orElse(RECENT);
    }
}

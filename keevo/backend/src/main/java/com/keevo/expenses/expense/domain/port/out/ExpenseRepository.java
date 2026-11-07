package com.keevo.expenses.expense.domain.port.out;

import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpenseCursor;
import com.keevo.expenses.expense.domain.model.ExpenseSort;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.10 — the {@code expenses} table of the current tenant schema (module-owned, JDBC). Runs in the caller's
 * transaction.
 */
public interface ExpenseRepository {

    /** Keyset cursor column of the sync pull ({@code idx_expenses_sync_keyset}). */
    String SYNC_CURSOR = "updated_at";

    /** Whether the module's table exists in the tenant schema (never provisioned → false). */
    boolean tablesExist();

    /** Any expense with this id, deleted ones included. */
    Optional<Expense> findById(UUID id);

    /** The same, row-locked for an edit or a deletion. */
    Optional<Expense> findByIdForUpdate(UUID id);

    /** Inserts the expense unless its id already exists ({@code ON CONFLICT DO NOTHING}); true when inserted. */
    boolean insertIfAbsent(Expense expense);

    /** Writes the content, store, author, {@code updated_at} and {@code archived_at} of an existing expense. */
    void update(Expense expense);

    /**
     * The filters of a list. {@code storeId} null = every store. {@code search} = a part of the label or note
     * (case-insensitive, already trimmed); {@code from} / {@code to} inclusive.
     */
    record Filter(UUID storeId, ExpenseCategory category, LocalDate from, LocalDate to, String search) {
    }

    /** At most {@code limit} live expenses matching {@code filter}, in {@code sort} order, strictly after {@code after}. */
    List<Expense> page(Filter filter, ExpenseSort sort, ExpenseCursor after, int limit);

    /** Number and sum of every live expense matching {@code filter} (one aggregate). */
    Totals totals(Filter filter);

    record Totals(long count, BigDecimal amount) {
    }

    /**
     * The sync pull delta: expenses (deleted included) with {@code updated_at >= since}, ordered by
     * ({@code updated_at}, id as text), strictly after the given position, of {@code storeId} only when not null.
     */
    List<Expense> delta(Instant since, Instant afterCursor, String afterId, UUID storeId, int limit);
}

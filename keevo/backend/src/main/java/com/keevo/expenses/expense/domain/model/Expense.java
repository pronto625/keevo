package com.keevo.expenses.expense.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 20.10 — one expense of a store (Bref {@code Expense}). {@code archivedAt} set = deleted (kept for the sync
 * tombstone, invisible everywhere else; a deletion is final).
 *
 * @param label Bref's {@code description} (≤ 500)
 * @param note  Keevo's extra free text (≤ 1000)
 */
public record Expense(
        UUID id,
        UUID storeId,
        ExpenseCategory category,
        BigDecimal amount,
        LocalDate expenseDate,
        String label,
        String note,
        ExpensePaymentMethod paymentMethod,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt
) {

    public Expense {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(expenseDate, "expenseDate");
    }

    /** A new expense with validated content. */
    public static Expense create(UUID id, UUID storeId, ExpenseContent content, UUID actorId, Instant now) {
        return new Expense(id, storeId, content.category(), content.amount(), content.expenseDate(), content.label(),
                content.note(), content.paymentMethod(), actorId, actorId, now, now, null);
    }

    /** The same expense with its whole content replaced (and possibly moved to {@code newStoreId}). */
    public Expense withContent(ExpenseContent content, UUID newStoreId, UUID actorId, Instant now) {
        return new Expense(id, newStoreId, content.category(), content.amount(), content.expenseDate(),
                content.label(), content.note(), content.paymentMethod(), createdBy, actorId, createdAt, now, null);
    }

    /** The same expense deleted. */
    public Expense archived(UUID actorId, Instant now) {
        return new Expense(id, storeId, category, amount, expenseDate, label, note, paymentMethod, createdBy, actorId,
                createdAt, now, now);
    }

    public boolean deleted() {
        return archivedAt != null;
    }
}

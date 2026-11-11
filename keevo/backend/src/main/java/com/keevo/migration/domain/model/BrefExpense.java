package com.keevo.migration.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.4 — a Bref {@code expense} row, unchanged ({@code expense.entity.ts}): {@code shopId}, {@code ownerId},
 * {@code createdById} as text, amount {@code decimal(14,2)}, {@code expenseDate} a civil date ({@code YYYY-MM-DD}),
 * soft-deleted with {@code deletedAt}.
 */
public record BrefExpense(UUID id, String ownerId, String shopId, String createdById, BigDecimal amount,
                          String category, String description, String expenseDate, String paymentMethod,
                          Instant createdAt, Instant updatedAt, Instant deletedAt) {

    public BrefExpense {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        amount = amount == null ? BigDecimal.ZERO : amount;
    }

    public String canonical() {
        return Canonical.of("id", id, "ownerId", ownerId, "shopId", shopId, "createdById", createdById,
                "amount", Canonical.decimal(amount), "category", category, "description", description,
                "expenseDate", expenseDate, "paymentMethod", paymentMethod, "createdAt", createdAt,
                "updatedAt", updatedAt, "deletedAt", deletedAt);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BrefExpense other && canonical().equals(other.canonical());
    }

    @Override
    public int hashCode() {
        return canonical().hashCode();
    }

    @Override
    public String toString() {
        return "BrefExpense[" + id + "]";
    }
}

package com.keevo.expenses.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.4 — expense writes of the Bref migration tool: Bref expenses inserted with their id, store, exact amount,
 * dates and author, archived when deleted in Bref; idempotent ({@code ON CONFLICT (id) DO NOTHING}), one statement,
 * no event. The tenant context must be bound to {@code tenantSchema}; joins the caller's transaction. A value a stored
 * expense cannot hold (unknown category or method, amount out of 0.01–999 999 999 999 or with more than 2 decimals,
 * label over 500) throws {@link IllegalArgumentException} before any write.
 */
public interface ExpenseImportPort {

    /** @return the ids inserted by this call */
    Set<UUID> importExpenses(String tenantSchema, List<ImportedExpense> expenses);

    /**
     * An expense to import.
     *
     * @param category      one of the 10 keys ({@link ExpenseLabels#isCategory})
     * @param paymentMethod a payment-method key, {@code null} when none
     * @param archivedAt    {@code null} = live
     */
    record ImportedExpense(UUID id, UUID storeId, String category, BigDecimal amount, LocalDate expenseDate,
                           String label, String paymentMethod, UUID createdBy, Instant createdAt, Instant updatedAt,
                           Instant archivedAt) {

        public ImportedExpense {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(storeId, "storeId");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(expenseDate, "expenseDate");
            Objects.requireNonNull(createdBy, "createdBy");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        @Override
        public String toString() {
            return "ImportedExpense[" + id + "]";
        }
    }
}

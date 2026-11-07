package com.keevo.expenses.expense.adapter.in.rest.dto;

import com.keevo.expenses.expense.domain.model.Expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An expense as the REST API and the sync pull ({@code expenses}) return it (Story 20.10): the category and payment
 * method by key (Bref's) with their French labels for display; the amount without trailing zeros (15000, 12.5).
 */
public record ExpenseResponseDto(
        UUID id,
        UUID storeId,
        String category,
        String categoryLabel,
        BigDecimal amount,
        LocalDate expenseDate,
        String label,
        String note,
        String paymentMethod,
        String paymentMethodLabel,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {

    public static ExpenseResponseDto fromDomain(Expense e) {
        return new ExpenseResponseDto(e.id(), e.storeId(), e.category().key(), e.category().label(),
                plain(e.amount()), e.expenseDate(), e.label(), e.note(),
                e.paymentMethod() == null ? null : e.paymentMethod().key(),
                e.paymentMethod() == null ? null : e.paymentMethod().label(),
                e.createdBy(), e.createdAt(), e.updatedAt());
    }

    /** 15000.00 → 15000, 12.50 → 12.5 (never an exponent). */
    public static BigDecimal plain(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}

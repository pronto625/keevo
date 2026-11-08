package com.keevo.expenses.api;

import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpensePaymentMethod;

import java.util.Arrays;
import java.util.Optional;

/**
 * Story 20.13 — the French labels of the expense codes for readers outside the module (the super-admin cross-account
 * expense list). The codes and labels stay defined once, in the expenses domain.
 */
public final class ExpenseLabels {

    private ExpenseLabels() {
    }

    /** True when {@code key} is one of the 10 category keys. */
    public static boolean isCategory(String key) {
        return ExpenseCategory.fromKey(key).isPresent();
    }

    /** The French label of a category key; empty for an unknown key. */
    public static Optional<String> categoryLabel(String key) {
        return ExpenseCategory.fromKey(key).map(ExpenseCategory::label);
    }

    /** The French label of a payment-method key; empty for null / unknown. */
    public static Optional<String> paymentMethodLabel(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(ExpensePaymentMethod.values()).filter(m -> m.key().equals(key)).findFirst()
                .map(ExpensePaymentMethod::label);
    }
}

package com.keevo.expenses.expense.adapter.in.rest.dto;

import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpensePaymentMethod;

import java.util.Arrays;
import java.util.List;

/**
 * Story 20.10 — the choices of the expense form: the 10 categories and 5 payment methods, by key with their French
 * label, in Bref's order.
 */
public record ExpenseOptionsDto(List<Option> categories, List<Option> paymentMethods) {

    public record Option(String key, String label) {
    }

    public static ExpenseOptionsDto all() {
        return new ExpenseOptionsDto(
                Arrays.stream(ExpenseCategory.values()).map(c -> new Option(c.key(), c.label())).toList(),
                Arrays.stream(ExpensePaymentMethod.values()).map(m -> new Option(m.key(), m.label())).toList());
    }
}

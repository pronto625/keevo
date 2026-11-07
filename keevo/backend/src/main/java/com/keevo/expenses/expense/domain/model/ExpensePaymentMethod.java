package com.keevo.expenses.expense.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Story 20.10 — how an expense was paid (optional), Bref's 5 keys ({@code EXPENSE_PAYMENT_METHODS}) with the Bref
 * app's French labels.
 */
public enum ExpensePaymentMethod {
    CASH("cash", "Espèces"),
    MOBILE_MONEY("mobile_money", "Mobile money"),
    BANK_TRANSFER("bank_transfer", "Virement"),
    CARD("card", "Carte"),
    OTHER("other", "Autre");

    /** The keys as a regular expression alternative (bean validation of the request bodies). */
    public static final String KEYS_PATTERN = "cash|mobile_money|bank_transfer|card|other";

    private final String key;
    private final String label;

    ExpensePaymentMethod(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** The method of a key (exact); empty when unknown or absent. */
    public static Optional<ExpensePaymentMethod> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(m -> m.key.equals(key)).findFirst();
    }
}

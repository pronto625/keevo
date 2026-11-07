package com.keevo.expenses.expense.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Story 20.10 — the 10 expense categories of Bref ({@code api-bref/src/expense/expense-categories.ts}), stored and
 * exchanged by their stable key (never a label); the French label is the Bref app's ({@code fr/expense.json}).
 */
public enum ExpenseCategory {
    GOODS_PURCHASE("goods_purchase", "Achat de marchandises"),
    RENT("rent", "Loyer"),
    SALARIES("salaries", "Salaires"),
    TRANSPORT("transport", "Transport"),
    UTILITIES("utilities", "Eau et électricité"),
    TELECOM("telecom", "Internet et téléphone"),
    MARKETING("marketing", "Publicité"),
    TAXES("taxes", "Taxes et impôts"),
    MAINTENANCE("maintenance", "Entretien et réparations"),
    OTHER("other", "Autre");

    /** The keys as a regular expression alternative (bean validation of the request bodies). */
    public static final String KEYS_PATTERN =
            "goods_purchase|rent|salaries|transport|utilities|telecom|marketing|taxes|maintenance|other";

    private final String key;
    private final String label;

    ExpenseCategory(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** The category of a key (exact, case-sensitive like Bref's {@code IsIn}); empty when unknown. */
    public static Optional<ExpenseCategory> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(c -> c.key.equals(key)).findFirst();
    }
}

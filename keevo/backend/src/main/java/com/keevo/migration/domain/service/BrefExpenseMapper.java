package com.keevo.migration.domain.service;

import com.keevo.expenses.api.ExpenseImportPort.ImportedExpense;
import com.keevo.expenses.api.ExpenseLabels;
import com.keevo.migration.domain.model.BrefExpense;
import com.keevo.migration.domain.model.ImportNote.Kind;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Story 24.4 — a Bref expense as a Keevo expense (pure): same id, the shop's store, the exact amount (Keevo keeps 2
 * decimals like Bref), the civil date, the description as label (cut to 500, noted), the category and method keys
 * (Bref and Keevo share them; an unknown category becomes « Autre », an unknown method is dropped — noted), the
 * author ({@code createdById}, else the owner), deleted → archived (noted). Blocking values are refused by
 * {@link BrefSalesValidator} before.
 */
public final class BrefExpenseMapper {

    public static final int MAX_LABEL = 500;
    static final String OTHER = "other";

    private BrefExpenseMapper() {
    }

    public record MappedExpense(ImportedExpense expense, List<Kind> notes) {
    }

    public static MappedExpense map(BrefExpense e, UUID ownerId) {
        List<Kind> notes = new ArrayList<>();
        String category = BrefMappings.trimmed(e.category());
        category = category == null ? null : category.toLowerCase(Locale.ROOT);
        if (category == null || !ExpenseLabels.isCategory(category)) {
            category = OTHER;
            notes.add(Kind.EXPENSE_CATEGORY_UNKNOWN);
        }
        String method = BrefMappings.trimmed(e.paymentMethod());
        method = method == null ? null : method.toLowerCase(Locale.ROOT);
        if (method != null && ExpenseLabels.paymentMethodLabel(method).isEmpty()) {
            method = null;
            notes.add(Kind.EXPENSE_METHOD_UNKNOWN);
        }
        String label = BrefMappings.trimmed(e.description());
        if (label != null && label.codePointCount(0, label.length()) > MAX_LABEL) {
            label = BrefMappings.cut(label, MAX_LABEL);
            notes.add(Kind.EXPENSE_FIELD_SHORTENED);
        }
        if (e.deletedAt() != null) {
            notes.add(Kind.EXPENSE_DELETED);
        }
        UUID createdBy = BrefMappings.uuidOrNull(e.createdById());
        LocalDate date = BrefDocumentPlanner.civilDate(e.expenseDate());
        if (date == null) {
            throw new IllegalArgumentException("expense date"); // blocked by the validator before
        }
        return new MappedExpense(new ImportedExpense(e.id(), BrefMappings.uuidOrNull(e.shopId()), category,
                e.amount(), date, label, method, createdBy == null ? ownerId : createdBy, e.createdAt(),
                e.updatedAt(), e.deletedAt()), List.copyOf(notes));
    }
}

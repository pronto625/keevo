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
 * author (the Keevo account of {@code createdById} — 24.7 {@code ImportRun.accountId} —, else the owner, noted), deleted → archived (noted). Blocking values are refused by
 * {@link BrefSalesValidator} before.
 */
public final class BrefExpenseMapper {

    public static final int MAX_LABEL = 500;
    static final String OTHER = "other";

    private BrefExpenseMapper() {
    }

    public record MappedExpense(ImportedExpense expense, List<Kind> notes) {
    }

    public static MappedExpense map(BrefExpense e, UUID ownerAccount) {
        return map(e, ownerAccount, id -> id);
    }

    /** @param accounts the Keevo account of a Bref user ({@code null} when none: not imported, or only invited) */
    public static MappedExpense map(BrefExpense e, UUID ownerAccount, java.util.function.Function<UUID, UUID> accounts) {
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
        UUID createdBy = BrefDocumentPlanner.author(e.createdById(), ownerAccount,
                new BrefDocumentPlanner.Lookup(java.util.Map.of(), java.util.Map.of(), accounts), notes,
                Kind.AUTHOR_REPLACED);
        LocalDate date = BrefDocumentPlanner.civilDate(e.expenseDate());
        if (date == null) {
            throw new IllegalArgumentException("expense date"); // blocked by the validator before
        }
        return new MappedExpense(new ImportedExpense(e.id(), BrefMappings.uuidOrNull(e.shopId()), category,
                e.amount(), date, label, method, createdBy, e.createdAt(),
                e.updatedAt(), e.deletedAt()), List.copyOf(notes));
    }
}

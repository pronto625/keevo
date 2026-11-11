package com.keevo.expenses.expense.domain.port.out;

import com.keevo.expenses.api.ExpenseImportPort.ImportedExpense;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Story 24.4 — set-based persistence of the Bref expenses import on the bound tenant schema. */
public interface ExpenseImportWriter {

    /** Inserts the expenses whose id is absent, one statement; returns the ids inserted. */
    Set<UUID> insertAbsent(List<ImportedExpense> expenses);
}

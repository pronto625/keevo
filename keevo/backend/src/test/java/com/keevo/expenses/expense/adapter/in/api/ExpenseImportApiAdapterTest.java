package com.keevo.expenses.expense.adapter.in.api;

import com.keevo.expenses.api.ExpenseImportPort.ImportedExpense;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 24.4 — the expenses import port refuses what a stored expense cannot hold, before any write. */
@DisplayName("24.4 — expenses import port checks")
class ExpenseImportApiAdapterTest {

    private static final Instant T0 = Instant.parse("2026-03-05T09:00:00Z");

    private static ImportedExpense expense(String category, String amount, String method, String label) {
        return new ImportedExpense(UUID.randomUUID(), UUID.randomUUID(), category, new BigDecimal(amount),
                LocalDate.parse("2026-03-05"), label, method, UUID.randomUUID(), T0, T0, null);
    }

    @Test
    @DisplayName("valid: a known category and method, 2 decimals, a label of 500")
    void valid() {
        assertThatCode(() -> ExpenseImportApiAdapter.check(expense("rent", "15000.50", "cash", "x".repeat(500))))
                .doesNotThrowAnyException();
        assertThatCode(() -> ExpenseImportApiAdapter.check(expense("other", "0.01", null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refused: unknown category or method, amount below 0.01, too large or with 3 decimals, label over 500")
    void refused() {
        assertThatThrownBy(() -> ExpenseImportApiAdapter.check(expense("loisirs", "10", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpenseImportApiAdapter.check(expense("rent", "10", "cheque", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpenseImportApiAdapter.check(expense("rent", "0", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpenseImportApiAdapter.check(expense("rent", "1000000000000", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpenseImportApiAdapter.check(expense("rent", "1.005", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpenseImportApiAdapter.check(expense("rent", "10", null, "x".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

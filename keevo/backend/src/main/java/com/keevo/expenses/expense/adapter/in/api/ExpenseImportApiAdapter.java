package com.keevo.expenses.expense.adapter.in.api;

import com.keevo.expenses.api.ExpenseImportPort;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpenseContent;
import com.keevo.expenses.expense.domain.model.ExpensePaymentMethod;
import com.keevo.expenses.expense.domain.port.out.ExpenseImportWriter;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.4 — implements {@link ExpenseImportPort}: each expense is checked against the rules a stored expense must
 * meet ({@link ExpenseContent} limits, the category and method keys, the table checks) before one batch insert. Joins
 * the caller's transaction; the tenant context must be the explicit schema.
 */
@Component
class ExpenseImportApiAdapter implements ExpenseImportPort {

    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");

    private final ExpenseImportWriter writer;

    ExpenseImportApiAdapter(ExpenseImportWriter writer) {
        this.writer = writer;
    }

    @Override
    public Set<UUID> importExpenses(String tenantSchema, List<ImportedExpense> expenses) {
        TenantContext.requireCurrent(tenantSchema);
        if (expenses == null || expenses.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (ImportedExpense e : expenses) {
            check(e);
            require(ids.add(e.id()), "duplicate expense");
        }
        return writer.insertAbsent(expenses);
    }

    static void check(ImportedExpense e) {
        require(ExpenseCategory.fromKey(e.category()).isPresent(), "expense category");
        require(e.paymentMethod() == null || Arrays.stream(ExpensePaymentMethod.values())
                .anyMatch(m -> m.key().equals(e.paymentMethod())), "expense payment method");
        require(e.amount().compareTo(MIN_AMOUNT) >= 0 && e.amount().compareTo(ExpenseContent.MAX_AMOUNT) <= 0
                && e.amount().stripTrailingZeros().scale() <= 2, "expense amount");
        require(e.label() == null || e.label().codePointCount(0, e.label().length()) <= ExpenseContent.MAX_LABEL,
                "expense label");
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("invalid imported expense: " + what);
        }
    }
}

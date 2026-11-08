package com.keevo.expenses.expense.application.usecase;

import com.keevo.expenses.expense.application.service.ExpenseAccess;
import com.keevo.expenses.expense.application.service.ExpenseStoreScope;
import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpenseCursor;
import com.keevo.expenses.expense.domain.model.ExpenseSort;
import com.keevo.expenses.expense.domain.port.out.ExpenseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story 20.10 — reads expenses within the actor's stores: one expense, or a keyset page of the list (Bref filters and
 * sorts) with, on the first page, the count and sum of every matching expense (Bref's {@code totalAmount}), and the
 * display names of the page's stores (one batch lookup).
 */
@Service
public class ExpenseQueryUseCase {

    /**
     * A list request; {@code storeId} = the OWNER's store filter (ignored for a store member), {@code after} = the
     * position after the previous page ({@code null} = first page).
     */
    public record ListQuery(UUID storeId, ExpenseCategory category, LocalDate from, LocalDate to, String search,
                            ExpenseSort sort, ExpenseCursor after, int size) {

        /** The hash of this query's filters, carried by its cursors ({@link ExpenseCursor#filters}). */
        public String filters() {
            return ExpenseCursor.filters(storeId, category, from, to, search, sort);
        }
    }

    /** A page; {@code next} null once exhausted; {@code totals} only on the first page; store names of the page. */
    public record Page(List<Expense> items, ExpenseCursor next, ExpenseRepository.Totals totals,
                       Map<UUID, String> storeNames) {
    }

    private final ExpenseRepository expenses;
    private final ExpenseStoreScope stores;

    public ExpenseQueryUseCase(ExpenseRepository expenses, ExpenseStoreScope stores) {
        this.expenses = expenses;
        this.stores = stores;
    }

    /** A live expense the actor sees, else 404 {@code EXPENSE_NOT_FOUND}. */
    @Transactional(readOnly = true)
    public Expense get(UUID id, ExpenseAccess access) {
        return expenses.findById(id).filter(e -> !e.deleted() && access.sees(e.storeId()))
                .orElseThrow(ManageExpenseUseCase::notFound);
    }

    /** The display names of the given stores (one query). */
    @Transactional(readOnly = true)
    public Map<UUID, String> storeNames(Collection<UUID> storeIds, String tenantSchema) {
        return stores.names(tenantSchema, Set.copyOf(storeIds));
    }

    /** Three queries at most: the page ({@code size + 1} rows), the first page's aggregate, the page's store names. */
    @Transactional(readOnly = true)
    public Page list(ListQuery q, ExpenseAccess access, String tenantSchema) {
        ExpenseStoreScope.ListScope scope = stores.listScope(access, q.storeId(), tenantSchema);
        if (scope.none()) {
            return new Page(List.of(), null,
                    q.after() == null ? new ExpenseRepository.Totals(0, java.math.BigDecimal.ZERO) : null, Map.of());
        }
        ExpenseRepository.Filter filter = new ExpenseRepository.Filter(scope.storeId(), q.category(), q.from(), q.to(),
                q.search());
        List<Expense> found = expenses.page(filter, q.sort(), q.after(), q.size() + 1);
        boolean more = found.size() > q.size();
        List<Expense> items = more ? found.subList(0, q.size()) : found;
        ExpenseCursor next = more ? ExpenseCursor.after(q.filters(), q.sort(), items.get(items.size() - 1)) : null;
        ExpenseRepository.Totals totals = q.after() == null ? expenses.totals(filter) : null;
        Map<UUID, String> names = stores.names(tenantSchema,
                items.stream().map(Expense::storeId).collect(Collectors.toSet()));
        return new Page(List.copyOf(items), next, totals, names);
    }
}

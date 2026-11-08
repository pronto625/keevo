package com.keevo.expenses.expense.adapter.in.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.expenses.expense.adapter.in.rest.dto.ExpenseResponseDto;
import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.port.out.ExpenseRepository;
import com.keevo.expenses.module.ExpensesModule;
import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.PullScope;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ModuleProvisioner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story 20.10 — the {@code expenses} delta of the sync pull: the expenses changed since the device's cursor, each one
 * exactly as the REST API returns it ({@link ExpenseResponseDto}), a deleted one as a tombstone
 * {@code {id, deleted: true, updatedAt}}. Keyset paged on ({@code updated_at}, id as text) —
 * {@code idx_expenses_sync_keyset}. Store-specific: a store member's pull carries their store's expenses only
 * (bound parameter), a member without store none. Owned by {@code expenses}: skipped while the module is DISABLED; a
 * tenant whose table was never provisioned has no delta.
 */
@Component
public class ExpenseDeltaProvider implements DeltaEntityProvider {

    /** Key of the delta in the pull response. */
    public static final String ENTITY_KEY = "expenses";

    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {
    };

    private final ExpenseRepository expenses;
    private final ObjectMapper objectMapper;
    private final ModuleProvisioner provisioner;
    private final com.keevo.expenses.expense.application.usecase.ExpenseQueryUseCase queries;

    public ExpenseDeltaProvider(ExpenseRepository expenses, ObjectMapper objectMapper, ModuleProvisioner provisioner,
                                com.keevo.expenses.expense.application.usecase.ExpenseQueryUseCase queries) {
        this.queries = queries;
        this.expenses = expenses;
        this.objectMapper = objectMapper;
        this.provisioner = provisioner;
    }

    @Override
    public String entityKey() {
        return ENTITY_KEY;
    }

    @Override
    public String moduleId() {
        return ExpensesModule.ID;
    }

    /** Without a scope (internal callers): every store. */
    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit) {
        return queryDeltaPage(since, after, limit, PullScope.UNRESTRICTED);
    }

    @Override
    public Page queryDeltaPage(Instant since, Position after, int limit, PullScope scope) {
        if (scope.excludesEveryStore() || !expenses.tablesExist()) {
            return new Page(List.of(), null);
        }
        provisioner.ensureProvisioned(TenantContext.getCurrentTenant(), ExpensesModule.ID);
        // one extra row tells whether another page exists
        List<Expense> found = expenses.delta(since, after == null ? null : after.cursor(),
                after == null ? null : after.id(), scope.restricted() ? scope.storeId() : null, limit + 1);
        boolean more = found.size() > limit;
        List<Expense> page = more ? found.subList(0, limit) : found;
        if (page.isEmpty()) {
            return new Page(List.of(), null);
        }
        // the page's store names: one batch lookup
        Map<java.util.UUID, String> names = queries.storeNames(page.stream().filter(e -> !e.deleted())
                .map(Expense::storeId).collect(java.util.stream.Collectors.toSet()), TenantContext.getCurrentTenant());
        List<Map<String, Object>> rows = page.stream().map(e -> e.deleted() ? tombstone(e)
                : objectMapper.convertValue(ExpenseResponseDto.fromDomain(e, names.get(e.storeId())), ROW)).toList();
        Expense last = page.get(page.size() - 1);
        return new Page(rows, more ? new Position(last.updatedAt(), last.id().toString()) : null);
    }

    private static Map<String, Object> tombstone(Expense expense) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", expense.id().toString());
        row.put("deleted", true);
        row.put("updatedAt", expense.updatedAt() == null ? null : expense.updatedAt().toString());
        return row;
    }
}

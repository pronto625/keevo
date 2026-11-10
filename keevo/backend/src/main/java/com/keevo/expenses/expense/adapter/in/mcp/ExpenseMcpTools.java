package com.keevo.expenses.expense.adapter.in.mcp;

import com.keevo.expenses.expense.adapter.in.CurrentExpenseAccess;
import com.keevo.expenses.expense.application.service.ExpenseStoreScope;
import com.keevo.expenses.expense.application.usecase.ExpenseQueryUseCase;
import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpenseCursor;
import com.keevo.expenses.expense.domain.model.ExpenseSort;
import com.keevo.expenses.module.ExpensesModule;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.ai.AiToolArguments;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.8 — AI tools of the {@code expenses} module, mirroring {@code ExpenseController#list} (permission
 * {@code EXPENSE_VIEW}, module {@code expenses} READ, same filters, cursor and store scope through
 * {@link CurrentExpenseAccess}: the owner sees every store or one, a manager their store). No expense or user id.
 */
@Component
public class ExpenseMcpTools {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    static final int MAX_SEARCH_LENGTH = 100;
    static final String STALE_CURSOR = "Position de page invalide : rechargez la liste";

    private final ExpenseQueryUseCase queries;

    public ExpenseMcpTools(ExpenseQueryUseCase queries) {
        this.queries = queries;
    }

    public record ExpenseRow(LocalDate date, String category, BigDecimal amount, String label, String paymentMethod,
                             String store) {
    }

    /** {@code matching} and {@code totalAmount}: every matching expense, given on the first page only. */
    public record ExpensesPage(Long matching, BigDecimal totalAmount, List<ExpenseRow> expenses, String nextCursor) {
    }

    @Tool(name = "list_expenses", description = "Dépenses d'une période (combien ai-je dépensé, plus grosse dépense, "
            + "dépenses d'une catégorie) : date, catégorie, montant, libellé, moyen de paiement, boutique, avec sur la "
            + "première page le nombre et le total de toutes les dépenses filtrées. "
            + "Filtres : dates, catégorie (goods_purchase, rent, salaries, transport, utilities, telecom, marketing, "
            + "taxes, maintenance, other), recherche dans le libellé, boutique (propriétaire). Tri : recent (défaut), "
            + "oldest, amount_desc, amount_asc. Par pages avec nextCursor (mêmes filtres). Montants en FCFA.")
    @RequiresPermission(ExpensesModule.EXPENSE_VIEW)
    @RequiresEntitlement(value = ExpensesModule.ID, access = RequiresEntitlement.Access.READ)
    public ExpensesPage listExpenses(
            @ToolParam(required = false, description = "Premier jour inclus (AAAA-MM-JJ)") LocalDate from,
            @ToolParam(required = false, description = "Dernier jour inclus (AAAA-MM-JJ)") LocalDate to,
            @ToolParam(required = false, description = "Clé de catégorie, par exemple rent") String category,
            @ToolParam(required = false, description = "Partie du libellé ou de la note") String search,
            @ToolParam(required = false, description = "Identifiant d'une boutique (outil list_stores), "
                    + "propriétaire seulement") UUID storeId,
            @ToolParam(required = false, description = "recent, oldest, amount_desc ou amount_asc") String sort,
            @ToolParam(required = false, description = "Dépenses par page, 1 à 50 (défaut 20)") Integer size,
            @ToolParam(required = false, description = "nextCursor de la page précédente") String cursor) {
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        ExpenseCategory parsedCategory = null;
        if (category != null && !category.isBlank()) {
            parsedCategory = ExpenseCategory.fromKey(category).orElseThrow(() ->
                    new InvalidRequestParameterException("category", "unknown category", "Catégorie de dépense inconnue"));
        }
        AiToolArguments.checkPeriod(from, to);
        String text = search == null || search.isBlank() ? null : search.strip();
        if (text != null && (text.indexOf('\0') >= 0 || text.length() > MAX_SEARCH_LENGTH)) {
            throw new InvalidRequestParameterException("search", "invalid search",
                    "La recherche ne doit pas dépasser " + MAX_SEARCH_LENGTH + " caractères");
        }
        ExpenseSort order = ExpenseSort.fromKey(sort);
        String filters = ExpenseCursor.filters(storeId, parsedCategory, from, to, text, order);
        ExpenseCursor after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = ExpenseCursor.decode(cursor, filters, order);
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestParameterException("cursor", "cursor is malformed or of other filters",
                        STALE_CURSOR);
            }
        }
        ExpenseQueryUseCase.Page page;
        try {
            page = queries.list(new ExpenseQueryUseCase.ListQuery(storeId, parsedCategory, from, to, text, order, after,
                    pageSize), CurrentExpenseAccess.get(), TenantContext.getCurrentTenant());
        } catch (DomainException e) {
            if (ExpenseStoreScope.isUnknownStore(e)) { // same 400 as the REST list
                throw new InvalidRequestParameterException("storeId", "unknown store", e.getUserMessage());
            }
            throw e;
        }
        List<ExpenseRow> rows = page.items().stream().map(e -> row(e, page)).toList();
        return new ExpensesPage(page.totals() == null ? null : page.totals().count(),
                page.totals() == null ? null : page.totals().amount(), rows,
                page.next() == null ? null : page.next().encode());
    }

    private static ExpenseRow row(Expense e, ExpenseQueryUseCase.Page page) {
        return new ExpenseRow(e.expenseDate(), e.category() == null ? null : e.category().label(), e.amount(),
                e.label(), e.paymentMethod() == null ? null : e.paymentMethod().label(),
                page.storeNames().get(e.storeId()));
    }
}

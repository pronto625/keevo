package com.keevo.expenses.expense.adapter.in.rest;

import com.keevo.expenses.expense.adapter.in.CurrentExpenseAccess;
import com.keevo.expenses.expense.adapter.in.rest.dto.ExpenseOptionsDto;
import com.keevo.expenses.expense.adapter.in.rest.dto.ExpenseRequestDto;
import com.keevo.expenses.expense.adapter.in.rest.dto.ExpenseResponseDto;
import com.keevo.expenses.expense.application.service.ExpenseStoreScope;
import com.keevo.expenses.expense.application.usecase.ExpenseQueryUseCase;
import com.keevo.expenses.expense.application.usecase.ManageExpenseUseCase;
import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpenseCursor;
import com.keevo.expenses.expense.domain.model.ExpenseSort;
import com.keevo.expenses.module.ExpensesModule;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Expenses of the tenant — Story 20.10 (Bref expenses, FUS-FR25). Every handler is gated by the {@code expenses}
 * module ({@code @RequiresEntitlement}: GET → READ, other verbs → WRITE) and a module permission
 * ({@code @RequiresPermission}): view → {@code EXPENSE_VIEW}; record / edit → {@code EXPENSE_CREATE}; delete →
 * {@code EXPENSE_DELETE}. Those permissions are held by the OWNER only by default (user decision 2026-11-11): a
 * built-in MANAGER, EMPLOYEE or VIEWER gets 403 {@code FORBIDDEN} « Vous n'avez pas les droits nécessaires » on every
 * route; an owner-defined role (19.14) may grant them. Store scope: an OWNER sees every store (or {@code storeId=}), a
 * member holding the permission their store only.
 */
@RestController
@RequestMapping("/api/v1/expenses")
public class ExpenseController {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_SEARCH_LENGTH = 100;
    static final String STALE_CURSOR = "Position de page invalide : rechargez la liste";

    private final ManageExpenseUseCase manage;
    private final ExpenseQueryUseCase queries;

    public ExpenseController(ManageExpenseUseCase manage, ExpenseQueryUseCase queries) {
        this.manage = manage;
        this.queries = queries;
    }

    /**
     * POST /api/v1/expenses — a new expense (201); {@code id} sent again → the expense already created (200), a
     * deleted one → 404 {@code EXPENSE_NOT_FOUND}.
     */
    @RequiresPermission(ExpensesModule.EXPENSE_CREATE)
    @RequiresEntitlement(ExpensesModule.ID)
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<ExpenseResponseDto>> create(@Valid @RequestBody ExpenseRequestDto request) {
        String tenant = TenantContext.getCurrentTenant();
        var created = manage.create(request.id(), request.toCommand(), CurrentExpenseAccess.get(),
                CurrentActor.actorId(), tenant);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponseWrapper.ok(dto(created.expense(), tenant)));
    }

    /**
     * GET /api/v1/expenses?storeId=&category=&from=&to=&search=&sort=&cursor=&size= — Bref's filters and sorts
     * ({@code recent} default, {@code oldest}, {@code amount_desc}, {@code amount_asc}; anything else = recent), keyset
     * paged: {@code meta.nextCursor} (opaque, bound to the filters and sort: reused with other ones → 400) gives the
     * next page while {@code meta.hasMore}. The first page (no cursor) also carries {@code meta.count} and
     * {@code meta.totalAmount} of every matching expense.
     */
    @RequiresPermission(ExpensesModule.EXPENSE_VIEW)
    @RequiresEntitlement(value = ExpensesModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<ExpenseResponseDto>>> list(
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestParameterException("size", "size must be between 1 and " + MAX_PAGE_SIZE,
                    "La taille de page doit être comprise entre 1 et " + MAX_PAGE_SIZE);
        }
        ExpenseCategory parsedCategory = null;
        if (category != null && !category.isBlank()) {
            parsedCategory = ExpenseCategory.fromKey(category).orElseThrow(() ->
                    new InvalidRequestParameterException("category", "unknown category", "Catégorie de dépense inconnue"));
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestParameterException("from", "from must not be after to",
                    "La date de début doit précéder la date de fin");
        }
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
                    size), CurrentExpenseAccess.get(), TenantContext.getCurrentTenant());
        } catch (DomainException e) {
            if (ExpenseStoreScope.isUnknownStore(e)) {
                throw new InvalidRequestParameterException("storeId", "unknown store", e.getUserMessage()); // 20.14 — one message
            }
            throw e;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", size);
        meta.put("hasMore", page.next() != null);
        meta.put("nextCursor", page.next() == null ? null : page.next().encode());
        if (page.totals() != null) {
            meta.put("count", page.totals().count());
            meta.put("totalAmount", ExpenseResponseDto.plain(page.totals().amount()));
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(page.items().stream()
                .map(e -> ExpenseResponseDto.fromDomain(e, page.storeNames().get(e.storeId()))).toList(), meta));
    }

    /** GET /api/v1/expenses/options — the categories and payment methods of the form (keys and French labels). */
    @RequiresPermission(ExpensesModule.EXPENSE_VIEW)
    @RequiresEntitlement(value = ExpensesModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/options")
    public ResponseEntity<ApiResponseWrapper<ExpenseOptionsDto>> options() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(ExpenseOptionsDto.all()));
    }

    /** GET /api/v1/expenses/{id}. */
    @RequiresPermission(ExpensesModule.EXPENSE_VIEW)
    @RequiresEntitlement(value = ExpensesModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ExpenseResponseDto>> get(@PathVariable UUID id) {
        String tenant = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto(queries.get(id, CurrentExpenseAccess.get()), tenant)));
    }

    /**
     * PUT /api/v1/expenses/{id} — replaces the whole content (an absent date keeps the current one; optional
     * {@code expectedUpdatedAt} → 409 when stale).
     */
    @RequiresPermission(ExpensesModule.EXPENSE_CREATE)
    @RequiresEntitlement(ExpensesModule.ID)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<ExpenseResponseDto>> update(@PathVariable UUID id,
                                                                         @Valid @RequestBody ExpenseRequestDto request) {
        String tenant = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto(manage.update(id, request.toCommand(),
                CurrentExpenseAccess.get(), CurrentActor.actorId(), tenant), tenant)));
    }

    /** DELETE /api/v1/expenses/{id} — final; already deleted → 204 as well. */
    @RequiresPermission(ExpensesModule.EXPENSE_DELETE)
    @RequiresEntitlement(ExpensesModule.ID)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        manage.delete(id, CurrentExpenseAccess.get(), CurrentActor.actorId());
        return ResponseEntity.noContent().build();
    }

    private ExpenseResponseDto dto(Expense e, String tenant) {
        return ExpenseResponseDto.fromDomain(e, queries.storeNames(List.of(e.storeId()), tenant).get(e.storeId()));
    }
}

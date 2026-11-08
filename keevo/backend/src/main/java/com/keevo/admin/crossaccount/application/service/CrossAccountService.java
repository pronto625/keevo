package com.keevo.admin.crossaccount.application.service;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerItem;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpenseDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpensePage;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductItem;
import com.keevo.admin.crossaccount.domain.port.in.CrossAccountUseCase;
import com.keevo.admin.crossaccount.domain.port.out.CrossAccountReadPort;
import com.keevo.admin.crosstenant.AdminParams;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.expenses.api.ExpenseLabels;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Story 20.13 — the super-admin cross-account lists (Bref {@code customer-admin}, {@code product-admin},
 * {@code expense-admin}): validates the raw parameters (422 VALIDATION_ERROR with a French message), resolves the account of a
 * detail (404 « Compte introuvable ») and delegates to the cross-tenant read port. Read-only, never
 * {@code @Transactional} (no tenant context: schema-qualified SQL).
 */
@Service
public class CrossAccountService implements CrossAccountUseCase {

    static final String INVALID_STORE = "Boutique invalide";
    static final String INVALID_ID = "Identifiant invalide";

    private final CrossAccountReadPort reader;
    private final CrossTenantSql crossTenant;

    public CrossAccountService(CrossAccountReadPort reader, CrossTenantSql crossTenant) {
        this.reader = reader;
        this.crossTenant = crossTenant;
    }

    @Override
    public CursorPage<CustomerItem> listCustomers(CustomerQuery q) {
        String tenantId = CrossTenantSql.requireUuid(q.tenantId(), CrossTenantSql.INVALID_TENANT);
        String storeId = CrossTenantSql.requireUuid(q.storeId(), INVALID_STORE);
        String search = AdminParams.search(q.search());
        int limit = AdminParams.limit(q.limit());
        String filters = "customers|" + tenantId + "|" + storeId + "|" + search;
        return reader.customers(new CrossAccountReadPort.CustomerCriteria(tenantId, storeId, search, q.cursor(),
                filters, limit));
    }

    @Override
    public CustomerDetail getCustomer(String tenantId, String customerId) {
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        String id = CrossTenantSql.requireUuid(customerId, INVALID_ID);
        return (id == null ? java.util.Optional.<CustomerDetail>empty() : reader.customer(tenant, id))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.CLIENT_NOT_FOUND, "Client introuvable"));
    }

    @Override
    public CursorPage<ProductItem> listProducts(ProductQuery q) {
        String tenantId = CrossTenantSql.requireUuid(q.tenantId(), CrossTenantSql.INVALID_TENANT);
        String search = AdminParams.search(q.search());
        boolean archived = archived(q.status());
        int limit = AdminParams.limit(q.limit());
        String filters = "products|" + tenantId + "|" + search + "|" + archived;
        return reader.products(new CrossAccountReadPort.ProductCriteria(tenantId, search, archived, q.cursor(),
                filters, limit));
    }

    @Override
    public ProductDetail getProduct(String tenantId, String productId) {
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        String id = CrossTenantSql.requireUuid(productId, INVALID_ID);
        return (id == null ? java.util.Optional.<ProductDetail>empty() : reader.product(tenant, id))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.PRODUCT_NOT_FOUND, "Produit introuvable"));
    }

    @Override
    public ExpensePage listExpenses(ExpenseQuery q) {
        String tenantId = CrossTenantSql.requireUuid(q.tenantId(), CrossTenantSql.INVALID_TENANT);
        String storeId = CrossTenantSql.requireUuid(q.storeId(), INVALID_STORE);
        String category = q.category() == null || q.category().isBlank() ? null : q.category().trim();
        if (category != null && !ExpenseLabels.isCategory(category)) {
            throw AdminParams.invalid("Catégorie de dépense inconnue");
        }
        LocalDate from = AdminParams.date(q.from(), "La date de début");
        LocalDate to = AdminParams.date(q.to(), "La date de fin");
        AdminParams.range(from, to);
        String search = AdminParams.search(q.search());
        int limit = AdminParams.limit(q.limit());
        String filters = "expenses|" + tenantId + "|" + storeId + "|" + category + "|" + from + "|" + to + "|" + search;
        return reader.expenses(new CrossAccountReadPort.ExpenseCriteria(tenantId, storeId, category, from, to, search,
                q.cursor(), filters, limit));
    }

    @Override
    public ExpenseDetail getExpense(String tenantId, String expenseId) {
        TenantBranch tenant = crossTenant.requireTenant(tenantId);
        String id = CrossTenantSql.requireUuid(expenseId, INVALID_ID);
        return (id == null ? java.util.Optional.<ExpenseDetail>empty() : reader.expense(tenant, id))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.EXPENSE_NOT_FOUND, "Dépense introuvable"));
    }

    /** {@code active} (default) or {@code archived}, case-insensitive. */
    static boolean archived(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "active" -> false;
            case "archived" -> true;
            default -> throw AdminParams.invalid("Statut inconnu : choisissez active ou archived");
        };
    }
}

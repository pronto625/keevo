package com.keevo.admin.crossaccount.domain.port.in;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerItem;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpenseDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpensePage;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductItem;
import com.keevo.admin.crosstenant.CursorPage;

/**
 * Story 20.13 — super-admin cross-account lists and details (Bref {@code GET /customers}, {@code /customers/:id},
 * {@code /products}, {@code /expenses}). Raw request values; the service validates them (422 VALIDATION_ERROR with a French message).
 */
public interface CrossAccountUseCase {

    record CustomerQuery(String tenantId, String storeId, String search, String cursor, String limit) {
    }

    record ProductQuery(String tenantId, String search, String status, String cursor, String limit) {
    }

    record ExpenseQuery(String tenantId, String storeId, String category, String from, String to, String search,
                        String cursor, String limit) {
    }

    CursorPage<CustomerItem> listCustomers(CustomerQuery query);

    CustomerDetail getCustomer(String tenantId, String customerId);

    CursorPage<ProductItem> listProducts(ProductQuery query);

    ProductDetail getProduct(String tenantId, String productId);

    ExpensePage listExpenses(ExpenseQuery query);

    ExpenseDetail getExpense(String tenantId, String expenseId);
}

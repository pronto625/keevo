package com.keevo.admin.crossaccount.domain.port.out;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerItem;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpenseDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ExpensePage;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductDetail;
import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.ProductItem;
import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.crosstenant.TenantBranch;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Story 20.13 — cross-tenant reads of the super-admin lists: one bounded {@code UNION ALL} statement per list
 * (keyset, see {@code KeysetSort}), one schema-qualified query per detail. Criteria are validated by the service;
 * {@code cursor} is the raw request cursor, decoded against {@code filters} by the adapter (it owns the sort keys).
 */
public interface CrossAccountReadPort {

    record CustomerCriteria(String tenantId, String storeId, String search, String cursor, String filters, int limit) {
    }

    record ProductCriteria(String tenantId, String search, boolean archived, String cursor, String filters,
                           int limit) {
    }

    record ExpenseCriteria(String tenantId, String storeId, String category, LocalDate from, LocalDate to,
                           String search, String cursor, String filters, int limit) {
    }

    CursorPage<CustomerItem> customers(CustomerCriteria criteria);

    Optional<CustomerDetail> customer(TenantBranch tenant, String customerId);

    CursorPage<ProductItem> products(ProductCriteria criteria);

    Optional<ProductDetail> product(TenantBranch tenant, String productId);

    ExpensePage expenses(ExpenseCriteria criteria);

    Optional<ExpenseDetail> expense(TenantBranch tenant, String expenseId);
}

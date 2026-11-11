package com.keevo.admin.crosstenant;

import java.util.List;

/**
 * Story 20.13 — the tenant tables (with the columns read) whose presence the super-admin API checks in the catalogue
 * before building a cross-tenant statement: a module never used by a tenant, or an older module version, is simply
 * not a branch, so a statement never fails on a missing table / column. Resolved for every requested tenant in ONE
 * catalogue query ({@link CrossTenantSql#liveTenants()} / {@link CrossTenantSql#withPresence}).
 */
public enum TenantTable {
    SALES("sales", "status", "occurred_at", "created_at", "total_amount", "employee_id", "store_id", "payment_mode",
            "client_id"),
    /** The invoicing tables at the version the revenue SQL reads (same predicate as {@code ProvisionedRevenueSource}). */
    INVOICING("invoice_items", "product_id"),
    /** Story 20.14 — the documents carry their store (invoicing V16). */
    INVOICE_STORE("invoices", "store_id"),
    INVOICES("invoices", "id", "kind", "status", "payment_state", "source_sale_id", "deleted_at", "superseded_at",
            "issue_date", "total_ttc", "created_at"),
    INVOICE_PAYMENTS("invoice_payments", "id", "invoice_id", "amount", "paid_at"),
    STORES("stores", "id", "name", "type", "is_active", "created_at"),
    EMPLOYEES("employees", "id", "user_id", "first_name", "last_name", "store_id"),
    CATEGORIES("categories", "id", "name"),
    CLIENTS("clients", "id", "name", "phone", "email", "store_id", "archived", "created_at", "updated_at",
            "marketing_consent_at", "marketing_opt_out_at"),
    CLIENT_DETAIL("clients", "client_type", "niu", "rccm", "address", "contact_name", "website", "birth_date",
            "notes"),
    PRODUCTS("products", "id", "name", "price", "stock_quantity", "track_stock", "status", "archived", "category_id",
            "barcode", "merged_into_id", "updated_at"),
    PRODUCT_DETAIL("products", "description", "buy_price", "minimum_threshold", "created_at"),
    EXPENSES("expenses", "id", "store_id", "category", "amount", "expense_date", "label", "note", "payment_method",
            "created_at", "archived_at"),
    EXPENSE_DETAIL("expenses", "updated_at", "created_by"),
    // Story 19.11b — the tables read by the analytics / operations / admin catalogue screens (optional modules
    // included). Each spec lists only the columns its branch reads, so an older schema missing an unrelated column
    // keeps its figures.
    /** Sales as read by the activity / overview / top stores figures. */
    SALES_CORE("sales", "store_id", "status", "occurred_at", "created_at", "total_amount"),
    /** The products table itself (admin catalogue summary total). */
    PRODUCTS_TABLE("products", "id"),
    /** Products count (overview: non-archived). */
    PRODUCTS_ARCHIVED("products", "archived"),
    /** Admin catalogue list rows. */
    PRODUCTS_LIST("products", "id", "name", "price", "stock_quantity", "status", "updated_at"),
    /** Admin catalogue: the category join. */
    PRODUCTS_CATEGORY("products", "category_id"),
    /** Admin catalogue summary: active / draft counts. */
    PRODUCTS_STATUS("products", "status", "archived"),
    /** Admin catalogue summary: low / out of stock counts (17.12). */
    PRODUCTS_STOCK("products", "stock_quantity", "track_stock"),
    /** Admin catalogue summary: created this week. */
    PRODUCTS_CREATED("products", "created_at"),
    INVENTORY_SESSIONS("inventory_sessions", "started_at"),
    STOCK_TRANSFERS("stock_transfers", "occurred_at"),
    REPORTS("reports", "delivery_status", "last_attempt_at", "created_at"),
    TENANT_PREFERENCES("tenant_preferences", "stock_alert_enabled"),
    NOTIFICATION_COOLDOWNS("notification_cooldowns", "cooldown_type", "last_sent_at"),
    SYNC_OPERATIONS_LOG("sync_operations_log", "processed_at"),
    SUBSCRIPTIONS("subscriptions", "plan_type", "created_at"),
    AUDIT_LOG("audit_log", "user_id", "source", "occurred_at");

    private final String table;
    private final List<String> columns;

    TenantTable(String table, String... columns) {
        this.table = table;
        this.columns = List.of(columns);
    }

    public String table() {
        return table;
    }

    public List<String> columns() {
        return columns;
    }
}

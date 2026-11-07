package com.keevo.shared.infrastructure.persistence;

import java.util.List;

// Story 11.5 — keyset pull indexes. DeltaPageQuery filters and orders each delta table by
// (<cursor>, CAST(id AS text)); each index repeats the provider's exact cursor expression (its CURSOR constant) and
// the text-cast id so the planner can walk it instead of sorting the table. SyncKeysetIndexesMatchProvidersTest (sync
// test package) guards the coupling. New schemas get them at registration (TenantSchemaProvisioner, best effort);
// existing schemas get them from SyncKeysetIndexJob (background, CREATE INDEX CONCURRENTLY). Story 16.12: they are
// not part of the module migrations (the foundation scripts leave them to this list), so a large existing table is
// never indexed inside a migration transaction.

/**
 * One keyset pull index: {@code name ON table (cursor, (CAST(id AS text)))}.
 *
 * @param name     index name (unqualified)
 * @param table    delta table (unqualified)
 * @param cursor   cursor column or expression, identical to the provider's {@code CURSOR}
 * @param moduleId Story 15.5b — the module owning the table when it is a module table (created by the module's
 *                 own migrations, only in the schemas of tenants that use it, e.g. {@code invoices} of
 *                 {@code invoicing}), {@code null} for a foundation table (created at registration). A module table's index is created by the
 *                 module migration that creates or follows the table, never at registration (the table does
 *                 not exist yet); {@link SyncKeysetIndexJob} then checks and repairs it like the others (a
 *                 schema without the table is skipped).
 */
public record SyncKeysetIndex(String name, String table, String cursor, String moduleId) {

    /** A foundation table's index. */
    public SyncKeysetIndex(String name, String table, String cursor) {
        this(name, table, cursor, null);
    }

    /** Whether the table is created only for the tenants of a paid module (its index comes with its migrations). */
    public boolean moduleTable() {
        return moduleId != null;
    }

    /** Index key list as written in the DDL; an expression cursor gets its own parentheses. */
    String keys() {
        String lead = cursor.matches("[a-z_]+") ? cursor : "(" + cursor + ")";
        return lead + ", (CAST(id AS text))";
    }

    /** Plain DDL on the current {@code search_path} (provisioning of a new, empty schema). */
    public String createSql() {
        return "CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + keys() + ")";
    }

    /** Non-blocking DDL on an existing schema (must run in autocommit). */
    String createConcurrentlySql(String schema) {
        return createConcurrentlySql(schema, name);
    }

    /** Same DDL under another index name (temporary index of a swap rebuild). */
    String createConcurrentlySql(String schema, String indexName) {
        return "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + indexName
                + " ON \"" + TenantSchema.validate(schema) + "\"." + table + " (" + keys() + ")";
    }

    /**
     * The definition PostgreSQL reports for this index ({@code pg_get_indexdef}), with the
     * schema qualifier removed: a column or function-call cursor is printed bare and the
     * text-cast id as {@code ((id)::text)}.
     */
    String expectedDefinition() {
        return "CREATE INDEX " + name + " ON " + table + " USING btree (" + cursor + ", ((id)::text))";
    }

    /** Story 11.5 — every keyset pull index, one per delta table (core tables, then module tables — 15.5b). */
    public static final List<SyncKeysetIndex> SYNC_KEYSET_INDEXES = List.of(
            new SyncKeysetIndex("idx_products_sync_keyset", "products", "updated_at"),
            new SyncKeysetIndex("idx_categories_sync_keyset", "categories", "updated_at"),
            new SyncKeysetIndex("idx_stores_sync_keyset", "stores", "updated_at"),
            new SyncKeysetIndex("idx_clients_sync_keyset", "clients", "updated_at"),
            new SyncKeysetIndex("idx_suppliers_sync_keyset", "suppliers", "updated_at"),
            new SyncKeysetIndex("idx_employees_sync_keyset", "employees", "updated_at"),
            new SyncKeysetIndex("idx_sales_sync_keyset", "sales", "updated_at"),
            new SyncKeysetIndex("idx_stock_levels_sync_keyset", "stock_levels", "updated_at"),
            new SyncKeysetIndex("idx_stock_movements_sync_keyset", "stock_movements", "occurred_at"),
            new SyncKeysetIndex("idx_stock_transfers_sync_keyset", "stock_transfers",
                    "GREATEST(occurred_at, COALESCE(updated_at, occurred_at))"),
            new SyncKeysetIndex("idx_audit_log_sync_keyset", "audit_log", "occurred_at"),
            new SyncKeysetIndex("idx_day_closures_sync_keyset", "day_closures", "received_at"),
            new SyncKeysetIndex("idx_inventory_sessions_sync_keyset", "inventory_sessions", "updated_at"),
            new SyncKeysetIndex("idx_inventory_counts_sync_keyset", "inventory_counts", "updated_at"),
            new SyncKeysetIndex("idx_reports_sync_keyset", "reports", "updated_at"),
            // Story 15.5b — module table: created by db/modules/invoicing/V6__invoice_sync_keyset.sql
            new SyncKeysetIndex("idx_invoices_sync_keyset", "invoices", "updated_at", "invoicing"),
            // Story 20.10 — module table: created by db/modules/expenses/V1__expenses.sql
            new SyncKeysetIndex("idx_expenses_sync_keyset", "expenses", "updated_at", "expenses"));
}

package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;

/**
 * Qualified names of the invoicing tables in the current tenant's schema (Story 14.1). The module owns its tables
 * (no JPA entity) and reaches them with JDBC: every statement names {@code "kv_xxxxxx".<table>}, the schema coming
 * from {@link TenantContext} and validated by {@link TenantSchema#validate} (never interpolated unchecked).
 */
final class InvoicingTables {

    static final String SETTINGS = "invoicing_settings";
    static final String INVOICES = "invoices";
    static final String ITEMS = "invoice_items";
    static final String COUNTERS = "invoice_counters";

    private InvoicingTables() {
    }

    /** {@code "kv_xxxxxx".<table>} for the bound tenant; no / invalid tenant → {@link IllegalStateException}. */
    static String qualified(String table) {
        String schema = TenantContext.getCurrentTenant();
        try {
            TenantSchema.validate(schema);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invoicing used without a valid tenant schema bound");
        }
        return "\"" + schema + "\"." + table;
    }
}

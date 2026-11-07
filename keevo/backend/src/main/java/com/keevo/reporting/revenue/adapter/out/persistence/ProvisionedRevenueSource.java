package com.keevo.reporting.revenue.adapter.out.persistence;

import com.keevo.reporting.revenue.domain.port.out.RevenueQueryPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story 20.9 (R4) — the revenue source of the current tenant: {@link RevenueRelations} with the branches whose data
 * exists in the tenant schema.
 * <ul>
 *   <li>sales: always (foundation tables);</li>
 *   <li>invoices and credit notes: when the invoicing tables are in the schema at the version the SQL reads
 *       ({@code invoice_items.product_id}, invoicing V11 — earlier versions are implied). Their data counts whatever
 *       the module's current activation: an owner who switches invoicing off keeps the revenue he invoiced.</li>
 * </ul>
 * Never provisions a module and never fails for one: a schema without the tables (or an unreadable catalogue,
 * logged at WARN) reads sales only. Cost: one catalogue lookup ({@code pg_attribute} on {@code to_regclass}) per
 * resolution until the tables are found, then none (cached per tenant schema — tables are never dropped).
 */
@Component
public class ProvisionedRevenueSource implements RevenueQueryPort {

    private static final Logger log = LoggerFactory.getLogger(ProvisionedRevenueSource.class);

    static final Relations SALES_ONLY = new Relations(RevenueRelations.POS_DOCUMENTS, RevenueRelations.POS_LINES);
    static final Relations ALL = new Relations(RevenueRelations.documents(true, true), RevenueRelations.lines(true, true));

    /** Resolved through the current search_path (the tenant schema). */
    static final String INVOICING_TABLES_PRESENT = "SELECT EXISTS (SELECT 1 FROM pg_attribute "
            + "WHERE attrelid = to_regclass('invoice_items') AND attname = 'product_id' AND NOT attisdropped)";

    private final JdbcTemplate jdbc;
    private final Set<String> withInvoicing = ConcurrentHashMap.newKeySet();

    public ProvisionedRevenueSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Relations current() {
        String schema = TenantContext.getCurrentTenant();
        boolean tenant = TenantSchema.isValid(schema);
        if (tenant && withInvoicing.contains(schema)) {
            return ALL;
        }
        boolean present;
        try {
            present = Boolean.TRUE.equals(jdbc.queryForObject(INVOICING_TABLES_PRESENT, Boolean.class));
        } catch (DataAccessException e) {
            log.warn("revenue source: invoicing tables not checkable ({}) — sales only", e.getClass().getSimpleName());
            return SALES_ONLY;
        }
        if (!present) {
            return SALES_ONLY;
        }
        if (tenant) {
            withInvoicing.add(schema);
        }
        return ALL;
    }

    @Override
    public Relations storeScoped() {
        return SALES_ONLY;
    }
}

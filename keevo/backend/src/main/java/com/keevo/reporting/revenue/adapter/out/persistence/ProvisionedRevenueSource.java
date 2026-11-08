package com.keevo.reporting.revenue.adapter.out.persistence;

import com.keevo.shared.capability.revenue.RevenueQueryPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story 20.9 (R4) — the revenue source of the current tenant: {@link RevenueRelations} with the branches whose data
 * exists in the tenant schema.
 * <ul>
 *   <li>sales: always (foundation tables);</li>
 *   <li>invoices and credit notes: when the invoicing tables are in the schema at the version the SQL reads
 *       ({@code invoice_items.product_id}, invoicing V11 — earlier versions are implied). Their data counts whatever
 *       the module's current activation: an owner who switches invoicing off keeps the revenue he invoiced.</li>
 *   <li>Story 20.14 — the documents' store: when {@code invoices.store_id} exists (invoicing V16). Then the
 *       store-scoped relations are the full ones (a document without store matches no store); below V16 documents
 *       count in the all-stores figures only and store-scoped figures read sales only.</li>
 * </ul>
 * Never provisions a module and never fails for one: a schema without the tables (or an unreadable catalogue,
 * logged at WARN) reads sales only. Cost: one catalogue lookup ({@code pg_attribute} on {@code to_regclass}) per
 * tenant and per {@link #LOWER_LEVEL_TTL} while the V16 column is missing, then none (the V16 level is cached for
 * good — tables are never dropped). The level only goes up: a lower level is re-checked after its TTL (the module
 * may have been provisioned or migrated meanwhile), never below what was already seen.
 */
@Component
public class ProvisionedRevenueSource implements RevenueQueryPort {

    private static final Logger log = LoggerFactory.getLogger(ProvisionedRevenueSource.class);

    static final Relations SALES_ONLY = new Relations(RevenueRelations.POS_DOCUMENTS, RevenueRelations.POS_LINES);
    /** Documents without store (invoicing V11–V15). */
    static final Relations ALL = new Relations(RevenueRelations.documents(true, true),
            RevenueRelations.lines(true, true), RevenueRelations.taxedDocuments(true, true, false));
    /** Story 20.14 — documents with their store (invoicing V16+). */
    static final Relations ALL_WITH_STORES = new Relations(RevenueRelations.documents(true, true, true),
            RevenueRelations.lines(true, true, true), RevenueRelations.taxedDocuments(true, true, true));
    /** Story 20.11 — store-scoped below V16 (sales only) with the sales' VAT. */
    static final Relations STORE_SCOPED_TAXED = new Relations(RevenueRelations.POS_DOCUMENTS, RevenueRelations.POS_LINES,
            RevenueRelations.taxedSales());

    /** No invoicing branch readable. */
    static final int SALES = 0;
    /** Invoicing V11+ without the documents' store. */
    static final int DOCUMENTS = 1;
    /** Invoicing V16+: documents with their store. */
    static final int DOCUMENTS_WITH_STORES = 2;

    /**
     * Resolved through the current search_path (the tenant schema): {@link #SALES}, {@link #DOCUMENTS} or
     * {@link #DOCUMENTS_WITH_STORES}.
     */
    static final String INVOICING_LEVEL = "SELECT CASE "
            + "WHEN NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('invoice_items') "
            + "AND attname = 'product_id' AND NOT attisdropped) THEN 0 "
            + "WHEN EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('invoices') "
            + "AND attname = 'store_id' AND NOT attisdropped) THEN 2 ELSE 1 END";

    /** How long a level below {@link #DOCUMENTS_WITH_STORES} is trusted before the catalogue is read again. */
    static final Duration LOWER_LEVEL_TTL = Duration.ofMinutes(5);

    /** A tenant's level and until when it is trusted ({@code null} = for good). */
    private record Cached(int level, Instant until) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, Cached> levels = new ConcurrentHashMap<>();

    public ProvisionedRevenueSource(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Relations current() {
        return switch (level()) {
            case DOCUMENTS_WITH_STORES -> ALL_WITH_STORES;
            case DOCUMENTS -> ALL;
            default -> SALES_ONLY;
        };
    }

    @Override
    public Relations storeScoped() {
        return level() == DOCUMENTS_WITH_STORES ? ALL_WITH_STORES : SALES_ONLY;
    }

    @Override
    public Relations storeScopedTaxed() {
        return switch (level()) {
            case DOCUMENTS_WITH_STORES -> ALL_WITH_STORES;
            case DOCUMENTS -> STORE_SCOPED_TAXED;
            default -> SALES_ONLY;
        };
    }

    private int level() {
        String schema = TenantContext.getCurrentTenant();
        boolean tenant = TenantSchema.isValid(schema);
        Instant now = clock.instant();
        Cached cached = tenant ? levels.get(schema) : null;
        if (cached != null && (cached.until() == null || now.isBefore(cached.until()))) {
            return cached.level();
        }
        Integer found;
        try {
            found = jdbc.queryForObject(INVOICING_LEVEL, Integer.class);
        } catch (DataAccessException e) {
            log.warn("revenue source: invoicing tables not checkable ({}) — sales only", e.getClass().getSimpleName());
            return cached == null ? SALES : cached.level();
        }
        // the level only goes up (tables are never dropped)
        int level = Math.max(found == null ? SALES : found, cached == null ? SALES : cached.level());
        if (tenant) {
            levels.put(schema, new Cached(level, level == DOCUMENTS_WITH_STORES ? null : now.plus(LOWER_LEVEL_TTL)));
        }
        return level;
    }
}

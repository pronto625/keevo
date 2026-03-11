package com.keevo.catalog.contact.adapter.out.persistence;

import com.keevo.catalog.contact.domain.port.out.ClientSalesStatsPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ClientSalesStatsAdapter — JDBC implementation of {@link ClientSalesStatsPort} (Story 2.5).
 *
 * <p>Uses schema-qualified SQL (e.g. {@code "kv_abc123".sales}) so that
 * JdbcTemplate always targets the current tenant's schema regardless of
 * the PostgreSQL search_path. Mirrors the pattern from {@code ProductCountAdapter}.
 *
 * <p>Runs with {@code NOT_SUPPORTED} propagation so that a missing
 * {@code sales} table (pre-Story 2.5 tenant) does not abort the outer
 * JPA transaction — returns 0 gracefully instead.
 */
@Component
public class ClientSalesStatsAdapter implements ClientSalesStatsPort {

    private static final Logger log = LoggerFactory.getLogger(ClientSalesStatsAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public ClientSalesStatsAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public long countSalesByClient(UUID clientId) {
        String schema = TenantContext.getCurrentTenant();
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM \"" + schema + "\".sales WHERE client_id = ?",
                    Long.class, clientId);
            return count != null ? count : 0L;
        } catch (Exception e) {
            log.debug("sales table not yet available in schema {}: {}", schema, e.getMessage());
            return 0L;
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public long totalSpentByClient(UUID clientId) {
        String schema = TenantContext.getCurrentTenant();
        try {
            Long total = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(total_amount), 0) FROM \"" + schema + "\".sales WHERE client_id = ?",
                    Long.class, clientId);
            return total != null ? total : 0L;
        } catch (Exception e) {
            log.debug("sales table not yet available in schema {}: {}", schema, e.getMessage());
            return 0L;
        }
    }
}

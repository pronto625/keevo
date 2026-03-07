package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.domain.port.out.StoreCountPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * StoreCountAdapter — JDBC implementation of {@link StoreCountPort}.
 *
 * <p>Uses a fully-qualified table name ({@code "schema".stores}) derived from
 * {@link TenantContext} to avoid relying on {@code search_path}. This is required
 * because {@link JdbcTemplate} acquires connections directly from the DataSource,
 * bypassing Hibernate's {@code SchemaAwareMultiTenantConnectionProvider} which
 * would otherwise set {@code search_path} only for Hibernate-managed connections.
 *
 * <p>NOTE: Temporary count-only adapter. Epic 3 will introduce a full StoreRepository
 * with CRUD operations — this adapter will be superseded at that point.
 */
@Component
public class StoreCountAdapter implements StoreCountPort {

    private final JdbcTemplate jdbcTemplate;

    public StoreCountAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int countActiveStores() {
        String schema = TenantContext.getCurrentTenant();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + schema + "\".stores WHERE is_active = TRUE",
                Integer.class);
        return count != null ? count : 0;
    }
}

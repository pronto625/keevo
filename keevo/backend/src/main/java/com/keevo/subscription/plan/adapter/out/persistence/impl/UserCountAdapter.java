package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.domain.port.out.UserCountPort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * UserCountAdapter — JDBC implementation of {@link UserCountPort}.
 *
 * <p>Counts employees (role='EMPLOYEE', is_active=true) in the tenant-schema
 * {@code users} table. The OWNER is excluded from the employee limit count.
 *
 * <p>Uses a fully-qualified table name ({@code "schema".users}) derived from
 * {@link TenantContext} because {@link JdbcTemplate} bypasses Hibernate's
 * {@code SchemaAwareMultiTenantConnectionProvider} (which sets {@code search_path}
 * only for Hibernate-managed connections).
 *
 * <p>NOTE: Temporary count-only adapter. Epic 3 will introduce a full UserRepository
 * — this adapter will be superseded at that point.
 */
@Component
public class UserCountAdapter implements UserCountPort {

    private final JdbcTemplate jdbcTemplate;

    public UserCountAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int countEmployees() {
        String schema = TenantSchema.validate(TenantContext.getCurrentTenant());
        Integer count = jdbcTemplate.queryForObject(
                // Employees live in the tenant "employees" table (the legacy tenant "users"
                // table is empty): the subscription screen showed 0 employees (device test).
                "SELECT COUNT(*) FROM \"" + schema + "\".employees WHERE status = 'ACTIVE'",
                Integer.class);
        return count != null ? count : 0;
    }
}

package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.identity.employee.domain.port.out.EmployeeCountPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * EmployeeCountAdapter — JDBC implementation of {@link EmployeeCountPort}.
 *
 * <p>Uses fully-qualified table name from {@link TenantContext} (same pattern as StoreCountAdapter).
 * Story 3.5 — AC2.
 */
@Component
public class EmployeeCountAdapter implements EmployeeCountPort {

    private final JdbcTemplate jdbcTemplate;

    public EmployeeCountAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int countActiveEmployees() {
        String schema = TenantContext.getCurrentTenant();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + schema + "\".employees WHERE status = 'ACTIVE'",
                Integer.class);
        return count != null ? count : 0;
    }
}

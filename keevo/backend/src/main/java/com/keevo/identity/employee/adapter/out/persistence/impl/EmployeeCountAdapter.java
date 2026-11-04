package com.keevo.identity.employee.adapter.out.persistence.impl;

import com.keevo.identity.employee.domain.port.out.EmployeeCountPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * EmployeeCountAdapter — JDBC implementation of {@link EmployeeCountPort}.
 *
 * <p>Uses fully-qualified table name from {@link TenantContext} (same pattern as StoreCountAdapter).
 * Story 3.5 — AC2. Lives in identity (owner of the {@code employees} table) since 12.2d.
 */
@Component
public class EmployeeCountAdapter implements EmployeeCountPort {

    private final JdbcTemplate jdbcTemplate;

    public EmployeeCountAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ActiveCounts countActive(UUID storeId, UUID excludedEmployeeId) {
        String schema = TenantSchema.validate(TenantContext.getCurrentTenant());
        ActiveCounts counts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FILTER (WHERE status = 'ACTIVE') AS tenant, "
                        + "COUNT(*) FILTER (WHERE status = 'ACTIVE' AND store_id = ? AND id IS DISTINCT FROM ?) AS store "
                        + "FROM \"" + schema + "\".employees",
                (rs, i) -> new ActiveCounts(rs.getInt("tenant"), rs.getInt("store")), storeId, excludedEmployeeId);
        return counts != null ? counts : new ActiveCounts(0, 0);
    }

    @Override
    public int countActiveEmployeesInStore(UUID storeId, UUID excludedEmployeeId) {
        String schema = TenantSchema.validate(TenantContext.getCurrentTenant());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + schema + "\".employees WHERE store_id = ? AND status = 'ACTIVE' "
                        + "AND id IS DISTINCT FROM ?",
                Integer.class, storeId, excludedEmployeeId);
        return count != null ? count : 0;
    }
}

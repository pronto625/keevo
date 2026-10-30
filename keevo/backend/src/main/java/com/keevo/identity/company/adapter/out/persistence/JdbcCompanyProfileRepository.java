package com.keevo.identity.company.adapter.out.persistence;

import com.keevo.identity.company.domain.port.out.CompanyProfileRepository;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw JDBC on {@code public.tenants.designation} (V31, Story 14.2) — not mapped in {@code TenantJpaEntity}
 * (ddl-auto=validate tolerates the extra column, same pattern as {@code tokens_valid_after}). Fully-qualified
 * {@code public.} table, parameterized by the validated schema name.
 */
@Component
public class JdbcCompanyProfileRepository implements CompanyProfileRepository {

    private static final String SELECT = "SELECT id, code, name, designation, status FROM public.tenants "
            + "WHERE schema_name = ?";
    private static final String UPDATE = "UPDATE public.tenants SET designation = ? "
            + "WHERE schema_name = ?";

    private final JdbcTemplate jdbc;

    public JdbcCompanyProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantIdentity> find(String tenantSchema) {
        if (!TenantSchema.isValid(tenantSchema)) {
            return Optional.empty();
        }
        List<TenantIdentity> rows = jdbc.query(SELECT, (rs, n) -> new TenantIdentity(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("designation"), rs.getString("status")), tenantSchema);
        return rows.stream().findFirst();
    }

    @Override
    public boolean updateDesignation(String tenantSchema, String designation) {
        return jdbc.update(UPDATE, designation, TenantSchema.validate(tenantSchema)) == 1;
    }
}

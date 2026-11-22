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

    private static final String SELECT_DETAILS = "SELECT description, category, address, phone, email, niu, rccm, "
            + "country, setup_completed_at IS NOT NULL AS setup_completed FROM public.tenants WHERE schema_name = ?";
    /** NULL parameter = keep; blank = clear (NULLIF turns '' into NULL); designation and details share one statement. */
    private static final String UPDATE_DETAILS = "UPDATE public.tenants SET "
            + "designation = CASE WHEN ?::text IS NULL THEN designation ELSE NULLIF(btrim(?), '') END, "
            + "description = CASE WHEN ?::text IS NULL THEN description ELSE NULLIF(btrim(?), '') END, "
            + "category = CASE WHEN ?::text IS NULL THEN category ELSE NULLIF(btrim(?), '') END, "
            + "address = CASE WHEN ?::text IS NULL THEN address ELSE NULLIF(btrim(?), '') END, "
            + "phone = CASE WHEN ?::text IS NULL THEN phone ELSE NULLIF(btrim(?), '') END, "
            + "email = CASE WHEN ?::text IS NULL THEN email ELSE NULLIF(btrim(?), '') END, "
            + "niu = CASE WHEN ?::text IS NULL THEN niu ELSE NULLIF(btrim(?), '') END, "
            + "rccm = CASE WHEN ?::text IS NULL THEN rccm ELSE NULLIF(btrim(?), '') END, "
            + "country = COALESCE(NULLIF(btrim(?), ''), country) "
            + "WHERE schema_name = ?";
    private static final String MARK_SETUP = "UPDATE public.tenants SET setup_completed_at = NOW() "
            + "WHERE schema_name = ? AND setup_completed_at IS NULL";

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

    @Override
    public Optional<CompanyDetails> findDetails(String tenantSchema) {
        if (!TenantSchema.isValid(tenantSchema)) {
            return Optional.empty();
        }
        return jdbc.query(SELECT_DETAILS, (rs, n) -> new CompanyDetails(rs.getString("description"),
                rs.getString("category"), rs.getString("address"), rs.getString("phone"), rs.getString("email"),
                rs.getString("niu"), rs.getString("rccm"), rs.getString("country"),
                rs.getBoolean("setup_completed")), tenantSchema).stream().findFirst();
    }

    @Override
    public boolean updateDetails(String tenantSchema, DetailsPatch p) {
        return jdbc.update(UPDATE_DETAILS,
                p.designation(), p.designation(), p.description(), p.description(), p.category(), p.category(),
                p.address(), p.address(), p.phone(), p.phone(), p.email(), p.email(), p.niu(), p.niu(),
                p.rccm(), p.rccm(), p.country(), TenantSchema.validate(tenantSchema)) == 1;
    }

    @Override
    public boolean markSetupCompleted(String tenantSchema) {
        TenantSchema.validate(tenantSchema);
        // 0 rows = already completed or unknown; the caller reads the state afterwards
        jdbc.update(MARK_SETUP, tenantSchema);
        return findDetails(tenantSchema).isPresent();
    }
}

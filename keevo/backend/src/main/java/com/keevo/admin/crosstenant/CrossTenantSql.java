package com.keevo.admin.crosstenant;

import com.keevo.admin.AdminLabels;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 20.13 (FUS-NFR11) — the building blocks of the cross-tenant reads of the super-admin API, so that no hot path
 * runs one query per tenant schema:
 * <ul>
 *   <li>{@link #tenantsWith}: ONE catalogue query over {@code public.tenants} × {@code pg_attribute} returning the
 *       tenant schemas that hold a table with all the required columns (a module never used by a tenant, or an older
 *       module version, is simply not a branch — the union never fails on a missing table / column);</li>
 *   <li>{@link #unionAll}: one {@code UNION ALL} of per-tenant branches (each schema validated by {@link TenantSchema}
 *       before interpolation), bounded by {@code keevo.admin.cross-tenant.max-branches}.</li>
 * </ul>
 * KV-ADMIN (system tenant) and {@code DELETED} tenants are never branches.
 */
@Component
public class CrossTenantSql {

    private static final Logger log = LoggerFactory.getLogger(CrossTenantSql.class);

    public static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000000";
    static final String TOO_MANY = "Trop de comptes à parcourir : filtrez la liste par compte";
    public static final String INVALID_TENANT = "Compte invalide";
    static final String TENANT_NOT_FOUND = "Compte introuvable";

    private static final String TENANT_COLUMNS =
            "SELECT t.id::text AS id, COALESCE(NULLIF(btrim(t.designation), ''), NULLIF(btrim(t.name), '')) AS name, "
            + "t.schema_name, t.status, t.created_at FROM public.tenants t "
            + "WHERE t.id <> CAST('" + SYSTEM_TENANT_ID + "' AS uuid) AND t.status <> 'DELETED' ";

    /** The table with every required column exists in the tenant schema (no interpolation: built in SQL). */
    private static final String HAS_TABLE =
            "AND (SELECT COUNT(DISTINCT a.attname) FROM pg_attribute a "
            + "WHERE a.attrelid = to_regclass(quote_ident(t.schema_name) || '.' || quote_ident(?)) "
            + "AND a.attnum > 0 AND NOT a.attisdropped AND a.attname::text = ANY (string_to_array(?, ','))) = ? ";

    private final JdbcTemplate jdbc;
    private final int maxBranches;

    public CrossTenantSql(JdbcTemplate jdbc,
                          @Value("${keevo.admin.cross-tenant.max-branches:1000}") int maxBranches) {
        this.jdbc = jdbc;
        this.maxBranches = maxBranches;
    }

    /**
     * The tenants whose schema holds {@code table} with all of {@code columns} — every live tenant, or only
     * {@code tenantId} when given (already validated, see {@link #requireUuid}). One catalogue query.
     */
    public List<TenantBranch> tenantsWith(String tenantId, String table, List<String> columns) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(TENANT_COLUMNS);
        if (tenantId != null) {
            sql.append("AND t.id = CAST(? AS uuid) ");
            params.add(tenantId);
        }
        sql.append(HAS_TABLE).append("ORDER BY t.id");
        params.add(table);
        params.add(String.join(",", columns));
        params.add(columns.size());
        return valid(jdbc.query(sql.toString(), TENANT_ROW, params.toArray()));
    }

    /** Every live tenant (not deleted, not the system tenant) — one query. */
    public List<TenantBranch> liveTenants() {
        return valid(jdbc.query(TENANT_COLUMNS + "ORDER BY t.id", TENANT_ROW));
    }

    /** One live tenant, or empty. */
    public Optional<TenantBranch> tenant(String tenantId) {
        return valid(jdbc.query(TENANT_COLUMNS + "AND t.id = CAST(? AS uuid)", TENANT_ROW, tenantId))
                .stream().findFirst();
    }

    /** One live tenant, or 404 {@code TENANT_NOT_FOUND} « Compte introuvable ». */
    public TenantBranch requireTenant(String tenantId) {
        return tenant(requireUuid(tenantId, INVALID_TENANT))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.TENANT_NOT_FOUND, TENANT_NOT_FOUND));
    }

    /**
     * {@code (branch0) UNION ALL (branch1) …} for the given tenants; the caller writes each branch (and validates the
     * schema it interpolates). Refuses more than {@code max-branches} branches (422 VALIDATION_ERROR).
     */
    public String unionAll(List<TenantBranch> tenants, Function<TenantBranch, String> branch) {
        if (tenants.size() > maxBranches) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, TOO_MANY);
        }
        StringJoiner union = new StringJoiner(" UNION ALL ");
        for (TenantBranch tenant : tenants) {
            union.add("(" + branch.apply(tenant) + ")");
        }
        return union.toString();
    }

    /** The tenant id as a SQL text literal ({@code 'uuid'}) — a parsed UUID, never free text. */
    public static String tenantLiteral(TenantBranch tenant) {
        return "'" + UUID.fromString(tenant.id()) + "'";
    }

    /** {@code value} as a canonical UUID string, else 422 VALIDATION_ERROR with {@code message}; null / blank → null. */
    public static String requireUuid(String value, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message);
        }
    }

    private static List<TenantBranch> valid(List<TenantBranch> tenants) {
        return tenants.stream().filter(t -> {
            if (TenantSchema.isValid(t.schemaName())) {
                return true;
            }
            log.warn("Cross-tenant read: tenant {} skipped (invalid schema name)", t.id());
            return false;
        }).toList();
    }

    private static final RowMapper<TenantBranch> TENANT_ROW = (rs, n) -> {
        Timestamp created = rs.getTimestamp("created_at");
        String name = rs.getString("name");
        return new TenantBranch(rs.getString("id"), name == null ? AdminLabels.UNNAMED_TENANT : name,
                rs.getString("schema_name"), rs.getString("status"), created == null ? null : created.toInstant());
    };
}

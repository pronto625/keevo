package com.keevo.admin.crosstenant;

import com.keevo.admin.AdminLabels;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Story 20.13 (FUS-NFR11) — the building blocks of the cross-tenant reads of the super-admin API, so that no hot path
 * runs one query per tenant schema:
 * <ul>
 *   <li>{@link #liveTenants()} / {@link #withPresence}: the live tenants with the {@link TenantTable}s present in
 *       their schema, in ONE query over {@code public.tenants} × {@code pg_attribute} — a missing table / column is
 *       never a branch, so it never fails a statement. Callers resolve it once per request and pass the branches
 *       down;</li>
 *   <li>{@link #query}: per-tenant branches combined with {@code UNION ALL}, run in chunks of
 *       {@code keevo.admin.cross-tenant.chunk-size} (default 100) branches. A branch takes ~10–25 AccessShareLocks
 *       (tables + indexes); a statement over every tenant would exhaust {@code max_locks_per_transaction} (64 ×
 *       max_connections slots) around 250–300 tenants. Each chunk autocommits, so its locks are released before the
 *       next one. A chunk failing on a schema / data error is retried tenant by tenant: the failing tenant is skipped
 *       (WARN, tenant id only), the others are kept. Connection / resource failures are rethrown (no partial 200 on a
 *       database outage).</li>
 * </ul>
 * KV-ADMIN (system tenant) and {@code DELETED} tenants are never branches.
 */
@Component
public class CrossTenantSql {

    private static final Logger log = LoggerFactory.getLogger(CrossTenantSql.class);

    public static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000000";
    public static final String INVALID_TENANT = "Compte invalide";
    static final String TENANT_NOT_FOUND = "Compte introuvable";
    /** SQL predicate of a live tenant row aliased {@code t}. */
    public static final String LIVE_TENANT = "t.id <> CAST('" + SYSTEM_TENANT_ID + "' AS uuid) "
            + "AND t.status <> 'DELETED'";

    /** The table specs as a constant VALUES list (enum constants only — never request data). */
    private static final String SPECS = specs();

    private static final String TENANT_COLUMNS =
            "SELECT t.id::text AS id, COALESCE(NULLIF(btrim(t.designation), ''), NULLIF(btrim(t.name), '')) AS name, "
            + "t.schema_name, t.status, t.created_at, "
            + "(SELECT string_agg(spec.name, ',') FROM (VALUES " + SPECS + ") AS spec(name, tbl, cols, n) "
            + "WHERE (SELECT COUNT(DISTINCT a.attname) FROM pg_attribute a "
            + "WHERE a.attrelid = to_regclass(quote_ident(t.schema_name) || '.' || spec.tbl) "
            + "AND a.attnum > 0 AND NOT a.attisdropped AND a.attname::text = ANY (string_to_array(spec.cols, ','))) "
            + "= spec.n) AS present FROM public.tenants t WHERE " + LIVE_TENANT + " ";

    /** One tenant's branch; appends the bound values it uses to {@code params}, in order. */
    @FunctionalInterface
    public interface BranchSql {
        String sql(TenantBranch tenant, List<Object> params);
    }

    private final JdbcTemplate jdbc;
    private final int chunkSize;

    public CrossTenantSql(JdbcTemplate jdbc, @Value("${keevo.admin.cross-tenant.chunk-size:100}") int chunkSize) {
        this.jdbc = jdbc;
        this.chunkSize = Math.max(1, chunkSize);
    }

    // ── tenants and table presence ──────────────────────────────────────────

    /** Every live tenant with its table presence — one query. */
    public List<TenantBranch> liveTenants() {
        return valid(jdbc.query(TENANT_COLUMNS + "ORDER BY t.id", TENANT_ROW));
    }

    /** One live tenant with its table presence, or empty. */
    public Optional<TenantBranch> tenant(String tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return valid(jdbc.query(TENANT_COLUMNS + "AND t.id = CAST(? AS uuid)", TENANT_ROW, tenantId)).stream().findFirst();
    }

    /** One live tenant, or 404 {@code TENANT_NOT_FOUND} « Compte introuvable » (malformed id → 422). */
    public TenantBranch requireTenant(String tenantId) {
        return tenant(requireUuid(tenantId, INVALID_TENANT))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.TENANT_NOT_FOUND, TENANT_NOT_FOUND));
    }

    /** All live tenants, or only {@code tenantId} (validated), holding every {@code required} table — one query. */
    public List<TenantBranch> tenantsWith(String tenantId, TenantTable... required) {
        List<TenantBranch> tenants = tenantId == null ? liveTenants() : tenant(tenantId).stream().toList();
        return tenants.stream().filter(t -> t.has(required)).toList();
    }

    /**
     * The given tenants (built by callers from their own queries) with their table presence resolved in ONE query;
     * tenants no longer live are dropped. Already resolved branches are returned as they are.
     */
    public List<TenantBranch> withPresence(List<TenantBranch> tenants) {
        if (tenants.isEmpty() || tenants.stream().allMatch(TenantBranch::resolved)) {
            return tenants;
        }
        Map<String, TenantBranch> live = new HashMap<>();
        jdbc.query(TENANT_COLUMNS + "AND t.id = ANY (CAST(string_to_array(?, ',') AS uuid[]))", TENANT_ROW,
                tenants.stream().map(TenantBranch::id).collect(Collectors.joining(",")))
                .forEach(t -> live.put(t.id(), t));
        List<TenantBranch> out = new ArrayList<>();
        for (TenantBranch t : tenants) {
            TenantBranch resolved = live.get(t.id());
            if (resolved != null && TenantSchema.isValid(resolved.schemaName())) {
                out.add(t.withTables(resolved.tables()));
            }
        }
        return out;
    }

    // ── chunked union ────────────────────────────────────────────────────────

    /**
     * Runs {@code outer(UNION ALL of the branches)} over {@code tenants}, chunk by chunk, and returns every row of
     * every chunk (callers sum aggregates / merge keyset pages in Java). See the class comment for the failure policy.
     */
    public <T> List<T> query(List<TenantBranch> tenants, BranchSql branch, UnaryOperator<String> outer,
                             RowMapper<T> mapper) {
        List<T> rows = new ArrayList<>();
        for (int from = 0; from < tenants.size(); from += chunkSize) {
            List<TenantBranch> chunk = tenants.subList(from, Math.min(tenants.size(), from + chunkSize));
            try {
                rows.addAll(run(chunk, branch, outer, mapper));
            } catch (DataAccessResourceFailureException | TransientDataAccessException
                     | RecoverableDataAccessException e) {
                throw e;
            } catch (DataAccessException e) {
                log.warn("Cross-tenant read: a chunk of {} tenants failed ({}), retrying tenant by tenant",
                        chunk.size(), e.getClass().getSimpleName());
                for (TenantBranch tenant : chunk) {
                    try {
                        rows.addAll(run(List.of(tenant), branch, outer, mapper));
                    } catch (DataAccessResourceFailureException | TransientDataAccessException
                             | RecoverableDataAccessException resource) {
                        throw resource;
                    } catch (DataAccessException single) {
                        log.warn("Cross-tenant read: tenant {} skipped ({})", tenant.id(),
                                single.getClass().getSimpleName());
                    }
                }
            }
        }
        return rows;
    }

    private <T> List<T> run(List<TenantBranch> chunk, BranchSql branch, UnaryOperator<String> outer,
                            RowMapper<T> mapper) {
        List<Object> params = new ArrayList<>();
        String union = unionAll(chunk, t -> branch.sql(t, params));
        return jdbc.query(outer.apply(union), mapper, params.toArray());
    }

    /** {@code (branch0) UNION ALL (branch1) …} — each branch validates the schema it interpolates. */
    public static String unionAll(List<TenantBranch> tenants, Function<TenantBranch, String> branch) {
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

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String specs() {
        StringJoiner values = new StringJoiner(", ");
        for (TenantTable spec : TenantTable.values()) {
            values.add("('" + spec.name() + "', '" + spec.table() + "', '" + String.join(",", spec.columns()) + "', "
                    + spec.columns().size() + ")");
        }
        return values.toString();
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
        String present = rs.getString("present");
        Set<TenantTable> tables = EnumSet.noneOf(TenantTable.class);
        if (present != null) {
            for (String spec : present.split(",")) {
                tables.add(TenantTable.valueOf(spec));
            }
        }
        return new TenantBranch(rs.getString("id"), name == null ? AdminLabels.UNNAMED_TENANT : name,
                rs.getString("schema_name"), rs.getString("status"), created == null ? null : created.toInstant(),
                tables);
    };
}

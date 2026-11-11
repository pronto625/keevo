package com.keevo.admin.tenant.application.service;

import com.keevo.admin.MemberRolesSql;
import com.keevo.admin.AdminLabels;
import com.keevo.admin.TenantLastActivitySql;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.admin.tenant.domain.model.*;
import com.keevo.admin.tenant.domain.port.in.*;
import com.keevo.identity.api.TenantDeletionPort;
import com.keevo.identity.api.PlanKind;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AdminTenantService — implements Super Admin tenant management use cases.
 *
 * <p><b>Architecture rules:</b>
 * <ol>
 *   <li>listTenants(): queries public schema via JdbcTemplate ONLY — no TenantContext manipulation.
 *       Story 19.11c (FUS-NFR11): the cross-schema metrics of the page (storeCount, lastActivityAt, owner name) come
 *       from the table presence of the page tenants in one catalogue query, then two chunked {@code UNION ALL}
 *       statements ({@link CrossTenantSql}, 100 tenants each) — never a statement per tenant; the « dernière
 *       activité » filter is one union over the candidates with the range in SQL.
 *   </li>
 *   <li>getTenantDetail(): switches TenantContext to the target tenant's schema for stores,
 *       employees, and audit log, then restores it — same pattern as ActivatePlanService.
 *   </li>
 *   <li>Always excludes SUPER_ADMIN system tenant (id = 00000000-0000-0000-0000-000000000000).
 *   </li>
 * </ol>
 *
 * <p><b>Schema name safety:</b> schemaName is sourced from {@code public.tenants.schema_name} and
 * was validated at provisioning time to match {@code ^kv_[a-z0-9]{6}$}. It is never taken from
 * user input, and every interpolation is still gated by {@link TenantSchema} (invalid rows are
 * skipped in the list, rejected in the detail) — Story v1s-13-12.
 */
@Service
public class AdminTenantService implements ListTenantsUseCase, GetTenantDetailUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminTenantService.class);

    private static final String ADMIN_TENANT_ID = "00000000-0000-0000-0000-000000000000";
    private static final int AUDIT_PAGE_SIZE = 50;

    /** List {@code plan} filter: no filter. Any other value is a catalogue plan code or {@link #PLAN_FILTER_PAID}. */
    private static final String PLAN_FILTER_ALL = "ALL";
    /** List {@code plan} filter: legacy « paying » = every plan of kind TRIAL or PAID (Story 19.7a: by kind). */
    private static final String PLAN_FILTER_PAID = "PAID";

    /** Trimmed « first last » of an employees row aliased {@code e}; NULL when both are blank. */
    private static final String EMPLOYEE_FULL_NAME =
            "NULLIF(TRIM(CONCAT_WS(' ', TRIM(e.first_name), TRIM(e.last_name))), '')";

    private final JdbcTemplate jdbc;
    private final TenantDeletionPort tenantDeletion;
    private final PlanCatalog catalog;
    private final CrossTenantSql crossTenant;

    /** With the seeded plan catalogue (tests). */
    public AdminTenantService(JdbcTemplate jdbcTemplate, TenantDeletionPort tenantDeletion) {
        this(jdbcTemplate, tenantDeletion, PlanCatalog.SEED);
    }

    /** With cross-tenant statements of the default chunk size (tests). */
    public AdminTenantService(JdbcTemplate jdbcTemplate, TenantDeletionPort tenantDeletion, PlanCatalog catalog) {
        this(jdbcTemplate, tenantDeletion, catalog,
                new CrossTenantSql(jdbcTemplate, CrossTenantSql.DEFAULT_CHUNK_SIZE));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminTenantService(JdbcTemplate jdbcTemplate, TenantDeletionPort tenantDeletion, PlanCatalog catalog,
                              CrossTenantSql crossTenant) {
        this.jdbc = jdbcTemplate;
        this.tenantDeletion = tenantDeletion;
        this.catalog = catalog;
        this.crossTenant = crossTenant;
    }

    // ── ListTenantsUseCase ────────────────────────────────────────────────────

    @Override
    // Deliberately NOT @Transactional: each per-tenant statement autocommits, so one broken tenant
    // schema cannot abort (25P02) the PostgreSQL transaction of the following tenants' queries.
    public Page<AdminTenantListItem> execute(ListTenantsQuery query) {
        List<Object> params = new ArrayList<>();
        String whereClause = buildWhereClause(query, params);

        // Count query
        String countSql = "SELECT COUNT(*) FROM public.tenants t "
                + "LEFT JOIN public.user_tenant_memberships m_owner "
                + "    ON m_owner.tenant_id = t.id AND m_owner.role = 'OWNER' "
                + "LEFT JOIN public.users owner "
                + "    ON owner.id = m_owner.user_id "
                + whereClause;

        long offset = (long) query.page() * query.pageSize();
        Long total;
        if (query.lastActivityFrom() != null || query.lastActivityTo() != null) {
            // 9-1 AC3 — last activity = latest merchant event (TenantLastActivitySql, same definition
            // as the column). It lives in each tenant schema: the candidates are filtered in chunked unions
            // (19.11c — no statement per candidate), then paginated on their ids.
            List<UUID> active = tenantsActiveInRange(whereClause, params,
                    query.lastActivityFrom(), query.lastActivityTo());
            total = (long) active.size();
            List<UUID> pageIds = active.stream().skip(offset).limit(query.pageSize()).toList();
            if (pageIds.isEmpty()) {
                return new PageImpl<>(List.of(), PageRequest.of(query.page(), query.pageSize()), total);
            }
            whereClause = whereClause + " AND t.id IN ("
                    + pageIds.stream().map(x -> "?").collect(Collectors.joining(",")) + ")";
            params.addAll(pageIds);
            offset = 0;
        } else {
            total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        }
        if (total == null || total == 0) {
            return new PageImpl<>(List.of(), PageRequest.of(query.page(), query.pageSize()), 0L);
        }

        // List query — includes employee_count via subquery
        String listSql = "SELECT t.id, t.code, t.name, t.schema_name, t.plan_type, t.status, "
                + "t.created_at, t.deletion_scheduled_at, "
                + "COALESCE(owner.phone_number, '') AS owner_phone, "
                + "(SELECT COUNT(DISTINCT m.user_id) "
                + " FROM public.user_tenant_memberships m "
                + " WHERE m.tenant_id = t.id AND m.role IN (" + MemberRolesSql.IN_LIST + ")) AS employee_count "
                + "FROM public.tenants t "
                + "LEFT JOIN public.user_tenant_memberships m_owner "
                + "    ON m_owner.tenant_id = t.id AND m_owner.role = 'OWNER' "
                + "LEFT JOIN public.users owner "
                + "    ON owner.id = m_owner.user_id "
                + whereClause
                + " GROUP BY t.id, owner.phone_number "
                + "ORDER BY t.created_at DESC "
                + "LIMIT ? OFFSET ?";

        List<Object> listParams = new ArrayList<>(params);
        listParams.add(query.pageSize());
        listParams.add(offset);

        // The schema name comes with each row (was one batched lookup, Story 11.5).
        TenantListRowMapper rowMapper = new TenantListRowMapper();
        List<PageRow> rows = jdbc.query(listSql,
                (rs, n) -> new PageRow(rowMapper.mapRow(rs, n), rs.getString("schema_name")), listParams.toArray());

        return new PageImpl<>(enrichWithCrossSchemaMetrics(rows), PageRequest.of(query.page(), query.pageSize()),
                total);
    }

    // ── GetTenantDetailUseCase ────────────────────────────────────────────────

    @Override
    // Deliberately NOT @Transactional: each per-tenant statement autocommits, so one broken tenant
    // schema cannot abort (25P02) the PostgreSQL transaction of the following tenants' queries.
    public AdminTenantDetail execute(GetTenantDetailQuery query) {
        // Load summary from public schema
        String summarySql = "SELECT t.id, t.code, t.name, t.schema_name, t.plan_type, t.status, "
                + "t.created_at, t.deletion_scheduled_at, "
                + "COALESCE(owner.phone_number, '') AS owner_phone, "
                + "(SELECT COUNT(DISTINCT m.user_id) "
                + " FROM public.user_tenant_memberships m "
                + " WHERE m.tenant_id = t.id AND m.role IN (" + MemberRolesSql.IN_LIST + ")) AS employee_count "
                + "FROM public.tenants t "
                + "LEFT JOIN public.user_tenant_memberships m_owner "
                + "    ON m_owner.tenant_id = t.id AND m_owner.role = 'OWNER' "
                + "LEFT JOIN public.users owner ON owner.id = m_owner.user_id "
                + "WHERE t.id = ? "
                + "GROUP BY t.id, owner.phone_number";

        List<AdminTenantListItem> summaryRows = jdbc.query(
                summarySql, new TenantListRowMapper(), query.tenantId());

        if (summaryRows.isEmpty()) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND,
                    "Tenant not found: " + query.tenantId());
        }
        // Story 11.5 — schema_name loaded once and reused for the enrichment
        String schemaName = loadSchemaName(query.tenantId());
        AdminTenantListItem summary = enrichWithCrossSchemaMetrics(List.of(new PageRow(summaryRows.get(0), schemaName)))
                .get(0);

        // Switch TenantContext to load cross-schema data
        String previousTenant = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(schemaName);
        try {
            List<AdminStoreInfo> stores = loadStores(schemaName, query.tenantId());
            List<AdminEmployeeInfo> employees = loadEmployees(schemaName, query.tenantId());
            long auditTotal = countAuditEvents(schemaName, query.tenantId());
            List<AdminAuditEvent> auditEvents = loadAuditEvents(schemaName, query.tenantId(), query.auditPage());
            AdminPlanInfo plan = loadPlan(schemaName, query.tenantId());
            AdminSuspensionInfo suspension = loadSuspension(query.tenantId());
            return new AdminTenantDetail(summary, stores, employees, auditEvents, auditTotal, plan, suspension);
        } finally {
            TenantContext.setCurrentTenant(previousTenant);
        }
    }

    // ── Cancel / Force-delete (called directly by controller) ─────────────────

    /**
     * SUPER_ADMIN cancel. Conditional UPDATE: refuses a tenant that is no longer DELETION_PENDING
     * (e.g. DELETED by a concurrent scheduler run — the UPDATE waits on its row lock, then matches
     * nothing) or whose schema is already dropped (v1s-15-9). Unlike the owner, the admin may
     * still rescue a tenant whose grace period has expired.
     */
    @Transactional
    public void cancelDeletion(UUID tenantId) {
        int updated = jdbc.update(
                "UPDATE public.tenants SET status = 'ACTIVE', deletion_scheduled_at = NULL "
                + "WHERE id = ? AND status = 'DELETION_PENDING' "
                + "AND to_regnamespace(schema_name) IS NOT NULL",
                tenantId);
        if (updated == 0) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND,
                    "Tenant not found, not DELETION_PENDING or schema already dropped: " + tenantId);
        }
        // Story 16.6 — durable RGPD trace, same transaction as the status change
        tenantDeletion.recordAdminDeletionCancel(tenantId);
    }

    /**
     * SUPER_ADMIN force-delete through the scheduler's deletion routine (v1s-15-9) — previously it
     * only flipped the status to DELETED, leaking the schema, memberships and tokens.
     *
     * <p>Deliberately NOT {@code @Transactional}: the first UPDATE auto-commits a "deleting" marker
     * (DELETION_PENDING with {@code deletion_scheduled_at = now()}) before the DROP, which runs on
     * its own connection. If the routine then fails, the expired DELETION_PENDING row is retried by
     * the next daily run, and the owner cannot cancel it (grace period over).
     *
     * @throws DomainException TENANT_NOT_FOUND for an unknown, already DELETED or system tenant;
     *                         DELETION_NOT_PENDING if the tenant was cancelled concurrently
     *                         (a concurrent deletion by the daily run counts as success)
     */
    public void forceDelete(UUID tenantId) {
        int marked = jdbc.update(
                "UPDATE public.tenants SET status = 'DELETION_PENDING', deletion_scheduled_at = now() "
                + "WHERE id = ? AND id <> '" + ADMIN_TENANT_ID + "'::uuid AND status <> 'DELETED'",
                tenantId);
        if (marked == 0) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND,
                    "Tenant not found or already deleted: " + tenantId);
        }
        // Story 16.6 — durable RGPD trace (REQUESTED/ADMIN, due now); deleteNow writes EXECUTED/ADMIN
        tenantDeletion.recordAdminDeletionRequest(tenantId);
        if (!tenantDeletion.deleteNow(tenantId)) {
            // A concurrent daily run may have deleted it between the marker and the routine:
            // the admin's intent is fulfilled, so that is a success, not a conflict.
            String status = jdbc.queryForObject(
                    "SELECT status FROM public.tenants WHERE id = ?", String.class, tenantId);
            if (!"DELETED".equals(status)) {
                throw new DomainException(ErrorCode.DELETION_NOT_PENDING,
                        "Tenant deletion was cancelled concurrently: " + tenantId);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Escapes the LIKE wildcards ({@code \\} first, then {@code %} and {@code _}) for {@code ESCAPE '\\'}. */
    static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String buildWhereClause(ListTenantsQuery q, List<Object> params) {
        StringBuilder sb = new StringBuilder("WHERE t.id <> '")
                .append(ADMIN_TENANT_ID).append("'::uuid");

        if (q.search() != null && !q.search().isBlank()) {
            // Story 12.12 — phones are stored as '+<digits>' (V10): match the raw term and its
            // normalised form so '237…' still finds '+237…'.
            // Story 16.14 — the account code (KV-XXXXXX) matches too, like the name (case-insensitive, partial).
            // `%` / `_` / `\` typed in a code are literal characters, not wildcards.
            sb.append(" AND (t.name ILIKE ? OR t.code ILIKE ? ESCAPE '\\' OR owner.phone_number IN (?, ?))");
            String like = "%" + q.search().trim() + "%";
            params.add(like);
            params.add("%" + escapeLike(q.search().trim()) + "%");
            params.add(q.search().trim());
            params.add(PhoneNumbers.normalize(q.search()));
        }

        String plan = normalizePlanFilter(q.plan());
        if (plan != null) {
            if (PLAN_FILTER_PAID.equals(plan)) { // 19.7a: by kind, joined on the catalogue
                sb.append(" AND t.plan_type IN (SELECT pl.code FROM public.plans pl WHERE pl.kind IN (?, ?))");
                params.add(PlanKind.TRIAL.name());
                params.add(PlanKind.PAID.name());
            } else {
                sb.append(" AND t.plan_type = ?");
                params.add(plan);
            }
        }

        if (q.status() != null && !q.status().isBlank() && !q.status().equals("ALL")) {
            sb.append(" AND t.status = ?");
            params.add(q.status());
        }

        if (q.registeredFrom() != null) {
            sb.append(" AND t.created_at >= ?");
            params.add(Timestamp.from(q.registeredFrom()));
        }
        if (q.registeredTo() != null) {
            sb.append(" AND t.created_at <= ?");
            params.add(Timestamp.from(q.registeredTo()));
        }

        return sb.toString();
    }

    /**
     * Ids (newest tenant first) of the tenants matching the public filters whose last activity —
     * the latest merchant event, see {@link TenantLastActivitySql} — falls in [{@code from}, {@code to}]. A
     * tenant never active, with an invalid schema name or an unreadable schema is excluded.
     *
     * <p>Story 19.11c: 1 candidates query + 1 presence query + one union per 100 candidates (the range is applied
     * in each branch, only the matching ids come back) — was one statement per candidate.
     */
    private List<UUID> tenantsActiveInRange(String whereClause, List<Object> params, Instant from, Instant to) {
        List<TenantBranch> candidates = jdbc.query(
                "SELECT t.id::text AS id, t.schema_name FROM public.tenants t "
                + "LEFT JOIN public.user_tenant_memberships m_owner "
                + "    ON m_owner.tenant_id = t.id AND m_owner.role = 'OWNER' "
                + "LEFT JOIN public.users owner ON owner.id = m_owner.user_id "
                + whereClause
                + " GROUP BY t.id ORDER BY t.created_at DESC",
                (rs, n) -> new TenantBranch(rs.getString("id"), null, rs.getString("schema_name"), null, null),
                params.toArray());
        List<TenantBranch> readable = crossTenant.withPresence(candidates.stream()
                .filter(c -> TenantSchema.isValid(c.schemaName())).toList());
        Set<String> matching = new java.util.HashSet<>(crossTenant.query(readable, (tenant, branchParams) -> {
                    String last = TenantLastActivitySql.expression(tenant, branchParams);
                    StringBuilder range = new StringBuilder("x.last_activity IS NOT NULL");
                    if (from != null) {
                        range.append(" AND x.last_activity >= ?");
                        branchParams.add(Timestamp.from(from));
                    }
                    if (to != null) {
                        range.append(" AND x.last_activity <= ?");
                        branchParams.add(Timestamp.from(to));
                    }
                    return "SELECT x.tenant_id FROM (SELECT " + CrossTenantSql.tenantLiteral(tenant)
                            + "::text AS tenant_id, " + last + " AS last_activity) x WHERE " + range;
                },
                union -> "SELECT tenant_id FROM (" + union + ") u", (rs, n) -> rs.getString("tenant_id")));
        return candidates.stream().map(TenantBranch::id).filter(matching::contains).map(UUID::fromString).toList();
    }

    /**
     * Plan filter: null/blank/ALL → null (no filter); a plan code of the catalogue, or PAID (legacy, = every plan of
     * kind TRIAL or PAID) kept; anything else → 422 VALIDATION_ERROR naming the accepted values (Story 19.7a: the
     * catalogue's codes, not a fixed list).
     */
    private String normalizePlanFilter(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String plan = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!PLAN_FILTER_ALL.equals(plan) && !PLAN_FILTER_PAID.equals(plan) && catalog.find(plan).isEmpty()) {
            List<String> accepted = new ArrayList<>();
            accepted.add(PLAN_FILTER_ALL);
            catalog.all().forEach(p -> accepted.add(p.code().code()));
            accepted.add(PLAN_FILTER_PAID);
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "plan must be one of " + String.join(", ", accepted));
        }
        return PLAN_FILTER_ALL.equals(plan) ? null : plan;
    }

    /** A row of the list (or the detail summary) with its tenant schema name. */
    private record PageRow(AdminTenantListItem item, String schemaName) {
    }

    /**
     * Fills storeCount, lastActivityAt and the owner's name of the given rows from their tenant schemas (Story
     * 19.11c, FUS-NFR11): the table presence of the rows' tenants in one catalogue query, then one union for the
     * store count + last activity and one union for the owner's name (100 tenants per statement) — never a statement
     * per tenant. The two figures stay separate statements so a broken employees table only loses the name
     * (« Propriétaire »), never the metrics. A row with an invalid schema name is returned as it is; a tenant whose
     * schema is gone (deleted) or unreadable keeps zero metrics and « Propriétaire ». Order of the rows kept.
     */
    private List<AdminTenantListItem> enrichWithCrossSchemaMetrics(List<PageRow> rows) {
        List<TenantBranch> branches = crossTenant.withPresence(rows.stream()
                .filter(r -> TenantSchema.isValid(r.schemaName())) // gate before SQL interpolation
                .map(r -> new TenantBranch(r.item().id().toString(), r.item().name(), r.schemaName(), r.item().status(),
                        null))
                .toList());

        Map<String, Object[]> metrics = new HashMap<>();
        crossTenant.query(branches, (tenant, params) -> {
                    String schema = TenantSchema.validate(tenant.schemaName());
                    String stores = tenant.has(TenantTable.STORES_ACTIVE)
                            ? "(SELECT COUNT(*) FROM " + schema + ".stores WHERE is_active = true)" : "CAST(0 AS bigint)";
                    return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, " + stores
                            + " AS store_count, " + TenantLastActivitySql.expression(tenant, params) + " AS last_activity";
                }, union -> "SELECT * FROM (" + union + ") u",
                (rs, n) -> new Object[]{rs.getString("tenant_id"), rs.getLong("store_count"),
                        rs.getTimestamp("last_activity")})
                .forEach(r -> metrics.put((String) r[0], r));

        Map<String, String> owners = new HashMap<>();
        crossTenant.query(branches.stream().filter(t -> t.has(TenantTable.EMPLOYEE_NAMES)).toList(),
                (tenant, params) -> {
                    String schema = TenantSchema.validate(tenant.schemaName());
                    params.add(tenant.id());
                    return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, (SELECT "
                            + EMPLOYEE_FULL_NAME + " FROM " + schema + ".employees e "
                            + "JOIN public.user_tenant_memberships m ON m.user_id = e.user_id "
                            + "WHERE m.tenant_id = CAST(? AS uuid) AND m.role = 'OWNER' "
                            + "ORDER BY e.created_at LIMIT 1) AS owner_name";
                }, union -> "SELECT * FROM (" + union + ") u",
                (rs, n) -> new String[]{rs.getString("tenant_id"), rs.getString("owner_name")})
                .forEach(r -> {
                    if (r[1] != null && !r[1].isBlank()) owners.put(r[0], r[1].trim());
                });

        List<AdminTenantListItem> out = new ArrayList<>(rows.size());
        for (PageRow row : rows) {
            AdminTenantListItem item = row.item();
            if (!TenantSchema.isValid(row.schemaName())) {
                out.add(item);
                continue;
            }
            String id = item.id().toString();
            Object[] m = metrics.get(id);
            Instant lastActivity = m == null ? null : toInstant(m[2]);
            int storeCount = m == null ? 0 : ((Number) m[1]).intValue();
            out.add(new AdminTenantListItem(
                    item.id(), item.code(), item.name(), item.ownerPhone(),
                    item.plan(), item.status(),
                    item.registeredAt(), lastActivity,
                    storeCount, item.employeeCount(),
                    item.deletionScheduledAt(), owners.getOrDefault(id, AdminLabels.OWNER)));
        }
        return out;
    }

    /**
     * Runs a query against one tenant's schema. A schema / SQL / data error of this tenant only
     * (missing table, broken schema…) yields {@code null} so the caller skips it; connection or
     * resource failures (pool exhaustion, database down) are rethrown, never hidden as "skip".
     */
    private List<Map<String, Object>> tenantRows(UUID tenantId, String sql, Object... args) {
        try {
            return jdbc.queryForList(sql, args);
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            // tenant id only — never the schema name or SQL in logs
            log.warn("Admin tenants: tenant {} schema query skipped ({})", tenantId, e.getClass().getSimpleName());
            return null;
        }
    }

    private static void rethrowIfResourceFailure(DataAccessException e) {
        if (e instanceof DataAccessResourceFailureException
                || e instanceof TransientDataAccessException
                || e instanceof RecoverableDataAccessException) {
            throw e;
        }
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Instant i) return i;
        return null;
    }

    private String loadSchemaName(UUID tenantId) {
        String s = jdbc.queryForObject(
                "SELECT schema_name FROM public.tenants WHERE id = ?",
                String.class, tenantId);
        if (s == null) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND,
                    "Tenant schema not found: " + tenantId);
        }
        if (!TenantSchema.isValid(s)) {
            // gate before SQL interpolation — a corrupted row answers like an unknown tenant
            // (404), never a 500 (follow-up review v1s-13-12); the raw value is not echoed.
            log.warn("Tenant {} has an invalid schema_name (length={}) — detail refused", tenantId, s.length());
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant schema not found: " + tenantId);
        }
        return s;
    }

    private List<AdminStoreInfo> loadStores(String schema, UUID tenantId) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return List.of();
        }
        try {
            return jdbc.query(
                    "SELECT id::text, name, type AS store_type, 0 AS total_revenue FROM " + schema + ".stores WHERE is_active = true ORDER BY name",
                    (rs, row) -> new AdminStoreInfo(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("store_type"),
                            rs.getLong("total_revenue")
                    ));
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            return List.of();
        }
    }

    /**
     * Members of the tenant (OWNER, EMPLOYEE) read from {@code public.*} with their last active day
     * from {@code public.user_daily_activity} (Story 9-4), then their names from
     * {@code <schema>.employees} in a separate fail-soft statement: a broken tenant table only
     * loses the names (« Propriétaire » / « Employé »), never the members. No user id leaves here.
     */
    private List<AdminEmployeeInfo> loadEmployees(String schema, UUID tenantId) {
        record Member(UUID userId, String role, LocalDate lastDay) {}
        List<Member> members = jdbc.query(
                "SELECT m.user_id, m.role, "
                + "(SELECT MAX(d.activity_date) FROM public.user_daily_activity d "
                + "  WHERE d.user_id = m.user_id AND d.tenant_id = m.tenant_id) AS last_day "
                + "FROM public.user_tenant_memberships m "
                + "WHERE m.tenant_id = ? AND m.role IN (" + MemberRolesSql.IN_LIST + ")",
                (rs, row) -> {
                    java.sql.Date day = rs.getDate("last_day");
                    String uid = rs.getString("user_id");
                    return new Member(uid != null ? UUID.fromString(uid) : null, rs.getString("role"),
                            day != null ? day.toLocalDate() : null);
                },
                tenantId);
        Map<UUID, String> names = loadEmployeeNames(schema, tenantId,
                members.stream().map(Member::userId).filter(java.util.Objects::nonNull).toList());
        return members.stream()
                .map(m -> new AdminEmployeeInfo(memberName(names.get(m.userId()), m.role()), m.role(), m.lastDay()))
                .sorted(java.util.Comparator
                        .comparing((AdminEmployeeInfo e) -> "OWNER".equals(e.role()) ? 0 : 1)
                        .thenComparing(AdminEmployeeInfo::name))
                .toList();
    }

    /** Names (trimmed first + last) of the given users in the tenant's employees table; fail-soft. */
    private Map<UUID, String> loadEmployeeNames(String schema, UUID tenantId, List<UUID> userIds) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return Map.of();
        }
        if (userIds.isEmpty()) return Map.of();
        List<Map<String, Object>> rows = tenantRows(tenantId,
                "SELECT e.user_id, " + EMPLOYEE_FULL_NAME + " AS full_name FROM " + schema + ".employees e "
                + "WHERE e.user_id IN (" + userIds.stream().map(x -> "?").collect(Collectors.joining(",")) + ") "
                + "ORDER BY e.created_at",
                userIds.toArray());
        Map<UUID, String> out = new HashMap<>();
        if (rows == null) return out;
        for (Map<String, Object> r : rows) {
            Object uid = r.get("user_id");
            Object name = r.get("full_name");
            if (uid != null && name != null) out.putIfAbsent(UUID.fromString(uid.toString()), name.toString().trim());
        }
        return out;
    }

    /** Stored name, else the role label: « Propriétaire », « Gérant », « Lecteur », otherwise « Employé ». */
    static String memberName(String fullName, String role) {
        if (fullName != null && !fullName.isBlank()) return fullName.trim();
        if (role == null) return AdminLabels.EMPLOYEE;
        return switch (role) {
            case "OWNER" -> AdminLabels.OWNER;
            case "MANAGER" -> AdminLabels.MANAGER;
            case "VIEWER" -> AdminLabels.VIEWER;
            default -> AdminLabels.EMPLOYEE;
        };
    }

    /** Audit actor: « Système » for a SYSTEM event, else the employee name, « Propriétaire », or unknown. */
    static String actorName(String source, String fullName, boolean isOwner) {
        if ("SYSTEM".equals(source)) return AdminLabels.SYSTEM;
        if (fullName != null && !fullName.isBlank()) return fullName.trim();
        return isOwner ? AdminLabels.OWNER : AdminLabels.UNKNOWN_USER;
    }

    /** Story 9-2 — current subscription (first row by created_at), null when unreadable. */
    /** Story 9-2 — suspension trace from {@code public.tenants}; null when not suspended. */
    private AdminSuspensionInfo loadSuspension(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT suspended_at, suspended_reason FROM public.tenants "
                + "WHERE id = ? AND status = 'SUSPENDED' AND suspended_at IS NOT NULL", tenantId);
        if (rows == null || rows.isEmpty()) return null;
        Object at = rows.get(0).get("suspended_at");
        Instant suspendedAt = at instanceof Timestamp ts ? ts.toInstant()
                : at instanceof java.time.OffsetDateTime odt ? odt.toInstant() : null;
        return suspendedAt == null ? null
                : new AdminSuspensionInfo(suspendedAt, (String) rows.get(0).get("suspended_reason"));
    }

    private AdminPlanInfo loadPlan(String schema, UUID tenantId) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return null;
        }
        try {
            List<AdminPlanInfo> rows = jdbc.query(
                    "SELECT plan_type, expires_at FROM \"" + schema + "\".subscriptions "
                    + "ORDER BY created_at ASC LIMIT 1",
                    (rs, row) -> {
                        Timestamp exp = rs.getTimestamp("expires_at");
                        return new AdminPlanInfo(rs.getString("plan_type"),
                                exp != null ? exp.toInstant() : null);
                    });
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            // tenant id only — never the schema name or SQL in logs
            log.warn("Tenant detail: subscription of tenant {} unreadable", tenantId, e);
            return null;
        }
    }

    private long countAuditEvents(String schema, UUID tenantId) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return 0L;
        }
        try {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".audit_log "
                    + "WHERE occurred_at >= NOW() - INTERVAL '30 days'",
                    Long.class);
            return count != null ? count : 0L;
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            return 0L;
        }
    }

    /**
     * Audit lines of the last 30 days, newest first, from the enriched audit log (v1s-18-6): action
     * and entity type codes (labelled by the dashboard), {@code entity_label}, store name and actor
     * name — never {@code entity_id} nor {@code user_id}.
     */
    private List<AdminAuditEvent> loadAuditEvents(String schema, UUID tenantId, int page) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return List.of();
        }
        record AuditRow(String action, String entityType, String entityLabel, String source,
                        UUID userId, boolean isOwner, String storeName, Instant occurredAt) {}
        List<AuditRow> rows;
        try {
            rows = jdbc.query(
                    "SELECT a.action, a.entity_type, a.entity_label, a.occurred_at, a.source, a.user_id, "
                    + "st.name AS store_name, "
                    + "EXISTS (SELECT 1 FROM public.user_tenant_memberships m "
                    + "        WHERE m.user_id = a.user_id AND m.tenant_id = ? AND m.role = 'OWNER') AS actor_is_owner "
                    + "FROM " + schema + ".audit_log a "
                    + "LEFT JOIN " + schema + ".stores st ON st.id = a.store_id "
                    + "WHERE a.occurred_at >= NOW() - INTERVAL '30 days' "
                    + "ORDER BY a.occurred_at DESC "
                    + "LIMIT ? OFFSET ?",
                    (rs, row) -> {
                        Timestamp at = rs.getTimestamp("occurred_at");
                        String uid = rs.getString("user_id");
                        return new AuditRow(
                                rs.getString("action"),
                                rs.getString("entity_type"),
                                blankToNull(rs.getString("entity_label")),
                                rs.getString("source"),
                                uid != null ? UUID.fromString(uid) : null,
                                rs.getBoolean("actor_is_owner"),
                                blankToNull(rs.getString("store_name")),
                                at != null ? at.toInstant() : null);
                    },
                    tenantId, AUDIT_PAGE_SIZE, (long) page * AUDIT_PAGE_SIZE);
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            log.warn("Tenant detail: audit log of tenant {} unreadable ({})", tenantId, e.getClass().getSimpleName());
            return List.of();
        }
        // Actor names in a separate fail-soft statement: a broken employees table keeps the lines.
        Map<UUID, String> names = loadEmployeeNames(schema, tenantId, rows.stream()
                .filter(r -> !"SYSTEM".equals(r.source()))
                .map(AuditRow::userId).filter(java.util.Objects::nonNull).distinct().toList());
        return rows.stream()
                .map(r -> new AdminAuditEvent(r.action(), r.entityType(), r.entityLabel(),
                        actorName(r.source(), r.userId() != null ? names.get(r.userId()) : null, r.isOwner()),
                        r.storeName(), r.occurredAt()))
                .toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ── RowMapper ─────────────────────────────────────────────────────────────

    /**
     * Maps a tenant row from the public schema.
     * Note: storeCount is set to 0 here; enrichWithCrossSchemaMetrics fills it in.
     * Note: lastActivityAt is set to null here; enrichWithCrossSchemaMetrics fills it in.
     */
    private static class TenantListRowMapper implements RowMapper<AdminTenantListItem> {
        @Override
        public AdminTenantListItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            // Story 9-2 — raw plan code (labelled by the dashboard); public.tenants.plan_type is now
            // kept in step with the tenant-schema subscription.
            String plan = rs.getString("plan_type");

            Timestamp deletionTs = rs.getTimestamp("deletion_scheduled_at");
            Timestamp createdTs  = rs.getTimestamp("created_at");

            return new AdminTenantListItem(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("owner_phone"),
                    plan,
                    rs.getString("status"),
                    createdTs  != null ? createdTs.toInstant()  : null,
                    null,    // lastActivityAt filled by enrichWithCrossSchemaMetrics
                    0,       // storeCount filled by enrichWithCrossSchemaMetrics
                    rs.getInt("employee_count"),
                    deletionTs != null ? deletionTs.toInstant() : null
            );
        }
    }
}

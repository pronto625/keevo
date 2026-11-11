package com.keevo.admin.alerts.adapter.out.persistence;

import com.keevo.admin.AdminLabels;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.identity.api.PlanKind;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.DeletionPending;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ExpiredPlan;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.HighFeedback;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ReportFailures;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.SilentTenant;
import com.keevo.admin.alerts.domain.port.out.AlertSourcesPort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JdbcAlertSourcesAdapter — reads behind the admin alerts feed (Story 9-5).
 *
 * <p>Deliberately NOT {@code @Transactional}: each per-company statement autocommits, so one broken
 * company schema cannot abort (25P02) the PostgreSQL transaction of the next ones. A company's own
 * SQL/schema error skips it; connection / resource failures are rethrown (never a falsely empty feed).
 * The system company ({@code KV-ADMIN}) is always excluded.
 *
 * <p>Story 19.11c (FUS-NFR11): the per-company reads (expired paying plans, failed reports) are one table-presence
 * query then chunked {@code UNION ALL} statements ({@link CrossTenantSql}, 100 companies each, failing chunk retried
 * company by company) — never a statement per company. A company without the table reads as nothing to report.
 */
@Component
public class JdbcAlertSourcesAdapter implements AlertSourcesPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcAlertSourcesAdapter.class);

    static final String SYSTEM_TENANT = "00000000-0000-0000-0000-000000000000";

    static final String HIGH_FEEDBACK_SQL =
            "SELECT f.id, f.type, f.description, f.submitted_at, t.id AS tenant_id, t.name AS tenant_name "
            + "FROM public.feedback f "
            + "LEFT JOIN public.tenants t ON t.schema_name = f.tenant_id AND t.status <> 'DELETED' "
            + "WHERE f.priority = 'HIGH' AND f.handled_at IS NULL AND f.submitted_at >= ? "
            + "AND f.tenant_id NOT IN (SELECT s.schema_name FROM public.tenants s "
            + "  WHERE s.id = '" + SYSTEM_TENANT + "'::uuid) "
            + "ORDER BY f.submitted_at DESC";

    static final String DELETION_PENDING_SQL =
            "SELECT id, name, deletion_scheduled_at FROM public.tenants "
            + "WHERE status = 'DELETION_PENDING' AND id <> '" + SYSTEM_TENANT + "'::uuid";

    static final String LIVE_TENANTS_SQL =
            "SELECT id, name, schema_name FROM public.tenants "
            + "WHERE status NOT IN ('DELETED', 'DELETION_PENDING') AND id <> '" + SYSTEM_TENANT + "'::uuid "
            + "ORDER BY name";

    /**
     * Story 19.7a: « not free » = a plan of kind TRIAL or PAID (joined); parameters: the two kinds, the instant. One
     * branch of the 19.11c union: the company id literal, then the schema.
     */
    static final String EXPIRED_NOT_FREE_SQL =
            "SELECT %s::text AS tenant_id, x.plan_type, x.expires_at FROM (SELECT s.plan_type, s.expires_at "
            + "FROM \"%s\".subscriptions s JOIN public.plans p ON p.code = s.plan_type "
            + "WHERE p.kind IN (?, ?) AND s.status = 'ACTIVE' AND s.expires_at IS NOT NULL AND s.expires_at < ? "
            + "ORDER BY s.created_at ASC LIMIT 1) x";

    /** One branch of the 19.11c union (the schema); parameter: the instant. */
    static final String REPORT_FAILURES_SQL =
            "SELECT COUNT(*) AS failures, MAX(COALESCE(last_attempt_at, created_at)) AS latest "
            + "FROM \"%s\".reports "
            + "WHERE delivery_status = 'FAILED' AND COALESCE(last_attempt_at, created_at) >= ?";

    static final String SILENT_TENANTS_SQL =
            "SELECT t.id, t.name, t.schema_name, MAX(s.last_push_at) AS last_push_at "
            + "FROM public.user_sync_state s JOIN public.tenants t ON t.schema_name = s.tenant_id "
            + "WHERE t.status = 'ACTIVE' AND t.id <> '" + SYSTEM_TENANT + "'::uuid "
            + "GROUP BY t.id, t.name, t.schema_name "
            + "HAVING MAX(s.last_push_at) < ? "
            // a device registered recently that has not pushed yet counts as recent activity
            + "AND NOT EXISTS (SELECT 1 FROM public.user_sync_state n WHERE n.tenant_id = t.schema_name "
            + "  AND n.last_push_at IS NULL AND n.updated_at >= ?) "
            + "ORDER BY MAX(s.last_push_at) ASC";

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;

    /** With cross-tenant statements of the default chunk size (tests). */
    public JdbcAlertSourcesAdapter(JdbcTemplate jdbc) {
        this(jdbc, new CrossTenantSql(jdbc, CrossTenantSql.DEFAULT_CHUNK_SIZE));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcAlertSourcesAdapter(JdbcTemplate jdbc, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
    }

    @Override
    public List<HighFeedback> unhandledHighFeedbackSince(Instant since) {
        return jdbc.query(HIGH_FEEDBACK_SQL, (rs, i) -> new HighFeedback(
                UUID.fromString(rs.getString("id")),
                rs.getString("tenant_id") == null ? AdminLabels.DELETED_TENANT : name(rs.getString("tenant_name")),
                rs.getString("type"),
                rs.getString("description"),
                instant(rs, "submitted_at")), Timestamp.from(since));
    }

    @Override
    public List<DeletionPending> deletionPending() {
        return jdbc.query(DELETION_PENDING_SQL, (rs, i) -> new DeletionPending(
                UUID.fromString(rs.getString("id")),
                name(rs.getString("name")),
                instant(rs, "deletion_scheduled_at")));
    }

    @Override
    public List<ExpiredPlan> expiredPlansNotFree(Instant expiredBefore) {
        Timestamp at = Timestamp.from(expiredBefore);
        List<LiveTenant> tenants = liveTenants();
        Map<String, Map.Entry<String, Instant>> expired = new HashMap<>();
        crossTenant.query(branches(tenants, TenantTable.SUBSCRIPTION_EXPIRY), (tenant, params) -> {
                    params.add(PlanKind.TRIAL.name());
                    params.add(PlanKind.PAID.name());
                    params.add(at);
                    String schema = TenantSchema.validate(tenant.schemaName());
                    return String.format(EXPIRED_NOT_FREE_SQL, CrossTenantSql.tenantLiteral(tenant), schema);
                }, union -> "SELECT * FROM (" + union + ") u",
                (rs, i) -> Map.entry(rs.getString("tenant_id"),
                        Map.entry(rs.getString("plan_type"), instant(rs, "expires_at"))))
                .forEach(e -> expired.put(e.getKey(), e.getValue()));
        List<ExpiredPlan> out = new ArrayList<>();
        for (LiveTenant t : tenants) { // company name order, as before
            Map.Entry<String, Instant> plan = expired.get(t.id().toString());
            if (plan != null) out.add(new ExpiredPlan(t.id(), t.name(), plan.getKey(), plan.getValue()));
        }
        return out;
    }

    @Override
    public ReportFailures reportFailuresSince(Instant since) {
        Timestamp from = Timestamp.from(since);
        long failures = 0;
        int companies = 0;
        Instant latest = null;
        for (Object[] chunk : crossTenant.query(branches(liveTenants(), TenantTable.REPORTS), (tenant, params) -> {
                    params.add(from);
                    return String.format(REPORT_FAILURES_SQL, TenantSchema.validate(tenant.schemaName()));
                },
                union -> "SELECT COALESCE(SUM(failures), 0) AS failures, COUNT(*) FILTER (WHERE failures > 0) "
                        + "AS companies, MAX(latest) AS latest FROM (" + union + ") u",
                (rs, i) -> new Object[]{rs.getLong("failures"), rs.getInt("companies"), instant(rs, "latest")})) {
            failures += (Long) chunk[0];
            companies += (Integer) chunk[1];
            Instant at = (Instant) chunk[2];
            if (at != null && (latest == null || at.isAfter(latest))) latest = at;
        }
        return new ReportFailures(failures, companies, latest);
    }

    @Override
    public List<SilentTenant> silentTenants(Instant pushedBefore) {
        return jdbc.query(SILENT_TENANTS_SQL, (rs, i) -> new SilentTenant(
                UUID.fromString(rs.getString("id")),
                name(rs.getString("name")),
                rs.getString("schema_name"),
                instant(rs, "last_push_at")), Timestamp.from(pushedBefore), Timestamp.from(pushedBefore));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    record LiveTenant(UUID id, String name, String schema) {}

    private List<LiveTenant> liveTenants() {
        return jdbc.query(LIVE_TENANTS_SQL, (rs, i) -> new LiveTenant(
                        UUID.fromString(rs.getString("id")), name(rs.getString("name")), rs.getString("schema_name")))
                .stream()
                .filter(t -> {
                    if (TenantSchema.isValid(t.schema())) return true; // gate before the name is put in SQL
                    log.warn("Alerts: company {} skipped (invalid schema name)", t.id());
                    return false;
                })
                .toList();
    }

    /** The companies holding {@code table} (presence resolved in one catalogue query), in the given order. */
    private List<TenantBranch> branches(List<LiveTenant> tenants, TenantTable table) {
        return crossTenant.withPresence(tenants.stream()
                        .map(t -> new TenantBranch(t.id().toString(), t.name(), t.schema(), null, null)).toList())
                .stream().filter(t -> t.has(table)).toList();
    }

    private static String name(String raw) {
        return raw == null || raw.isBlank() ? AdminLabels.UNNAMED_TENANT : raw.trim();
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}

package com.keevo.admin.alerts.adapter.out.persistence;

import com.keevo.admin.AdminLabels;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.DeletionPending;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ExpiredPlan;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.HighFeedback;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ReportFailures;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.SilentTenant;
import com.keevo.admin.alerts.domain.port.out.AlertSourcesPort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * JdbcAlertSourcesAdapter — reads behind the admin alerts feed (Story 9-5).
 *
 * <p>Deliberately NOT {@code @Transactional}: each per-company statement autocommits, so one broken
 * company schema cannot abort (25P02) the PostgreSQL transaction of the next ones. A company's own
 * SQL/schema error skips it; connection / resource failures are rethrown (never a falsely empty feed).
 * The system company ({@code KV-ADMIN}) is always excluded.
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

    static final String EXPIRED_NOT_FREE_SQL =
            "SELECT plan_type, expires_at FROM \"%s\".subscriptions "
            + "WHERE plan_type <> 'FREE' AND status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < ? "
            + "ORDER BY created_at ASC LIMIT 1";

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

    public JdbcAlertSourcesAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        List<ExpiredPlan> out = new ArrayList<>();
        for (LiveTenant t : liveTenants()) {
            if (!TenantSchema.isValid(t.schema())) { // liveTenants() already filters; re-checked at use
                log.warn("Alerts: company {} skipped (invalid schema name)", t.id());
                continue;
            }
            List<Map<String, Object>> rows = perCompany(t, () ->
                    jdbc.queryForList(String.format(EXPIRED_NOT_FREE_SQL, t.schema()), at));
            if (rows == null || rows.isEmpty()) continue;
            Map<String, Object> row = rows.get(0);
            out.add(new ExpiredPlan(t.id(), t.name(), (String) row.get("plan_type"), toInstant(row.get("expires_at"))));
        }
        return out;
    }

    @Override
    public ReportFailures reportFailuresSince(Instant since) {
        Timestamp from = Timestamp.from(since);
        long failures = 0;
        int companies = 0;
        Instant latest = null;
        for (LiveTenant t : liveTenants()) {
            if (!TenantSchema.isValid(t.schema())) { // liveTenants() already filters; re-checked at use
                log.warn("Alerts: company {} skipped (invalid schema name)", t.id());
                continue;
            }
            Map<String, Object> row = perCompany(t, () ->
                    jdbc.queryForMap(String.format(REPORT_FAILURES_SQL, t.schema()), from));
            if (row == null) continue;
            long n = ((Number) row.get("failures")).longValue();
            if (n == 0) continue;
            failures += n;
            companies++;
            Instant at = row.get("latest") == null ? null : toInstant(row.get("latest"));
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
                .filter(t -> TenantSchema.isValid(t.schema())) // gate before the name is put in SQL
                .toList();
    }

    /** Runs one company's statement: null on its own SQL error, connection failures rethrown. */
    <T> T perCompany(LiveTenant t, Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessResourceFailureException | TransientDataAccessException
                 | RecoverableDataAccessException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("Alerts: company {} skipped ({})", t.id(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static String name(String raw) {
        return raw == null || raw.isBlank() ? AdminLabels.UNNAMED_TENANT : raw.trim();
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Instant i) return i;
        throw new IllegalStateException("Unexpected timestamp type: " + value);
    }
}

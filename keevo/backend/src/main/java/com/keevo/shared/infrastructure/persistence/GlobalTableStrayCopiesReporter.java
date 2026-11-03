package com.keevo.shared.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Story 13.4 — counts, once at boot and in the background (never delays readiness), the copies of global-only
 * public tables ({@link #GLOBAL_ONLY_TABLES}) that the former public → tenant schema sync (removed by Story 16.12)
 * mirrored into tenant schemas. They are never dropped by an update (production data is not altered); one INFO line
 * per boot gives, per table, the number of copies and how many look non-empty ({@code pg_class.reltuples > 0}, a
 * planner estimate) or were never analysed ({@code reltuples < 0}, unknown) — no schema name. Reads the catalogue
 * ({@code pg_class} ⨝ {@code pg_namespace}, ordinary tables) with a query timeout; a failure only logs a WARN.
 */
@Component
public class GlobalTableStrayCopiesReporter {

    private static final Logger log = LoggerFactory.getLogger(GlobalTableStrayCopiesReporter.class);

    static final int QUERY_TIMEOUT_SECONDS = 10;
    static final String COUNT_SQL_PREFIX = "SELECT n.nspname, c.relname, c.reltuples FROM pg_catalog.pg_class c "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE c.relkind = 'r' AND n.nspname <> 'public' AND c.relname IN (";
    static final String COUNT_SQL_SUFFIX = ")";

    /**
     * The public tables that are global only (never tenant tables): any copy of them in a tenant schema is a stray
     * copy left by the former schema sync. Story 16.12 added the ones that sync still mirrored ({@code plan_profiles},
     * {@code profile_modules}, {@code bundle_definitions}, {@code bundle_modules}, {@code admin_jobs}).
     */
    public static final Set<String> GLOBAL_ONLY_TABLES = Set.of(
            "tenants",
            // Story 9-2 — super-admin plan management
            "tenant_payments", "tenant_plan_events", "plan_expiry_reminders",
            // Story 9-4 — platform analytics activity tracking
            "user_daily_activity",
            // Story 9-5 — system health samples, admin alert acks, sync silence notices, global push notifications
            "platform_health_samples", "admin_alert_acks", "sync_silence_notices", "global_notifications",
            // Story 13.1 — module registry: global catalogue + per-tenant module state
            "module_definitions", "tenant_modules",
            // Story 13.4 — infrastructure: ShedLock, public Flyway history, Spring Modulith outbox
            "shedlock", "flyway_schema_history", "event_publication", "event_listener_receipt",
            // identity — global auth
            "refresh_tokens", "password_reset_tokens", "user_tenant_memberships",
            // sync — device sync state
            "user_sync_state",
            // feedback — global inbox of the super admin
            "feedback",
            // Story 16.6 — RGPD account deletion trace
            "account_deletion_log",
            // Story 16.8 / 16.2 / 16.4 — plan profiles, bundles, admin jobs (were still mirrored until 16.12)
            "plan_profiles", "profile_modules", "bundle_definitions", "bundle_modules", "admin_jobs");

    /**
     * Copies of one global-only table in tenant schemas.
     *
     * @param copies      tenant schemas holding a copy
     * @param nonEmpty    copies whose row estimate is positive
     * @param notAnalysed copies never analysed (row count unknown)
     */
    public record StrayCopies(long copies, long nonEmpty, long notAnalysed) {
    }

    private final JdbcTemplate jdbc;

    public GlobalTableStrayCopiesReporter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    /** Global-only table → its copies in tenant schemas ({@link TenantSchema#isValid}); tables without copies omitted. */
    public Map<String, StrayCopies> countStrayCopies() {
        List<String> tables = new ArrayList<>(GLOBAL_ONLY_TABLES);
        Collections.sort(tables);
        String placeholders = tables.stream().map(t -> "?").collect(Collectors.joining(", "));
        Map<String, long[]> counts = new TreeMap<>();
        jdbc.query(COUNT_SQL_PREFIX + placeholders + COUNT_SQL_SUFFIX, rs -> {
            if (!TenantSchema.isValid(rs.getString("nspname"))) {
                return; // not a tenant schema
            }
            long[] c = counts.computeIfAbsent(rs.getString("relname"), k -> new long[3]);
            float estimate = rs.getFloat("reltuples");
            c[0]++;
            if (estimate > 0) {
                c[1]++;
            } else if (estimate < 0) {
                c[2]++;
            }
        }, tables.toArray());
        Map<String, StrayCopies> result = new TreeMap<>();
        counts.forEach((table, c) -> result.put(table, new StrayCopies(c[0], c[1], c[2])));
        return result;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        startReport();
    }

    /** Runs the report on a background thread; the future completes once the line is logged (never fails). */
    public CompletableFuture<Void> startReport() {
        return CompletableFuture.runAsync(this::report, runnable -> {
            Thread thread = new Thread(runnable, "stray-copies-report");
            thread.setDaemon(true);
            thread.start();
        });
    }

    void report() {
        try {
            Map<String, StrayCopies> counts = countStrayCopies();
            if (counts.isEmpty()) {
                log.info("Global-only tables: no stray copy in tenant schemas");
            } else {
                log.info("Global-only tables: stray copies in tenant schemas (kept, not used, cleanup decision "
                        + "pending; table=copies/non-empty estimate/never analysed) — {}", counts.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue().copies() + "/" + e.getValue().nonEmpty() + "/"
                                + e.getValue().notAnalysed())
                        .collect(Collectors.joining(", ")));
            }
        } catch (RuntimeException e) {
            log.warn("Global-only tables: stray copies could not be counted ({})", e.getClass().getSimpleName());
        }
    }
}

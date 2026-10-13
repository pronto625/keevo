package com.keevo.shared.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * TenantSchemaMigrationRunner — runs idempotent DDL migrations against ALL existing
 * tenant schemas on startup, for schema changes that cannot be handled by a simple
 * {@code ADD COLUMN IF NOT EXISTS} (e.g. CHECK constraint modifications).
 *
 * <p>Story v1s-13-12: after the M1–M6 constraint migrations, each tenant is fully synced
 * against {@code public} via {@link TenantSchemaSyncService#syncNow(String)}, so per-tenant
 * columns (e.g. {@code version}) exist before the first request — schedulers never trigger
 * the request-time sync. A per-schema failure is logged at ERROR (naming the schema) and
 * counted, but never aborts startup: one broken tenant must not take every tenant down.
 * DELETED tenants are not loaded; a tenant whose schema no longer exists is skipped (WARN).
 *
 * <p>Runs as a {@link SmartLifecycle} with phase {@value #PHASE}, i.e. before the embedded
 * web server starts accepting requests ({@code WebServerStartStopLifecycle} runs at
 * {@code DEFAULT_PHASE - 1024}) and before {@code @Scheduled} tasks are registered (the
 * scheduling post-processor registers them on {@code ContextRefreshedEvent}, which is
 * published after all lifecycle beans have started).
 *
 * Story 3.3 hotfix: adds IN_TRANSIT to stock_transfers.status CHECK constraint.
 */
@Component
public class TenantSchemaMigrationRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaMigrationRunner.class);

    /** Low phase: before the web server (DEFAULT_PHASE - 1024) and before scheduler registration. */
    static final int PHASE = 0;

    /** Non-deleted tenants, with whether their schema still exists. */
    static final String LOAD_TENANTS_SQL =
            "SELECT t.schema_name, EXISTS (SELECT 1 FROM information_schema.schemata s "
            + "WHERE s.schema_name = t.schema_name) AS schema_present "
            + "FROM public.tenants t WHERE t.status <> 'DELETED'";

    private final DataSource dataSource;
    private final TenantSchemaSyncService syncService;
    private volatile boolean running;

    public TenantSchemaMigrationRunner(DataSource dataSource, TenantSchemaSyncService syncService) {
        this.dataSource = dataSource;
        this.syncService = syncService;
    }

    // ── SmartLifecycle ───────────────────────────────────────────────────────

    @Override
    public void start() {
        if (running) return;
        running = true;
        runAll();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    // ── Work ─────────────────────────────────────────────────────────────────

    /** Migrates + syncs every non-deleted tenant schema; never throws. */
    public void runAll() {
        long startedAt = System.nanoTime();
        log.info("TenantSchemaMigrationRunner: starting tenant schema migrations + sync");
        List<TenantRow> tenants = loadTenants();
        int ok = 0, skipped = 0, failed = 0;
        for (TenantRow tenant : tenants) {
            String schema = tenant.schema();
            if (!TenantSchema.isValid(schema)) {
                log.warn("TenantSchemaMigrationRunner: skipping invalid tenant schema name (length={})",
                        schema == null ? "null" : String.valueOf(schema.length()));
                skipped++;
                continue;
            }
            if (!tenant.present()) {
                log.warn("TenantSchemaMigrationRunner: skipping tenant schema '{}' — schema does not exist", schema);
                skipped++;
                continue;
            }
            boolean tenantFailed = false;
            try {
                migrateSchema(schema);
            } catch (Exception e) {
                log.error("TenantSchemaMigrationRunner: M1-M6 migrations FAILED for schema '{}'", schema, e);
                tenantFailed = true;
            }
            try {
                syncService.syncNow(schema);
            } catch (Exception e) {
                log.error("TenantSchemaMigrationRunner: schema sync FAILED for schema '{}' — "
                        + "tenant may be missing columns until fixed", schema, e);
                tenantFailed = true;
            }
            if (tenantFailed) failed++; else ok++;
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (failed > 0) {
            log.error("TenantSchemaMigrationRunner: done with FAILURES — ok={} skipped={} failed={} total={} elapsedMs={}",
                    ok, skipped, failed, tenants.size(), elapsedMs);
        } else {
            log.info("TenantSchemaMigrationRunner: done — ok={} skipped={} failed={} total={} elapsedMs={}",
                    ok, skipped, failed, tenants.size(), elapsedMs);
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /** Session-level bound on lock waits during M1–M6 (reset afterwards). */
    static final String M_LOCK_TIMEOUT_SQL = "SET lock_timeout = '10s'";

    private record TenantRow(String schema, boolean present) {}

    private List<TenantRow> loadTenants() {
        List<TenantRow> tenants = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(LOAD_TENANTS_SQL)) {
            while (rs.next()) {
                tenants.add(new TenantRow(rs.getString("schema_name"), rs.getBoolean("schema_present")));
            }
        } catch (SQLException e) {
            log.error("TenantSchemaMigrationRunner: could not load tenant schemas — skipping tenant migrations + sync", e);
        }
        return tenants;
    }

    /**
     * Apply all idempotent constraint migrations for a single tenant schema.
     * Each migration is wrapped in a DO $$ block so it never throws on second run.
     */
    private void migrateSchema(String schema) throws SQLException {
        TenantSchema.validate(schema); // callers already filter; defence in depth before interpolation

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("SET search_path TO \"" + schema + "\"");
            // Follow-up review v1s-13-12: M1–M6 now run before the web server — bound every DDL
            // lock wait (e.g. behind an old instance during a rolling deploy) like the sync does.
            stmt.execute(M_LOCK_TIMEOUT_SQL);
            try {
                migrateSchemaStatements(conn, stmt, schema);
            } finally {
                // Session settings on a pooled connection: always restore them, even on failure.
                stmt.execute("RESET lock_timeout");
                stmt.execute("SET search_path TO public");
            }
        }
    }

    private void migrateSchemaStatements(Connection conn, Statement stmt, String schema) throws SQLException {
        {
            // Migration M1: add IN_TRANSIT to status CHECK constraint (guarded — table may not exist)
            if (tableExists(conn, schema, "stock_transfers")) {
                stmt.execute(TenantSchemaProvisioner.DDL_STOCK_TRANSFERS_MIGRATE_IN_TRANSIT);
            }

            // Migration M2 (Story 7.2): ensure reports table exists, then add actor_id + store_name
            stmt.execute(TenantSchemaProvisioner.DDL_REPORTS);           // CREATE TABLE IF NOT EXISTS
            stmt.execute(TenantSchemaProvisioner.DDL_REPORTS_MIGRATE_ACTOR_ID); // ADD COLUMN IF NOT EXISTS
            stmt.execute(TenantSchemaProvisioner.DDL_REPORTS_MIGRATE_STORE_NAME); // ADD COLUMN IF NOT EXISTS
            stmt.execute(TenantSchemaProvisioner.DDL_REPORTS_MIGRATE_ACTOR_NAME); // ADD COLUMN IF NOT EXISTS (Story 7.6)

            // Migration M3 (Story 7.5): add report-preference columns to tenant_preferences
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_EOD_ENABLED);
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_EOD_CHANNEL);
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_WEEKLY_ENABLED);
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_WEEKLY_DAY);
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_WEEKLY_TIME);
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_WEEKLY_CHANNEL);
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_INVENTORY_ENABLED);
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_INVENTORY_CHANNEL);
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_STOCK_CHANNEL);

            // Migration M4 (Story 8.1): notification_cooldowns table + trend preference
            stmt.execute(TenantSchemaProvisioner.DDL_NOTIFICATION_COOLDOWNS);
            stmt.execute(TenantSchemaProvisioner.DDL_NOTIFICATION_COOLDOWNS_IDX_TYPE_STORE);
            stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_TREND_NOTIFICATION);

            // Migration M5 (Story 4.1 / 4.2 / 4.3): sales column migrations (guarded — tables may not exist)
            if (tableExists(conn, schema, "sales")) {
                stmt.execute(TenantSchemaProvisioner.DDL_SALES_MIGRATE_STATUS);
                stmt.execute(TenantSchemaProvisioner.DDL_SALES_MIGRATE_OCCURRED_AT);
                stmt.execute(TenantSchemaProvisioner.DDL_SALES_DROP_STATUS_CHECK);
                stmt.execute(TenantSchemaProvisioner.DDL_SALES_ADD_STATUS_CHECK_V2);
                stmt.execute(TenantSchemaProvisioner.DDL_SALES_MIGRATE_DISCOUNT_AMOUNT);
                stmt.execute(TenantSchemaProvisioner.DDL_SALE_ITEMS);             // CREATE TABLE IF NOT EXISTS
                stmt.execute(TenantSchemaProvisioner.DDL_SALE_ITEMS_IDX_SALE);
                stmt.execute(TenantSchemaProvisioner.DDL_SALE_ITEMS_MIGRATE_CATALOGUE_PRICE);

                // Migration M6 (Story 5.2): updated_at for delta-pull sync
                stmt.execute(TenantSchemaProvisioner.DDL_SALES_MIGRATE_UPDATED_AT);
                stmt.execute(TenantSchemaProvisioner.DDL_SALE_ITEMS_MIGRATE_UPDATED_AT);
            }
        }
    }

    private boolean tableExists(Connection conn, String schema, String tableName) throws SQLException {
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            check.setString(1, schema);
            check.setString(2, tableName);
            try (ResultSet rs = check.executeQuery()) {
                return rs.next();
            }
        }
    }
}

package com.keevo.shared.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
 * <p>Runs AFTER the Spring context is fully started (ApplicationRunner).
 * A per-schema failure is logged as a warning but never aborts startup.
 *
 * Story 3.3 hotfix: adds IN_TRANSIT to stock_transfers.status CHECK constraint.
 */
@Component
public class TenantSchemaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaMigrationRunner.class);

    private final DataSource dataSource;

    public TenantSchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("TenantSchemaMigrationRunner: starting constraint migrations");
        List<String> schemas = loadAllTenantSchemas();
        int ok = 0, skipped = 0, failed = 0;
        for (String schema : schemas) {
            try {
                migrateSchema(schema);
                ok++;
            } catch (Exception e) {
                log.warn("Migration failed for schema '{}': {}", schema, e.getMessage());
                failed++;
            }
        }
        log.info("TenantSchemaMigrationRunner: done — ok={} skipped={} failed={} total={}",
                ok, skipped, failed, schemas.size());
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private List<String> loadAllTenantSchemas() {
        List<String> schemas = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT schema_name FROM public.tenants")) {
            while (rs.next()) {
                schemas.add(rs.getString("schema_name"));
            }
        } catch (SQLException e) {
            log.warn("Could not load tenant schemas — skipping migrations: {}", e.getMessage());
        }
        return schemas;
    }

    /**
     * Apply all idempotent constraint migrations for a single tenant schema.
     * Each migration is wrapped in a DO $$ block so it never throws on second run.
     */
    private void migrateSchema(String schema) throws SQLException {
        if (schema == null || !schema.matches("^kv_[a-z0-9]{6}$")) return;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("SET search_path TO \"" + schema + "\"");

            // Migration M1: add IN_TRANSIT to status CHECK constraint (guarded — table may not exist)
            if (tableExists(conn, schema, "stock_transfers")) {
                stmt.execute(TenantSchemaProvisioner.DDL_STOCK_TRANSFERS_MIGRATE_IN_TRANSIT);
            }

            // Migration M2 (Story 7.2): ensure reports table exists, then add actor_id + store_name
            stmt.execute(TenantSchemaProvisioner.DDL_REPORTS);           // CREATE TABLE IF NOT EXISTS
            stmt.execute(TenantSchemaProvisioner.DDL_REPORTS_MIGRATE_ACTOR_ID); // ADD COLUMN IF NOT EXISTS
            stmt.execute(TenantSchemaProvisioner.DDL_REPORTS_MIGRATE_STORE_NAME); // ADD COLUMN IF NOT EXISTS

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

            stmt.execute("SET search_path TO public");
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

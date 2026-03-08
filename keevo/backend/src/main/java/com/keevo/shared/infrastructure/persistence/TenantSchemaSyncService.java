package com.keevo.shared.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TenantSchemaSyncService — automatic tenant schema synchronization against the public schema.
 *
 * <p><b>Design principle:</b> the {@code public} PostgreSQL schema is the single source of truth
 * for table structure. Hibernate {@code ddl-auto=update} keeps it current at startup.
 * This service ensures every tenant schema is always a structural superset of {@code public}
 * (minus global-only tables).
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>On the first authenticated request for a given tenant schema (per JVM lifetime),
 *       query {@code information_schema.tables} to find tables present in {@code public}
 *       but missing in the tenant schema.</li>
 *   <li>For each missing table: {@code CREATE TABLE IF NOT EXISTS "<tenant>"."<t>"
 *       (LIKE public."<t>" INCLUDING ALL)} — copies column definitions, NOT NULL,
 *       CHECK constraints, defaults, indexes, and storage settings.</li>
 *   <li>For each existing table: compare {@code information_schema.columns} and add any
 *       columns present in {@code public} but absent in the tenant schema via
 *       {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}.</li>
 *   <li>Results are cached in-memory — subsequent requests for the same schema are
 *       a single {@link ConcurrentHashMap} lookup (nanosecond cost).</li>
 * </ol>
 *
 * <p><b>Why raw DataSource here (unlike JpaOnboardingStoreRepository)?</b><br>
 * The sync runs BEFORE the JPA session is opened (in the security filter), and we
 * use fully-qualified table names ({@code "kv_xxx"."table"}) so no {@code search_path}
 * is needed. Raw JDBC is intentional and correct for this use case.
 *
 * <p><b>Tables NOT synced to tenant schemas (global-only):</b> {@code tenants}.
 *
 * <p>Architecture: shared infrastructure — zero knowledge of business domain.
 */
@Component
public class TenantSchemaSyncService {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaSyncService.class);

    /** Schema used as the structural source of truth. */
    private static final String SOURCE_SCHEMA = "public";

    /**
     * Tables that live in {@code public} for global/admin use only and must
     * NOT be replicated into tenant schemas.
     */
    private static final Set<String> GLOBAL_ONLY_TABLES = Set.of("tenants");

    /**
     * Tables that are tenant-schema-specific (not mirrored from {@code public}) but MUST
     * always be present in every tenant schema. These are created programmatically by
     * {@link TenantSchemaProvisioner} for new tenants, and by this service for existing ones.
     *
     * <p>Key = table name, Value = DDL statement (CREATE TABLE IF NOT EXISTS).
     */
    private static final java.util.Map<String, String> REQUIRED_TENANT_TABLES_DDL =
            java.util.Map.of(
                    "audit_log", TenantSchemaProvisioner.DDL_AUDIT_LOG,
                    "products", TenantSchemaProvisioner.DDL_PRODUCTS
            );

    /** Valid tenant schema pattern — prevents any SQL injection. */
    private static final String SCHEMA_PATTERN = "^kv_[a-z0-9]{6}$";

    /**
     * In-memory cache of schemas already synced in this JVM lifetime.
     * Safe to cache because schema structure only changes on app restart
     * (which also clears this cache).
     */
    private final ConcurrentHashMap<String, Boolean> syncedSchemas = new ConcurrentHashMap<>();

    private final DataSource dataSource;

    public TenantSchemaSyncService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sync the given tenant schema against {@code public} if not already done this session.
     *
     * <p>Safe to call on every authenticated request — the in-memory cache makes repeated
     * calls effectively free after the first sync.
     *
     * @param schemaName tenant schema (must match {@code ^kv_[a-z0-9]{6}$})
     */
    public void syncIfNeeded(String schemaName) {
        if (schemaName == null || !schemaName.matches(SCHEMA_PATTERN)) return;
        if (syncedSchemas.containsKey(schemaName)) return;

        try {
            doSync(schemaName);
            syncedSchemas.put(schemaName, Boolean.TRUE);
        } catch (Exception e) {
            log.error("Schema sync failed for '{}' — request will proceed with existing schema: {}",
                    schemaName, e.getMessage(), e);
            // Non-blocking: the request proceeds; worst case, a 500 surfaces the real issue
        }
    }

    /**
     * Invalidate the sync cache for a given schema (e.g., after programmatic provisioning).
     * The next request for this schema will trigger a fresh sync.
     */
    public void invalidate(String schemaName) {
        syncedSchemas.remove(schemaName);
    }

    // ── Core sync logic ───────────────────────────────────────────────────────

    private void doSync(String tenantSchema) throws SQLException {
        log.debug("Starting schema sync: source=public target={}", tenantSchema);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Set<String> sourceTables = getTablesInSchema(conn, SOURCE_SCHEMA);
                Set<String> tenantTables = getTablesInSchema(conn, tenantSchema);

                // Exclude tables that must stay in public only
                sourceTables.removeAll(GLOBAL_ONLY_TABLES);

                // Step 1 — Create tables that exist in public but not in tenant schema
                Set<String> missingTables = new LinkedHashSet<>(sourceTables);
                missingTables.removeAll(tenantTables);
                for (String table : missingTables) {
                    createTableLike(conn, tenantSchema, table);
                    log.info("Schema sync [{}]: created missing table '{}'", tenantSchema, table);
                }

                // Step 2 — Add columns that exist in public but not in existing tenant tables
                Set<String> commonTables = new LinkedHashSet<>(sourceTables);
                commonTables.retainAll(tenantTables);
                for (String table : commonTables) {
                    syncColumns(conn, tenantSchema, table);
                }

                // Step 3 — Ensure required tenant-only tables (not in public) are present
                ensureRequiredTenantTables(conn, tenantSchema);

                conn.commit();
                log.debug("Schema sync complete for '{}'", tenantSchema);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ── Required tenant-only tables ───────────────────────────────────────────

    /**
     * Ensure all tables in {@link #REQUIRED_TENANT_TABLES_DDL} exist in the given
     * tenant schema. These tables are not mirrored from {@code public} so they must
     * be created explicitly using their DDL constants.
     */
    private void ensureRequiredTenantTables(Connection conn, String tenantSchema)
            throws SQLException {
        Set<String> existing = getTablesInSchema(conn, tenantSchema);
        for (java.util.Map.Entry<String, String> entry : REQUIRED_TENANT_TABLES_DDL.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET search_path TO \"" + tenantSchema + "\"");
                    stmt.execute(entry.getValue());
                    stmt.execute("SET search_path TO public");
                    log.info("Schema sync [{}]: created required tenant table '{}'",
                            tenantSchema, entry.getKey());
                }
            }
        }
    }

    // ── Table-level operations ────────────────────────────────────────────────

    private void createTableLike(Connection conn, String tenantSchema, String tableName)
            throws SQLException {
        // LIKE ... INCLUDING ALL copies: column defs, NOT NULL, CHECK constraints,
        // defaults (gen_random_uuid(), NOW(), etc.), indexes, storage.
        // FK references are intentionally NOT copied (tenant tables use internal UUIDs).
        String sql = String.format(
                "CREATE TABLE IF NOT EXISTS \"%s\".\"%s\" (LIKE \"%s\".\"%s\" INCLUDING ALL)",
                tenantSchema, tableName, SOURCE_SCHEMA, tableName);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    // ── Column-level operations ───────────────────────────────────────────────

    private void syncColumns(Connection conn, String tenantSchema, String tableName)
            throws SQLException {
        Set<String> sourceColumns = getColumnsInTable(conn, SOURCE_SCHEMA, tableName);
        Set<String> tenantColumns = getColumnsInTable(conn, tenantSchema, tableName);

        Set<String> missingColumns = new LinkedHashSet<>(sourceColumns);
        missingColumns.removeAll(tenantColumns);
        if (missingColumns.isEmpty()) return;

        for (String col : missingColumns) {
            String colDef = buildColumnDefinition(conn, SOURCE_SCHEMA, tableName, col);
            if (colDef == null) continue;

            String sql = String.format(
                    "ALTER TABLE \"%s\".\"%s\" ADD COLUMN IF NOT EXISTS %s",
                    tenantSchema, tableName, colDef);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                log.info("Schema sync [{}]: added column '{}'.'{}'", tenantSchema, tableName, col);
            }
        }
    }

    // ── information_schema queries ────────────────────────────────────────────

    private Set<String> getTablesInSchema(Connection conn, String schema) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = ? AND table_type = 'BASE TABLE' " +
                "ORDER BY table_name")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tables.add(rs.getString("table_name"));
            }
        }
        return tables;
    }

    private Set<String> getColumnsInTable(Connection conn, String schema, String tableName)
            throws SQLException {
        Set<String> cols = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? " +
                "ORDER BY ordinal_position")) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString("column_name"));
            }
        }
        return cols;
    }

    /**
     * Build a SQL column definition string (e.g., {@code "failed_attempts" integer NOT NULL DEFAULT 0})
     * by reading {@code information_schema.columns} for the given source column.
     *
     * @return DDL fragment suitable for use in ALTER TABLE ADD COLUMN, or {@code null} if not found
     */
    private String buildColumnDefinition(Connection conn, String schema,
                                         String tableName, String columnName)
            throws SQLException {
        String sql = """
                SELECT column_name,
                       data_type,
                       udt_name,
                       is_nullable,
                       column_default,
                       character_maximum_length,
                       numeric_precision,
                       numeric_scale
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND column_name = ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            ps.setString(3, columnName);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                StringBuilder def = new StringBuilder();
                def.append('"').append(rs.getString("column_name")).append('"').append(' ');

                // Resolve SQL type
                String dataType = rs.getString("data_type");
                String udtName  = rs.getString("udt_name");
                int maxLen      = rs.getInt("character_maximum_length"); // 0 if null
                int numPrec     = rs.getInt("numeric_precision");
                int numScale    = rs.getInt("numeric_scale");

                switch (dataType) {
                    case "character varying" ->
                        def.append(maxLen > 0 ? "VARCHAR(" + maxLen + ")" : "TEXT");
                    case "character" ->
                        def.append(maxLen > 0 ? "CHAR(" + maxLen + ")" : "CHAR");
                    case "numeric", "decimal" -> {
                        if (numPrec > 0) def.append("NUMERIC(").append(numPrec)
                                            .append(',').append(numScale).append(')');
                        else def.append("NUMERIC");
                    }
                    case "USER-DEFINED" -> def.append(udtName); // e.g. uuid, jsonb
                    case "ARRAY"        -> def.append(udtName.replaceFirst("^_", "")).append("[]");
                    default             -> def.append(dataType); // integer, boolean, text, timestamptz …
                }

                // NOT NULL
                if ("NO".equals(rs.getString("is_nullable"))) def.append(" NOT NULL");

                // DEFAULT (skip sequence-based defaults — they are created by INCLUDING ALL
                // for new tables; for ALTER TABLE ADD COLUMN we keep expression defaults)
                String colDefault = rs.getString("column_default");
                if (colDefault != null && !colDefault.contains("nextval(")) {
                    def.append(" DEFAULT ").append(colDefault);
                }

                return def.toString();
            }
        }
    }
}

package com.keevo.identity.auth.infrastructure.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TenantMigrationIntegrationTest — Validates TenantSchemaProvisioner against REAL PostgreSQL.
 *
 * <p>TDD rationale: unit tests mock TenantSchemaProvisioner entirely — they cannot detect
 * missing DDL tables. This integration test is the sole source of truth for what
 * actually exists after provisioning.
 *
 * <p>Uses the existing keevo_postgres container (localhost:5444/keevo_dev) that is part
 * of the standard dev docker-compose stack — no additional container spin-up required.
 * Each test class run uses a unique isolated schema (kv_itXXXX) dropped on teardown.
 *
 * <p>AC1: TenantSchemaProvisioner creates all required tables in the tenant schema.
 */
@DisplayName("TenantMigrationIntegrationTest (integration — real PostgreSQL)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantMigrationIntegrationTest {

    // Unique schema per test-class run to allow parallel execution without conflicts
    // Must match regex ^kv_[a-z0-9]{6}$ enforced by TenantSchemaProvisioner
    private static final String SCHEMA =
            "kv_" + String.format("%06x", System.currentTimeMillis() & 0xFFFFFFL);
    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";

    // ── AC1: Tables created by TenantSchemaProvisioner ────────────────────────
    // All 7 tables provisioned by TenantSchemaProvisioner.createTables().
    // Note: products, stock_levels, sales, sale_items, warehouses, audit_log,
    // sync_queue, notifications are provisioned by later epic stories.
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "users", "subscriptions", "roles", "user_roles",
            "stores", "categories", "tenant_preferences", "employees"
    );

    private static DataSource dataSource;
    private static TenantSchemaProvisioner schemaProvisioner;

    @BeforeAll
    static void setUpDatabase() {
        // Credentials from keevo/.env / docker-compose.yml (keevo_postgres, port 5444)
        String host   = System.getProperty("db.host",      "localhost");
        String port   = System.getProperty("db.port",      "5444");
        String dbName = System.getProperty("db.name",      "keevo_dev");
        String user   = System.getProperty("db.user",      "keevo");
        String pass   = System.getProperty("db.password",  "keevo_local_pwd");

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL("jdbc:postgresql://" + host + ":" + port + "/" + dbName);
        ds.setUser(user);
        ds.setPassword(pass);
        dataSource = ds;

        // Skip all tests if the DB is unreachable (CI without dev stack)
        try (Connection ignored = ds.getConnection()) {
            schemaProvisioner = new TenantSchemaProvisioner(ds);
        } catch (SQLException e) {
            assumeTrue(false, "keevo_postgres not reachable — skipping integration tests: " + e.getMessage());
        }
    }

    @AfterAll
    static void dropTestSchema() {
        if (dataSource == null) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        } catch (SQLException ignored) { /* best-effort cleanup */ }
    }

    @Test
    @Order(1)
    @DisplayName("migrate() creates all 7 required tables in the tenant schema (AC1)")
    void migrate_createsAllRequiredTables() throws Exception {
        schemaProvisioner.provision(TENANT_ID, SCHEMA);

        Set<String> actualTables = getTablesInSchema(SCHEMA);

        assertThat(actualTables)
                .as("Tenant schema '%s' must contain all 12 AC1-required tables", SCHEMA)
                .containsAll(REQUIRED_TABLES);
    }

    @Test
    @Order(2)
    @DisplayName("migrate() creates schema with correct name")
    void migrate_createsSchemaWithCorrectName() throws Exception {
        schemaProvisioner.provision(TENANT_ID, SCHEMA);

        assertThat(schemaExists(SCHEMA)).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("migrate() seeds 2 default roles: OWNER and EMPLOYEE (AC1)")
    void migrate_seedsOwnerAndEmployeeRoles() throws Exception {
        schemaProvisioner.provision(TENANT_ID, SCHEMA);

        Set<String> roles = getRolesInSchema(SCHEMA);
        assertThat(roles).containsExactlyInAnyOrder("OWNER", "EMPLOYEE");
    }

    @Test
    @Order(4)
    @DisplayName("migrate() seeds PREMIUM_TRIAL subscription with unlimited limits and 6-month expiry (AC1 — SPEC CHANGE 2026-03-06)")
    void migrate_seedsPremiumTrialSubscription() throws Exception {
        // SPEC CHANGE 2026-03-06: new tenants start on PREMIUM_TRIAL (unlimited, 6 months), NOT FREE
        schemaProvisioner.provision(TENANT_ID, SCHEMA);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT plan_type, max_stores, max_products, max_employees, status, expires_at " +
                     "FROM \"" + SCHEMA + "\".subscriptions WHERE plan_type = 'PREMIUM_TRIAL'")) {

            assertThat(rs.next()).as("PREMIUM_TRIAL subscription row must exist").isTrue();
            assertThat(rs.getString("plan_type")).isEqualTo("PREMIUM_TRIAL");
            assertThat(rs.getInt("max_stores")).isEqualTo(Integer.MAX_VALUE);
            assertThat(rs.getInt("max_products")).isEqualTo(Integer.MAX_VALUE);
            assertThat(rs.getInt("max_employees")).isEqualTo(Integer.MAX_VALUE);
            assertThat(rs.getString("status")).isEqualTo("ACTIVE");

            // expires_at must be approximately NOW() + 6 months
            // PostgreSQL INTERVAL '6 months' = 181-184 calendar days depending on the month.
            // We accept [180, 187] days from now to handle all calendar combinations.
            java.sql.Timestamp expiresAt = rs.getTimestamp("expires_at");
            assertThat(expiresAt).as("expires_at must be set").isNotNull();
            long expiryFromNowSeconds = expiresAt.toInstant().getEpochSecond()
                    - java.time.Instant.now().getEpochSecond();
            long minSeconds = 180L * 86400L; // 180 days
            long maxSeconds = 187L * 86400L; // 187 days
            assertThat(expiryFromNowSeconds)
                    .as("expires_at must be 6 months from now (180–187 days)")
                    .isBetween(minSeconds, maxSeconds);
        }
    }

    @Test
    @Order(5)
    @DisplayName("migrate() is idempotent — running twice does not throw")
    void migrate_isIdempotent() {
        schemaProvisioner.provision(TENANT_ID, SCHEMA);
        schemaProvisioner.provision(TENANT_ID, SCHEMA); // must not throw
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Set<String> getTablesInSchema(String schema) throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        return tables;
    }

    private boolean schemaExists(String schema) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Set<String> getRolesInSchema(String schema) throws Exception {
        Set<String> roles = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM \"" + schema + "\".roles")) {
            while (rs.next()) {
                roles.add(rs.getString("name"));
            }
        }
        return roles;
    }
}

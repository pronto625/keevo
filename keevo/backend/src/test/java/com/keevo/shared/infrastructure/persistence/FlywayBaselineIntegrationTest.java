package com.keevo.shared.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Flyway baseline migration V1.
 *
 * <p>Verifies (AC1, AC3, AC4, AC5):
 * <ul>
 *   <li>AC3: Fresh DB → Flyway creates entire public schema from V1</li>
 *   <li>AC4: Re-running migrate is no-op (idempotent)</li>
 *   <li>AC5: Hibernate ddl-auto=validate passes → V1 and JPA entities are consistent</li>
 * </ul>
 *
 * <p>Uses real PostgreSQL 16 (existing container on port 5444 for dev, or Testcontainers in CI).
 */
@SpringBootTest(
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.baseline-on-migrate=true",
                "spring.flyway.baseline-version=0",
                "spring.flyway.schemas=public",
                "spring.flyway.default-schema=public",
                "spring.flyway.locations=classpath:db/migration",
                // AC5: Hibernate validates entities against the Flyway-created schema
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@ActiveProfiles("flyway-test")
class FlywayBaselineIntegrationTest {

    private static final String TEST_DB = "keevo_flyway_test";
    private static final String HOST = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
    private static final String PORT = System.getenv().getOrDefault("POSTGRES_PORT", "5444");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        // Use a fresh test database to ensure Flyway creates schema from scratch (AC3)
        registry.add("spring.datasource.url", 
            () -> "jdbc:postgresql://" + HOST + ":" + PORT + "/" + TEST_DB);
        registry.add("spring.datasource.username", () -> "keevo");
        registry.add("spring.datasource.password", () -> "keevo_local_pwd");
    }

    @BeforeAll
    static void createTestDatabase() throws Exception {
        // Connect to default 'postgres' database to create test database
        String adminUrl = "jdbc:postgresql://" + HOST + ":" + PORT + "/postgres";
        try (Connection conn = java.sql.DriverManager.getConnection(adminUrl, "keevo", "keevo_local_pwd");
             Statement stmt = conn.createStatement()) {
            // Drop and recreate to ensure fresh state
            stmt.execute("DROP DATABASE IF EXISTS " + TEST_DB);
            stmt.execute("CREATE DATABASE " + TEST_DB);
        }
    }

    @AfterAll
    static void dropTestDatabase() throws Exception {
        String adminUrl = "jdbc:postgresql://" + HOST + ":" + PORT + "/postgres";
        try (Connection conn = java.sql.DriverManager.getConnection(adminUrl, "keevo", "keevo_local_pwd");
             Statement stmt = conn.createStatement()) {
            // Terminate active connections before dropping
            stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE datname = '" + TEST_DB + "' AND pid <> pg_backend_pid()");
            stmt.execute("DROP DATABASE IF EXISTS " + TEST_DB);
        }
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoadsWithFlywayBaseline() {
        // AC3: If the Spring context starts without SchemaManagementException,
        // it means Flyway created the schema AND Hibernate validated entities against it.
        assertThat(flyway).isNotNull();
    }

    @Test
    void flywayVersion1IsApplied() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();

        // baseline-version=0 inserts a synthetic baseline at v0, then V1 runs as a real migration
        MigrationInfo v1Migration = java.util.Arrays.stream(applied)
                .filter(info -> info.getVersion() != null && info.getVersion().getVersion().equals("1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("V1 migration not found in applied migrations"));

        assertThat(v1Migration.getDescription()).containsIgnoringCase("baseline");
    }

    @Test
    void flywaySchemaHistoryTableExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables " +
                     "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void reRunMigrateIsNoOp() {
        // AC4: Running migrate again should be a no-op (no new migrations applied)
        int migrationsExecuted = flyway.migrate().migrationsExecuted;
        assertThat(migrationsExecuted).isZero();
    }

    @Test
    void allExpectedTablesExist() throws Exception {
        String[] expectedTables = {
                "users", "tenants", "user_tenant_memberships", "refresh_tokens",
                "audit_log", "device_tokens", "draft_notifications",
                "categories", "products", "product_suppliers",
                "clients", "suppliers", "stores", "subscriptions", "tenant_preferences",
                "stock_levels", "stock_movements", "stock_transfers",
                "sales", "sale_items", "day_closures", "employees",
                "inventory_sessions", "inventory_counts", "reports",
                "sync_operations_log", "sync_conflicts_log",
                "user_sync_state", "flyway_schema_history"
        };

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String table : expectedTables) {
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_name = '" + table + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1))
                            .as("Table '%s' should exist in public schema", table)
                            .isEqualTo(1);
                }
            }
        }
    }

    // ── V2 migration tests (Story 10.2) ─────────────────────────────────────────

    @Test
    void v2DropUsersTenantIdIsApplied() throws Exception {
        // AC4: V2 migration drops the dangling tenant_id column from public.users
        // AC5: On a fresh DB, V1 creates users with tenant_id, then V2 drops it

        // Verify tenant_id column does NOT exist in public.users
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.columns " +
                     "WHERE table_schema = 'public' AND table_name = 'users' " +
                     "AND column_name = 'tenant_id'")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("Column 'tenant_id' should NOT exist in public.users after V2 migration")
                    .isZero();
        }

        // Verify V2 is recorded with success=true and correct description (AC4)
        MigrationInfo v2Migration = java.util.Arrays.stream(flyway.info().applied())
                .filter(info -> info.getVersion() != null && "2".equals(info.getVersion().getVersion()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("V2 migration not found in applied migrations"));

        assertThat(v2Migration.getState())
                .as("V2 migration should be in SUCCESS state")
                .isEqualTo(MigrationState.SUCCESS);
        assertThat(v2Migration.getDescription())
                .as("V2 migration description should match filename (AC4)")
                .containsIgnoringCase("drop users tenant id");
    }

    @Test
    void v2IsIdempotent() {
        // AC6: Re-running migrate after V2 is already applied should be a no-op
        int migrationsExecuted = flyway.migrate().migrationsExecuted;
        assertThat(migrationsExecuted)
                .as("Re-running migrate should execute 0 migrations (V2 already applied)")
                .isZero();
    }
}

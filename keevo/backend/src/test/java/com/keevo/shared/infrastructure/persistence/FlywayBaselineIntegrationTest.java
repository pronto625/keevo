package com.keevo.shared.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Flyway public-schema migrations (Stories 10.1 / 10.2, rewritten by
 * v1s-15-11).
 *
 * <p>Runs the Flyway API, configured exactly like {@code application.yml} (baseline-on-migrate,
 * baseline version 0, schema {@code public}, {@code classpath:db/migration}), against a pristine
 * database on the shared Testcontainers PostgreSQL ({@link SharedPostgresContainer#freshDatabase}),
 * so it is CI-portable and never depends on a local PostgreSQL.
 * <ul>
 *   <li>AC3: fresh DB, Flyway creates the entire public schema from V1;</li>
 *   <li>AC4: re-running migrate is a no-op;</li>
 *   <li>V2 is applied exactly once and never re-executed.</li>
 * </ul>
 * Hibernate {@code ddl-auto=validate} against the migrated schema is proven by
 * {@link KeevoApplicationContextIntegrationTest} (the full context boots on the same migrations).
 */
class FlywayBaselineIntegrationTest {

    private static final String TEST_DB = "keevo_flyway_baseline";

    private static String url;
    private static Flyway flyway;
    private static MigrateResult firstMigrate;

    @BeforeAll
    static void migrateFreshDatabase() {
        // freshDatabase terminates any backend left by a crashed run before dropping the database.
        url = SharedPostgresContainer.freshDatabase(TEST_DB);
        flyway = Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .schemas("public")
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .load();
        firstMigrate = flyway.migrate();
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @Test
    void freshMigrateSucceeds() {
        assertThat(firstMigrate.success).isTrue();
        assertThat(firstMigrate.migrationsExecuted).isPositive();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void flywayVersion1IsApplied() {
        MigrationInfo v1Migration = Arrays.stream(flyway.info().applied())
                .filter(info -> info.getVersion() != null && info.getVersion().getVersion().equals("1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("V1 migration not found in applied migrations"));

        assertThat(v1Migration.getDescription()).containsIgnoringCase("baseline");
        assertThat(v1Migration.getState()).isEqualTo(MigrationState.SUCCESS);
    }

    @Test
    void flywaySchemaHistoryTableExists() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'")).isEqualTo(1);
    }

    @Test
    void reRunMigrateIsNoOp() {
        // AC4: Running migrate again should be a no-op (no new migrations applied)
        assertThat(flyway.migrate().migrationsExecuted).isZero();
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
        for (String table : expectedTables) {
            assertThat(count("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name = '" + table + "'"))
                    .as("Table '%s' should exist in public schema", table)
                    .isEqualTo(1);
        }
    }

    // ── V2 migration tests (Story 10.2) ─────────────────────────────────────────

    @Test
    void v2DropUsersTenantIdIsApplied() throws Exception {
        // On a fresh DB, V1 creates users with tenant_id, then V2 drops it
        assertThat(count("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'tenant_id'"))
                .as("Column 'tenant_id' should NOT exist in public.users after V2 migration")
                .isZero();

        MigrationInfo v2Migration = Arrays.stream(flyway.info().applied())
                .filter(info -> info.getVersion() != null && "2".equals(info.getVersion().getVersion()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("V2 migration not found in applied migrations"));

        assertThat(v2Migration.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v2Migration.getDescription()).containsIgnoringCase("drop users tenant id");
    }

    @Test
    void v2IsIdempotent() throws Exception {
        // V2 ran once on the fresh database ...
        assertThat(firstMigrate.migrations).extracting(m -> m.version).containsOnlyOnce("2");
        assertThat(count("SELECT COUNT(*) FROM public.flyway_schema_history WHERE version = '2'"))
                .isEqualTo(1);

        // ... and a re-migrate never executes it again (assertion scoped to V2, not to every later Vn).
        MigrateResult again = flyway.migrate();
        assertThat(again.migrations).extracting(m -> m.version).doesNotContain("2");
        assertThat(count("SELECT COUNT(*) FROM public.flyway_schema_history WHERE version = '2'"))
                .isEqualTo(1);
    }

    private static int count(String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story v1s-13-12 — tenant schema provisioning hygiene on a real PostgreSQL 16 migrated by
 * Flyway (V1–V13): idempotent public CHECK constraints (V13), complete tenant {@code users}
 * DDL, and the boot-time full sync of existing tenants by {@link TenantSchemaMigrationRunner}.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TenantSchemaProvisioner.class, TenantSchemaSyncService.class, TenantSchemaMigrationRunner.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@DisplayName("Tenant schema provisioning hygiene on PostgreSQL (Story v1s-13-12)")
class SchemaProvisioningHygienePostgresIntegrationTest {

    /**
     * This class drops and recreates public CHECK constraints and commits tenants, so it runs on
     * its own pristine database of the shared container instead of the shared {@code test} one
     * (no {@code @ServiceConnection}: it would override these URL properties).
     */
    private static final String DATABASE = "keevo_schema_hygiene";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgresContainer.freshDatabase(DATABASE);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgresContainer::username);
        registry.add("spring.datasource.password", SharedPostgresContainer::password);
    }

    private static final String V13 = "db/migration/V13__idempotent_check_constraints.sql";

    private static final Map<String, String> CONSTRAINTS = Map.of(
            "inventory_sessions_scope_check", "inventory_sessions",
            "inventory_sessions_status_check", "inventory_sessions",
            "products_status_check", "products",
            "stock_movements_movement_type_check", "stock_movements",
            "stock_transfers_status_check", "stock_transfers",
            "stores_type_check", "stores");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired TenantSchemaMigrationRunner runner;

    // ── V13 — public CHECK constraints ───────────────────────────────────────

    @Test
    @DisplayName("after Flyway: the 6 named CHECK constraints exist; movement_type allows SALE_CANCELLED")
    void constraintsPresentAfterFlyway() {
        CONSTRAINTS.forEach((name, table) -> assertThat(constraintExists(table, name)).as(name).isTrue());
        assertThat(constraintDef("stock_movements", "stock_movements_movement_type_check"))
                .contains("SALE_CANCELLED").contains("ADJUSTMENT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history WHERE version = '13' AND success", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Hibernate-era tables (constraints missing or stale): re-running V13 recreates them; a second run leaves one per name")
    void v13RecreatesMissingConstraintsIdempotently() throws Exception {
        jdbc.execute("ALTER TABLE public.products DROP CONSTRAINT products_status_check");
        // stale value list under the same name (Hibernate era: no IN_TRANSIT)
        jdbc.execute("ALTER TABLE public.stock_transfers DROP CONSTRAINT stock_transfers_status_check");
        jdbc.execute("ALTER TABLE public.stock_transfers ADD CONSTRAINT stock_transfers_status_check "
                + "CHECK (status IN ('COMPLETED','PENDING_SYNC','CONFLICT'))");

        runV13();
        assertThat(constraintExists("products", "products_status_check")).isTrue();
        assertThat(constraintValidated("products", "products_status_check")).isTrue();
        assertThat(constraintDef("stock_transfers", "stock_transfers_status_check")).contains("IN_TRANSIT");

        runV13(); // already present → dropped and re-added, never duplicated
        CONSTRAINTS.forEach((name, table) -> assertThat(constraintCount(table, name)).as(name).isEqualTo(1));
    }

    @Test
    @DisplayName("legacy row violating the CHECK: V13 adds the constraint NOT VALID and raises a WARNING")
    void v13FallsBackToNotValidOnLegacyBadRow() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.execute("ALTER TABLE public.stores DROP CONSTRAINT stores_type_check");
        jdbc.update("INSERT INTO public.stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, now(), now(), true, 'legacy', 'KIOSK')", id);
        try {
            List<String> warnings = runV13();

            assertThat(constraintExists("stores", "stores_type_check")).isTrue();
            assertThat(constraintValidated("stores", "stores_type_check")).isFalse();
            assertThat(warnings).anyMatch(w -> w.contains("stores_type_check") && w.contains("NOT VALID"));
        } finally {
            jdbc.update("DELETE FROM public.stores WHERE id = ?", id);
            jdbc.execute("ALTER TABLE public.stores VALIDATE CONSTRAINT stores_type_check");
        }
    }

    // ── Tenant provisioning + boot-time sync ─────────────────────────────────

    @Test
    @DisplayName("provision(): tenant users has failed_attempts INTEGER NOT NULL DEFAULT 0 and locked_until TIMESTAMPTZ")
    void provisionCreatesUsersLockoutColumns() {
        provisioner.provision("tenant-hyg1", "kv_hyg001");

        Map<String, Object> failed = column("kv_hyg001", "users", "failed_attempts");
        assertThat(failed.get("data_type")).isEqualTo("integer");
        assertThat(failed.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(failed.get("column_default"))).isEqualTo("0");
        assertThat(column("kv_hyg001", "users", "locked_until").get("data_type"))
                .isEqualTo("timestamp with time zone");
    }

    @Test
    @DisplayName("boot: an existing tenant missing sales.version gets it from the runner, before any request")
    void runnerSyncsExistingTenantAtBoot() {
        String schema = "kv_hyg002";
        provisioner.provision("tenant-hyg2", schema);
        jdbc.execute("ALTER TABLE \"" + schema + "\".sales DROP COLUMN version");
        jdbc.execute("ALTER TABLE \"" + schema + "\".stock_levels DROP COLUMN IF EXISTS version");
        assertThat(columnExists(schema, "sales", "version")).isFalse();
        registerTenant(schema, "KV-HYG002");
        try {
            runner.runAll();

            assertThat(columnExists(schema, "sales", "version")).isTrue();
            assertThat(columnExists(schema, "stock_levels", "version")).isTrue();
        } finally {
            jdbc.update("DELETE FROM public.tenants WHERE schema_name = ?", schema);
        }
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @DisplayName("boot (follow-up): a tenant whose schema no longer exists is skipped (WARN), not failed")
    void runnerSkipsTenantWhoseSchemaIsMissing(org.springframework.boot.test.system.CapturedOutput output) {
        String schema = "kv_hyg009";   // valid name, never provisioned
        registerTenant(schema, "KV-HYG009");
        try {
            runner.runAll();

            Integer schemas = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?", Integer.class, schema);
            assertThat(schemas).isZero();
            assertThat(output.getOut() + output.getErr())
                    .contains("skipping tenant schema '" + schema + "' — schema does not exist")
                    .doesNotContain("FAILED for schema '" + schema + "'");
        } finally {
            jdbc.update("DELETE FROM public.tenants WHERE schema_name = ?", schema);
        }
    }

    @Test
    @DisplayName("boot: an existing tenant missing users.failed_attempts/locked_until gets them back")
    void runnerRestoresUsersLockoutColumns() {
        String schema = "kv_hyg003";
        provisioner.provision("tenant-hyg3", schema);
        jdbc.execute("ALTER TABLE \"" + schema + "\".users DROP COLUMN failed_attempts, DROP COLUMN locked_until");
        registerTenant(schema, "KV-HYG003");
        try {
            runner.runAll();

            assertThat(columnExists(schema, "users", "failed_attempts")).isTrue();
            assertThat(columnExists(schema, "users", "locked_until")).isTrue();
        } finally {
            jdbc.update("DELETE FROM public.tenants WHERE schema_name = ?", schema);
        }
    }

    @Test
    @DisplayName("boot: a tenant whose stock_movements has the old movement_type CHECKs accepts SALE_CANCELLED after the runner")
    void runnerWidensOldTenantMovementTypeCheck() throws Exception {
        String schema = "kv_hyg004";
        provisioner.provision("tenant-hyg4", schema);
        String old = "CHECK (movement_type IN ('SALE','STOCK_ENTRY','TRANSFER_IN','TRANSFER_OUT','ADJUSTMENT'))";
        jdbc.execute("ALTER TABLE \"" + schema + "\".stock_movements DROP CONSTRAINT ck_movement_type");
        jdbc.execute("ALTER TABLE \"" + schema + "\".stock_movements ADD CONSTRAINT ck_movement_type " + old);
        // inherited from public by LIKE … INCLUDING ALL
        jdbc.execute("ALTER TABLE \"" + schema + "\".stock_movements ADD CONSTRAINT stock_movements_movement_type_check " + old);
        assertThat(insertSaleCancelledMovement(schema)).as("old constraints reject SALE_CANCELLED").isFalse();
        registerTenant(schema, "KV-HYG004");
        try {
            runner.runAll();

            assertThat(insertSaleCancelledMovement(schema)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = ?::regclass "
                    + "AND conname = 'stock_movements_movement_type_check'", Integer.class,
                    "\"" + schema + "\".stock_movements")).isZero();
        } finally {
            jdbc.update("DELETE FROM public.tenants WHERE schema_name = ?", schema);
        }
    }

    @Test
    @DisplayName("upgrade: a legacy movement_type row does not abort the tenant — constraint NOT VALID, new columns added, row kept")
    void runnerKeepsLegacyMovementTypeRow() throws Exception {
        String schema = "kv_hyg005";
        provisioner.provision("tenant-hyg5", schema);
        String q = "\"" + schema + "\".";
        jdbc.execute("ALTER TABLE " + q + "stock_movements DROP CONSTRAINT ck_movement_type");
        insertWithoutForeignKeys("INSERT INTO " + q + "stock_movements (product_id, store_id, movement_type, "
                + "quantity_before, quantity_change, quantity_after, actor_id) VALUES (gen_random_uuid(), "
                + "gen_random_uuid(), 'LEGACY_TYPE', 0, 1, 1, gen_random_uuid())");
        jdbc.execute("ALTER TABLE " + q + "sales DROP COLUMN mobile_money_ref");
        registerTenant(schema, "KV-HYG005");
        try {
            runner.runAll();

            assertThat(columnExists(schema, "sales", "mobile_money_ref")).as("tenant upgrade committed").isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + q + "stock_movements WHERE movement_type = 'LEGACY_TYPE'",
                    Integer.class)).as("legacy row untouched").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conrelid = ?::regclass "
                    + "AND conname = 'ck_movement_type'", Boolean.class, q + "stock_movements")).isFalse();
            assertThat(insertSaleCancelledMovement(schema)).isTrue();
        } finally {
            jdbc.update("DELETE FROM public.tenants WHERE schema_name = ?", schema);
        }
    }

    @Test
    @DisplayName("upgrade: duplicate product names skip uq_products_name with a WARNING — no row changed, upgrade committed")
    void runnerSkipsUniqueNameIndexOnDuplicates() throws Exception {
        String schema = "kv_hyg006";
        provisioner.provision("tenant-hyg6", schema);
        String q = "\"" + schema + "\".";
        jdbc.execute("DROP INDEX " + q + "uq_products_name");
        insertWithoutForeignKeys("INSERT INTO " + q + "products (name, sku) VALUES ('Savon', 'SAV-1'), ('SAVON', 'SAV-2')");
        jdbc.execute("ALTER TABLE " + q + "sales DROP COLUMN mobile_money_ref");
        registerTenant(schema, "KV-HYG006");
        try {
            runner.runAll();

            assertThat(columnExists(schema, "sales", "mobile_money_ref")).as("tenant upgrade committed").isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + q + "products WHERE lower(name) = 'savon'",
                    Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, q + "uq_products_name"))
                    .as("index left out while duplicates exist").isTrue();
        } finally {
            jdbc.update("DELETE FROM public.tenants WHERE schema_name = ?", schema);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Commits {@code sql} with FKs disabled (session_replication_role) — legacy rows for upgrade tests. */
    private void insertWithoutForeignKeys(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            try {
                stmt.execute("SET LOCAL session_replication_role = replica");
                stmt.execute(sql);
                conn.commit();
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Executes the shipped V13 script as-is; returns the server WARNING messages. */
    private List<String> runV13() throws Exception {
        String sql = new ClassPathResource(V13).getContentAsString(StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            for (SQLWarning w = stmt.getWarnings(); w != null; w = w.getNextWarning()) {
                warnings.add(w.getMessage());
            }
        }
        return warnings;
    }

    private void registerTenant(String schema, String code) {
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) "
                + "VALUES (?, now(), now(), ?, 1, 1, 1, 'FREE', ?, 'ACTIVE')", UUID.randomUUID(), code, schema);
    }

    /**
     * Tries to insert a SALE_CANCELLED movement (FKs disabled via session_replication_role, CHECKs
     * still enforced), always rolled back. Returns whether the CHECK constraints accepted it.
     */
    private boolean insertSaleCancelledMovement(String schema) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            try {
                stmt.execute("SET LOCAL session_replication_role = replica");
                stmt.execute("INSERT INTO \"" + schema + "\".stock_movements (product_id, store_id, movement_type, "
                        + "quantity_before, quantity_change, quantity_after, actor_id) VALUES ("
                        + "gen_random_uuid(), gen_random_uuid(), 'SALE_CANCELLED', 0, 1, 1, gen_random_uuid())");
                return true;
            } catch (java.sql.SQLException e) {
                if (!"23514".equals(e.getSQLState())) throw e; // only check_violation means "rejected"
                return false;
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    private boolean constraintExists(String table, String name) {
        return constraintCount(table, name) == 1;
    }

    private int constraintCount(String table, String name) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_constraint "
                + "WHERE conrelid = ('public.' || ?)::regclass AND conname = ?", Integer.class, table, name);
    }

    private boolean constraintValidated(String table, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT convalidated FROM pg_constraint "
                + "WHERE conrelid = ('public.' || ?)::regclass AND conname = ?", Boolean.class, table, name));
    }

    private String constraintDef(String table, String name) {
        return jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = ('public.' || ?)::regclass AND conname = ?", String.class, table, name);
    }

    private Map<String, Object> column(String schema, String table, String col) {
        return jdbc.queryForMap("SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?", schema, table, col);
    }

    private boolean columnExists(String schema, String table, String col) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                Integer.class, schema, table, col) == 1;
    }
}

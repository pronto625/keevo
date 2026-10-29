package com.keevo.shared.module;

import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.module.adapter.out.persistence.FlywayModuleMigrations;
import com.keevo.shared.module.adapter.out.persistence.JdbcTenantModuleStateStore;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModuleProvisionerService;
import com.keevo.shared.module.application.ModuleRegistryService;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 13.4 — {@link ModuleProvisioner} against PostgreSQL: a fresh database of the shared container migrated with
 * the application's public Flyway settings, three tenant schemas (non-empty, like real tenants) and the test-only
 * modules of {@link TestModuleCatalogs#provisioningCatalog()} (migrations under {@code src/test/resources/db/modules}
 * and {@code db/modules-next}). Covers every row of the spec's I/O matrix.
 */
class ModuleProvisionerPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_module_provisioner";
    private static final String A = "kv_prva01";
    private static final String B = "kv_prvb02";
    private static final String C = "kv_prvc03";
    private static final Map<String, String> CODES = Map.of(A, "KV-PRVA01", B, "KV-PRVB02", C, "KV-PRVC03");
    private static final Map<String, UUID> IDS = Map.of(A, UUID.randomUUID(), B, UUID.randomUUID(),
            C, UUID.randomUUID());
    private static final List<String> NEXT_ROOTS = List.of(FlywayModuleMigrations.DEFAULT_ROOT, "db/modules-next");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private ModuleRegistry registry;
    private ModuleProvisioner provisioner;

    @BeforeAll
    static void migratePublic() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration")
                .load().migrate();
        dataSource = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(dataSource);
        for (String schema : List.of(A, B, C)) {
            jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                            + "max_stores, plan_type, schema_name, status, name) "
                            + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')",
                    IDS.get(schema), CODES.get(schema), schema);
        }
        // a DELETED tenant is never part of a batch
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) "
                + "VALUES (?, now(), now(), 'KV-PRVD04', 3, 500, 1, 'FREE', 'kv_prvd04', 'DELETED', 'Supprimé')",
                UUID.randomUUID());
        // a live tenant whose schema is not created yet: skipped by a batch
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) "
                + "VALUES (?, now(), now(), 'KV-PRVE05', 3, 500, 1, 'FREE', 'kv_prve05', 'ACTIVE', 'Sans schéma')",
                UUID.randomUUID());
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void freshSchemas() {
        jdbc.update("DELETE FROM public.tenant_modules");
        for (String schema : List.of(A, B, C)) {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); // test database only
            jdbc.execute("CREATE SCHEMA \"" + schema + "\"");
            // a pre-existing tenant table with data: must stay untouched by module provisioning
            jdbc.execute("CREATE TABLE \"" + schema + "\".existing_data (id INT PRIMARY KEY, label TEXT)");
            jdbc.update("INSERT INTO \"" + schema + "\".existing_data VALUES (1, 'avant')");
        }
        registry = new ModuleRegistryService(TestModuleCatalogs.provisioningCatalog(),
                new JdbcTenantModuleStateStore(dataSource), new RequestScopedActiveModulesCache(),
                java.time.Clock.systemUTC());
        provisioner = provisioner(List.of(FlywayModuleMigrations.DEFAULT_ROOT));
    }

    private ModuleProvisioner provisioner(List<String> roots) {
        ModuleCatalog catalog = TestModuleCatalogs.provisioningCatalog();
        JdbcTenantModuleStateStore store = new JdbcTenantModuleStateStore(dataSource);
        return new ModuleProvisionerService(catalog, registry, store, store,
                new FlywayModuleMigrations(dataSource, roots));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<String> tables(String schema) {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? "
                + "ORDER BY table_name", String.class, schema);
    }

    private static List<String> columns(String schema, String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = ? ORDER BY ordinal_position", String.class, schema, table);
    }

    private static Map<String, Object> row(String schema, String moduleId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, tier, activated_at, provisioned_version "
                + "FROM public.tenant_modules WHERE tenant_id = ? AND module_id = ?", IDS.get(schema), moduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void insertRow(String schema, String moduleId, String status, String tier) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, ?)",
                IDS.get(schema), moduleId, status, tier);
    }

    private static List<String> history(String schema, String table) {
        return jdbc.queryForList("SELECT COALESCE(version, '-') || ':' || type || ':' || success FROM \""
                + schema + "\".\"" + table + "\" ORDER BY installed_rank", String.class);
    }

    private static void assertExistingDataUntouched(String schema) {
        assertThat(jdbc.queryForObject("SELECT label FROM \"" + schema + "\".existing_data WHERE id = 1",
                String.class)).isEqualTo("avant");
    }

    // ── matrix ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("first provision: V1+V2 applied in A only, isolated history, free module → row ACTIVE/FREE v2")
    void firstProvision() {
        ProvisioningResult result = provisioner.provision(A, "provtest");

        assertThat(result).isEqualTo(new ProvisioningResult("provtest", null, "2", 2));
        assertThat(tables(A)).contains("provtest_items", "flyway_history_provtest", "existing_data");
        assertThat(columns(A, "provtest_items")).containsExactly("id", "name", "quantity");
        assertThat(tables("public")).doesNotContain("provtest_items", "flyway_history_provtest");
        assertThat(history(A, "flyway_history_provtest"))
                .containsExactly("0:BASELINE:true", "1:SQL:true", "2:SQL:true");
        Map<String, Object> row = row(A, "provtest");
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("tier", "FREE")
                .containsEntry("provisioned_version", "2");
        assertThat(row.get("activated_at")).isNotNull();
        assertExistingDataUntouched(A);
    }

    @Test
    @DisplayName("existing row: only provisioned_version is updated, status/tier untouched")
    void existingRowUpdated() {
        insertRow(A, "provtest", "TRIAL", "STANDARD");

        provisioner.provision(A, "provtest");

        assertThat(row(A, "provtest")).containsEntry("status", "TRIAL").containsEntry("tier", "STANDARD")
                .containsEntry("provisioned_version", "2").containsEntry("activated_at", null);
    }

    @Test
    @DisplayName("re-provision: no-op, nothing applied, history unchanged")
    void reProvision() {
        provisioner.provision(A, "provtest");
        List<String> before = history(A, "flyway_history_provtest");

        ProvisioningResult again = provisioner.provision(A, "provtest");

        assertThat(again).isEqualTo(new ProvisioningResult("provtest", "2", "2", 0));
        assertThat(again.changed()).isFalse();
        assertThat(history(A, "flyway_history_provtest")).isEqualTo(before);
        assertThat(row(A, "provtest")).containsEntry("provisioned_version", "2");
    }

    @Test
    @DisplayName("new version shipped: only V3 is applied, version 3")
    void newVersionShipped() {
        provisioner.provision(A, "provtest");

        ProvisioningResult next = provisioner(NEXT_ROOTS).provision(A, "provtest");

        assertThat(next).isEqualTo(new ProvisioningResult("provtest", "2", "3", 1));
        assertThat(tables(A)).contains("provtest_labels");
        assertThat(row(A, "provtest")).containsEntry("provisioned_version", "3");
        assertThat(history(A, "flyway_history_provtest")).hasSize(4);
    }

    @Test
    @DisplayName("isolation: provisioning A leaves B without tables, history nor row")
    void isolation() {
        provisioner.provision(A, "provtest");

        assertThat(tables(B)).containsExactly("existing_data");
        assertThat(row(B, "provtest")).isNull();
        assertExistingDataUntouched(B);
    }

    @Test
    @DisplayName("no migrations: no-op result, no history table, no row")
    void noMigrations() {
        assertThat(provisioner.hasMigrations("provempty")).isFalse();
        assertThat(provisioner.hasMigrations("provtest")).isTrue();

        ProvisioningResult result = provisioner.provision(A, "provempty");

        assertThat(result).isEqualTo(new ProvisioningResult("provempty", null, null, 0));
        assertThat(tables(A)).containsExactly("existing_data");
        assertThat(row(A, "provempty")).isNull();
    }

    @Test
    @DisplayName("inactive module without row: IllegalStateException before any migration")
    void inactiveModule() {
        assertThatThrownBy(() -> provisioner.provision(A, "provpaid"))
                .isInstanceOf(ModuleNotActiveException.class);
        assertThat(tables(A)).containsExactly("existing_data");
        assertThat(row(A, "provpaid")).isNull();
    }

    @Test
    @DisplayName("paid module with an ACTIVE row: provisioned, row status untouched")
    void paidModuleWithRow() {
        insertRow(A, "provpaid", "ACTIVE", "PREMIUM");

        assertThat(provisioner.provision(A, "provpaid").toVersion()).isEqualTo("1");
        assertThat(row(A, "provpaid")).containsEntry("status", "ACTIVE").containsEntry("tier", "PREMIUM")
                .containsEntry("provisioned_version", "1");
    }

    @Test
    @DisplayName("disabled row: the row decides (not the free rule) — still provisioned, status untouched")
    void disabledRowStillProvisioned() {
        // 13.8 writes the row first; a DISABLED row keeps its tables (spec-socle §10: tables kept for life)
        insertRow(A, "provtest", "DISABLED", "FREE");

        provisioner.provision(A, "provtest");

        assertThat(row(A, "provtest")).containsEntry("status", "DISABLED").containsEntry("provisioned_version", "2");
    }

    @Test
    @DisplayName("broken migration: ModuleProvisioningException without schema/SQL, version stays 1")
    void brokenMigration() {
        assertThat(provisioner.provision(A, "provbroken").toVersion()).isEqualTo("1");

        assertThatThrownBy(() -> provisioner(NEXT_ROOTS).provision(A, "provbroken"))
                .isInstanceOf(ModuleProvisioningException.class)
                .hasMessageContaining("provbroken")
                .hasMessageContaining("length=9")
                .hasNoCause()
                .satisfies(e -> {
                    ModuleProvisioningException mpe = (ModuleProvisioningException) e;
                    assertThat(e.getMessage()).doesNotContain(A).doesNotContainIgnoringCase("TABLEX")
                            .doesNotContainIgnoringCase("ALTER").contains("42601");
                    assertThat(mpe.failure()).contains("42601"); // SQL state: syntax error
                    assertThat(mpe.highestAppliedVersion()).isEqualTo("1");
                });

        assertThat(row(A, "provbroken")).containsEntry("provisioned_version", "1");
        assertThat(history(A, "flyway_history_provbroken")).containsExactly("0:BASELINE:true", "1:SQL:true");
        // the failed script rolled back entirely (transactional DDL)
        assertThat(columns(A, "provbroken_items")).containsExactly("id");
    }

    @Test
    @DisplayName("batch: 3 live tenants, one fails → 2 provisioned, 1 failure by tenant code, loop continues")
    void batch() {
        // B already has a conflicting table: its V1 fails
        jdbc.execute("CREATE TABLE \"" + B + "\".provbatch_items (id INT)");

        ProvisioningReport report = provisioner.provisionAll("provbatch");

        assertThat(report.provisioned()).containsExactly("KV-PRVA01", "KV-PRVC03");
        assertThat(report.skipped()).containsExactly("KV-PRVE05"); // live tenant without a schema yet
        assertThat(report.failures()).extracting(ProvisioningReport.Failure::tenantCode).containsExactly("KV-PRVB02");
        assertThat(report.failures().get(0).reason()).doesNotContain(B).contains("provbatch");
        assertThat(report.successful()).isFalse();
        assertThat(tables(A)).contains("provbatch_items");
        assertThat(tables(C)).contains("provbatch_items");
        assertThat(row(B, "provbatch")).isNull();
        assertThat(tables("kv_prvd04")).isEmpty(); // DELETED tenant: never touched (no schema either)
    }

    @Test
    @DisplayName("batch of a paid module: tenants without an active row are skipped, not failed")
    void batchSkipsInactive() {
        insertRow(C, "provpaid", "ACTIVE", "PREMIUM");

        ProvisioningReport report = provisioner.provisionAll("provpaid");

        assertThat(report.provisioned()).containsExactly("KV-PRVC03");
        assertThat(report.skipped()).containsExactly("KV-PRVA01", "KV-PRVB02", "KV-PRVE05");
        assertThat(report.failures()).isEmpty();
    }

    @Test
    @DisplayName("concurrent runs on the same (schema, module): serialised, migrations applied once")
    void concurrentProvisioning() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<ProvisioningResult> task = () -> {
                start.await();
                return provisioner.provision(A, "provtest");
            };
            Future<ProvisioningResult> first = pool.submit(task);
            Future<ProvisioningResult> second = pool.submit(task);
            start.countDown();
            ProvisioningResult r1 = first.get();
            ProvisioningResult r2 = second.get();
            assertThat(r1.migrationsApplied() + r2.migrationsApplied()).isEqualTo(2);
            assertThat(List.of(r1.toVersion(), r2.toVersion())).containsOnly("2");
        } finally {
            pool.shutdownNow();
        }
        // one baseline row, each migration once
        assertThat(history(A, "flyway_history_provtest"))
                .containsExactly("0:BASELINE:true", "1:SQL:true", "2:SQL:true");
        assertThat(row(A, "provtest")).containsEntry("provisioned_version", "2");
    }

    @Test
    @DisplayName("partial failure: V1+V2 commit, V3 fails in the same run → version 2 recorded, then rethrown")
    void partialFailureRecordsReachedVersion() {
        assertThatThrownBy(() -> provisioner(NEXT_ROOTS).provision(A, "provpartial"))
                .isInstanceOf(ModuleProvisioningException.class)
                .satisfies(e -> assertThat(((ModuleProvisioningException) e).highestAppliedVersion()).isEqualTo("2"));

        assertThat(row(A, "provpartial")).containsEntry("status", "ACTIVE").containsEntry("provisioned_version", "2");
        assertThat(columns(A, "provpartial_items")).containsExactly("id", "label");
    }

    @Test
    @DisplayName("monotonic version: a stored higher version is never lowered (WARN), numeric part comparison")
    void monotonicVersion() {
        insertRow(A, "provtest", "ACTIVE", "FREE");
        jdbc.update("UPDATE public.tenant_modules SET provisioned_version = '5' WHERE module_id = 'provtest'");
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ModuleProvisionerService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(provisioner.provision(A, "provtest").toVersion()).isEqualTo("2");
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(row(A, "provtest")).containsEntry("provisioned_version", "5");
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(e.getFormattedMessage()).contains("provtest").doesNotContain(A);
        });

        JdbcTenantModuleStateStore store = new JdbcTenantModuleStateStore(dataSource);
        jdbc.update("UPDATE public.tenant_modules SET provisioned_version = '9' WHERE module_id = 'provtest'");
        assertThat(store.updateProvisionedVersion(A, "provtest", "10")).isEqualTo(1); // 10 > 9 (not text order)
        assertThat(store.updateProvisionedVersion(A, "provtest", "9")).isZero();
        assertThat(store.updateProvisionedVersion(A, "provtest", "10")).isEqualTo(1); // same version: idempotent
        jdbc.update("UPDATE public.tenant_modules SET provisioned_version = '2.9' WHERE module_id = 'provtest'");
        assertThat(store.updateProvisionedVersion(A, "provtest", "2.10")).isEqualTo(1);
        assertThat(store.updateProvisionedVersion(A, "provtest", "2.9")).isZero();
        assertThat(row(A, "provtest")).containsEntry("provisioned_version", "2.10");
        // insert path on an existing row (concurrent writer): monotonic too
        assertThat(store.insertActiveFree(A, "provtest", "1")).isZero();
        assertThat(row(A, "provtest")).containsEntry("provisioned_version", "2.10");
    }

    @Test
    @DisplayName("misnamed scripts: only misnamed → no-op (no row); misnamed beside a valid one → fails, nothing created")
    void misnamedScripts() {
        assertThat(provisioner.hasMigrations("provmisnamed")).isFalse();
        assertThat(provisioner.provision(A, "provmisnamed"))
                .isEqualTo(new ProvisioningResult("provmisnamed", null, null, 0));
        assertThat(row(A, "provmisnamed")).isNull();

        assertThat(provisioner.hasMigrations("provmixed")).isTrue();
        assertThatThrownBy(() -> provisioner.provision(A, "provmixed"))
                .isInstanceOf(ModuleProvisioningException.class).hasNoCause();
        assertThat(row(A, "provmixed")).isNull();
        assertThat(tables(A)).doesNotContain("provmixed_items", "provmixed_more", "provmisnamed_items");
    }

    @Test
    @DisplayName("row write failure: wrapped in ModuleProvisioningException without cause")
    void rowWriteFailureWrapped() {
        JdbcTenantModuleStateStore store = new JdbcTenantModuleStateStore(dataSource);
        TenantModuleProvisioningPort failing = new TenantModuleProvisioningPort() {
            @Override
            public int updateProvisionedVersion(String schema, String moduleId, String version) {
                throw new org.springframework.dao.DataAccessResourceFailureException("boom " + schema);
            }

            @Override
            public int insertActiveFree(String schema, String moduleId, String version) {
                throw new org.springframework.dao.DataAccessResourceFailureException("boom " + schema);
            }

            @Override
            public List<LiveTenant> liveTenants() {
                return store.liveTenants();
            }
        };
        ModuleProvisioner p = new ModuleProvisionerService(TestModuleCatalogs.provisioningCatalog(), registry, store,
                failing, new FlywayModuleMigrations(dataSource, List.of(FlywayModuleMigrations.DEFAULT_ROOT)));

        assertThatThrownBy(() -> p.provision(A, "provtest"))
                .isInstanceOf(ModuleProvisioningException.class).hasNoCause()
                .hasMessageNotContaining(A).hasMessageNotContaining("boom")
                .hasMessageContaining("DataAccessResourceFailureException");
    }

    @Test
    @DisplayName("pooled connections: provisioning never leaves a pool connection on the tenant search_path")
    void pooledConnectionSearchPathUnchanged() throws Exception {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(SharedPostgresContainer.jdbcUrl(TEST_DB));
        config.setUsername(SharedPostgresContainer.username());
        config.setPassword(SharedPostgresContainer.password());
        config.setMaximumPoolSize(1); // one physical connection: the one Flyway would get if it used the pool
        try (com.zaxxer.hikari.HikariDataSource pool = new com.zaxxer.hikari.HikariDataSource(config)) {
            JdbcTemplate pooled = new JdbcTemplate(pool);
            String before = pooled.queryForObject("SHOW search_path", String.class);
            JdbcTenantModuleStateStore store = new JdbcTenantModuleStateStore(pool);
            ModuleProvisioner p = new ModuleProvisionerService(TestModuleCatalogs.provisioningCatalog(), registry,
                    store, store, new FlywayModuleMigrations(pool));

            assertThat(p.provision(A, "provtest").migrationsApplied()).isEqualTo(2);
            assertThatThrownBy(() -> new FlywayModuleMigrations(pool, NEXT_ROOTS).migrate(A, "provbroken"))
                    .isInstanceOf(ModuleProvisioningException.class);

            assertThat(pooled.queryForObject("SHOW search_path", String.class)).isEqualTo(before).doesNotContain(A);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isEqualTo(1);
        }
    }

    // ── invalid input ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("invalid input: platform/invalid schema and unknown module → IAE without the raw value")
    void invalidInput() {
        assertThatThrownBy(() -> provisioner.provision("public", "provtest"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("public");
        assertThatThrownBy(() -> provisioner.provision("KV_1; DROP", "provtest"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("DROP");
        assertThatThrownBy(() -> provisioner.provision(A, "Evil';--"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("Evil");
        assertThatThrownBy(() -> provisioner.provision(A, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provisioner.hasMigrations("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provisioner.provisionAll("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThat(tables(A)).containsExactly("existing_data");
    }

    @Test
    @DisplayName("unknown or DELETED tenant: IllegalStateException, nothing created")
    void unknownTenant() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS kv_prvd04");
        try {
            assertThatThrownBy(() -> provisioner.provision("kv_nobody", "provtest"))
                    .isInstanceOf(TenantNotFoundException.class);
            assertThatThrownBy(() -> provisioner.provision("kv_prvd04", "provtest"))
                    .isInstanceOf(TenantNotFoundException.class);
            assertThat(tables("kv_prvd04")).isEmpty();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS kv_prvd04 CASCADE"); // test database only
        }
    }
}

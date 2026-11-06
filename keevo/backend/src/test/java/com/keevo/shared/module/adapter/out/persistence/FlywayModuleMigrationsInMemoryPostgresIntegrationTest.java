package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleManifestLoader;
import com.keevo.shared.module.ModuleOrder;
import com.keevo.shared.module.ModuleProvisioningException;
import com.keevo.shared.module.application.port.ModuleMigrationPort.Outcome;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 16.12 (2) — module migrations served to Flyway from memory ({@link ModuleScriptCache} +
 * {@link InMemoryModuleResourceProvider}) against PostgreSQL: histories written by the previous classpath
 * configuration validate unchanged, naming validation and the "next release" root still work, and parallel runs
 * across schemas do not fail.
 */
class FlywayModuleMigrationsInMemoryPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_module_inmemory";
    private static final List<String> NEXT_ROOTS = List.of(FlywayModuleMigrations.DEFAULT_ROOT, "db/modules-next");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

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
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    private static void freshSchema(String schema) {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); // test database only
        jdbc.execute("CREATE SCHEMA \"" + schema + "\"");
        jdbc.execute("CREATE TABLE \"" + schema + "\".existing_data (id INT PRIMARY KEY)"); // never empty
    }

    /** The configuration before 16.12 (2): Flyway scans {@code classpath:<root>/<id>} itself. */
    private static Flyway classpathFlyway(String schema, String moduleId, List<String> roots) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema).defaultSchema(schema)
                .table(FlywayModuleMigrations.historyTable(moduleId))
                .locations(roots.stream().map(root -> "classpath:" + root + "/" + moduleId).toArray(String[]::new))
                .baselineOnMigrate(true).baselineVersion("0")
                .createSchemas(false)
                .placeholderReplacement(false)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
    }

    private static List<Map<String, Object>> history(String schema, String moduleId) {
        return jdbc.queryForList("SELECT installed_rank, version, script, checksum, success FROM \"" + schema + "\"."
                + FlywayModuleMigrations.historyTable(moduleId) + " ORDER BY installed_rank");
    }

    /** Every production module shipping scripts, in module order. */
    private static List<String> productionModules() {
        List<ModuleManifest> manifests = ModuleManifestLoader.loadFromClasspath(new PathMatchingResourcePatternResolver())
                .stream().map(LoadedModuleManifest::manifest).toList();
        FlywayModuleMigrations migrations = new FlywayModuleMigrations(dataSource);
        return ModuleOrder.order(manifests).stream().map(ModuleManifest::id)
                .filter(migrations::hasMigrations).toList();
    }

    @Test
    @DisplayName("(a) histories written by the classpath configuration: re-run from memory applies nothing, checksums validate")
    void classpathHistoriesValidate() {
        String schema = "kv_inmem1";
        freshSchema(schema);
        List<String> modules = productionModules();
        assertThat(modules).contains("core", "catalog", "invoicing");
        for (String module : modules) {
            assertThat(classpathFlyway(schema, module, List.of(FlywayModuleMigrations.DEFAULT_ROOT)).migrate().success)
                    .isTrue();
        }
        Map<String, List<Map<String, Object>>> before = new java.util.LinkedHashMap<>();
        modules.forEach(module -> before.put(module, history(schema, module)));

        FlywayModuleMigrations migrations = new FlywayModuleMigrations(dataSource);
        for (String module : modules) {
            Outcome outcome = migrations.migrate(schema, module);
            assertThat(outcome.applied()).as(module).isZero();
            assertThat(outcome.toVersion()).as(module).isEqualTo(migrations.latestVersion(module));
            assertThat(history(schema, module)).as(module).isEqualTo(before.get(module));
        }
        // and the reverse: a history written from memory validates under the classpath configuration
        freshSchema("kv_inmem2");
        migrations.migrate("kv_inmem2", "provtest");
        assertThat(classpathFlyway("kv_inmem2", "provtest", List.of(FlywayModuleMigrations.DEFAULT_ROOT))
                .migrate().migrationsExecuted).isZero();
        assertThat(history("kv_inmem2", "provtest")).extracting(row -> row.get("script"))
                .containsExactly("<< Flyway Baseline >>", "V1__provtest_items.sql", "V2__provtest_items_quantity.sql");
    }

    @Test
    @DisplayName("(b) a misnamed script beside valid ones still fails (naming validation), nothing applied")
    void misnamedBesideValidFails() {
        String schema = "kv_inmem3";
        freshSchema(schema);
        FlywayModuleMigrations migrations = new FlywayModuleMigrations(dataSource);
        assertThat(migrations.hasMigrations("provmisnamed")).isFalse();
        assertThatThrownBy(() -> migrations.migrate(schema, "provmixed"))
                .isInstanceOf(ModuleProvisioningException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name = 'provmixed_items'", Integer.class, schema)).isZero();
    }

    @Test
    @DisplayName("(c) the db/modules-next root is merged: V3 applied after V1–V2")
    void nextRootMerged() {
        String schema = "kv_inmem4";
        freshSchema(schema);
        Outcome first = new FlywayModuleMigrations(dataSource).migrate(schema, "provtest");
        assertThat(first.toVersion()).isEqualTo("2");
        FlywayModuleMigrations next = new FlywayModuleMigrations(dataSource, NEXT_ROOTS);
        assertThat(next.latestVersion("provtest")).isEqualTo("3");
        Outcome outcome = next.migrate(schema, "provtest");
        assertThat(outcome.fromVersion()).isEqualTo("2");
        assertThat(outcome.toVersion()).isEqualTo("3");
        assertThat(outcome.applied()).isEqualTo(1);
    }

    @Test
    @DisplayName("(d) 4 threads × 8 schemas × 2 modules migrate in parallel without error")
    void parallelRuns() throws Exception {
        List<String> schemas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String schema = "kv_inmp0" + i;
            freshSchema(schema);
            schemas.add(schema);
        }
        FlywayModuleMigrations migrations = new FlywayModuleMigrations(dataSource, NEXT_ROOTS);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Outcome>> runs = new ArrayList<>();
            for (String schema : schemas) {
                for (String module : List.of("provtest", "provbatch")) {
                    runs.add(pool.submit(() -> migrations.migrate(schema, module)));
                }
            }
            for (Future<Outcome> run : runs) {
                assertThat(run.get(120, TimeUnit.SECONDS).applied()).isPositive();
            }
        } finally {
            pool.shutdownNow();
        }
        for (String schema : schemas) {
            assertThat(history(schema, "provtest")).extracting(row -> row.get("version"))
                    .containsExactly("0", "1", "2", "3");
            assertThat(history(schema, "provbatch")).extracting(row -> row.get("version"))
                    .containsExactly("0", "1");
        }
    }

    // ── own cache over a temp class loader (module not on the application classpath) ──────────────────────────

    private static void write(Path classpath, String path, String text) throws IOException {
        Path file = classpath.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static boolean tableExists(String schema, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name = ?", Integer.class, schema, table) > 0;
    }

    @Test
    @DisplayName("(e) parallel runs through the in-memory provider: scripts applied everywhere, location read once")
    void ownCacheReadOnce(@TempDir Path classpath) throws Exception {
        write(classpath, "db/modules/isolated/V1__isolated_items.sql",
                "CREATE TABLE isolated_items (id INT PRIMARY KEY);\n");
        write(classpath, "db/modules/isolated/sub/V2__isolated_label.sql",
                "ALTER TABLE isolated_items ADD COLUMN label TEXT; -- libellé\n");
        List<String> schemas = List.of("kv_inmo01", "kv_inmo02", "kv_inmo03", "kv_inmo04");
        schemas.forEach(FlywayModuleMigrationsInMemoryPostgresIntegrationTest::freshSchema);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classpath.toUri().toURL()}, null)) {
            CountingResolver resolver = new CountingResolver(new PathMatchingResourcePatternResolver(loader));
            FlywayModuleMigrations migrations = new FlywayModuleMigrations(dataSource,
                    List.of(FlywayModuleMigrations.DEFAULT_ROOT), new ModuleScriptCache(resolver));
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<Outcome>> runs = new ArrayList<>();
                for (String schema : schemas) {
                    runs.add(pool.submit(() -> migrations.migrate(schema, "isolated")));
                }
                for (Future<Outcome> run : runs) {
                    assertThat(run.get(120, TimeUnit.SECONDS).toVersion()).isEqualTo("2");
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(migrations.hasMigrations("isolated")).isTrue();
            assertThat(migrations.latestVersion("isolated")).isEqualTo("2");
            assertThat(resolver.calls).containsExactly(Map.entry("classpath*:db/modules/isolated/**/*.sql", 1));
        }
        for (String schema : schemas) {
            assertThat(tableExists(schema, "isolated_items")).isTrue();
            assertThat(history(schema, "isolated")).extracting(row -> row.get("script"))
                    .containsExactly("<< Flyway Baseline >>", "V1__isolated_items.sql", "sub/V2__isolated_label.sql");
        }
    }

    @Test
    @DisplayName("(f) the same version in two roots with different text fails as Flyway does, nothing applied")
    void duplicateVersionAcrossRoots(@TempDir Path classpath) throws Exception {
        write(classpath, "db/modules/dup/V1__dup_a.sql", "CREATE TABLE dup_a (id INT);\n");
        write(classpath, "db/modules-next/dup/V1__dup_b.sql", "CREATE TABLE dup_b (id INT);\n");
        String schema = "kv_inmd01";
        freshSchema(schema);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classpath.toUri().toURL()}, null)) {
            FlywayModuleMigrations migrations = new FlywayModuleMigrations(dataSource, NEXT_ROOTS,
                    new ModuleScriptCache(new CountingResolver(new PathMatchingResourcePatternResolver(loader))));
            assertThatThrownBy(() -> migrations.migrate(schema, "dup"))
                    .isInstanceOf(ModuleProvisioningException.class);
        }
        assertThat(tableExists(schema, "dup_a")).isFalse();
        assertThat(tableExists(schema, "dup_b")).isFalse();
    }

    /** Delegating resolver counting {@code getResources} per pattern. */
    private static final class CountingResolver implements ResourcePatternResolver {
        private final ResourcePatternResolver delegate;
        final Map<String, Integer> calls = new java.util.concurrent.ConcurrentHashMap<>();

        CountingResolver(ResourcePatternResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Resource[] getResources(String locationPattern) throws IOException {
            calls.merge(locationPattern, 1, Integer::sum);
            return delegate.getResources(locationPattern);
        }

        @Override
        public Resource getResource(String location) {
            return delegate.getResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return delegate.getClassLoader();
        }
    }
}

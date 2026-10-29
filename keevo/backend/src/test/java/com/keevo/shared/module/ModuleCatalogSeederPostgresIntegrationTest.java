package com.keevo.shared.module;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.module.adapter.out.persistence.JdbcModuleDefinitionStore;
import com.keevo.shared.module.application.ModuleCatalogSeeder;
import com.keevo.shared.module.application.ModuleCatalogSeeder.SeedReport;
import com.keevo.shared.module.application.port.ModuleDefinitionPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 13.1 — V29 + the boot seed against PostgreSQL, on a pristine database of the shared container
 * (migrated with the application's Flyway settings): first boot inserts the 10 modules, a second boot changes
 * nothing, a version bump updates the row and keeps {@code installed_at}, an older build never downgrades it,
 * a removed module's row is kept (WARN), and a failure mid-seed leaves the catalogue untouched (one transaction).
 */
class ModuleCatalogSeederPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_module_registry";
    private static final PathMatchingResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();

    private static JdbcTemplate jdbc;
    private static JdbcModuleDefinitionStore store;
    private static DataSourceTransactionManager transactionManager;
    private static ModuleCatalogSeeder seeder;

    private ListAppender<ILoggingEvent> logs;

    @BeforeAll
    static void migrateFreshDatabase() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration")
                .load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcModuleDefinitionStore(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        seeder = new ModuleCatalogSeeder(store, transactionManager, RESOLVER);
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void emptyCatalogueAndCaptureLogs() {
        jdbc.update("DELETE FROM public.module_definitions");
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ModuleCatalogSeeder.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(ModuleCatalogSeeder.class)).detachAppender(logs);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<LoadedModuleManifest> shipped() {
        return ModuleManifestLoader.loadFromClasspath(RESOLVER);
    }

    private static List<LoadedModuleManifest> shippedWith(String id, String from, String to) {
        List<ModuleManifestSource> sources = new ArrayList<>();
        for (LoadedModuleManifest m : shipped()) {
            String json = m.manifest().id().equals(id) ? m.json().replace(from, to) : m.json();
            sources.add(new ModuleManifestSource(m.location(), m.manifest().id(), json.getBytes(StandardCharsets.UTF_8)));
        }
        return ModuleManifestLoader.load(sources);
    }

    private static String version(String id) {
        return jdbc.queryForObject("SELECT version FROM public.module_definitions WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("first boot inserts the 10 modules with every seeded column; second boot changes nothing")
    void firstAndSecondBoot() {
        SeedReport first = seeder.seed(shipped());
        assertThat(first.inserted()).hasSize(10);
        assertThat(first.updated()).isEmpty();

        Map<String, Object> pos = jdbc.queryForMap("SELECT version, display_name, category, requires::text AS requires, "
                + "enhances::text AS enhances, is_free, manifest->>'entitlement' AS entitlement, installed_at "
                + "FROM public.module_definitions WHERE id = 'pos'");
        assertThat(pos).containsEntry("version", "1.0.0").containsEntry("display_name", "Point de vente")
                .containsEntry("category", "business").containsEntry("requires", "[\"core\", \"catalog\", \"inventory\"]")
                .containsEntry("enhances", "[\"crm\", \"reporting\"]").containsEntry("is_free", true)
                .containsEntry("entitlement", "module.pos");
        assertThat(pos.get("installed_at")).isNotNull();
        assertThat(jdbc.queryForList("SELECT id FROM public.module_definitions ORDER BY id", String.class))
                .containsExactly("admin", "catalog", "core", "crm", "inventory", "messaging", "pos", "reporting",
                        "store", "sync");

        List<Map<String, Object>> before = jdbc.queryForList("SELECT *, xmin::text AS row_version FROM public.module_definitions ORDER BY id");
        SeedReport second = seeder.seed(shipped());
        assertThat(second.changedRows()).isZero();
        assertThat(second.unchanged()).hasSize(10);
        assertThat(jdbc.queryForList("SELECT *, xmin::text AS row_version FROM public.module_definitions ORDER BY id"))
                .isEqualTo(before); // xmin unchanged = no row rewritten
    }

    @Test
    @DisplayName("manifest JSONB = the validated manifest with every default applied")
    void manifestCacheHoldsDefaults() {
        List<ModuleManifestSource> sources = new ArrayList<>();
        for (LoadedModuleManifest m : shipped()) {
            String json = m.manifest().id().equals("store")
                    ? "{\"id\":\"store\",\"version\":\"1.0.0\",\"displayName\":\"Boutiques\",\"category\":\"business\","
                      + "\"requires\":[\"core\"],\"typo\":1}"
                    : m.json();
            sources.add(new ModuleManifestSource(m.location(), m.manifest().id(), json.getBytes(StandardCharsets.UTF_8)));
        }
        seeder.seed(ModuleManifestLoader.load(sources));

        Map<String, Object> cached = jdbc.queryForMap("SELECT manifest->>'entitlement' AS entitlement, "
                + "manifest->>'free' AS free, manifest->'extensionPoints'->>'fills' AS fills, "
                + "manifest->>'permissions' AS permissions, manifest ? 'typo' AS has_typo "
                + "FROM public.module_definitions WHERE id = 'store'");
        assertThat(cached).containsEntry("entitlement", "module.store").containsEntry("free", "false")
                .containsEntry("fills", "[]").containsEntry("permissions", "[]").containsEntry("has_typo", false);
    }

    @Test
    @DisplayName("version bump 1.0.0 -> 1.1.0: row updated, installed_at kept; same version + new content updates too")
    void versionBump() {
        seeder.seed(shipped());
        jdbc.update("UPDATE public.module_definitions SET installed_at = '2026-01-01T00:00:00Z' WHERE id = 'catalog'");

        SeedReport report = seeder.seed(shippedWith("catalog", "\"version\":\"1.0.0\"", "\"version\":\"1.1.0\""));
        assertThat(report.updated()).containsExactly("catalog");
        assertThat(report.unchanged()).hasSize(9);

        Map<String, Object> catalog = jdbc.queryForMap("SELECT version, manifest->>'version' AS cached, installed_at "
                + "FROM public.module_definitions WHERE id = 'catalog'");
        assertThat(catalog).containsEntry("version", "1.1.0").containsEntry("cached", "1.1.0");
        assertThat(((java.sql.Timestamp) catalog.get("installed_at")).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z").toInstant());

        SeedReport sameVersion = seeder.seed(shippedWith("crm", "\"displayName\":\"Clients et fournisseurs\"",
                "\"displayName\":\"Clients\""));
        assertThat(sameVersion.updated()).containsExactly("crm");
        assertThat(jdbc.queryForObject("SELECT display_name FROM public.module_definitions WHERE id = 'crm'",
                String.class)).isEqualTo("Clients");
    }

    @Test
    @DisplayName("older build: an older manifest version never downgrades the row (seeder WARN + SQL guard)")
    void noDowngrade() {
        seeder.seed(shippedWith("catalog", "\"version\":\"1.0.0\"", "\"version\":\"1.10.0\""));
        assertThat(version("catalog")).isEqualTo("1.10.0");

        SeedReport report = seeder.seed(shippedWith("catalog", "\"version\":\"1.0.0\"", "\"version\":\"1.9.0\""));
        assertThat(report.skippedOlder()).containsExactly("catalog");
        assertThat(report.changedRows()).isZero();
        assertThat(version("catalog")).isEqualTo("1.10.0"); // numeric, not lexical: 1.9.0 < 1.10.0
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains("'catalog'", "1.9.0", "1.10.0", "not downgraded"));

        // SQL guard on its own (e.g. a concurrent boot that read the versions before the newer one committed)
        LoadedModuleManifest older = shippedWith("catalog", "\"version\":\"1.0.0\"", "\"version\":\"1.2.0\"").stream()
                .filter(m -> m.manifest().id().equals("catalog")).findFirst().orElseThrow();
        assertThat(store.upsert(older)).isEqualTo(ModuleDefinitionPort.UpsertOutcome.UNCHANGED);
        assertThat(version("catalog")).isEqualTo("1.10.0");
    }

    @Test
    @DisplayName("module removed from the classpath: row kept, WARN")
    void removedModuleKept() {
        jdbc.update("INSERT INTO public.module_definitions (id, version, display_name, category, manifest) "
                + "VALUES ('legacy', '0.9.0', 'Ancien module', 'business', '{}'::jsonb)");

        SeedReport report = seeder.seed(shipped());
        assertThat(report.notOnClasspath()).containsExactly("legacy");
        assertThat(version("legacy")).isEqualTo("0.9.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.module_definitions", Integer.class)).isEqualTo(11);
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains("'legacy'", "no manifest", "row kept"));
    }

    @Test
    @DisplayName("failure mid-seed: the whole seed rolls back (one transaction), no half-seeded catalogue")
    void seedIsAtomic() {
        ModuleDefinitionPort failingOnMessaging = new ModuleDefinitionPort() {
            @Override
            public UpsertOutcome upsert(LoadedModuleManifest manifest) {
                if (manifest.manifest().id().equals("messaging")) {
                    throw new IllegalStateException("boom");
                }
                return store.upsert(manifest);
            }

            @Override
            public Map<String, String> findVersions() {
                return store.findVersions();
            }
        };
        ModuleCatalogSeeder failing = new ModuleCatalogSeeder(failingOnMessaging, transactionManager, RESOLVER);

        assertThatThrownBy(() -> failing.seed(shipped())).hasMessage("boom");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.module_definitions", Integer.class)).isZero();
    }

    @Test
    @DisplayName("tenant_modules: created empty, generated id, UNIQUE(tenant_id, module_id), status/tier CHECKs")
    void tenantModulesTable() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_modules", Integer.class)).isZero();
        UUID tenant = UUID.randomUUID();
        String insert = "INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, ?)";
        jdbc.update(insert, tenant, "pos", "ACTIVE", "FREE");
        assertThat(jdbc.queryForObject("SELECT id FROM public.tenant_modules WHERE tenant_id = ?", UUID.class, tenant))
                .isNotNull();
        assertThatThrownBy(() -> jdbc.update(insert, tenant, "pos", "TRIAL", "FREE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, tenant, "crm", "ON", "FREE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, tenant, "crm", "ACTIVE", "GOLD"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' "
                + "AND tablename = 'tenant_modules'", String.class))
                .containsExactlyInAnyOrder("tenant_modules_pkey", "tenant_modules_tenant_module_key");
        String definition = "INSERT INTO public.module_definitions (id, version, display_name, category, manifest) "
                + "VALUES ('x1', ?, 'X', ?, '{}'::jsonb)";
        assertThatThrownBy(() -> jdbc.update(definition, "1.0.0", "addon"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(definition, "1.0", "business"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM public.tenant_modules");
    }
}

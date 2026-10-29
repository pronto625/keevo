package com.keevo.shared.module;

import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.module.adapter.out.persistence.JdbcModuleDefinitionStore;
import com.keevo.shared.module.adapter.out.persistence.JdbcTenantModuleStateStore;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModuleCatalogSeeder;
import com.keevo.shared.module.application.ModuleRegistryService;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 13.2 — {@link ModuleRegistry} against PostgreSQL (fresh database of the shared container, migrated with the
 * application's Flyway settings, catalogue seeded from the shipped manifests): every row of the I/O matrix through
 * the real JDBC adapter, plus the per-request query count.
 */
class ModuleRegistryPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_module_registry_state";
    private static final String TENANT = "kv_reg001";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String DELETED_TENANT = "kv_reg002";
    private static final String[] SHIPPED = {"admin", "catalog", "core", "crm", "inventory", "messaging", "pos",
            "reporting", "store", "sync"};

    private static JdbcTemplate jdbc;
    private static JdbcTenantModuleStateStore store;
    private static ModuleCatalog shippedCatalog;

    private final AtomicInteger queries = new AtomicInteger();
    private ModuleRegistry registry;

    @BeforeAll
    static void migrateAndSeed() {
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
        store = new JdbcTenantModuleStateStore(dataSource);
        shippedCatalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
        new ModuleCatalogSeeder(new JdbcModuleDefinitionStore(dataSource), new DataSourceTransactionManager(dataSource),
                shippedCatalog).afterPropertiesSet();
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), 'KV-REG001', 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')",
                TENANT_ID, TENANT);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), 'KV-REG002', 3, 500, 1, 'FREE', ?, 'DELETED', 'Supprimé')",
                UUID.randomUUID(), DELETED_TENANT);
        // an orphan catalogue row (13.1 keeps rows whose manifest left the classpath)
        jdbc.update("INSERT INTO public.module_definitions (id, version, display_name, category, manifest) "
                + "VALUES ('legacy', '1.0.0', 'Ancien', 'business', '{}'::jsonb)");
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM public.tenant_modules");
        TenantModuleStatePort counting = schema -> {
            queries.incrementAndGet();
            return store.findRows(schema);
        };
        // rule cases run on a test catalogue (free + paid + core + platform), not on the shipped manifests
        registry = new ModuleRegistryService(TestModuleCatalogs.testCatalog(), counting,
                new RequestScopedActiveModulesCache(), Clock.systemUTC());
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void row(String moduleId, String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, 'FREE')",
                TENANT_ID, moduleId, status);
    }

    @Test
    @DisplayName("shipped set: the 10 manifests, all free today (precondition), all active for a tenant without rows")
    void shippedCatalogue() {
        ModuleRegistry shipped = new ModuleRegistryService(shippedCatalog, store, new RequestScopedActiveModulesCache(), Clock.systemUTC());
        assertThat(shipped.installed()).extracting(ModuleManifest::id).containsExactly(SHIPPED);
        assertThat(shipped.installed()).allSatisfy(m -> assertThat(m.free())
                .as("shipped module %s is expected free (V1 parity: no backfill)", m.id()).isTrue());
        assertThat(shipped.activeModules(TENANT)).containsExactly(SHIPPED);
    }

    @Test
    @DisplayName("existing tenant without rows: the free modules are active, the paid one is not")
    void existingTenantNoRows() {
        assertThat(registry.activeModules(TENANT)).containsExactlyElementsOf(TestModuleCatalogs.FREE);
    }

    @Test
    @DisplayName("paid module: off without a row, on with a TRIAL row")
    void paidModule() {
        assertThat(registry.isActive(TENANT, "invoicing")).isFalse();
        row("invoicing", "TRIAL");
        assertThat(registry.isActive(TENANT, "invoicing")).isTrue();
    }

    @Test
    @DisplayName("DELETED tenant = unknown: only core")
    void deletedTenant() {
        assertThat(store.findRows(DELETED_TENANT)).isEmpty();
        assertThat(registry.activeModules(DELETED_TENANT)).containsExactly("core");
        assertThat(registry.isKnownTenant(DELETED_TENANT)).isFalse();
    }

    @Test
    @DisplayName("platform-admin schema: core + platform modules, no query")
    void platformAdmin() {
        assertThat(registry.activeModules("public")).containsExactly("admin", "core");
        assertThat(queries).hasValue(0);
    }

    @Test
    @DisplayName("V30: a module id outside the manifest id syntax is rejected")
    void moduleIdCheck() {
        for (String bad : new String[]{"POS", "pos ", "-pos", "pos--x", "1pos", ""}) {
            assertThatThrownBy(() -> row(bad, "ACTIVE")).as(bad)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("tenant_modules_module_id_check");
        }
        row("multi-store", "ACTIVE");
    }

    @Test
    @DisplayName("pos DISABLED: pos off, the 9 others on")
    void disabledRow() {
        row("pos", "DISABLED");
        assertThat(registry.isActive(TENANT, "pos")).isFalse();
        assertThat(registry.activeModules(TENANT)).hasSize(TestModuleCatalogs.FREE.size() - 1).doesNotContain("pos");
    }

    @Test
    @DisplayName("EXPIRED / AVAILABLE off; TRIAL / ACTIVE on")
    void statuses() {
        row("crm", "EXPIRED");
        row("store", "AVAILABLE");
        row("reporting", "TRIAL");
        row("admin", "ACTIVE");
        assertThat(registry.isActive(TENANT, "crm")).isFalse();
        assertThat(registry.isActive(TENANT, "store")).isFalse();
        assertThat(registry.isActive(TENANT, "reporting")).isTrue();
        assertThat(registry.isActive(TENANT, "admin")).isTrue();
    }

    @Test
    @DisplayName("13.5: expires_at / grace_until / tier are read — ACTIVE past expiry in grace = READ_ONLY")
    void expiryColumnsRead() {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, expires_at, grace_until) "
                + "VALUES (?, 'invoicing', 'ACTIVE', 'PREMIUM', now() - interval '1 hour', now() + interval '7 days')",
                TENANT_ID);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, expires_at, grace_until) "
                + "VALUES (?, 'crm', 'EXPIRED', 'STANDARD', now() - interval '8 days', now() - interval '1 day')",
                TENANT_ID);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, expires_at) "
                + "VALUES (?, 'pos', 'TRIAL', 'STANDARD', now() + interval '1 day')", TENANT_ID);
        assertThat(store.findRows(TENANT)).get().satisfies(rows -> {
            assertThat(rows.get("invoicing").tier()).isEqualTo("PREMIUM");
            assertThat(rows.get("invoicing").expiresAt()).isBefore(java.time.Instant.now());
            assertThat(rows.get("invoicing").graceUntil()).isAfter(java.time.Instant.now());
            assertThat(rows.get("pos").graceUntil()).isNull();
        });
        assertThat(registry.moduleState(TENANT, "invoicing")).isEqualTo(ModuleState.READ_ONLY);
        assertThat(registry.moduleState(TENANT, "crm")).isEqualTo(ModuleState.DISABLED);
        assertThat(registry.moduleState(TENANT, "pos")).isEqualTo(ModuleState.ENABLED);
        assertThat(registry.isActive(TENANT, "invoicing")).isTrue(); // read-only stays active (data visible)
    }

    @Test
    @DisplayName("13.5: TIMESTAMPTZ instants are read exactly, whatever the JVM default zone")
    void expiryInstantsIndependentOfJvmZone() {
        java.time.Instant expires = java.time.Instant.parse("2026-11-02T23:30:00Z");
        java.time.Instant grace = java.time.Instant.parse("2026-11-09T23:30:00Z");
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, expires_at, grace_until) "
                        + "VALUES (?, 'invoicing', 'ACTIVE', 'PREMIUM', ?, ?)", TENANT_ID,
                java.time.OffsetDateTime.ofInstant(expires, java.time.ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(grace, java.time.ZoneOffset.ofHours(-5)));
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati")); // UTC+14
            assertThat(store.findRows(TENANT)).get().satisfies(rows -> {
                assertThat(rows.get("invoicing").expiresAt()).isEqualTo(expires);
                assertThat(rows.get("invoicing").graceUntil()).isEqualTo(grace);
            });
        } finally {
            java.util.TimeZone.setDefault(previous);
        }
    }

    @Test
    @DisplayName("core DISABLED: core still active")
    void coreDisabledStillActive() {
        row("core", "DISABLED");
        assertThat(registry.isActive(TENANT, "core")).isTrue();
    }

    @Test
    @DisplayName("orphan legacy ACTIVE: not active, not installed")
    void orphanRow() {
        row("legacy", "ACTIVE");
        assertThat(store.findRows(TENANT)).get().satisfies(rows ->
                assertThat(rows.get("legacy").status()).isEqualTo(TenantModuleStatus.ACTIVE));
        assertThat(registry.isActive(TENANT, "legacy")).isFalse();
        assertThat(registry.activeModules(TENANT)).doesNotContain("legacy");
        assertThat(registry.installed()).extracting(ModuleManifest::id).doesNotContain("legacy");
        assertThat(registry.definition("legacy")).isEmpty();
    }

    @Test
    @DisplayName("unknown module id: false")
    void unknownModule() {
        assertThat(registry.isActive(TENANT, "ghost")).isFalse();
    }

    @Test
    @DisplayName("unknown tenant: only core; the adapter tells it apart from a tenant without rows")
    void unknownTenant() {
        assertThat(store.findRows("kv_nobody")).isEmpty();
        assertThat(store.findRows(TENANT)).contains(Map.of());
        assertThat(registry.activeModules("kv_nobody")).containsExactly("core");
        assertThat(registry.isKnownTenant("kv_nobody")).isFalse();
        assertThat(registry.isKnownTenant(TENANT)).isTrue();
    }

    @Test
    @DisplayName("a row of another tenant does not leak")
    void otherTenantRows() {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, 'pos', 'DISABLED', 'FREE')",
                UUID.randomUUID());
        assertThat(registry.isActive(TENANT, "pos")).isTrue();
    }

    @Test
    @DisplayName("invalid schema strings: IllegalArgumentException, no query (service and adapter)")
    void invalidSchema() {
        for (String schema : new String[]{"KV_1", "PUBLIC"}) {
            assertThatThrownBy(() -> registry.activeModules(schema)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.findRows(schema)).isInstanceOf(IllegalArgumentException.class);
        }
        // the adapter never accepts the platform schema (the service answers it without a query)
        assertThatThrownBy(() -> store.findRows("public")).isInstanceOf(IllegalArgumentException.class);
        assertThat(queries).hasValue(0);
    }

    @Test
    @DisplayName("same request, 5 calls: 1 query")
    void oneQueryPerRequest() {
        row("pos", "DISABLED");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        registry.activeModules(TENANT);
        registry.isActive(TENANT, "pos");
        registry.isActive(TENANT, "crm");
        registry.isActive(TENANT, "core");
        registry.activeModules(TENANT);
        assertThat(queries).hasValue(1);
    }

    @Test
    @DisplayName("scheduler thread (no request bound): every call reads the database and sees a change at once")
    void schedulerThreadReadsEachCall() {
        boolean[] seen = CompletableFuture.supplyAsync(() -> {
            boolean before = registry.isActive(TENANT, "pos");
            row("pos", "DISABLED");
            boolean after = registry.isActive(TENANT, "pos");
            registry.activeModules(TENANT);
            return new boolean[]{before, after};
        }).join();
        assertThat(seen).containsExactly(true, false);
        assertThat(queries).hasValue(3);
    }
}

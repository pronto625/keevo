package com.keevo.shared.module;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.module.ModuleLifecycle.ActivationRequest;
import com.keevo.shared.module.ModuleLifecycle.ActivationResult;
import com.keevo.shared.module.ModuleLifecycle.Actor;
import com.keevo.shared.module.adapter.out.persistence.FlywayModuleMigrations;
import com.keevo.shared.module.adapter.out.persistence.JdbcTenantModuleLifecycleStore;
import com.keevo.shared.module.adapter.out.persistence.JdbcTenantModuleStateStore;
import com.keevo.shared.module.application.DependencyResolverService;
import com.keevo.shared.module.application.EntitlementActivationEligibility;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModuleLifecycleService;
import com.keevo.shared.module.application.ModuleProvisionerService;
import com.keevo.shared.module.application.ModuleRegistryService;
import com.keevo.shared.module.application.port.ActiveModulesCache;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story 13.8a — {@link ModuleLifecycle} end to end on PostgreSQL with the test catalogue
 * {@link TestModuleCatalogs#lifecycleCatalog()} (a dependency chain, a migrating paid module, a broken migration):
 * a fresh database of the shared container migrated with the public Flyway, real JDBC stores, real Flyway module
 * migrations, the service behind a Spring transaction proxy ({@code @Transactional} honoured) and a
 * {@code pg_advisory_xact_lock} lock port — the production lock adapter's SQL. Every row of the spec's I/O matrix
 * that the test catalogue covers; the shipped-catalogue rows (HTTP, 403, sync push) are in
 * {@code com.keevo.admin.module.ModuleAdminFullContextTest}.
 */
class ModuleLifecyclePostgresIntegrationTest {

    private static final String TEST_DB = "keevo_module_lifecycle";
    private static final String SCHEMA = "kv_lcya01";
    private static final String CODE = "KV-LCYA01";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DELETED_TENANT = UUID.randomUUID();
    private static final Actor ADMIN = new Actor(UUID.randomUUID(), true);

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private final List<AuditPort.AuditEntry> audits = new CopyOnWriteArrayList<>();
    /** Per audit write: whether the activated/deactivated row was already visible to another connection. */
    private final List<Boolean> auditSawCommittedRow = new CopyOnWriteArrayList<>();
    private ModuleLifecycle lifecycle;
    private ModuleRegistryService registry;
    private DataSourceTransactionManager transactionManager;

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
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) "
                + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')", TENANT, CODE, SCHEMA);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) "
                + "VALUES (?, now(), now(), 'KV-LCYD02', 3, 500, 1, 'FREE', 'kv_lcyd02', 'DELETED', 'Supprimé')",
                DELETED_TENANT);
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void freshSchema() {
        jdbc.update("DELETE FROM public.tenant_modules"); // test database only
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE"); // test database only
        jdbc.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        jdbc.execute("CREATE TABLE \"" + SCHEMA + "\".existing_data (id INT PRIMARY KEY, label TEXT)");
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".existing_data VALUES (1, 'avant')");
        lifecycle = build(new RequestScopedActiveModulesCache());
    }

    /** The lifecycle behind a Spring transaction proxy, its registry on {@code cache}. */
    private ModuleLifecycle build(ActiveModulesCache cache, KeevoModule... modules) {
        ModuleCatalog catalog = TestModuleCatalogs.lifecycleCatalog();
        JdbcTenantModuleStateStore states = new JdbcTenantModuleStateStore(dataSource);
        registry = new ModuleRegistryService(catalog, states, cache, Clock.systemUTC());
        ModuleProvisionerService provisioner = new ModuleProvisionerService(catalog, registry, states, states,
                new FlywayModuleMigrations(dataSource));
        DependencyResolverService resolver = new DependencyResolverService(catalog, registry,
                new EntitlementActivationEligibility());
        // the production adapter's statement, on the transaction's connection
        TransactionLockPort locks = key -> jdbc.queryForObject(
                "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))", Integer.class, key);
        AuditPort audit = new AuditPort() {
            @Override
            public void record(AuditEntry entry) {
                // a fresh physical connection (DriverManagerDataSource): sees committed rows only
                auditSawCommittedRow.add(jdbc.queryForObject(
                        "SELECT count(*) FROM public.tenant_modules WHERE id = ?", Integer.class,
                        entry.entityId()) == 1);
                audits.add(entry);
            }

            @Override
            public AuditSlice search(AuditQuery query, String cursor, Integer page, int size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<AuditEntryRecord> findById(UUID id) {
                throw new UnsupportedOperationException();
            }
        };
        ModuleLifecycleService target = new ModuleLifecycleService(catalog, registry, resolver, provisioner,
                new JdbcTenantModuleLifecycleStore(dataSource), locks, audit, Clock.systemUTC(),
                com.keevo.shared.module.application.KeevoModuleDirectory.of(catalog, List.of(modules)));
        transactionManager = new DataSourceTransactionManager(dataSource);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addInterface(ModuleLifecycle.class);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (ModuleLifecycle) proxy.getProxy();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void insertRow(String moduleId, String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, 'FREE')",
                TENANT, moduleId, status);
    }

    private static Map<String, Object> row(String moduleId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, tier, activated_at, expires_at, "
                + "grace_until, disabled_at, provisioned_version FROM public.tenant_modules "
                + "WHERE tenant_id = ? AND module_id = ?", TENANT, moduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((java.sql.Timestamp) value).toInstant();
    }

    private static List<String> tables() {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? "
                + "ORDER BY table_name", String.class, SCHEMA);
    }

    private static String code(Runnable call) {
        try {
            call.run();
            return null;
        } catch (DomainException e) {
            return e.getDomainCode();
        }
    }

    // ── matrix ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("free module with inactive requires: rows lcbase then lctop ACTIVE, both provisioned, [base, top]")
    void cascade() {
        insertRow("lcbase", "DISABLED");
        insertRow("lctop", "DISABLED");

        ActivationResult result = lifecycle.activate(TENANT, "lctop", ActivationRequest.active(), ADMIN);

        assertThat(result.activated()).extracting(ModuleLifecycle.ModuleRef::moduleId)
                .containsExactly("lcbase", "lctop");
        assertThat(result.activated()).extracting(ModuleLifecycle.ModuleRef::displayName)
                .containsExactly("Base de test", "Module supérieur");
        assertThat(result.status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(result.provisionedVersion()).isEqualTo("1");
        for (String id : List.of("lcbase", "lctop")) {
            assertThat(row(id)).containsEntry("status", "ACTIVE").containsEntry("provisioned_version", "1")
                    .containsEntry("disabled_at", null).containsEntry("expires_at", null);
            assertThat(row(id).get("activated_at")).isNotNull();
        }
        assertThat(tables()).contains("lcbase_items", "lctop_items", "flyway_history_lcbase",
                "flyway_history_lctop", "existing_data");
        assertThat(audits).extracting(AuditPort.AuditEntry::action).containsOnly("MODULE_ACTIVATED");
        assertThat(audits).extracting(AuditPort.AuditEntry::entityLabel)
                .containsExactly("Base de test", "Module supérieur");
        assertThat(jdbc.queryForObject("SELECT label FROM \"" + SCHEMA + "\".existing_data", String.class))
                .isEqualTo("avant");
    }

    @Test
    @DisplayName("paid module granted by the super-admin, expiresAt +30 d: ACTIVE, grace_until = +37 d, provisioned")
    void paidModule() {
        Instant expiry = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS);

        ActivationResult result = lifecycle.activate(TENANT, "lcpaid",
                new ActivationRequest(TenantModuleStatus.ACTIVE, expiry), ADMIN);

        Map<String, Object> row = row("lcpaid");
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("tier", "STANDARD")
                .containsEntry("provisioned_version", "1");
        assertThat(instant(row.get("expires_at"))).isEqualTo(expiry);
        assertThat(instant(row.get("grace_until"))).isEqualTo(expiry.plus(Duration.ofDays(7)));
        assertThat(result.expiresAt()).isEqualTo(expiry);
        assertThat(tables()).contains("lcpaid_items");
        assertThat(audits.get(0).valueAfter()).contains("Module payant", "finAbonnement")
                .doesNotContain(TENANT.toString(), SCHEMA, "lcpaid", CODE, "entreprise");
    }

    @Test
    @DisplayName("already active: 200 idempotent, nothing written, no audit")
    void alreadyActive() {
        lifecycle.activate(TENANT, "lctop", ActivationRequest.active(), ADMIN);
        Map<String, Object> before = row("lctop");
        audits.clear();

        ActivationResult again = lifecycle.activate(TENANT, "lctop", ActivationRequest.active(), ADMIN);

        assertThat(again.activated()).isEmpty();
        assertThat(again.provisionedVersion()).isEqualTo("1");
        assertThat(row("lctop")).isEqualTo(before);
        assertThat(audits).isEmpty();
        // the first (idempotent) call also provisioned lctop's dependency active by the free rule
        assertThat(tables()).contains("lcbase_items", "lctop_items");
    }

    @Test
    @DisplayName("repair plan, free target without a row: only the dependency is written, result ACTIVE/null/null")
    void repairPlanTargetWithoutRow() {
        insertRow("lcbase", "DISABLED");

        ActivationResult result = lifecycle.activate(TENANT, "lctop", ActivationRequest.active(), ADMIN);

        assertThat(result.activated()).extracting(ModuleLifecycle.ModuleRef::moduleId).containsExactly("lcbase");
        assertThat(result.status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(row("lcbase")).containsEntry("status", "ACTIVE");
        assertThat(tables()).contains("lcbase_items", "lctop_items");
    }

    @Test
    @DisplayName("renewal of an active paid module keeps activated_at; the end date and grace move")
    void renewalKeepsActivatedAt() {
        Instant expiry = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS);
        lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE, expiry), ADMIN);
        Object activatedAt = row("lcpaid").get("activated_at");

        lifecycle.activate(TENANT, "lcpaid", new ActivationRequest(TenantModuleStatus.ACTIVE,
                expiry.plus(Duration.ofDays(30))), ADMIN);

        assertThat(row("lcpaid").get("activated_at")).isEqualTo(activatedAt);
        assertThat(instant(row("lcpaid").get("expires_at"))).isEqualTo(expiry.plus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("audit after commit: each entry written once the row is committed; none when the transaction rolls back")
    void auditAfterCommitOnly() {
        insertRow("lcbase", "DISABLED");
        lifecycle.activate(TENANT, "lcbase", null, ADMIN);
        assertThat(audits).hasSize(1);
        assertThat(auditSawCommittedRow).containsExactly(true);

        audits.clear();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lifecycle.deactivate(TENANT, "lcplain", ADMIN); // joins the enclosing transaction
            status.setRollbackOnly();
        });
        assertThat(audits).isEmpty();
        assertThat(row("lcplain")).isNull();
    }

    @Test
    @DisplayName("after-completion eviction: a cache filled inside a rolled-back transaction is dropped at its end")
    void cacheEvictedAfterCompletion() {
        Map<String, ActiveModulesCache.Entry> entries = new ConcurrentHashMap<>();
        ActiveModulesCache cache = new ActiveModulesCache() {
            @Override
            public java.util.Optional<Entry> get(String tenantSchema) {
                return java.util.Optional.ofNullable(entries.get(tenantSchema));
            }

            @Override
            public void put(String tenantSchema, Entry entry) {
                entries.put(tenantSchema, entry);
            }

            @Override
            public void evict(String tenantSchema) {
                entries.remove(tenantSchema);
            }
        };
        ModuleLifecycle cached = build(cache);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            cached.deactivate(TENANT, "lcplain", ADMIN);
            assertThat(registry.isActive(SCHEMA, "lcplain")).isFalse(); // caches the uncommitted DISABLED row
            assertThat(entries).containsKey(SCHEMA);
            status.setRollbackOnly();
        });

        assertThat(entries).doesNotContainKey(SCHEMA); // else the request would keep a row that never existed
        assertThat(registry.isActive(SCHEMA, "lcplain")).isTrue();
    }

    @Test
    @DisplayName("unknown / DELETED tenant → TENANT_NOT_FOUND; unknown module 'ghost' → MODULE_NOT_FOUND; nothing written")
    void unknownTenantAndModule() {
        assertThat(code(() -> lifecycle.activate(UUID.randomUUID(), "lctop", null, ADMIN)))
                .isEqualTo("TENANT_NOT_FOUND");
        assertThat(code(() -> lifecycle.activate(DELETED_TENANT, "lctop", null, ADMIN)))
                .isEqualTo("TENANT_NOT_FOUND");
        assertThat(code(() -> lifecycle.activate(TENANT, "ghost", null, ADMIN))).isEqualTo("MODULE_NOT_FOUND");
        assertThat(code(() -> lifecycle.deactivate(TENANT, "ghost", ADMIN))).isEqualTo("MODULE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenant_modules", Integer.class)).isZero();
    }

    @Test
    @DisplayName("provisioning fails (broken migration): MODULE_PROVISIONING_FAILED, every row rolled back, no audit")
    void provisioningFails() {
        insertRow("lcbase", "DISABLED");
        insertRow("lcbroken", "DISABLED");
        Map<String, Object> baseBefore = row("lcbase");
        Map<String, Object> brokenBefore = row("lcbroken");

        Throwable thrown = catchThrowable(() -> lifecycle.activate(TENANT, "lcbroken", null, ADMIN));

        assertThat(thrown).isInstanceOf(DomainException.class);
        DomainException e = (DomainException) thrown;
        assertThat(e.getDomainCode()).isEqualTo("MODULE_PROVISIONING_FAILED");
        assertThat(e.getMessage()).contains("Module cassé", "n'a pas été activé")
                .doesNotContain(SCHEMA, "CREATE", "42601", "rien n'a été modifié");
        // the dependency's row written before the failure is rolled back too: module stays as before…
        assertThat(row("lcbase")).isEqualTo(baseBefore);
        // …but its tables, created by Flyway on its own connection, stay committed (accepted: a retry reuses them)
        assertThat(tables()).contains("lcbase_items", "flyway_history_lcbase");
        assertThat(row("lcbroken")).isEqualTo(brokenBefore);
        assertThat(audits).isEmpty();
        assertThat(tables()).doesNotContain("lcbroken_items");
    }

    @Test
    @DisplayName("deactivate a module with data: row DISABLED + disabled_at, tables and data kept, audit")
    void deactivateKeepsData() {
        lifecycle.activate(TENANT, "lctop", null, ADMIN);
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".lctop_items VALUES (?)", UUID.randomUUID());
        audits.clear();

        ModuleLifecycle.DeactivationResult result = lifecycle.deactivate(TENANT, "lctop", ADMIN);

        assertThat(result.status()).isEqualTo(TenantModuleStatus.DISABLED);
        assertThat(result.displayName()).isEqualTo("Module supérieur");
        Map<String, Object> row = row("lctop");
        assertThat(row).containsEntry("status", "DISABLED").containsEntry("provisioned_version", "1");
        assertThat(row.get("disabled_at")).isNotNull();
        assertThat(row.get("activated_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + SCHEMA + "\".lctop_items", Integer.class))
                .isEqualTo(1);
        assertThat(audits).extracting(AuditPort.AuditEntry::action).containsExactly("MODULE_DEACTIVATED");
        assertThat(lifecycle.deactivate(TENANT, "lctop", ADMIN).status()).isEqualTo(TenantModuleStatus.DISABLED);
        assertThat(audits).hasSize(1); // already disabled: idempotent

        // reactivation: same row, data reappears, disabled_at cleared
        lifecycle.activate(TENANT, "lctop", null, ADMIN);
        assertThat(row("lctop")).containsEntry("status", "ACTIVE").containsEntry("disabled_at", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + SCHEMA + "\".lctop_items", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("deactivate a free module without a row: a DISABLED row is inserted (never a delete)")
    void deactivateWithoutRow() {
        lifecycle.deactivate(TENANT, "lcplain", ADMIN);

        assertThat(row("lcplain")).containsEntry("status", "DISABLED").containsEntry("tier", "FREE");
        assertThat(row("lcplain").get("disabled_at")).isNotNull();
    }

    @Test
    @DisplayName("deactivate with active dependents → 409 MODULE_HAS_DEPENDENTS listing them; core → CORE_MODULE_REQUIRED")
    void deactivationRefused() {
        Throwable thrown = catchThrowable(() -> lifecycle.deactivate(TENANT, "lcbase", ADMIN));

        DomainException e = (DomainException) thrown;
        assertThat(e.getDomainCode()).isEqualTo("MODULE_HAS_DEPENDENTS");
        assertThat(e.getDetails().get("modules")).isEqualTo(List.of("Module cassé", "Module supérieur"));
        assertThat(code(() -> lifecycle.deactivate(TENANT, "core", ADMIN))).isEqualTo("CORE_MODULE_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenant_modules", Integer.class)).isZero();
    }

    @Test
    @DisplayName("two concurrent activations: serialised by the lock — one activates [base, top], the other is idempotent")
    void concurrentActivations() throws Exception {
        insertRow("lcbase", "DISABLED");
        insertRow("lctop", "DISABLED");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ActivationResult>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return lifecycle.activate(TENANT, "lctop", null, ADMIN);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return lifecycle.activate(TENANT, "lctop", null, ADMIN);
                    }));
            start.countDown();
            List<Integer> sizes = List.of(futures.get(0).get().activated().size(),
                    futures.get(1).get().activated().size());
            assertThat(sizes).containsExactlyInAnyOrder(2, 0);
        } finally {
            pool.shutdownNow();
        }
        assertThat(row("lcbase")).containsEntry("status", "ACTIVE").containsEntry("provisioned_version", "1");
        assertThat(row("lctop")).containsEntry("status", "ACTIVE").containsEntry("provisioned_version", "1");
        assertThat(audits).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + SCHEMA + "\".flyway_history_lctop "
                + "WHERE version = '1'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("tenant view: state + stored status/dates/version per module; expired paid module in grace = READ_ONLY")
    void tenantView() {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, expires_at, grace_until) "
                + "VALUES (?, 'lcpaid', 'ACTIVE', 'STANDARD', ?, ?)", TENANT,
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(6));

        List<ModuleLifecycle.TenantModuleView> views = lifecycle.tenantModules(TENANT);

        ModuleLifecycle.TenantModuleView paid = views.stream().filter(v -> v.moduleId().equals("lcpaid"))
                .findFirst().orElseThrow();
        assertThat(paid.state()).isEqualTo(ModuleState.READ_ONLY);
        assertThat(paid.status()).isEqualTo(TenantModuleStatus.ACTIVE);
        assertThat(paid.graceUntil()).isNotNull();
        assertThat(views).extracting(ModuleLifecycle.TenantModuleView::moduleId).doesNotContain("lcadmin");
    }

    // ── Story 13.9 — KeevoModule hooks inside the lifecycle transaction ───────────────────────────────

    /** A test module whose hooks throw (activation, deactivation or both), counting its calls. */
    static final class ThrowingHooks implements KeevoModule {
        private final String id;
        private final boolean onActivate;
        private final boolean onDeactivate;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        ThrowingHooks(String id, boolean onActivate, boolean onDeactivate) {
            this.id = id;
            this.onActivate = onActivate;
            this.onDeactivate = onDeactivate;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void onActivate(ModuleTenantContext ctx) {
            calls.incrementAndGet();
            if (onActivate) {
                throw new IllegalStateException("seed failed");
            }
        }

        @Override
        public void onDeactivate(ModuleTenantContext ctx) {
            calls.incrementAndGet();
            if (onDeactivate) {
                throw new IllegalStateException("cleanup failed");
            }
        }
    }

    @Test
    @DisplayName("13.9 — onActivate throws: MODULE_ACTIVATION_FAILED, every row of the cascade rolled back, no audit")
    void activationHookThrowsRollsBack() {
        ThrowingHooks top = new ThrowingHooks("lctop", true, false);
        lifecycle = build(new RequestScopedActiveModulesCache(), top);
        insertRow("lcbase", "DISABLED");
        insertRow("lctop", "DISABLED");
        Map<String, Object> baseBefore = row("lcbase");
        Map<String, Object> topBefore = row("lctop");

        assertThat(code(() -> lifecycle.activate(TENANT, "lctop", null, ADMIN))).isEqualTo("MODULE_ACTIVATION_FAILED");

        assertThat(top.calls).hasValue(1);
        assertThat(row("lcbase")).isEqualTo(baseBefore);
        assertThat(row("lctop")).isEqualTo(topBefore);
        assertThat(audits).isEmpty();
    }

    @Test
    @DisplayName("13.9 — onDeactivate throws: MODULE_DEACTIVATION_FAILED, the row stays ACTIVE, no audit")
    void deactivationHookThrowsRollsBack() {
        ThrowingHooks top = new ThrowingHooks("lctop", false, true);
        lifecycle = build(new RequestScopedActiveModulesCache(), top);
        insertRow("lctop", "DISABLED"); // a free module without row is already active: no activation to hook
        lifecycle.activate(TENANT, "lctop", null, ADMIN);
        audits.clear();

        assertThat(code(() -> lifecycle.deactivate(TENANT, "lctop", ADMIN))).isEqualTo("MODULE_DEACTIVATION_FAILED");

        assertThat(top.calls).hasValue(2); // onActivate (ok), then onDeactivate (throws)
        assertThat(row("lctop")).containsEntry("status", "ACTIVE").containsEntry("disabled_at", null);
        assertThat(audits).isEmpty();
    }

    @Test
    @DisplayName("13.9 — hooks once per change: activate → onActivate, re-activate → none, deactivate → onDeactivate")
    void hooksOnce() {
        ThrowingHooks top = new ThrowingHooks("lctop", false, false);
        lifecycle = build(new RequestScopedActiveModulesCache(), top);
        insertRow("lctop", "DISABLED");
        lifecycle.activate(TENANT, "lctop", null, ADMIN);
        lifecycle.activate(TENANT, "lctop", null, ADMIN);
        assertThat(top.calls).hasValue(1);
        lifecycle.deactivate(TENANT, "lctop", ADMIN);
        lifecycle.deactivate(TENANT, "lctop", ADMIN);
        assertThat(top.calls).hasValue(2);
        assertThat(row("lctop")).containsEntry("status", "DISABLED");
    }
}

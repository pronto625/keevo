package com.keevo.shared.module;

import com.keevo.crm.module.CrmModule;
import com.keevo.identity.api.PlanType;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.module.ModuleBackfill.BackfillReport;
import com.keevo.shared.module.adapter.out.persistence.FlywayModuleMigrations;
import com.keevo.shared.module.adapter.out.persistence.JdbcPlanModuleRowStore;
import com.keevo.shared.module.adapter.out.persistence.JdbcTenantModuleStateStore;
import com.keevo.shared.module.application.KeevoModuleDirectory;
import com.keevo.shared.module.application.ModuleBackfillService;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModuleProvisionerService;
import com.keevo.shared.module.application.ModuleRegistryService;
import com.keevo.shared.module.application.PlanModuleRowSyncService;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import com.keevo.subscription.plan.adapter.out.persistence.impl.JdbcPlanChangeRecorder;
import com.keevo.subscription.plan.application.service.ChangeTenantPlanService;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanCommand;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 16.5 — the {@code tenant_modules} backfill end to end on PostgreSQL with the shipped catalogue: a fresh
 * database migrated with the public Flyway (V32 back-labelling checked on rows written before it), real tenant
 * schemas ({@link TenantSchemaProvisioner}), real JDBC stores, real module migrations (foundation modules — 16.12,
 * invoicing), the row sync behind a Spring transaction proxy and the production ShedLock provider. Every row of the
 * spec's I/O matrix: trial, expired not yet downgraded, downgraded in grace, plain Free, super-admin row, unreadable
 * plan, missing schema, platform tenant, dry run, re-run, plan change after the backfill (and its rollback),
 * concurrent run.
 */
class ModuleBackfillPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_module_backfill";
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Duration GRACE = Duration.ofDays(7);
    private static final List<String> PAID = List.of("advanced-reports", "alerts", "counting", "exports",
            "invoicing", "multi-store", "profitability", "report-config");

    private static final Tenant TRIAL = new Tenant("kv_bftr01", "KV-BFTR01");
    private static final Tenant EXPIRED = new Tenant("kv_bfex02", "KV-BFEX02");
    private static final Tenant GRACED = new Tenant("kv_bfgr03", "KV-BFGR03");
    private static final Tenant FREE = new Tenant("kv_bffr04", "KV-BFFR04");
    private static final Tenant ADMIN_ROW = new Tenant("kv_bfad05", "KV-BFAD05");
    private static final Tenant BROKEN = new Tenant("kv_bfbr06", "KV-BFBR06");
    private static final Tenant NO_SCHEMA = new Tenant("kv_bfns07", "KV-BFNS07");
    private static final Tenant NO_SUBSCRIPTIONS = new Tenant("kv_bfnt08", "KV-BFNT08");
    private static final List<Tenant> WITH_SCHEMA = List.of(TRIAL, EXPIRED, GRACED, FREE, ADMIN_ROW, BROKEN,
            NO_SUBSCRIPTIONS);

    private static final Instant TRIAL_END = T0.plus(Duration.ofDays(40));
    private static final Instant EXPIRED_END = T0.minus(Duration.ofDays(2));
    private static final Instant DOWNGRADED_AT = T0.minus(Duration.ofDays(3));
    private static final Instant ADMIN_END = Instant.parse("2027-06-30T22:59:59Z");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static Map<String, String> labelledOrigins;

    private ModuleCatalog catalog;
    private DataSourceTransactionManager transactionManager;
    private LockProvider lockProvider;

    private record Tenant(String schema, String code, UUID id) {
        Tenant(String schema, String code) {
            this(schema, code, UUID.nameUUIDFromBytes(code.getBytes()));
        }
    }

    @BeforeAll
    static void migratePublic() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        dataSource = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(dataSource);
        // V32 back-labelling: rows written before it (super-admin paid row, provisioner free row)
        flyway(url).target("31").load().migrate();
        UUID labelled = UUID.randomUUID();
        jdbc.update("INSERT INTO public.module_definitions (id, version, display_name, category, is_free, manifest) "
                + "VALUES ('invoicing', '1.0.0', 'Facturation', 'premium', false, '{}'), "
                + "('crm', '1.0.0', 'Clients et fournisseurs', 'business', true, '{}')");
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES "
                + "(?, 'invoicing', 'ACTIVE', 'STANDARD'), (?, 'crm', 'ACTIVE', 'FREE')", labelled, labelled);
        flyway(url).load().migrate();
        labelledOrigins = new LinkedHashMap<>();
        jdbc.query("SELECT module_id, origin FROM public.tenant_modules WHERE tenant_id = ?",
                rs -> { labelledOrigins.put(rs.getString(1), rs.getString(2)); }, labelled);
        jdbc.update("DELETE FROM public.tenant_modules");

        for (Tenant t : List.of(TRIAL, EXPIRED, GRACED, FREE, ADMIN_ROW, BROKEN, NO_SCHEMA, NO_SUBSCRIPTIONS)) {
            jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                    + "max_stores, plan_type, schema_name, status, name) "
                    + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')", t.id(), t.code(), t.schema());
        }
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) VALUES ('00000000-0000-0000-0000-000000000000', "
                + "now(), now(), 'KV-ADMIN', 1, 1, 1, 'FREE', 'public', 'ACTIVE', 'Keevo')");
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration");
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void freshTenants() {
        jdbc.update("DELETE FROM public.tenant_modules"); // test database only
        jdbc.update("DELETE FROM public.tenant_plan_events");
        jdbc.update("DELETE FROM public.shedlock");
        TenantSchemaProvisioner schemas = new TenantSchemaProvisioner(dataSource);
        for (Tenant t : WITH_SCHEMA) {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + t.schema() + "\" CASCADE"); // test database only
            schemas.provision(t.id().toString(), t.schema()); // seeds a PREMIUM_TRIAL subscription
        }
        subscription(TRIAL, "PREMIUM_TRIAL", "ACTIVE", TRIAL_END);
        subscription(EXPIRED, "PREMIUM_TRIAL", "ACTIVE", EXPIRED_END);
        subscription(GRACED, "FREE", "ACTIVE", null);
        planEvent(GRACED, "PREMIUM_TRIAL", "FREE", "EXPIRY", DOWNGRADED_AT);
        subscription(FREE, "FREE", "ACTIVE", null);
        planEvent(FREE, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", T0.minus(Duration.ofDays(1)));
        subscription(ADMIN_ROW, "FREE", "ACTIVE", null);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, activated_at, expires_at, "
                + "grace_until, origin) VALUES (?, 'invoicing', 'ACTIVE', 'STANDARD', ?, ?, ?, 'ADMIN')",
                ADMIN_ROW.id(), ts(T0.minus(Duration.ofDays(10))), ts(ADMIN_END), ts(ADMIN_END.plus(GRACE)));
        // the plan of this tenant cannot be read (a column is missing): paid modules are granted by the rule
        jdbc.execute("ALTER TABLE \"" + BROKEN.schema() + "\".subscriptions DROP COLUMN status");
        // no subscriptions table at all: no rows can be derived either (reported, nothing written)
        jdbc.execute("DROP TABLE \"" + NO_SUBSCRIPTIONS.schema() + "\".subscriptions");

        catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
        transactionManager = new DataSourceTransactionManager(dataSource);
        lockProvider = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).withTableName("public.shedlock").usingDbTime().build());
    }

    // ── wiring ────────────────────────────────────────────────────────────────

    private ModuleRegistryService registry(Instant at) {
        return new ModuleRegistryService(catalog, new JdbcTenantModuleStateStore(dataSource),
                new RequestScopedActiveModulesCache(), Clock.fixed(at, ZoneOffset.UTC));
    }

    private PlanModuleRowSync rowSync(ModuleRegistry registry) {
        PlanModuleRowSyncService target = new PlanModuleRowSyncService(catalog,
                new JdbcPlanModuleRowStore(dataSource), registry, Clock.fixed(T0, ZoneOffset.UTC));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addInterface(PlanModuleRowSync.class);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (PlanModuleRowSync) proxy.getProxy();
    }

    private ModuleBackfill backfill() {
        ModuleRegistryService registry = registry(T0);
        JdbcTenantModuleStateStore states = new JdbcTenantModuleStateStore(dataSource);
        FlywayModuleMigrations migrations = new FlywayModuleMigrations(dataSource);
        ModuleProvisionerService provisioner = new ModuleProvisionerService(catalog, registry, states, states,
                migrations, KeevoModuleDirectory.of(catalog, List.of(new CrmModule(null), new InvoicingModule())),
                TransactionOperations.withoutTransaction());
        return new ModuleBackfillService(catalog, registry, rowSync(registry), new JdbcPlanModuleRowStore(dataSource),
                provisioner, migrations, lockProvider, Clock.fixed(T0, ZoneOffset.UTC));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static void subscription(Tenant t, String plan, String status, Instant expiresAt) {
        jdbc.update("UPDATE \"" + t.schema() + "\".subscriptions SET plan_type = ?, status = ?, expires_at = ?",
                plan, status, ts(expiresAt));
    }

    private static void planEvent(Tenant t, String from, String to, String cause, Instant at) {
        jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, occurred_at) "
                + "VALUES (?, ?, ?, ?, ?)", t.id(), from, to, cause, ts(at));
    }

    private Map<String, Map<String, ModuleState>> states(Instant at) {
        ModuleRegistryService registry = registry(at);
        Map<String, Map<String, ModuleState>> all = new LinkedHashMap<>();
        for (Tenant t : List.of(TRIAL, EXPIRED, GRACED, FREE, ADMIN_ROW, BROKEN, NO_SCHEMA, NO_SUBSCRIPTIONS)) {
            all.put(t.code(), registry.moduleStates(t.schema()));
        }
        return all;
    }

    /** module id → "STATUS/TIER/ORIGIN/expires/grace" of the tenant's rows. */
    private static Map<String, String> rows(Tenant t) {
        Map<String, String> rows = new LinkedHashMap<>();
        jdbc.query("SELECT module_id, status, tier, origin, expires_at, grace_until FROM public.tenant_modules "
                + "WHERE tenant_id = ? ORDER BY module_id", rs -> {
            rows.put(rs.getString(1), rs.getString(2) + "/" + rs.getString(3) + "/" + rs.getString(4) + "/"
                    + instant(rs.getTimestamp(5)) + "/" + instant(rs.getTimestamp(6)));
        }, t.id());
        return rows;
    }

    private static Map<String, Object> fullRow(Tenant t, String moduleId) {
        return jdbc.queryForMap("SELECT * FROM public.tenant_modules WHERE tenant_id = ? AND module_id = ?",
                t.id(), moduleId);
    }

    private static String provisionedVersion(Tenant t, String moduleId) {
        List<String> found = jdbc.queryForList("SELECT provisioned_version FROM public.tenant_modules "
                + "WHERE tenant_id = ? AND module_id = ?", String.class, t.id(), moduleId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String row(String status, String tier, String origin, Instant expires, Instant grace) {
        return status + "/" + tier + "/" + origin + "/" + expires + "/" + grace;
    }

    private static long dataRows() {
        long total = 0;
        for (Tenant t : WITH_SCHEMA) {
            for (String table : List.of("clients", "suppliers", "sales")) {
                total += jdbc.queryForObject("SELECT count(*) FROM \"" + t.schema() + "\"." + table, Long.class);
            }
        }
        return total;
    }

    private static int historyTables() {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_name LIKE 'flyway_history_%' AND table_schema LIKE 'kv_bf%'", Integer.class);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V32: rows written before it are labelled — paid module ADMIN, free module SYSTEM")
    void v32LabelsExistingRows() {
        assertThat(labelledOrigins).containsEntry("invoicing", "ADMIN").containsEntry("crm", "SYSTEM");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, "
                + "origin) VALUES (?, 'crm', 'ACTIVE', 'FREE', 'ROBOT')", UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("matrix: dry run writes nothing; real run = dry run counts, same states for every module, rows per "
            + "matrix, ADMIN row untouched, unreadable plan reported, crm/invoicing provisioned; re-run writes nothing")
    void backfillMatrix() {
        Map<String, Map<String, ModuleState>> before = states(T0);
        Map<String, Object> adminRowBefore = fullRow(ADMIN_ROW, "invoicing");
        long dataBefore = dataRows();
        int rowsBefore = jdbc.queryForObject("SELECT count(*) FROM public.tenant_modules", Integer.class);

        BackfillReport dry = backfill().run(true);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenant_modules", Integer.class))
                .isEqualTo(rowsBefore);
        assertThat(historyTables()).isZero();
        assertThat(dry.dryRun()).isTrue();

        BackfillReport real = backfill().run(false);

        assertThat(real.dryRun()).isFalse();
        assertThat(List.of(real.tenantsProcessed(), real.tenantsSkipped(), real.rowsInserted(), real.rowsUpdated(),
                real.rowsUnchanged(), real.rowsPreserved(), real.modulesProvisioned()))
                .isEqualTo(List.of(dry.tenantsProcessed(), dry.tenantsSkipped(), dry.rowsInserted(), dry.rowsUpdated(),
                        dry.rowsUnchanged(), dry.rowsPreserved(), dry.modulesProvisioned()));
        assertThat(real.tenants()).isEqualTo(dry.tenants());
        assertThat(real.failures()).isEqualTo(dry.failures());
        // 5 tenants synced (BROKEN and NO_SUBSCRIPTIONS failed), NO_SCHEMA and the platform tenant skipped
        assertThat(real.tenantsProcessed()).isEqualTo(5);
        assertThat(real.tenantsSkipped()).isEqualTo(2);
        assertThat(real.rowsInserted()).isEqualTo(5 * 8 - 1);
        assertThat(real.rowsPreserved()).isEqualTo(1);
        assertThat(real.failures()).extracting(ModuleBackfill.Failure::tenantCode)
                .containsExactly(BROKEN.code(), NO_SUBSCRIPTIONS.code());
        assertThat(real.failures().get(0).reason()).doesNotContain(BROKEN.schema()).contains("Abonnement");

        // parity: every module of every tenant has the same effective state
        assertThat(states(T0)).isEqualTo(before);
        assertThat(dataRows()).isEqualTo(dataBefore);

        Map<String, String> trial = rows(TRIAL);
        for (String id : PAID) {
            assertThat(trial).containsEntry(id, row("TRIAL", "PREMIUM", "PLAN", TRIAL_END, TRIAL_END.plus(GRACE)));
            assertThat(rows(EXPIRED)).containsEntry(id,
                    row("EXPIRED", "PREMIUM", "PLAN", EXPIRED_END, EXPIRED_END.plus(GRACE)));
            assertThat(rows(GRACED)).containsEntry(id,
                    row("EXPIRED", "PREMIUM", "PLAN", DOWNGRADED_AT, DOWNGRADED_AT.plus(GRACE)));
            assertThat(rows(FREE)).containsEntry(id, row("DISABLED", "FREE", "PLAN", null, null));
        }
        assertThat(before.get(EXPIRED.code()).get("invoicing")).isEqualTo(ModuleState.READ_ONLY);
        assertThat(before.get(GRACED.code()).get("invoicing")).isEqualTo(ModuleState.READ_ONLY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND status = 'DISABLED' AND disabled_at IS NOT NULL", Integer.class, FREE.id())).isEqualTo(8);
        // super-admin row untouched, the other paid modules DISABLED
        // (only its provisioned_version is recorded by the invoicing provisioning)
        assertThat(fullRow(ADMIN_ROW, "invoicing")).isEqualTo(withVersion(adminRowBefore, latestInvoicing()));
        assertThat(rows(ADMIN_ROW)).containsEntry("alerts", row("DISABLED", "FREE", "PLAN", null, null));
        // unreadable plan: nothing written
        assertThat(rows(BROKEN)).isEmpty();
        assertThat(rows(NO_SUBSCRIPTIONS)).isEmpty();
        assertThat(rows(NO_SCHEMA)).isEmpty();

        // Story 16.12: the 9 foundation modules (crm among them, V1 idempotent, no baseline any more) provisioned for
        // every synced tenant — crm keeps its SYSTEM ACTIVE/FREE row; invoicing for enabled tenants
        for (Tenant t : List.of(TRIAL, EXPIRED, GRACED, FREE, ADMIN_ROW)) {
            assertThat(rows(t).get("crm")).startsWith("ACTIVE/FREE/SYSTEM");
            assertThat(provisionedVersion(t, "crm")).isEqualTo("2"); // 20.2: crm V2
        }
        assertThat(provisionedVersion(TRIAL, "invoicing")).isEqualTo(latestInvoicing());
        assertThat(provisionedVersion(ADMIN_ROW, "invoicing")).isEqualTo(latestInvoicing());
        assertThat(provisionedVersion(EXPIRED, "invoicing")).isNull(); // read-only: not provisioned
        assertThat(real.modulesProvisioned()).isEqualTo(5 * 9 + 2);

        BackfillReport again = backfill().run(false);
        assertThat(again.rowsWritten()).isZero();
        assertThat(again.modulesProvisioned()).isZero();
        assertThat(again.tenants()).isEmpty();
        assertThat(again.rowsUnchanged()).isEqualTo(real.rowsInserted());
        assertThat(states(T0)).isEqualTo(before);
    }

    private static String latestInvoicing() {
        return new FlywayModuleMigrations(dataSource).latestVersion("invoicing");
    }

    private static Map<String, Object> withVersion(Map<String, Object> row, String version) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put("provisioned_version", version);
        return copy;
    }

    @Test
    @DisplayName("AC2: a backfilled trial turns READ_ONLY at its end then DISABLED after 7 days — as without rows")
    void trialEndPassesAfterBackfill() {
        List<Instant> instants = List.of(T0, TRIAL_END.minusSeconds(1), TRIAL_END, TRIAL_END.plus(GRACE).minusSeconds(1),
                TRIAL_END.plus(GRACE), TRIAL_END.plus(Duration.ofDays(30)));
        Map<Instant, ModuleState> withoutRows = new LinkedHashMap<>();
        instants.forEach(at -> withoutRows.put(at, registry(at).moduleState(TRIAL.schema(), "invoicing")));

        backfill().run(false);

        Map<Instant, ModuleState> withRows = new LinkedHashMap<>();
        instants.forEach(at -> withRows.put(at, registry(at).moduleState(TRIAL.schema(), "invoicing")));
        assertThat(withRows).isEqualTo(withoutRows);
        assertThat(withRows.values()).containsExactly(ModuleState.ENABLED, ModuleState.ENABLED, ModuleState.READ_ONLY,
                ModuleState.READ_ONLY, ModuleState.DISABLED, ModuleState.DISABLED);
    }

    @Test
    @DisplayName("plan change after the backfill: FREE → PREMIUM rewrites the PLAN rows in the same transaction, "
            + "the ADMIN row never changes; a rolled-back change leaves the rows as they were")
    void planChangeAfterBackfill() {
        backfill().run(false);
        Map<String, Object> adminRow = fullRow(ADMIN_ROW, "invoicing");
        Map<String, String> freeRows = rows(FREE);
        ModuleRegistryService registry = registry(T0);
        ChangeTenantPlanService plans = new ChangeTenantPlanService(jdbc, new JdbcPlanChangeRecorder(jdbc),
                rowSync(registry), com.keevo.shared.capability.entitlement.TestPlanProfiles.DEFAULTS);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Instant until = Instant.parse("2027-06-01T22:59:59Z");

        // rolled back: rows unchanged
        tx.executeWithoutResult(status -> {
            plans.changePlan(new ChangeTenantPlanCommand(FREE.id(), FREE.schema(), PlanType.PREMIUM, until,
                    PlanChangeCause.ADMIN_ACTIVATION, null, null));
            assertThat(rows(FREE)).as("written inside the plan change transaction").isNotEqualTo(freeRows);
            status.setRollbackOnly();
        });
        assertThat(rows(FREE)).isEqualTo(freeRows);

        // committed: PLAN rows ACTIVE until the new end
        tx.executeWithoutResult(status -> plans.changePlan(new ChangeTenantPlanCommand(FREE.id(), FREE.schema(),
                PlanType.PREMIUM, until, PlanChangeCause.ADMIN_ACTIVATION, null, null)));
        for (String id : PAID) {
            assertThat(rows(FREE)).containsEntry(id, row("ACTIVE", "PREMIUM", "PLAN", until, until.plus(GRACE)));
        }
        assertThat(registry(T0).moduleState(FREE.schema(), "invoicing")).isEqualTo(ModuleState.ENABLED);

        // the super-admin row survives plan changes both ways
        tx.executeWithoutResult(status -> plans.changePlan(new ChangeTenantPlanCommand(ADMIN_ROW.id(),
                ADMIN_ROW.schema(), PlanType.PREMIUM, until, PlanChangeCause.ADMIN_ACTIVATION, null, null)));
        tx.executeWithoutResult(status -> plans.changePlan(new ChangeTenantPlanCommand(ADMIN_ROW.id(),
                ADMIN_ROW.schema(), PlanType.FREE, null, PlanChangeCause.ADMIN_DOWNGRADE, null, null)));
        assertThat(fullRow(ADMIN_ROW, "invoicing")).isEqualTo(adminRow);
        assertThat(rows(ADMIN_ROW)).containsEntry("alerts", row("DISABLED", "FREE", "PLAN", null, null));
        assertThat(registry(T0).moduleState(ADMIN_ROW.schema(), "invoicing")).isEqualTo(ModuleState.ENABLED);
    }

    @Test
    @DisplayName("a second run while the first holds the lock → MODULE_BACKFILL_RUNNING; nothing written")
    void concurrentRun() {
        Optional<SimpleLock> held = lockProvider.lock(new LockConfiguration(Instant.now(), "moduleBackfill",
                Duration.ofMinutes(5), Duration.ZERO));
        assertThat(held).isPresent();
        try {
            assertThatThrownBy(() -> backfill().run(false))
                    .isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.getDomainCode()).isEqualTo("MODULE_BACKFILL_RUNNING"));
            assertThat(rows(TRIAL)).isEmpty();
        } finally {
            held.get().unlock();
        }
        assertThat(backfill().run(true).tenantsProcessed()).isEqualTo(5); // the lock is released after a run
    }

    private ModuleLifecycle lifecycle() {
        ModuleRegistryService registry = registry(T0);
        JdbcTenantModuleStateStore states = new JdbcTenantModuleStateStore(dataSource);
        KeevoModuleDirectory modules = KeevoModuleDirectory.of(catalog,
                List.of(new CrmModule(null), new InvoicingModule()));
        ModuleProvisionerService provisioner = new ModuleProvisionerService(catalog, registry, states, states,
                new FlywayModuleMigrations(dataSource), modules, TransactionOperations.withoutTransaction());
        com.keevo.shared.application.port.AuditPort audit = new com.keevo.shared.application.port.AuditPort() {
            @Override
            public void record(AuditEntry entry) {
            }

            @Override
            public AuditSlice search(AuditQuery query, String cursor, Integer page, int size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<AuditEntryRecord> findById(UUID id) {
                throw new UnsupportedOperationException();
            }
        };
        com.keevo.shared.module.application.ModuleLifecycleService target =
                new com.keevo.shared.module.application.ModuleLifecycleService(catalog, registry,
                        new com.keevo.shared.module.application.DependencyResolverService(catalog, registry,
                                new com.keevo.shared.module.application.EntitlementActivationEligibility()),
                        provisioner, new com.keevo.shared.module.adapter.out.persistence.JdbcTenantModuleLifecycleStore(
                                dataSource),
                        key -> jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))",
                                Integer.class, key),
                        audit, Clock.fixed(T0, ZoneOffset.UTC), modules);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addInterface(ModuleLifecycle.class);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (ModuleLifecycle) proxy.getProxy();
    }

    private static String origin(Tenant t, String moduleId) {
        return jdbc.queryForObject("SELECT origin FROM public.tenant_modules WHERE tenant_id = ? AND module_id = ?",
                String.class, t.id(), moduleId);
    }

    @Test
    @DisplayName("super-admin lifecycle over an existing PLAN row: activate / deactivate relabel it ADMIN, and later "
            + "plan changes never touch it")
    void lifecycleRelabelsPlanRowsAdmin() {
        backfill().run(false);
        assertThat(origin(FREE, "invoicing")).isEqualTo("PLAN");
        assertThat(origin(TRIAL, "alerts")).isEqualTo("PLAN");
        ModuleLifecycle lifecycle = lifecycle();
        ModuleLifecycle.Actor admin = new ModuleLifecycle.Actor(UUID.randomUUID(), true);
        ChangeTenantPlanService plans = new ChangeTenantPlanService(jdbc, new JdbcPlanChangeRecorder(jdbc),
                rowSync(registry(T0)), com.keevo.shared.capability.entitlement.TestPlanProfiles.DEFAULTS);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // activate a paid module on a Free tenant (its row was PLAN / DISABLED)
        lifecycle.activate(FREE.id(), "invoicing",
                new ModuleLifecycle.ActivationRequest(TenantModuleStatus.ACTIVE, ADMIN_END), admin);
        Map<String, Object> activated = fullRow(FREE, "invoicing");
        assertThat(activated).containsEntry("origin", "ADMIN").containsEntry("status", "ACTIVE");
        tx.executeWithoutResult(status -> plans.changePlan(new ChangeTenantPlanCommand(FREE.id(), FREE.schema(),
                PlanType.FREE, null, PlanChangeCause.ADMIN_DOWNGRADE, null, null)));
        assertThat(fullRow(FREE, "invoicing")).isEqualTo(activated);
        assertThat(registry(T0).moduleState(FREE.schema(), "invoicing")).isEqualTo(ModuleState.ENABLED);

        // deactivate a paid module on a trial tenant (its row was PLAN / TRIAL)
        lifecycle.deactivate(TRIAL.id(), "alerts", admin);
        Map<String, Object> deactivated = fullRow(TRIAL, "alerts");
        assertThat(deactivated).containsEntry("origin", "ADMIN").containsEntry("status", "DISABLED");
        Instant until = Instant.parse("2027-06-01T22:59:59Z");
        tx.executeWithoutResult(status -> plans.changePlan(new ChangeTenantPlanCommand(TRIAL.id(), TRIAL.schema(),
                PlanType.PREMIUM, until, PlanChangeCause.ADMIN_ACTIVATION, null, null)));
        assertThat(fullRow(TRIAL, "alerts")).isEqualTo(deactivated);
        assertThat(rows(TRIAL)).containsEntry("exports", row("ACTIVE", "PREMIUM", "PLAN", until, until.plus(GRACE)));
        assertThat(registry(T0).moduleState(TRIAL.schema(), "alerts")).isEqualTo(ModuleState.DISABLED);
    }
}

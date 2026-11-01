package com.keevo.shared.module;

import com.keevo.crm.module.CrmModule;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.NewStatus;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.Origin;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.Transition;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.module.adapter.out.persistence.FlywayModuleMigrations;
import com.keevo.shared.module.adapter.out.persistence.JdbcModuleExpiryStore;
import com.keevo.shared.module.adapter.out.persistence.JdbcPlanModuleRowStore;
import com.keevo.shared.module.adapter.out.persistence.JdbcTenantModuleLifecycleStore;
import com.keevo.shared.module.adapter.out.persistence.JdbcTenantModuleStateStore;
import com.keevo.shared.module.application.DependencyResolverService;
import com.keevo.shared.module.application.EntitlementActivationEligibility;
import com.keevo.shared.module.application.KeevoModuleDirectory;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModuleExpiryMaterializer;
import com.keevo.shared.module.application.ModuleExpiryMaterializer.Report;
import com.keevo.shared.module.application.ModuleLifecycleService;
import com.keevo.shared.module.application.ModuleProvisionerService;
import com.keevo.shared.module.application.ModuleRegistryService;
import com.keevo.shared.module.application.PlanModuleRowSyncService;
import com.keevo.shared.module.application.port.ModuleExpiryStore;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.shared.module.application.port.PlanModuleRowSync.SyncResult;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
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

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 16.1 — the nightly licence materialisation ({@link ModuleExpiryMaterializer}) on PostgreSQL with the shipped
 * catalogue and the real JDBC stores: expiry of an ADMIN row (dates kept, one event, idempotent re-run), end of grace
 * of the 8 PLAN rows of a downgraded tenant (one grouped event), SYSTEM / free rows untouched, per-tenant failure
 * isolation, plan sync after the job writing nothing, and a reactivation trial that brings the module's data back.
 */
class ModuleExpiryMaterializerPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_module_expiry";
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Duration GRACE = Duration.ofDays(7);
    private static final List<String> PAID = List.of("advanced-reports", "alerts", "counting", "exports",
            "invoicing", "multi-store", "profitability", "report-config");

    private static final Tenant ADMIN_ROW = new Tenant("kv_mxad01", "KV-MXAD01");
    private static final Tenant DOWNGRADED = new Tenant("kv_mxdg02", "KV-MXDG02");
    private static final Tenant OTHER = new Tenant("kv_mxot03", "KV-MXOT03");
    private static final Tenant REACTIVATED = new Tenant("kv_mxre04", "KV-MXRE04");
    private static final List<Tenant> ALL = List.of(ADMIN_ROW, DOWNGRADED, OTHER, REACTIVATED);

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private ModuleCatalog catalog;
    private DataSourceTransactionManager transactionManager;
    private final List<Object> events = new ArrayList<>();

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
        Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration").load().migrate();
        for (Tenant t : ALL) {
            jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                    + "max_stores, plan_type, schema_name, status, name) "
                    + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')", t.id(), t.code(),
                    t.schema());
        }
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void fresh() {
        jdbc.update("DELETE FROM public.tenant_modules"); // test database only
        jdbc.update("DELETE FROM public.tenant_plan_events");
        TenantSchemaProvisioner schemas = new TenantSchemaProvisioner(dataSource);
        for (Tenant t : List.of(DOWNGRADED, REACTIVATED)) {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + t.schema() + "\" CASCADE"); // test database only
            schemas.provision(t.id().toString(), t.schema());
        }
        catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
        transactionManager = new DataSourceTransactionManager(dataSource);
        events.clear();
    }

    // ── wiring ────────────────────────────────────────────────────────────────

    private ModuleRegistryService registry(Instant at) {
        return new ModuleRegistryService(catalog, new JdbcTenantModuleStateStore(dataSource),
                new RequestScopedActiveModulesCache(), Clock.fixed(at, ZoneOffset.UTC));
    }

    private TransactionLockPort locks() {
        return key -> jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))",
                Integer.class, key);
    }

    private ModuleExpiryMaterializer job(Instant at, ModuleExpiryStore store) {
        return new ModuleExpiryMaterializer(catalog, new JdbcPlanModuleRowStore(dataSource), store, locks(),
                events::add, transactionManager, Clock.fixed(at, ZoneOffset.UTC));
    }

    private ModuleExpiryMaterializer job(Instant at) {
        return job(at, new JdbcModuleExpiryStore(dataSource));
    }

    private PlanModuleRowSync rowSync(Instant at) {
        PlanModuleRowSyncService target = new PlanModuleRowSyncService(catalog,
                new JdbcPlanModuleRowStore(dataSource), registry(at), Clock.fixed(at, ZoneOffset.UTC), events::add);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addInterface(PlanModuleRowSync.class);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (PlanModuleRowSync) proxy.getProxy();
    }

    private ModuleLifecycle lifecycle(Instant at) {
        ModuleRegistryService registry = registry(at);
        JdbcTenantModuleStateStore states = new JdbcTenantModuleStateStore(dataSource);
        KeevoModuleDirectory modules = KeevoModuleDirectory.of(catalog,
                List.of(new CrmModule(null), new InvoicingModule()));
        ModuleProvisionerService provisioner = new ModuleProvisionerService(catalog, registry, states, states,
                new FlywayModuleMigrations(dataSource), modules, TransactionOperations.withoutTransaction());
        AuditPort audit = new AuditPort() {
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
        ModuleLifecycleService target = new ModuleLifecycleService(catalog, registry,
                new DependencyResolverService(catalog, registry, new EntitlementActivationEligibility()),
                provisioner, new JdbcTenantModuleLifecycleStore(dataSource), locks(), audit,
                Clock.fixed(at, ZoneOffset.UTC), modules);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addInterface(ModuleLifecycle.class);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (ModuleLifecycle) proxy.getProxy();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static void insertRow(Tenant t, String moduleId, String status, String origin, Instant expires,
                                  Instant grace) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, activated_at, expires_at, "
                + "grace_until, origin) VALUES (?, ?, ?, 'STANDARD', ?, ?, ?, ?)", t.id(), moduleId, status,
                ts(T0.minus(Duration.ofDays(60))), ts(expires), ts(grace), origin);
    }

    /** module id → "STATUS/ORIGIN/expires/grace/disabled". */
    private static Map<String, String> rows(Tenant t) {
        Map<String, String> rows = new LinkedHashMap<>();
        jdbc.query("SELECT module_id, status, origin, expires_at, grace_until, disabled_at FROM public.tenant_modules "
                + "WHERE tenant_id = ? ORDER BY module_id", rs -> {
            rows.put(rs.getString(1), rs.getString(2) + "/" + rs.getString(3) + "/" + instant(rs.getTimestamp(4))
                    + "/" + instant(rs.getTimestamp(5)) + "/" + instant(rs.getTimestamp(6)));
        }, t.id());
        return rows;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String row(String status, String origin, Instant expires, Instant grace, Instant disabled) {
        return status + "/" + origin + "/" + expires + "/" + grace + "/" + disabled;
    }

    private List<ModuleLicencesChangedEvent> licenceEvents() {
        return events.stream().filter(ModuleLicencesChangedEvent.class::isInstance)
                .map(ModuleLicencesChangedEvent.class::cast).toList();
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN row ended → EXPIRED with its dates, one event (a); second run: nothing changed, no event; "
            + "SYSTEM / free rows untouched")
    void adminExpiryIdempotent() {
        Instant end = T0.minus(Duration.ofMinutes(1));
        insertRow(ADMIN_ROW, "invoicing", "ACTIVE", "ADMIN", end, end.plus(GRACE));
        // a SYSTEM free row, even with past dates, is never part of the lifecycle
        insertRow(ADMIN_ROW, "crm", "ACTIVE", "SYSTEM", end, end);
        Map<String, String> crmBefore = Map.of("crm", rows(ADMIN_ROW).get("crm"));

        Report first = job(T0).materialize();

        assertThat(rows(ADMIN_ROW)).containsEntry("invoicing",
                row("EXPIRED", "ADMIN", end, end.plus(GRACE), null));
        assertThat(rows(ADMIN_ROW)).containsAllEntriesOf(crmBefore);
        assertThat(first.expired()).isEqualTo(1);
        assertThat(first.disabled()).isZero();
        assertThat(licenceEvents()).singleElement().satisfies(e -> {
            assertThat(e.tenantId()).isEqualTo(ADMIN_ROW.schema());
            assertThat(e.transitions()).containsExactly(new Transition("invoicing", "Facturation", Origin.ADMIN,
                    NewStatus.EXPIRED, end.plus(GRACE)));
        });
        assertThat(registry(T0).moduleState(ADMIN_ROW.schema(), "invoicing")).isEqualTo(ModuleState.READ_ONLY);

        events.clear();
        Report second = job(T0).materialize();
        assertThat(second.expired() + second.disabled()).isZero();
        assertThat(licenceEvents()).isEmpty();

        // the grace ends: DISABLED, disabled_at = run instant, dates kept
        Instant later = end.plus(GRACE).plusSeconds(1);
        job(later).materialize();
        assertThat(rows(ADMIN_ROW)).containsEntry("invoicing",
                row("DISABLED", "ADMIN", end, end.plus(GRACE), later));
        assertThat(rows(ADMIN_ROW)).containsAllEntriesOf(crmBefore);
        assertThat(licenceEvents()).last().satisfies(e -> assertThat(e.transitions())
                .extracting(Transition::status).containsExactly(NewStatus.DISABLED));
    }

    @Test
    @DisplayName("downgraded tenant, grace over: 8 PLAN rows DISABLED in one event; the plan sync after the job "
            + "writes 0 rows; the module data tables are kept")
    void graceOverThenSync() {
        // FREE after the nightly EXPIRY downgrade 10 days ago; rows written by the plan sync 9 days ago (in grace)
        Instant downgrade = T0.minus(Duration.ofDays(10));
        jdbc.update("UPDATE \"" + DOWNGRADED.schema() + "\".subscriptions SET plan_type = 'FREE', status = 'ACTIVE', "
                + "expires_at = NULL");
        jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, occurred_at) "
                + "VALUES (?, 'PREMIUM_TRIAL', 'FREE', 'EXPIRY', ?)", DOWNGRADED.id(), ts(downgrade));
        SyncResult written = rowSync(T0.minus(Duration.ofDays(9))).sync(DOWNGRADED.id(), DOWNGRADED.schema());
        assertThat(written.inserted()).isEqualTo(8);
        for (String id : PAID) {
            assertThat(rows(DOWNGRADED).get(id)).startsWith("EXPIRED/PLAN/");
        }
        Map<String, ModuleState> before = registry(T0).moduleStates(DOWNGRADED.schema());

        Report report = job(T0).materialize();

        assertThat(report.disabled()).isEqualTo(8);
        for (String id : PAID) {
            assertThat(rows(DOWNGRADED)).containsEntry(id,
                    row("DISABLED", "PLAN", downgrade, downgrade.plus(GRACE), T0));
        }
        assertThat(licenceEvents()).singleElement().satisfies(e -> assertThat(e.transitions())
                .hasSize(8).allMatch(t -> t.status() == NewStatus.DISABLED && t.origin() == Origin.PLAN));
        // bookkeeping only: the effective states were already DISABLED
        assertThat(registry(T0).moduleStates(DOWNGRADED.schema())).isEqualTo(before);

        // a later plan write for this FREE tenant: the sync sees the same DISABLED rows → 0 writes
        SyncResult after = rowSync(T0.plus(Duration.ofHours(1))).sync(DOWNGRADED.id(), DOWNGRADED.schema());
        assertThat(after.inserted() + after.updated()).isZero();
        assertThat(after.unchanged()).isEqualTo(8);
        assertThat(rows(DOWNGRADED).get("invoicing")).isEqualTo(
                row("DISABLED", "PLAN", downgrade, downgrade.plus(GRACE), T0)); // disabled_at kept
        // no table of the tenant was dropped
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name = 'subscriptions'", Integer.class, DOWNGRADED.schema())).isEqualTo(1);
    }

    @Test
    @DisplayName("plan sync after the grace, before the job: existing EXPIRED PLAN rows → DISABLED with disabled_at = "
            + "now (kept by a later sync), one DISABLED event; preview and fresh inserts publish nothing")
    void syncDisablingPublishes() {
        Instant downgrade = T0.minus(Duration.ofDays(10));
        jdbc.update("UPDATE \"" + DOWNGRADED.schema() + "\".subscriptions SET plan_type = 'FREE', status = 'ACTIVE', "
                + "expires_at = NULL");
        jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, occurred_at) "
                + "VALUES (?, 'PREMIUM_TRIAL', 'FREE', 'EXPIRY', ?)", DOWNGRADED.id(), ts(downgrade));
        rowSync(T0.minus(Duration.ofDays(9))).sync(DOWNGRADED.id(), DOWNGRADED.schema()); // 8 EXPIRED inserts
        assertThat(licenceEvents()).isEmpty();

        rowSync(T0).preview(DOWNGRADED.id(), DOWNGRADED.schema());
        assertThat(licenceEvents()).isEmpty();
        assertThat(rows(DOWNGRADED).get("invoicing")).startsWith("EXPIRED/PLAN/");

        SyncResult result = rowSync(T0).sync(DOWNGRADED.id(), DOWNGRADED.schema());

        assertThat(result.updated()).isEqualTo(8);
        assertThat(rows(DOWNGRADED)).containsEntry("invoicing",
                row("DISABLED", "PLAN", downgrade, downgrade.plus(GRACE), T0));
        assertThat(licenceEvents()).singleElement().satisfies(e -> {
            assertThat(e.tenantId()).isEqualTo(DOWNGRADED.schema());
            assertThat(e.transitions()).hasSize(8).contains(new Transition("invoicing", "Facturation", Origin.PLAN,
                    NewStatus.DISABLED, downgrade.plus(GRACE)));
        });

        events.clear();
        rowSync(T0.plus(Duration.ofDays(1))).sync(DOWNGRADED.id(), DOWNGRADED.schema());
        assertThat(rows(DOWNGRADED)).containsEntry("invoicing",
                row("DISABLED", "PLAN", downgrade, downgrade.plus(GRACE), T0)); // disabled_at kept
        assertThat(licenceEvents()).isEmpty();
        assertThat(job(T0.plus(Duration.ofDays(1))).materialize().disabled()).isZero();
    }

    @Test
    @DisplayName("a tenant whose transaction fails is logged and skipped; the other tenants are processed")
    void failureIsolation() {
        Instant end = T0.minus(Duration.ofDays(1));
        insertRow(ADMIN_ROW, "invoicing", "ACTIVE", "ADMIN", end, end.plus(GRACE));
        insertRow(OTHER, "invoicing", "TRIAL", "ADMIN", end, end.plus(GRACE));
        JdbcModuleExpiryStore real = new JdbcModuleExpiryStore(dataSource);
        ModuleExpiryStore failing = new ModuleExpiryStore() {
            @Override
            public List<DueRow> lockDueRows(UUID tenantId, Instant now) {
                return real.lockDueRows(tenantId, now);
            }

            @Override
            public boolean markExpired(UUID tenantId, String moduleId) {
                boolean changed = real.markExpired(tenantId, moduleId);
                if (ADMIN_ROW.id().equals(tenantId)) {
                    throw new IllegalStateException("boom"); // after the write: it must be rolled back
                }
                return changed;
            }

            @Override
            public boolean markDisabled(UUID tenantId, String moduleId, Instant now) {
                return real.markDisabled(tenantId, moduleId, now);
            }
        };

        Report report = job(T0, failing).materialize();

        assertThat(report.failures()).containsExactly(ADMIN_ROW.code());
        assertThat(rows(ADMIN_ROW).get("invoicing")).startsWith("ACTIVE/ADMIN/");
        assertThat(rows(OTHER).get("invoicing")).startsWith("EXPIRED/ADMIN/");
        assertThat(licenceEvents()).extracting(ModuleLicencesChangedEvent::tenantId)
                .containsExactly(OTHER.schema());
    }

    @Test
    @DisplayName("reactivation: DISABLED invoicing with 5 invoices → TRIAL without date = now + 14 d, the 5 "
            + "invoices are still there and the module is enabled")
    void reactivationKeepsData() {
        Instant start = T0.minus(Duration.ofDays(60));
        Instant end = T0.minus(Duration.ofDays(30));
        ModuleLifecycle.Actor admin = new ModuleLifecycle.Actor(UUID.randomUUID(), true);
        lifecycle(start).activate(REACTIVATED.id(), "invoicing",
                new ModuleLifecycle.ActivationRequest(TenantModuleStatus.ACTIVE, end), admin);
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO \"" + REACTIVATED.schema() + "\".invoices (id, client_name, vat_registered, "
                    + "vat_rate, prices_include_vat) VALUES (?, ?, false, 0, true)", UUID.randomUUID(), "Client " + i);
        }
        job(T0).materialize();
        assertThat(rows(REACTIVATED).get("invoicing")).startsWith("DISABLED/ADMIN/");
        assertThat(registry(T0).moduleState(REACTIVATED.schema(), "invoicing")).isEqualTo(ModuleState.DISABLED);

        ModuleLifecycle.ActivationResult result = lifecycle(T0).activate(REACTIVATED.id(), "invoicing",
                new ModuleLifecycle.ActivationRequest(TenantModuleStatus.TRIAL, null), admin);

        assertThat(result.status()).isEqualTo(TenantModuleStatus.TRIAL);
        assertThat(result.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(14)));
        assertThat(rows(REACTIVATED)).containsEntry("invoicing", row("TRIAL", "ADMIN", T0.plus(Duration.ofDays(14)),
                T0.plus(Duration.ofDays(21)), null));
        assertThat(registry(T0).moduleState(REACTIVATED.schema(), "invoicing")).isEqualTo(ModuleState.ENABLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + REACTIVATED.schema() + "\".invoices",
                Integer.class)).isEqualTo(5);
    }
}

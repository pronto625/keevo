package com.keevo.admin.crosstenant;

import com.keevo.admin.alerts.adapter.out.persistence.JdbcAlertSourcesAdapter;
import com.keevo.admin.catalog.application.service.AdminCatalogService;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;
import com.keevo.admin.catalog.domain.port.in.ListProductsQuery;
import com.keevo.admin.feedback.application.service.AdminFeedbackLabelService;
import com.keevo.admin.notification.adapter.out.persistence.JdbcPushAudienceAdapter;
import com.keevo.admin.notification.domain.model.GlobalNotificationTarget;
import com.keevo.admin.notification.domain.model.PushTargetTenant;
import com.keevo.admin.platform.adapter.out.persistence.JdbcPlatformProbeAdapter;
import com.keevo.admin.revenue.application.service.AdminRevenueService;
import com.keevo.admin.revenue.domain.port.in.RevenueRangeQuery;
import com.keevo.admin.subscription.application.service.AdminPlanService;
import com.keevo.admin.subscription.application.service.TargetTenantLookup;
import com.keevo.admin.subscription.domain.port.out.TenantPaymentRepository;
import com.keevo.admin.sync_monitoring.application.service.AdminSyncMonitoringService;
import com.keevo.admin.sync_monitoring.domain.model.AdminTenantSyncHealth;
import com.keevo.admin.tenant.application.service.AdminTenantService;
import com.keevo.admin.tenant.domain.model.AdminTenantListItem;
import com.keevo.admin.tenant.domain.port.in.ListTenantsQuery;
import com.keevo.feedback.api.FeedbackView;
import com.keevo.identity.api.TenantDeletionPort;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.subscription.api.PlanAdministrationPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Story 19.11c (R1, FUS-NFR11) — the remaining super-admin screens and jobs on a real PostgreSQL: tenant list and its
 * « dernière activité » filter, sync monitoring, revenue current plans, alerts feed, health refusals, plan alerts,
 * global push audience, feedback labels and the streamed catalogue export issue the same number of JDBC statements
 * with 3 and with 12 tenants; a module table absent from a schema never fails a figure; a schema failing at read time
 * is skipped alone; the new indexes serve the per-branch reads (EXPLAIN).
 *
 * <p>Same harness as {@code AdminScreensCrossTenantIntegrationTest} (19.11b): services wired by hand on a JDBC stack
 * whose DataSource counts the statements it prepares; chunk size 1000 so the tenants other classes leave in the shared
 * database never cross a chunk boundary between the two measures (the ⌈T/100⌉ growth is covered by
 * {@code CrossTenantSqlChunkTest}). Runs outside the test transaction, as in production.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Other super-admin screens and jobs without a per-account loop (19.11c) on PostgreSQL")
class AdminOtherScreensCrossTenantIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId WAT = ZoneId.of("Africa/Douala");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private final AtomicInteger statements = new AtomicInteger();
    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();

    private AdminTenantService tenantsService;
    private AdminSyncMonitoringService sync;
    private AdminRevenueService revenue;
    private JdbcAlertSourcesAdapter alerts;
    private JdbcPlatformProbeAdapter health;
    private AdminPlanService plans;
    private JdbcPushAudienceAdapter push;
    private AdminFeedbackLabelService feedback;
    private AdminCatalogService catalog;

    @BeforeEach
    void wire() {
        JdbcTemplate counting = new JdbcTemplate(countingDataSource(dataSource, statements));
        CrossTenantSql crossTenant = new CrossTenantSql(counting, 1000);
        Clock clock = Clock.systemUTC();
        tenantsService = new AdminTenantService(counting, mock(TenantDeletionPort.class), PlanCatalog.SEED, crossTenant);
        sync = new AdminSyncMonitoringService(counting, crossTenant);
        revenue = new AdminRevenueService(counting, clock, PlanCatalog.SEED, null, crossTenant);
        alerts = new JdbcAlertSourcesAdapter(counting, crossTenant);
        health = new JdbcPlatformProbeAdapter(crossTenant, dataSource);
        plans = new AdminPlanService(new TargetTenantLookup(counting), mock(PlanAdministrationPort.class),
                mock(TenantPaymentRepository.class), mock(ApplicationEventPublisher.class), counting, clock,
                PlanCatalog.SEED, null, crossTenant);
        push = new JdbcPushAudienceAdapter(counting, crossTenant);
        feedback = new AdminFeedbackLabelService(counting, crossTenant);
        catalog = new AdminCatalogService(counting, crossTenant);
    }

    @AfterEach
    void cleanup() {
        for (UUID u : users) {
            jdbc.update("DELETE FROM public.user_daily_activity WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.users WHERE id = ?", u);
        }
        for (String s : schemas) jdbc.update("DELETE FROM public.user_sync_state WHERE tenant_id = ?", s);
        for (UUID id : tenantIds) jdbc.update("DELETE FROM public.tenants WHERE id = ?", id);
        for (String s : schemas) jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE");
    }

    @Test
    @DisplayName("every screen / job issues the same number of statements with 3 and with 12 tenants")
    void statementCount_doesNotGrowWithTenants() {
        for (int i = 0; i < 3; i++) seedTenant("Compte " + i);
        Map<String, Integer> withThree = measure();

        for (int i = 3; i < 12; i++) seedTenant("Compte " + i);
        Map<String, Integer> withTwelve = measure();

        // The export reads EXPORT_CHUNK (10) companies per statement to bound memory: 9 more companies add at most
        // one statement; every other screen / job is constant.
        int exportGrowth = withTwelve.remove("catalog.export") - withThree.remove("catalog.export");
        assertThat(exportGrowth).isBetween(0, 1);
        assertThat(withTwelve).isEqualTo(withThree);
        assertThat(withThree.values()).allSatisfy(n -> assertThat(n).isBetween(1, 12));
    }

    @Test
    @DisplayName("tenant list: store count, last activity and owner name from the unions; activity filter in SQL")
    void tenantList_realSql() {
        UUID active = UUID.randomUUID();
        String activeSchema = provision(active, "Boutique Active", "FREE");
        owner(active, "+2376" + digits(8), activeSchema, "Awa", "Diallo");
        sale(activeSchema, Instant.now().minus(2, ChronoUnit.DAYS));
        UUID dormant = UUID.randomUUID();
        String dormantSchema = provision(dormant, "Boutique Dormante", "FREE");
        sale(dormantSchema, Instant.now().minus(90, ChronoUnit.DAYS));
        // a sale dated in the future by a phone clock never pushes the last activity past now
        sale(activeSchema, Instant.now().plus(5, ChronoUnit.DAYS));

        List<AdminTenantListItem> page = tenantsService.execute(new ListTenantsQuery("Boutique ", null, null, null,
                null, null, null, 0, 100)).getContent();
        AdminTenantListItem a = page.stream().filter(t -> t.id().equals(active)).findFirst().orElseThrow();
        assertThat(a.ownerName()).isEqualTo("Awa Diallo");
        assertThat(a.storeCount()).isPositive();
        assertThat(a.lastActivityAt()).isBeforeOrEqualTo(Instant.now()).isAfter(Instant.now().minus(1, ChronoUnit.HOURS));

        List<UUID> recent = tenantsService.execute(new ListTenantsQuery("Boutique ", null, null, null, null,
                Instant.now().minus(30, ChronoUnit.DAYS), null, 0, 100)).getContent().stream()
                .map(AdminTenantListItem::id).toList();
        assertThat(recent).contains(active).doesNotContain(dormant);
        List<UUID> old = tenantsService.execute(new ListTenantsQuery("Boutique ", null, null, null, null,
                null, Instant.now().minus(30, ChronoUnit.DAYS), 0, 100)).getContent().stream()
                .map(AdminTenantListItem::id).toList();
        assertThat(old).contains(dormant).doesNotContain(active);
    }

    @Test
    @DisplayName("sync table absent: failed ops kept, conflicts 0; a broken schema is skipped alone")
    void missingTable_andBrokenSchema() {
        UUID partial = UUID.randomUUID();
        String partialSchema = provision(partial, "Boutique Partielle", "FREE");
        jdbc.execute("DROP TABLE \"" + partialSchema + "\".sync_conflicts_log CASCADE");
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO \"" + partialSchema + "\".sync_operations_log (id, operation_type, entity_id, "
                    + "status, processed_at) VALUES (?, 'SALE_CREATED', ?, 'FAILED', now())",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString());
        }
        UUID broken = UUID.randomUUID();
        String brokenSchema = provision(broken, "Boutique Cassée", "FREE");
        jdbc.execute("ALTER TABLE \"" + brokenSchema + "\".sync_error_log RENAME TO sync_error_log_old");
        jdbc.execute("CREATE VIEW \"" + brokenSchema + "\".sync_error_log AS SELECT * FROM \"" + brokenSchema
                + "\".sync_error_log_old WHERE 1 / 0 = 1");
        UUID healthy = UUID.randomUUID();
        String healthySchema = provision(healthy, "Boutique Saine", "FREE");
        long before = health.syncRefusalsSince(Instant.now().minus(1, ChronoUnit.HOURS));
        jdbc.update("INSERT INTO \"" + healthySchema + "\".sync_error_log (operation_id, operation_type, payload) "
                + "VALUES ('op', 'SALE_CREATED', '{}'::jsonb)");

        AdminTenantSyncHealth row = sync.listTenantSyncHealth().stream()
                .filter(h -> h.tenantId().equals(partial.toString())).findFirst().orElseThrow();
        assertThat(row.failedOps7d()).isEqualTo(2);
        assertThat(row.conflicts7d()).isZero();
        assertThat(health.syncRefusalsSince(Instant.now().minus(1, ChronoUnit.HOURS))).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("catalogue export: every matching row once, grouped by company in name order, newest first")
    void catalogueExport_streamsEveryRow() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String b = provision(UUID.randomUUID(), "Zèbre " + tag, "FREE");
        String a = provision(UUID.randomUUID(), "Alpha " + tag, "FREE");
        product(a, tag + "-a-old", 30);
        product(a, tag + "-a-new", 1);
        product(b, tag + "-b-1", 5);

        List<String> rows = new ArrayList<>();
        catalog.prepareExport(new ListProductsQuery(tag, null, null, null, null, 0, Integer.MAX_VALUE))
                .forEach(p -> rows.add(p.name()));

        assertThat(rows).containsExactly(tag + "-a-new", tag + "-a-old", tag + "-b-1");
        // a typed % is a literal character, never a wildcard
        assertThat(catalog.execute(new ListProductsQuery(tag + "%", null, null, null, null, 0, 25)).getContent())
                .extracting(AdminProductListItem::name).isEmpty();
    }

    @Test
    @DisplayName("the per-branch reads are served by the 19.11c indexes (EXPLAIN, sequential scans off)")
    void indexes_serveTheBranches() {
        UUID tenant = UUID.randomUUID();
        String s = provision(tenant, "Boutique Index", "FREE");
        String plans = jdbc.execute((ConnectionCallback<String>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET enable_seqscan = off");
                StringBuilder out = new StringBuilder();
                for (String sql : List.of(
                        "SELECT MAX(COALESCE(sa.occurred_at, sa.created_at)) FROM \"" + s + "\".sales sa",
                        "SELECT a.occurred_at FROM \"" + s + "\".audit_log a WHERE a.user_id <> "
                                + "'00000000-0000-0000-0000-000000000000'::uuid AND (a.source IS NULL OR a.source <> "
                                + "'SYSTEM') ORDER BY a.occurred_at DESC LIMIT 1",
                        "SELECT p.id FROM \"" + s + "\".products p ORDER BY p.updated_at DESC NULLS LAST, p.id LIMIT 25",
                        "SELECT MAX(u.first_seen_at) FROM public.user_daily_activity u WHERE u.tenant_id = '"
                                + tenant + "'::uuid")) {
                    try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                        while (rs.next()) out.append(rs.getString(1)).append('\n');
                    }
                }
                st.execute("RESET enable_seqscan");
                return out.toString();
            }
        });

        assertThat(plans).contains("idx_sales_effective_at").contains("idx_audit_log_merchant_occurred")
                .contains("idx_products_updated_at").contains("idx_user_daily_activity_tenant_seen");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Statements issued by each screen / job. */
    private Map<String, Integer> measure() {
        Map<String, Integer> out = new LinkedHashMap<>();
        Instant monthAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        out.put("tenants.list", count(() -> tenantsService.execute(
                new ListTenantsQuery(null, null, null, null, null, null, null, 0, 25))));
        out.put("tenants.activityFilter", count(() -> tenantsService.execute(
                new ListTenantsQuery(null, null, null, null, null, monthAgo, null, 0, 25))));
        out.put("sync.overview", count(sync::getSyncOverview));
        out.put("sync.list", count(sync::listTenantSyncHealth));
        out.put("revenue.summary", count(() -> revenue.getSummary(new RevenueRangeQuery(null, null))));
        out.put("revenue.churn", count(() -> revenue.getChurn(null)));
        out.put("alerts.expiredPlans", count(() -> alerts.expiredPlansNotFree(Instant.now())));
        out.put("alerts.reportFailures", count(() -> alerts.reportFailuresSince(monthAgo)));
        out.put("health.refusals", count(() -> health.syncRefusalsSince(monthAgo)));
        out.put("plans.alerts", count(plans::listAlerts));
        out.put("push.count", count(() -> push.countDeviceTokens(push.targetTenants(GlobalNotificationTarget.ALL, null))));
        out.put("push.tokens", count(() -> {
            List<PushTargetTenant> all = push.targetTenants(GlobalNotificationTarget.ALL, null);
            push.forEachTenantTokens(all, (t, tokens) -> { });
            return null;
        }));
        out.put("feedback.labels", count(() -> feedback.labels(feedbackPage())));
        out.put("catalog.export", count(() -> {
            catalog.prepareExport(new ListProductsQuery(null, null, null, null, null, 0, Integer.MAX_VALUE))
                    .forEach(p -> { });
            return null;
        }));
        return out;
    }

    /** One feedback per seeded tenant, from its owner. */
    private List<FeedbackView> feedbackPage() {
        List<FeedbackView> page = new ArrayList<>();
        for (int i = 0; i < schemas.size() && i < users.size(); i++) {
            page.add(new FeedbackView(UUID.randomUUID(), "BUG", "Le scanner plante", schemas.get(i), users.get(i),
                    "1.0", "android", "/pos", Instant.now(), "NORMAL", null, null));
        }
        return page;
    }

    private int count(Supplier<?> call) {
        statements.set(0);
        call.get();
        return statements.get();
    }

    /** A provisioned paying tenant expiring in 3 days, with an owner, a product, a sale, a device and a refusal. */
    private void seedTenant(String name) {
        UUID tenant = UUID.randomUUID();
        String schema = provision(tenant, name, "PREMIUM");
        UUID user = owner(tenant, "+2376" + digits(8), schema, "Prénom", name);
        jdbc.update("INSERT INTO \"" + schema + "\".products (name, sku) VALUES ('Produit', 'P1')");
        sale(schema, Instant.now().minus(1, ChronoUnit.DAYS));
        jdbc.update("INSERT INTO \"" + schema + "\".subscriptions (plan_type, status, expires_at) "
                + "VALUES ('PREMIUM', 'ACTIVE', now() + interval '3 days')");
        jdbc.update("INSERT INTO \"" + schema + "\".device_tokens (id, user_id, token, platform, role) "
                + "VALUES (gen_random_uuid(), ?, ?, 'ANDROID', 'OWNER')", user, "tok-" + schema);
        jdbc.update("INSERT INTO \"" + schema + "\".sync_error_log (operation_id, operation_type, payload) "
                + "VALUES ('op', 'SALE_CREATED', '{}'::jsonb)");
        jdbc.update("INSERT INTO public.user_daily_activity (user_id, tenant_id, activity_date) VALUES (?, ?, ?)",
                user, tenant, Date.valueOf(LocalDate.now(WAT)));
    }

    private void sale(String schema, Instant at) {
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores WHERE type = 'STORE' LIMIT 1",
                UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".sales (store_id, employee_id, total_amount, status, occurred_at, "
                + "created_at) VALUES (?, ?, 1000, 'COMPLETED', ?, ?)", store, UUID.randomUUID(),
                Timestamp.from(at), Timestamp.from(at.isAfter(Instant.now()) ? Instant.now() : at));
    }

    /** A product named {@code name}, updated that many minutes ago. */
    private void product(String schema, String name, int minutesAgo) {
        jdbc.update("INSERT INTO \"" + schema + "\".products (name, sku, updated_at) "
                + "VALUES (?, ?, now() - ? * interval '1 minute')", name, "S" + minutesAgo, minutesAgo);
    }

    private String provision(UUID id, String name, String plan) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String schema = "kv_" + suffix;
        new TenantSchemaProvisioner(dataSource).provision(id.toString(), schema);
        schemas.add(schema);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), ?, 3, 500, 1, ?, ?, 'ACTIVE', ?)",
                id, "KV-" + suffix.toUpperCase(), plan, schema, name);
        tenantIds.add(id);
        return schema;
    }

    private UUID owner(UUID tenantId, String phone, String schema, String firstName, String lastName) {
        UUID userId = UUID.randomUUID();
        users.add(userId);
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'OWNER')",
                userId, phone);
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, "
                + "tenant_id, user_id) VALUES (?, now(), now(), true, 'OWNER', ?, ?)",
                UUID.randomUUID(), tenantId, userId);
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores WHERE type = 'STORE' LIMIT 1",
                UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, first_name, last_name, store_id) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, ?)", userId, firstName, lastName, store);
        return userId;
    }

    private static String digits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(java.util.concurrent.ThreadLocalRandom.current().nextInt(10));
        return sb.toString();
    }

    /** A DataSource whose connections count every statement they prepare / create. */
    private static DataSource countingDataSource(DataSource target, AtomicInteger counter) {
        Set<String> statementMethods = Set.of("prepareStatement", "createStatement", "prepareCall");
        InvocationHandler connections = (proxy, method, args) -> {
            Object result = invoke(target, method, args);
            if (result instanceof Connection connection) {
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (p, m, a) -> {
                            if (statementMethods.contains(m.getName())) counter.incrementAndGet();
                            return invoke(connection, m, a);
                        });
            }
            return result;
        };
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, connections);
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

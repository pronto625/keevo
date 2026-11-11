package com.keevo.admin.crosstenant;

import com.keevo.admin.analytics.application.service.AdminAnalyticsService;
import com.keevo.admin.analytics.domain.model.CountryCount;
import com.keevo.admin.catalog.application.service.AdminCatalogService;
import com.keevo.admin.catalog.domain.model.AdminCatalogSummary;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;
import com.keevo.admin.catalog.domain.port.in.ListProductsQuery;
import com.keevo.admin.operations.application.service.AdminOperationsService;
import com.keevo.admin.operations.domain.model.TenantActivity;
import com.keevo.admin.operations.domain.port.in.OperationsPeriodQuery;
import com.keevo.admin.platformrevenue.adapter.out.RevenueSourcePlatformRevenueAdapter;
import com.keevo.identity.auth.adapter.out.persistence.impl.OwnerRecipientResolverAdapter;
import com.keevo.identity.auth.adapter.out.persistence.impl.UserRepositoryAdapter;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Date;
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

/**
 * Story 19.11b (R1, FUS-NFR11) — the super-admin screens of analytics, operations and the admin catalogue on a real
 * PostgreSQL: the number of JDBC statements of each endpoint does not grow with the number of tenants, a module
 * table absent from a schema never fails a figure, a schema failing at read time is skipped alone, and the catalogue
 * list / summary SQL.
 *
 * <p>The services are wired by hand on a JDBC stack whose {@link DataSource} counts the statements it prepares.
 * The chunk size is 1000 here so that the tenants other test classes leave in the shared database never cross a
 * chunk boundary between the two measures: the documented ⌈T/100⌉ growth (lock budget, 20.13) is covered by
 * {@code CrossTenantSqlChunkTest}. Runs outside the test transaction, as in production.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Super-admin screens without a per-account loop (19.11b) on PostgreSQL")
class AdminScreensCrossTenantIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId WAT = ZoneId.of("Africa/Douala");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private final AtomicInteger statements = new AtomicInteger();
    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();

    private AdminAnalyticsService analytics;
    private AdminOperationsService operations;
    private AdminCatalogService catalog;

    @BeforeEach
    void wire() {
        JdbcTemplate counting = new JdbcTemplate(countingDataSource(dataSource, statements));
        CrossTenantSql crossTenant = new CrossTenantSql(counting, 1000);
        RevenueSourcePlatformRevenueAdapter revenue = new RevenueSourcePlatformRevenueAdapter(crossTenant);
        Clock clock = Clock.systemUTC();
        analytics = new AdminAnalyticsService(counting, clock,
                new OwnerRecipientResolverAdapter(new UserRepositoryAdapter(null, counting)), revenue, crossTenant);
        operations = new AdminOperationsService(counting, clock, revenue, crossTenant);
        catalog = new AdminCatalogService(counting, crossTenant);
    }

    @AfterEach
    void cleanup() {
        for (UUID u : users) {
            jdbc.update("DELETE FROM public.user_daily_activity WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.users WHERE id = ?", u);
        }
        for (UUID id : tenantIds) jdbc.update("DELETE FROM public.tenants WHERE id = ?", id);
        for (String s : schemas) jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE");
    }

    @Test
    @DisplayName("every screen issues the same number of statements with 3 and with 12 tenants")
    void statementCount_doesNotGrowWithTenants() {
        for (int i = 0; i < 3; i++) seedTenant("Compte " + i);
        Map<String, Integer> withThree = measure();

        for (int i = 3; i < 12; i++) seedTenant("Compte " + i);
        Map<String, Integer> withTwelve = measure();

        assertThat(withTwelve).isEqualTo(withThree);
        // Never one statement per tenant: a handful per endpoint whatever the tenant count.
        assertThat(withThree.values()).allSatisfy(n -> assertThat(n).isBetween(1, 12));
    }

    @Test
    @DisplayName("catalogue: list (search, category, order, total) and summary read the tenant schemas")
    void catalogue_realSql() {
        AdminCatalogSummary before = catalog.execute();
        UUID tenant = UUID.randomUUID();
        String schema = provision(tenant, "Boutique Catalogue");
        String suffix = schema.substring(3);
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".categories (id, name) VALUES (?, 'Épicerie')", category);
        jdbc.update("INSERT INTO \"" + schema + "\".products (name, sku, category_id, stock_quantity, updated_at) "
                + "VALUES (?, ?, ?, 0, now() - interval '1 hour')", "Riz ancien " + suffix, "A" + suffix, category);
        jdbc.update("INSERT INTO \"" + schema + "\".products (name, sku, stock_quantity, status) "
                + "VALUES (?, ?, 3, 'DRAFT')", "Riz récent " + suffix, "B" + suffix);

        var page = catalog.execute(new ListProductsQuery(suffix, null, null, null, null, 0, 25));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AdminProductListItem::name)
                .containsExactly("Riz récent " + suffix, "Riz ancien " + suffix);
        assertThat(page.getContent().get(1).categoryName()).isEqualTo("Épicerie");
        assertThat(page.getContent().get(1).tenantName()).isEqualTo("Boutique Catalogue");
        assertThat(catalog.execute(new ListProductsQuery(suffix, null, "OUT", null, null, 0, 25)).getContent())
                .extracting(AdminProductListItem::name).containsExactly("Riz ancien " + suffix);
        assertThat(catalog.execute(new ListProductsQuery(suffix, null, null, null, null, 1, 1)).getContent())
                .extracting(AdminProductListItem::name).containsExactly("Riz ancien " + suffix);

        AdminCatalogSummary after = catalog.execute();
        assertThat(after.totalProducts() - before.totalProducts()).isEqualTo(2);
        assertThat(after.draftProducts() - before.draftProducts()).isEqualTo(1);
        assertThat(after.lowStockProducts() - before.lowStockProducts()).isEqualTo(1);
        assertThat(after.outOfStockProducts() - before.outOfStockProducts()).isEqualTo(1);
        assertThat(after.createdThisWeek() - before.createdThisWeek()).isEqualTo(2);
    }

    @Test
    @DisplayName("catalogue pages: the per-tenant LIMIT keeps the global updated_at order across interleaved tenants")
    void cataloguePages_globalOrderAcrossTenants() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String first = provision(UUID.randomUUID(), "Boutique Paire");
        String second = provision(UUID.randomUUID(), "Boutique Impaire");
        // Minutes ago, inserted out of order: first gets 1, 3, 5, 7, 9; second gets 2, 4, 6, 8.
        int[] firstAges = {7, 1, 9, 3, 5};
        int[] secondAges = {6, 2, 8, 4};
        for (int age : firstAges) product(first, tag, age);
        for (int age : secondAges) product(second, tag, age);

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 5; page++) {
            catalog.execute(new ListProductsQuery(tag, null, null, null, null, page, 2)).getContent()
                    .forEach(p -> seen.add(p.name()));
        }

        assertThat(seen).containsExactly(tag + "-1", tag + "-2", tag + "-3", tag + "-4", tag + "-5", tag + "-6",
                tag + "-7", tag + "-8", tag + "-9");
    }

    @Test
    @DisplayName("a module table absent from a schema: no statement error, the other figures are kept")
    void missingModuleTable_readsAsAbsent() {
        UUID tenant = UUID.randomUUID();
        String schema = provision(tenant, "Boutique Sans Synchro");
        jdbc.execute("DROP TABLE \"" + schema + "\".sync_operations_log CASCADE");
        jdbc.execute("DROP TABLE \"" + schema + "\".inventory_sessions CASCADE");
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores WHERE type = 'STORE' LIMIT 1", UUID.class);
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO \"" + schema + "\".sales (store_id, employee_id, total_amount, status, occurred_at, "
                + "created_at) VALUES (?, ?, 700, 'COMPLETED', ?, ?)", store, UUID.randomUUID(),
                Timestamp.from(yesterday), Timestamp.from(yesterday));
        var adoptionBefore = analytics.getAdoption();
        activity(owner(tenant, "+2376" + digits(8)), tenant, LocalDate.now(WAT));

        var adoptionAfter = analytics.getAdoption();
        List<TenantActivity> rows = operations.listTenantActivity(new OperationsPeriodQuery("7D", null, null), 0, 100)
                .content();

        // Counted as active, uses SALES; INVENTORY / OFFLINE_SYNC unchanged (tables absent → not used).
        assertThat(adoptionAfter.activeTenants()).isEqualTo(adoptionBefore.activeTenants() + 1);
        assertThat(adoptionAfter.features().get(0).tenantCount())
                .isEqualTo(adoptionBefore.features().get(0).tenantCount() + 1);
        assertThat(adoptionAfter.features().get(1).tenantCount()).isEqualTo(adoptionBefore.features().get(1).tenantCount());
        assertThat(adoptionAfter.features().get(5).tenantCount()).isEqualTo(adoptionBefore.features().get(5).tenantCount());
        TenantActivity row = rows.stream().filter(r -> r.tenantId().equals(tenant.toString())).findFirst().orElseThrow();
        assertThat(row.lastActivityAt()).isNotNull();
        assertThat(row.storeCount()).isPositive();
        assertThat(row.gmv()).isEqualTo(700L);
        assertThat(operations.listInactiveTenants()).noneMatch(t -> t.tenantId().equals(tenant.toString()));
    }

    @Test
    @DisplayName("a schema failing at read time is skipped alone (chunk retried tenant by tenant)")
    void brokenSchema_skippedAlone() {
        UUID good = UUID.randomUUID();
        String goodSchema = provision(good, "Boutique Saine");
        jdbc.update("INSERT INTO \"" + goodSchema + "\".products (name, sku) VALUES ('Savon', 'SAV1')");
        UUID broken = UUID.randomUUID();
        String brokenSchema = provision(broken, "Boutique Cassée");
        // Same columns as the products table, but every read fails (division by zero at plan time).
        jdbc.execute("ALTER TABLE \"" + brokenSchema + "\".products RENAME TO products_old");
        jdbc.execute("CREATE VIEW \"" + brokenSchema + "\".products AS SELECT * FROM \"" + brokenSchema
                + "\".products_old WHERE 1 / 0 = 1");

        AdminCatalogSummary summary = catalog.execute();
        var page = catalog.execute(new ListProductsQuery("Savon", good.toString(), null, null, null, 0, 25));

        assertThat(summary.totalProducts()).isPositive();
        assertThat(page.getContent()).extracting(AdminProductListItem::name).containsExactly("Savon");
        assertThat(analytics.getOverview().totalProducts()).isPositive();
    }

    @Test
    @DisplayName("geography: every owner phone in one statement, calling code → country")
    void geography_batchOwners() {
        UUID cm = UUID.randomUUID();
        provision(cm, "Boutique Douala");
        owner(cm, "+2376" + digits(8));
        List<CountryCount> before = analytics.getGeography();
        UUID ci = UUID.randomUUID();
        provision(ci, "Boutique Abidjan");
        owner(ci, "+2250" + digits(9));

        List<CountryCount> after = analytics.getGeography();

        assertThat(count(after, "CI")).isEqualTo(count(before, "CI") + 1);
        assertThat(count(after, "CM")).isEqualTo(count(before, "CM"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Statements issued by each screen endpoint. */
    private Map<String, Integer> measure() {
        Map<String, Integer> out = new LinkedHashMap<>();
        OperationsPeriodQuery week = new OperationsPeriodQuery("7D", null, null);
        out.put("analytics.overview", count(analytics::getOverview));
        out.put("analytics.growth", count(() -> analytics.getGrowth("3")));
        out.put("analytics.geography", count(analytics::getGeography));
        out.put("analytics.retention", count(() -> analytics.getRetention(null)));
        out.put("analytics.topStores", count(() -> analytics.getTopStores(null, null)));
        out.put("analytics.adoption", count(analytics::getAdoption));
        out.put("operations.overview", count(() -> operations.getOverview(week)));
        out.put("operations.trend", count(() -> operations.getTrend(week)));
        out.put("operations.activity", count(() -> operations.listTenantActivity(week, 0, 25)));
        out.put("operations.inactive", count(operations::listInactiveTenants));
        out.put("catalog.products", count(() -> catalog.execute(new ListProductsQuery(null, null, null, null, null, 0, 25))));
        out.put("catalog.export", count(() -> catalog.execute(
                new ListProductsQuery(null, null, null, null, null, 0, Integer.MAX_VALUE))));
        out.put("catalog.summary", count(catalog::execute));
        return out;
    }

    private int count(Supplier<?> call) {
        statements.set(0);
        call.get();
        return statements.get();
    }

    private static long count(List<CountryCount> counts, String country) {
        return counts.stream().filter(c -> c.country().equals(country)).mapToLong(CountryCount::tenantCount).sum();
    }

    /** A provisioned tenant with an owner, a product, a sale yesterday and app activity today. */
    private void seedTenant(String name) {
        UUID tenant = UUID.randomUUID();
        String schema = provision(tenant, name);
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores WHERE type = 'STORE' LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".products (name, sku) VALUES ('Produit', 'P1')");
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO \"" + schema + "\".sales (store_id, employee_id, total_amount, status, occurred_at, "
                + "created_at) VALUES (?, ?, 1000, 'COMPLETED', ?, ?)", store, UUID.randomUUID(),
                Timestamp.from(yesterday), Timestamp.from(yesterday));
        activity(owner(tenant, "+2376" + digits(8)), tenant, LocalDate.now(WAT));
    }

    /** A product named {@code <tag>-<minutesAgo>}, updated that many minutes ago. */
    private void product(String schema, String tag, int minutesAgo) {
        jdbc.update("INSERT INTO \"" + schema + "\".products (name, sku, updated_at) VALUES (?, ?, now() - ? * interval '1 minute')",
                tag + "-" + minutesAgo, "S" + minutesAgo, minutesAgo);
    }

    private String provision(UUID id, String name) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String schema = "kv_" + suffix;
        new TenantSchemaProvisioner(dataSource).provision(id.toString(), schema);
        schemas.add(schema);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', ?)",
                id, "KV-" + suffix.toUpperCase(), schema, name);
        tenantIds.add(id);
        return schema;
    }

    private UUID owner(UUID tenantId, String phone) {
        UUID userId = UUID.randomUUID();
        users.add(userId);
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'OWNER')",
                userId, phone);
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, "
                + "tenant_id, user_id) VALUES (?, now(), now(), true, 'OWNER', ?, ?)",
                UUID.randomUUID(), tenantId, userId);
        return userId;
    }

    private void activity(UUID user, UUID tenantId, LocalDate day) {
        jdbc.update("INSERT INTO public.user_daily_activity (user_id, tenant_id, activity_date) VALUES (?, ?, ?)",
                user, tenantId, Date.valueOf(day));
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

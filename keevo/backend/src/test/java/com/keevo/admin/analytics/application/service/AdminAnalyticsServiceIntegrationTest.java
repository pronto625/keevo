package com.keevo.admin.analytics.application.service;

import com.keevo.admin.analytics.domain.model.*;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.infrastructure.config.ClockConfig;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 9-4 — the SQL of {@link AdminAnalyticsService} on a real PostgreSQL (Flyway-migrated public incl.
 * V18 + provisioned tenant schemas). Runs outside the test transaction, as in production. Other test
 * classes share the database: assertions target the data seeded here (lower bounds or before/after deltas).
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({AdminAnalyticsService.class, ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AdminAnalyticsService on PostgreSQL (9-4)")
class AdminAnalyticsServiceIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId WAT = ZoneId.of("Africa/Douala");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AdminAnalyticsService service;
    @MockitoBean OwnerRecipientResolver ownerRecipientResolver;

    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();

    private UUID tenant;
    private UUID otherTenant;
    private String schema;
    private String warehouseName;
    private String storeName;
    private String oldStoreName;
    private Instant now;

    @BeforeEach
    void seed() {
        now = Instant.now();
        tenant = UUID.randomUUID();
        schema = provisionTenant(tenant, "Boutique Analytics", true, now);
        String suffix = schema.substring(3);

        storeName = "Magasin " + suffix;
        oldStoreName = "Ancien " + suffix;
        UUID store = store(storeName);
        UUID oldStore = store(oldStoreName);
        Instant recent = now.minus(1, ChronoUnit.MINUTES);
        sale(store, 3000, "COMPLETED", recent);
        sale(store, 1000, "COMPLETED", recent);
        sale(store, 9999, "CANCELLED", recent);
        sale(oldStore, 2000, "COMPLETED", recent);
        sale(oldStore, 1000, "COMPLETED", now.minus(40, ChronoUnit.DAYS)); // previous 30-day period
        warehouseName = "Entrepôt " + suffix;
        UUID warehouse = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type) VALUES (?, ?, 'WAREHOUSE')",
                warehouse, warehouseName);
        sale(warehouse, 99_999, "COMPLETED", recent); // never ranked

        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku) VALUES (?, ?, ?)",
                product, "Produit " + suffix, "SKU" + suffix);
        jdbc.update("INSERT INTO \"" + schema + "\".products (name, sku, archived) VALUES (?, ?, true)",
                "Archivé " + suffix, "ARC" + suffix);

        // Feature footprints of the last 30 days.
        jdbc.update("INSERT INTO \"" + schema + "\".inventory_sessions (store_id, scope, started_by) VALUES (?, 'FULL', ?)",
                store, UUID.randomUUID());
        jdbc.update("INSERT INTO \"" + schema + "\".stock_transfers (source_store_id, destination_store_id, product_id, "
                + "quantity, actor_id) VALUES (?, ?, ?, 1, ?)", store, oldStore, product, UUID.randomUUID());
        jdbc.update("INSERT INTO \"" + schema + "\".reports (id, tenant_id, store_id, report_type, report_date, content, "
                + "delivery_status) VALUES (?, ?, ?, 'EOD', CURRENT_DATE, 'x', 'SENT')",
                UUID.randomUUID(), schema, store);
        jdbc.update("INSERT INTO \"" + schema + "\".tenant_preferences (stock_alert_enabled) VALUES (true)");
        jdbc.update("INSERT INTO \"" + schema + "\".notification_cooldowns (cooldown_type, product_id, store_id, "
                + "last_sent_at) VALUES ('STOCK_ALERT', ?, ?, now())", product, store);
        jdbc.update("INSERT INTO \"" + schema + "\".sync_operations_log (id, operation_type, status) VALUES (?, 'SALE', 'OK')",
                UUID.randomUUID().toString());

        // Valid schema name, never provisioned: its queries fail → skipped, never a 500.
        otherTenant = UUID.randomUUID();
        provisionTenant(otherTenant, "Boutique Cassée", false, now);
    }

    @AfterEach
    void cleanup() {
        for (UUID u : users) jdbc.update("DELETE FROM public.user_daily_activity WHERE user_id = ?", u);
        for (UUID id : tenantIds) jdbc.update("DELETE FROM public.tenants WHERE id = ?", id);
        for (String s : schemas) jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE");
    }

    @Test
    @DisplayName("overview: DAU/MAU from user_daily_activity, totals include our seeded tenant")
    void overview_realSql() {
        AnalyticsOverview before = service.getOverview();
        activity(tenant, today());
        activity(tenant, today().minusDays(10));

        AnalyticsOverview o = service.getOverview();

        assertThat(o.dau()).isEqualTo(before.dau() + 1);
        assertThat(o.mau()).isEqualTo(before.mau() + 2);
        assertThat(o.dauMauRatio()).isNotNull();
        assertThat(o.totalTenants()).isGreaterThanOrEqualTo(2);
        assertThat(o.totalStores()).isGreaterThanOrEqualTo(3); // provisioned store + 2 seeded
        assertThat(o.activeStores()).isGreaterThanOrEqualTo(2);
        assertThat(o.totalProducts()).isGreaterThanOrEqualTo(1);
        assertThat(o.totalCompletedSales()).isGreaterThanOrEqualTo(4);
        assertThat(o.totalGmv()).isGreaterThanOrEqualTo(7000);
    }

    @Test
    @DisplayName("DISTINCT users: one user active on several days and in two tenants counts once (DAU, MAU, month MAU)")
    void distinctUsers_realSql() {
        AnalyticsOverview before = service.getOverview();
        GrowthPoint monthBefore = currentMonth(service.getGrowth("1"));
        UUID user = UUID.randomUUID();
        activity(user, tenant, today());
        activity(user, tenant, today().minusDays(1));
        activity(user, otherTenant, today());

        AnalyticsOverview after = service.getOverview();
        GrowthPoint monthAfter = currentMonth(service.getGrowth("1"));

        assertThat(after.dau()).isEqualTo(before.dau() + 1);
        assertThat(after.mau()).isEqualTo(before.mau() + 1);
        assertThat(monthAfter.mau()).isEqualTo(monthBefore.mau() + 1);
    }

    private GrowthPoint currentMonth(List<GrowthPoint> points) {
        return points.stream().filter(p -> p.month().equals(YearMonth.from(today()).toString()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("growth: WAT month grouping, our sales and tenant in the current month")
    void growth_realSql() {
        List<GrowthPoint> points = service.getGrowth("3");

        assertThat(points).hasSize(3);
        GrowthPoint current = points.get(2);
        assertThat(current.month()).isEqualTo(YearMonth.from(today()).toString());
        assertThat(current.gmv()).isGreaterThanOrEqualTo(6000);
        assertThat(current.salesCount()).isGreaterThanOrEqualTo(3);
        assertThat(current.newTenants()).isGreaterThanOrEqualTo(2);
        assertThat(current.cumulativeTenants()).isGreaterThanOrEqualTo(current.newTenants());
    }

    @Test
    @DisplayName("top stores: store name joined, CANCELLED excluded, growth vs previous period")
    void topStores_realSql() {
        // Narrow range on today only to keep our stores in the top 20 of a shared database.
        String day = today().toString();
        TopStoresReport report = service.getTopStores(day, day);

        TopStore store = report.stores().stream().filter(s -> s.storeName().equals(storeName)).findFirst().orElseThrow();
        assertThat(store.tenantName()).isEqualTo("Boutique Analytics");
        assertThat(store.gmv()).isEqualTo(4000);
        assertThat(store.transactions()).isEqualTo(2);
        assertThat(store.averageBasket()).isEqualTo(2000);
        assertThat(store.growthPercent()).isNull();
        assertThat(report.stores()).noneMatch(s -> s.storeName().equals(warehouseName));

        TopStoresReport month = service.getTopStores(null, null);
        TopStore old = month.stores().stream().filter(s -> s.storeName().equals(oldStoreName)).findFirst().orElseThrow();
        assertThat(old.gmv()).isEqualTo(2000);
        assertThat(old.growthPercent()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("adoption: our active tenant uses every seeded feature (before/after delta)")
    void adoption_realSql() {
        AdoptionReport before = service.getAdoption();
        activity(tenant, today());

        AdoptionReport after = service.getAdoption();

        assertThat(after.activeTenants()).isEqualTo(before.activeTenants() + 1);
        for (int i = 0; i < after.features().size(); i++) {
            assertThat(after.features().get(i).tenantCount())
                    .as(after.features().get(i).feature())
                    .isEqualTo(before.features().get(i).tenantCount() + 1);
        }
    }

    @Test
    @DisplayName("retention: ISO-week grouping of activity, cohort of a tenant created two weeks ago")
    void retention_realSql() {
        LocalDate currentWeek = today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate cohortWeek = currentWeek.minusWeeks(2);
        UUID old = UUID.randomUUID();
        provisionTenant(old, "Boutique Cohorte", false, cohortWeek.atTime(12, 0).atZone(WAT).toInstant());
        activity(old, cohortWeek.plusWeeks(1).plusDays(3)); // Thursday of W+1

        RetentionReport report = service.getRetention(null);

        RetentionCohort cohort = report.cohorts().stream()
                .filter(c -> c.weekStart().equals(cohortWeek)).findFirst().orElseThrow();
        assertThat(cohort.tenantCount()).isGreaterThanOrEqualTo(1);
        assertThat(cohort.retention().get(0)).isNotNull().isGreaterThan(0.0);
        assertThat(cohort.retention().get(1)).isNull(); // W+2 = current week, not finished
        assertThat(cohort.retention().get(2)).isNull(); // W+3 is next week
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LocalDate today() {
        return LocalDate.now(WAT);
    }

    private String provisionTenant(UUID id, String name, boolean provision, Instant createdAt) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String s = "kv_" + suffix;
        if (provision) {
            new TenantSchemaProvisioner(dataSource).provision(id.toString(), s);
            schemas.add(s);
        }
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, ?, now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', ?)",
                id, Timestamp.from(createdAt), "KV-" + suffix.toUpperCase(), s, name);
        tenantIds.add(id);
        return s;
    }

    private UUID store(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type) VALUES (?, ?, 'STORE')", id, name);
        return id;
    }

    private void sale(UUID store, int amount, String status, Instant occurredAt) {
        jdbc.update("INSERT INTO \"" + schema + "\".sales (store_id, employee_id, total_amount, status, occurred_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                store, UUID.randomUUID(), amount, status, Timestamp.from(occurredAt), Timestamp.from(occurredAt));
    }

    private void activity(UUID tenantId, LocalDate day) {
        activity(UUID.randomUUID(), tenantId, day);
    }

    private void activity(UUID user, UUID tenantId, LocalDate day) {
        users.add(user);
        jdbc.update("INSERT INTO public.user_daily_activity (user_id, tenant_id, activity_date) VALUES (?, ?, ?)",
                user, tenantId, Date.valueOf(day));
    }
}

package com.keevo.admin.revenue.application.service;

import com.keevo.admin.revenue.domain.model.ChurnReport;
import com.keevo.admin.revenue.domain.model.RevenueSummary;
import com.keevo.admin.revenue.domain.model.RevenueTrendPoint;
import com.keevo.admin.revenue.domain.port.in.RevenueRangeQuery;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 9-3 — the SQL of {@link AdminRevenueService} on a real PostgreSQL (Flyway-migrated public incl. V17 +
 * provisioned tenant schemas): payments and plan events joined on tenants (DELETED excluded), plan counts read
 * from the tenant schemas, GMV of the 30 days before a churn.
 *
 * <p>The clock is fixed in June 2031 so the seeded months hold only this test's payments/events (other test
 * classes may share the database). Runs outside the test transaction, as in production.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({AdminRevenueService.class, AdminRevenueServiceIntegrationTest.FixedClock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AdminRevenueService on PostgreSQL (9-3)")
class AdminRevenueServiceIntegrationTest extends PostgresIntegrationTest {

    /** 2031-06-15 11:00 WAT. */
    static final Instant NOW = Instant.parse("2031-06-15T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AdminRevenueService service;

    private static final UUID SYSTEM_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();
    private boolean systemTenantInserted;
    /** Summary of June 2031 before seeding: plan counts of other test classes' tenants. */
    private RevenueSummary baseline;

    @BeforeEach
    void seed() {
        baseline = service.getSummary(new RevenueRangeQuery(null, null));

        // A: trial since January, converted in June with a 30-day payment covering today; schema says PREMIUM.
        UUID a = tenant("Revenus Alpha", "ACTIVE", true);
        plan(a, "PREMIUM");
        event(a, null, "PREMIUM_TRIAL", "REGISTRATION", "2031-01-01T10:00:00Z");
        event(a, "PREMIUM_TRIAL", "PREMIUM", "ADMIN_ACTIVATION", "2031-06-01T10:00:00Z");
        payment(a, 15_000, "2031-06-01T10:00:00Z", LocalDate.of(2031, 6, 1), LocalDate.of(2031, 6, 30));

        // B: paid 45 000 for 91 days (March–May), expired on 31/05 → churned in May.
        UUID b = tenant("Revenus Beta", "ACTIVE", true);
        plan(b, "FREE");
        payment(b, 45_000, "2031-03-01T10:00:00Z", LocalDate.of(2031, 3, 1), LocalDate.of(2031, 5, 30));
        event(b, "PREMIUM", "FREE", "EXPIRY", "2031-05-31T01:00:00Z");
        String bSchema = schemas.get(schemas.size() - 1);
        sale(bSchema, 5_000, "COMPLETED", Instant.parse("2031-05-20T10:00:00Z"));
        sale(bSchema, 7_000, "CANCELLED", Instant.parse("2031-05-20T11:00:00Z"));
        sale(bSchema, 9_000, "COMPLETED", Instant.parse("2031-04-01T10:00:00Z")); // > 30 days before

        // C: deleted tenant (schema still there, on PREMIUM) with a payment covering today → never counted.
        UUID c = tenant("Revenus Supprimé", "DELETED", true);
        plan(c, "PREMIUM");
        payment(c, 99_000, "2031-06-01T10:00:00Z", LocalDate.of(2031, 6, 1), LocalDate.of(2031, 6, 30));
        event(c, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", "2031-05-10T10:00:00Z");

        // D: suspended tenant on FREE → counted as free.
        UUID d = tenant("Revenus Suspendu", "SUSPENDED", true);
        plan(d, "FREE");

        // E: PREMIUM in its schema without any payment (legacy / gifted) → not in MRR, flagged.
        UUID e = tenant("Revenus Offert", "ACTIVE", true);
        plan(e, "PREMIUM");

        // System tenant (KV-ADMIN): a payment covering today and a churn in May → never counted.
        systemTenantInserted = jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, "
                + "max_products, max_stores, plan_type, schema_name, status, name) "
                + "VALUES (?, now(), now(), 'KV-ADMIN', 0, 0, 0, 'PREMIUM', 'public', 'ACTIVE', 'Keevo Admin') "
                + "ON CONFLICT DO NOTHING", SYSTEM_TENANT) == 1;
        payment(SYSTEM_TENANT, 50_000, "2031-06-01T10:00:00Z", LocalDate.of(2031, 4, 1), LocalDate.of(2031, 6, 30));
        event(SYSTEM_TENANT, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", "2031-05-15T10:00:00Z");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", SYSTEM_TENANT);
        jdbc.update("DELETE FROM public.tenant_plan_events WHERE tenant_id = ?", SYSTEM_TENANT);
        if (systemTenantInserted) jdbc.update("DELETE FROM public.tenants WHERE id = ?", SYSTEM_TENANT);
        for (UUID id : tenantIds) {
            jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM public.tenant_plan_events WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM public.tenants WHERE id = ?", id);
        }
        for (String s : schemas) jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE");
    }

    @Test
    @DisplayName("summary: MRR of payments covering today (deleted tenant excluded), conversion, plan counts")
    void summary_realSql() {
        RevenueSummary s = service.getSummary(new RevenueRangeQuery(null, null));

        assertThat(s.from()).isEqualTo(LocalDate.of(2031, 6, 1));
        assertThat(s.to()).isEqualTo(LocalDate.of(2031, 6, 30));
        assertThat(s.mrr()).isEqualTo(15_000);
        assertThat(s.arr()).isEqualTo(180_000);
        assertThat(s.payingTenants()).isEqualTo(1);
        assertThat(s.mrrPreviousMonth()).isZero();
        assertThat(s.mrrTrendPercent()).isNull();
        assertThat(s.newPayingTenants()).isEqualTo(1);
        assertThat(s.revenueCollected()).isEqualTo(15_000);
        assertThat(s.convertedTenants()).isEqualTo(1);
        assertThat(s.trialsEnded()).isEqualTo(1);
        // Plan counts read from the schemas: B and D (suspended) free, A and E premium, C deleted ignored.
        assertThat(s.freeTenants() - baseline.freeTenants()).isEqualTo(2);
        assertThat(s.trialTenants() - baseline.trialTenants()).isZero();
        assertThat(s.premiumTenants() - baseline.premiumTenants()).isEqualTo(2);
        assertThat(s.premiumWithoutPayment() - baseline.premiumWithoutPayment()).isEqualTo(1);
    }

    @Test
    @DisplayName("summary over May 2031: one churn (Beta), base = paying on 30/04 ∪ churned; system/deleted excluded")
    void summary_churnRange_realSql() {
        RevenueSummary s = service.getSummary(new RevenueRangeQuery("2031-05-01", "2031-05-31"));

        assertThat(s.churnedTenants()).isEqualTo(1);
        assertThat(s.churnBase()).isEqualTo(1);
        assertThat(s.churnRate()).isEqualTo(1.0);
        assertThat(s.revenueCollected()).isZero();
    }

    @Test
    @DisplayName("trend: 12 months ending June 2031, MRR / new paying / churn per month")
    void trend_realSql() {
        List<RevenueTrendPoint> points = service.getTrend();

        assertThat(points).hasSize(12);
        assertThat(points.get(11).month()).isEqualTo(YearMonth.of(2031, 6));
        assertThat(points.get(11).mrr()).isEqualTo(15_000);
        RevenueTrendPoint march = points.get(8);
        assertThat(march.month()).isEqualTo(YearMonth.of(2031, 3));
        assertThat(march.mrr()).isEqualTo(14_835);
        assertThat(march.newPayingTenants()).isEqualTo(1);
        RevenueTrendPoint may = points.get(10);
        assertThat(may.mrr()).isZero();
        assertThat(may.churnedTenants()).isEqualTo(1);
    }

    @Test
    @DisplayName("churn May 2031: Beta, plan end 30/05, tenure 3.0 months, GMV of the 30 days up to the end, rate 100 %")
    void churn_realSql() {
        ChurnReport report = service.getChurn("2031-05");

        assertThat(report.payingAtMonthStart()).isEqualTo(1);
        assertThat(report.churnRate()).isEqualTo(1.0);
        assertThat(report.tenants()).singleElement().satisfies(t -> {
            assertThat(t.tenantName()).isEqualTo("Revenus Beta");
            assertThat(t.planEndDate()).isEqualTo(LocalDate.of(2031, 5, 30));
            assertThat(t.tenureMonths()).isEqualTo(3.0);
            assertThat(t.lastGmv30d()).isEqualTo(5_000L);
        });
    }

    @Test
    @DisplayName("9-2 — a voided payment (typo corrected) counts nowhere: MRR, new paying, revenue collected")
    void voidedPayment_excluded_realSql() {
        UUID f = tenant("Revenus Annulé", "ACTIVE", true);
        plan(f, "PREMIUM");
        payment(f, 900_000, "2031-06-10T10:00:00Z", LocalDate.of(2031, 6, 10), LocalDate.of(2031, 7, 9));
        jdbc.update("UPDATE public.tenant_payments SET voided_at = now(), voided_by = ?, void_reason = 'Erreur de saisie' "
                + "WHERE tenant_id = ?", UUID.randomUUID(), f);

        RevenueSummary s = service.getSummary(new RevenueRangeQuery(null, null));

        assertThat(s.mrr()).isEqualTo(15_000);
        assertThat(s.payingTenants()).isEqualTo(1);
        assertThat(s.newPayingTenants()).isEqualTo(1);
        assertThat(s.revenueCollected()).isEqualTo(15_000);
    }

    private UUID tenant(String name, String status, boolean provision) {
        UUID id = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String schema = "kv_" + suffix;
        if (provision) {
            new TenantSchemaProvisioner(dataSource).provision(id.toString(), schema);
            schemas.add(schema);
        }
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, ?, ?)",
                id, "KV-" + suffix.toUpperCase(), schema, status, name);
        tenantIds.add(id);
        return id;
    }

    private void plan(UUID tenant, String plan) {
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class, tenant);
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = ?", plan);
    }

    private void payment(UUID tenant, int amount, String paidAt, LocalDate start, LocalDate end) {
        jdbc.update("INSERT INTO public.tenant_payments (tenant_id, amount_xaf, method, paid_at, period_start, "
                        + "period_end, recorded_by) VALUES (?, ?, 'CASH', ?, ?, ?, ?)",
                tenant, amount, Timestamp.from(Instant.parse(paidAt)), Date.valueOf(start), Date.valueOf(end),
                UUID.randomUUID());
    }

    private void event(UUID tenant, String from, String to, String cause, String at) {
        jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                tenant, from, to, cause, Timestamp.from(Instant.parse(at)));
    }

    private void sale(String schema, int amount, String status, Instant occurredAt) {
        jdbc.update("INSERT INTO \"" + schema + "\".sales (store_id, employee_id, total_amount, status, occurred_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), amount, status,
                Timestamp.from(occurredAt), Timestamp.from(occurredAt));
    }
}

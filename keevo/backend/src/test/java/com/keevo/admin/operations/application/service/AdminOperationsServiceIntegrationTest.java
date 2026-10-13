package com.keevo.admin.operations.application.service;

import com.keevo.admin.operations.domain.model.GmvTrendPoint;
import com.keevo.admin.operations.domain.model.InactiveTenant;
import com.keevo.admin.operations.domain.model.TenantActivity;
import com.keevo.admin.operations.domain.port.in.OperationsPeriodQuery;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 9.7 — the SQL of {@link AdminOperationsService} on a real PostgreSQL (Flyway-migrated public +
 * provisioned tenant schemas): COMPLETED-only GMV, COALESCE(occurred_at, created_at), WAT day grouping,
 * MAX(sale, audit) last activity, plan from the tenant subscriptions table, broken schema skipped.
 *
 * <p>Runs outside the test transaction ({@code NOT_SUPPORTED}), as in production. Other test classes may
 * share the database, so assertions target the tenants seeded here.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({AdminOperationsService.class, ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AdminOperationsService on PostgreSQL (9-7)")
class AdminOperationsServiceIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId WAT = ZoneId.of("Africa/Douala");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AdminOperationsService service;

    private final List<UUID> tenantIds = new ArrayList<>();
    private final List<String> schemas = new ArrayList<>();

    private UUID activeTenant;
    private UUID dormantTenant;
    private UUID brokenTenant;
    private String activeSchema;
    private Instant yesterday;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();
        yesterday = now.minus(1, ChronoUnit.DAYS);

        activeTenant = UUID.randomUUID();
        activeSchema = provisionTenant(activeTenant, "Boutique Active", true);
        jdbc.update("INSERT INTO \"" + activeSchema + "\".stores (name, type) VALUES ('Entrepôt', 'WAREHOUSE')");
        sale(activeSchema, 1000, "COMPLETED", yesterday, now);
        sale(activeSchema, 500, "COMPLETED", null, yesterday);          // created_at fallback
        sale(activeSchema, 300, "CANCELLED", yesterday, now);
        sale(activeSchema, 200, "PENDING_VALIDATION", yesterday, now);
        sale(activeSchema, 999, "COMPLETED", now.minus(10, ChronoUnit.DAYS), now); // outside 7D

        dormantTenant = UUID.randomUUID();
        String dormantSchema = provisionTenant(dormantTenant, "Boutique Dormante", true);
        sale(dormantSchema, 400, "COMPLETED", now.minus(20, ChronoUnit.DAYS), now.minus(20, ChronoUnit.DAYS));
        audit(dormantSchema, now.minus(15, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS));

        // Valid schema name, but the schema was never provisioned: its queries fail → skipped.
        brokenTenant = UUID.randomUUID();
        provisionTenant(brokenTenant, "Boutique Cassée", false);
    }

    @AfterEach
    void cleanup() {
        for (UUID id : tenantIds) jdbc.update("DELETE FROM public.tenants WHERE id = ?", id);
        for (String s : schemas) jdbc.execute("DROP SCHEMA IF EXISTS \"" + s + "\" CASCADE");
    }

    @Test
    @DisplayName("tenant activity: COMPLETED sales in window only, last activity, plan, stores")
    void tenantActivity_realSql() {
        List<TenantActivity> rows = service.listTenantActivity(
                new OperationsPeriodQuery("7D", null, null), 0, 100).content();

        TenantActivity active = find(rows, activeTenant).orElseThrow();
        assertThat(active.gmv()).isEqualTo(1500L);
        assertThat(active.orderCount()).isEqualTo(2L);
        assertThat(active.tenantName()).isEqualTo("Boutique Active");
        // Seeded by the provisioner in the tenant schema (public.tenants.plan_type is FREE): proves the subscriptions read.
        assertThat(active.plan()).isEqualTo("PREMIUM_TRIAL");
        assertThat(active.lastActivityAt()).isNotNull();
        // Provisioner seeds one STORE; the WAREHOUSE added in seed() is not counted.
        assertThat(active.storeCount()).isEqualTo(
                jdbc.queryForObject("SELECT COUNT(*) FROM \"" + activeSchema + "\".stores WHERE is_active AND type = 'STORE'",
                        Integer.class));
        assertThat(active.storeCount()).isGreaterThan(0);
        assertThat(find(rows, brokenTenant)).isEmpty();
    }

    @Test
    @DisplayName("trend: grouped by WAT day, our sales counted on the right day")
    void trend_realSql() {
        LocalDate day = LocalDate.ofInstant(yesterday, WAT);

        List<GmvTrendPoint> points = service.getTrend(new OperationsPeriodQuery("7D", null, null));

        assertThat(points).hasSize(7);
        GmvTrendPoint point = points.stream().filter(p -> p.date().equals(day)).findFirst().orElseThrow();
        assertThat(point.gmv()).isGreaterThanOrEqualTo(1500L);
        assertThat(point.orderCount()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("overview: our tenant counted as active and new, broken tenant skipped")
    void overview_realSql() {
        var overview = service.getOverview(new OperationsPeriodQuery("7D", null, null));

        assertThat(overview.totalGmv()).isGreaterThanOrEqualTo(1500L);
        assertThat(overview.activeTenantsCount()).isGreaterThanOrEqualTo(1);
        assertThat(overview.newTenantsCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("inactive: MAX(sale, audit) older than 14 days listed; active tenant not listed")
    void inactive_realSql() {
        List<InactiveTenant> inactive = service.listInactiveTenants();

        InactiveTenant dormant = inactive.stream()
                .filter(t -> t.tenantId().equals(dormantTenant.toString())).findFirst().orElseThrow();
        assertThat(dormant.daysSinceLastActivity()).isEqualTo(15L);
        assertThat(inactive).noneMatch(t -> t.tenantId().equals(activeTenant.toString()));
        assertThat(inactive).noneMatch(t -> t.tenantId().equals(brokenTenant.toString()));
    }

    private static Optional<TenantActivity> find(List<TenantActivity> rows, UUID id) {
        return rows.stream().filter(r -> r.tenantId().equals(id.toString())).findFirst();
    }

    private String provisionTenant(UUID id, String name, boolean provision) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String schema = "kv_" + suffix;
        if (provision) {
            new TenantSchemaProvisioner(dataSource).provision(id.toString(), schema);
            schemas.add(schema);
        }
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', ?)",
                id, "KV-" + suffix.toUpperCase(), schema, name);
        tenantIds.add(id);
        return schema;
    }

    private void sale(String schema, int amount, String status, Instant occurredAt, Instant createdAt) {
        jdbc.update("INSERT INTO \"" + schema + "\".sales (store_id, employee_id, total_amount, status, occurred_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), amount, status,
                occurredAt == null ? null : Timestamp.from(occurredAt), Timestamp.from(createdAt));
    }

    private void audit(String schema, Instant occurredAt) {
        jdbc.update("INSERT INTO \"" + schema + "\".audit_log (user_id, entity_type, entity_id, action, occurred_at) "
                        + "VALUES (?, 'SALE', ?, 'TEST', ?)",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(occurredAt));
    }
}

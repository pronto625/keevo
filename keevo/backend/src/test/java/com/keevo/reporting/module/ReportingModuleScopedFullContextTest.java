package com.keevo.reporting.module;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.event.catalog.sale.DayClosedEvent;
import com.keevo.shared.event.catalog.sale.DayClosureSummary;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.9 — Reporting converted: its end-of-day listener ({@code @TransactionalEventListener}, synchronous) is
 * {@code @ModuleScoped("reporting")}, in the real application context on PostgreSQL (real publication registry,
 * real report generator, WhatsApp no-op). DISABLED for the tenant → day closed, no report, publication completed;
 * READ_ONLY → the report is generated; ENABLED (no row) → generated as today.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Reporting @ModuleScoped listeners — full context (Story 13.9)")
class ReportingModuleScopedFullContextTest extends FullContextIntegrationTest {

    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ModuleRegistry registry;

    private String schema;
    private UUID tenantId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId); // test tenant only
        registry.evict(schema);
    }

    private void reportingRow(String status, Instant expiresAt, Instant graceUntil) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, expires_at, grace_until) "
                + "VALUES (?, 'reporting', ?, 'FREE', ?, ?) ON CONFLICT (tenant_id, module_id) DO UPDATE SET "
                + "status = EXCLUDED.status, expires_at = EXCLUDED.expires_at, grace_until = EXCLUDED.grace_until",
                tenantId, status, expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                graceUntil == null ? null : java.sql.Timestamp.from(graceUntil));
        registry.evict(schema);
    }

    /** Publishes a day closure in a committed transaction (the synchronous AFTER_COMMIT listener runs then). */
    private UUID closeDay() {
        UUID closureId = UUID.randomUUID();
        Instant now = Instant.now();
        DayClosedEvent event = new DayClosedEvent(closureId, storeId, UUID.randomUUID(),
                new DayClosureSummary(0, 0, null, null, 0, 0, 0, 0, 0), false, schema, now,
                now.minus(10, ChronoUnit.HOURS), now);
        tx.executeWithoutResult(s -> publisher.publishEvent(event));
        return closureId;
    }

    private int reports() {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".reports", Integer.class);
    }

    private List<Map<String, Object>> publications(UUID closureId) {
        return jdbc.queryForList("SELECT completion_date FROM public.event_publication WHERE listener_id LIKE ? "
                + "AND serialized_event LIKE ?", "%EndOfDayReportListener%", "%" + closureId + "%");
    }

    @Test
    @DisplayName("reporting DISABLED → day closed, no end-of-day report, publication completed")
    void disabledNoReport() {
        reportingRow("DISABLED", null, null);
        assertThat(registry.moduleState(schema, "reporting")).isEqualTo(ModuleState.DISABLED);

        UUID closureId = closeDay();

        assertThat(reports()).isZero();
        assertThat(publications(closureId)).singleElement()
                .satisfies(row -> assertThat(row.get("completion_date")).isNotNull());
    }

    @Test
    @DisplayName("reporting READ_ONLY (expired, in grace) → the report is generated")
    void readOnlyReport() {
        Instant now = Instant.now();
        reportingRow("ACTIVE", now.minus(1, ChronoUnit.DAYS), now.plus(6, ChronoUnit.DAYS));
        assertThat(registry.moduleState(schema, "reporting")).isEqualTo(ModuleState.READ_ONLY);

        closeDay();

        assertThat(reports()).isEqualTo(1);
    }

    @Test
    @DisplayName("reporting ENABLED by the free rule (no row) → the report is generated as today")
    void enabledReport() {
        closeDay();
        assertThat(reports()).isEqualTo(1);
    }
}

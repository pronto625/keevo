package com.keevo.sync.sync.application.provider;

import com.keevo.reporting.report.adapter.out.persistence.EndOfDayReportJpaEntity;
import com.keevo.reporting.report.adapter.out.persistence.EndOfDayReportSpringRepository;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sync pull on a real PostgreSQL: a report changed after its creation reaches the pull
 * (updated_at cursor), a pulled closure carries its stored summary, pulled dates are ISO
 * instants, and old tenant schemas get reports.updated_at backfilled from created_at.
 *
 * <p>Commits on a uniquely named tenant schema, dropped in {@link #dropSchema()}; the JPA test
 * writes one report in {@code public} and deletes it.</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({TenantSchemaProvisioner.class, TenantSchemaSyncService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Sync pull carries later changes and summaries (PostgreSQL)")
class SyncPullUpdatesPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "kv_spu001";
    private static final String T = "\"" + SCHEMA + "\".";
    private static final Instant CREATED = Instant.parse("2026-10-14T19:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired TenantSchemaSyncService schemaSync;
    @Autowired EndOfDayReportSpringRepository reports;

    @BeforeEach
    void provision() {
        provisioner.provision("tenant-" + SCHEMA, SCHEMA);
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    @DisplayName("reports: a delivery status changed after the client's cursor is pulled again")
    void reportUpdatedAfterCursorIsPulled() {
        UUID id = UUID.randomUUID();
        insertReport(id, "PENDING", CREATED);
        Instant clientCursor = CREATED.plus(1, ChronoUnit.HOURS);
        assertThat(pull(new ReportsDeltaProvider(em), clientCursor)).isEmpty();

        // A later delivery attempt (EndOfDayReportJpaEntity stamps updated_at on every write)
        jdbc.update("UPDATE " + T + "reports SET delivery_status = 'SENT', delivery_attempts = 1, "
                + "updated_at = ? WHERE id = ?", Timestamp.from(clientCursor.plusSeconds(60)), id);

        List<Map<String, Object>> rows = pull(new ReportsDeltaProvider(em), clientCursor);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("id", id.toString()).containsEntry("deliveryStatus", "SENT");
            assertThat(r).containsEntry("createdAt", "2026-10-14T19:00:00Z");
        });
    }

    @Test
    @DisplayName("dayClosures: the stored summary and ISO instants are sent")
    void dayClosureCarriesSummary() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + T + "day_closures (id, store_id, closed_at, closure_date, total_sales, "
                        + "total_revenue, top_product_id, top_product_name, top_product_qty, cash_amount, "
                        + "momo_amount, pending_sales_count, pending_sales_total, received_at) "
                        + "VALUES (?, ?, ?, ?, 12, 54000, 'prod-1', 'Savon', 7, 40000, 14000, 2, 3500, ?)",
                id, UUID.randomUUID(), Timestamp.from(CREATED), java.sql.Date.valueOf(LocalDate.of(2026, 10, 14)),
                Timestamp.from(CREATED));

        List<Map<String, Object>> rows = pull(new DayClosureDeltaProvider(em), CREATED.minusSeconds(1));
        assertThat(rows).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("topProductId", "prod-1")
                .containsEntry("topProductName", "Savon")
                .containsEntry("topProductQty", 7)
                .containsEntry("pendingSalesCount", 2)
                .containsEntry("pendingSalesTotal", 3500)
                .containsEntry("closedAt", "2026-10-14T19:00:00Z"));
    }

    @Test
    @DisplayName("schema sync: an old tenant gets reports.updated_at, backfilled from created_at")
    void oldTenantReportsBackfilled() throws Exception {
        jdbc.execute("ALTER TABLE " + T + "reports DROP COLUMN updated_at");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + T + "reports (id, tenant_id, store_id, report_type, report_date, content, "
                        + "created_at) VALUES (?, 'tenant', ?, 'DAILY', ?, 'x', ?)",
                id, UUID.randomUUID(), java.sql.Date.valueOf(LocalDate.of(2026, 10, 14)), Timestamp.from(CREATED));

        schemaSync.syncNow(SCHEMA);

        Timestamp updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM " + T + "reports WHERE id = ?", Timestamp.class, id);
        assertThat(updatedAt.toInstant()).isEqualTo(CREATED);
        assertThat(pull(new ReportsDeltaProvider(em), CREATED.minusSeconds(1))).hasSize(1);
    }

    @Test
    @DisplayName("sales: the Mobile Money reference is pulled; null for a cash sale")
    void saleCarriesMobileMoneyRef() {
        UUID momo = UUID.randomUUID();
        UUID cash = UUID.randomUUID();
        insertSale(momo, "MOBILE_MONEY", "MP2603.1234");
        insertSale(cash, "CASH", null);

        List<Map<String, Object>> rows = pull(new SaleDeltaProvider(em), CREATED.minusSeconds(1));
        assertThat(rows).hasSize(2);
        assertThat(rows).filteredOn(r -> momo.toString().equals(r.get("id")))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("mobileMoneyRef", "MP2603.1234"));
        assertThat(rows).filteredOn(r -> cash.toString().equals(r.get("id")))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("mobileMoneyRef", null));
    }

    @Test
    @DisplayName("schema sync: an old tenant gets sales.mobile_money_ref")
    void oldTenantGetsMobileMoneyRef() throws Exception {
        jdbc.execute("ALTER TABLE " + T + "sales DROP COLUMN mobile_money_ref");

        schemaSync.syncNow(SCHEMA);

        Integer columns = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'sales' AND column_name = 'mobile_money_ref'",
                Integer.class, SCHEMA);
        assertThat(columns).isEqualTo(1);
    }

    @Test
    @DisplayName("EndOfDayReportJpaEntity: every save moves updated_at")
    void reportEntityStampsUpdatedAt() {
        UUID id = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.executeWithoutResult(s -> reports.save(report(id, "PENDING", 0)));
            Instant first = reports.findById(id).orElseThrow().getUpdatedAt();
            assertThat(first).isNotNull();

            tx.executeWithoutResult(s -> reports.save(report(id, "SENT", 1)));
            EndOfDayReportJpaEntity saved = reports.findById(id).orElseThrow();
            assertThat(saved.getDeliveryStatus()).isEqualTo("SENT");
            assertThat(saved.getUpdatedAt()).isAfter(first);
        } finally {
            tx.executeWithoutResult(s -> reports.deleteById(id));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void insertReport(UUID id, String status, Instant at) {
        jdbc.update("INSERT INTO " + T + "reports (id, tenant_id, store_id, report_type, report_date, content, "
                        + "delivery_status, created_at, updated_at) VALUES (?, 'tenant', ?, 'DAILY', ?, 'x', ?, ?, ?)",
                id, UUID.randomUUID(), java.sql.Date.valueOf(LocalDate.of(2026, 10, 14)), status,
                Timestamp.from(at), Timestamp.from(at));
    }

    private void insertSale(UUID id, String paymentMode, String mobileMoneyRef) {
        jdbc.update("INSERT INTO " + T + "sales (id, store_id, employee_id, total_amount, payment_mode, "
                        + "mobile_money_ref, created_at, occurred_at, updated_at) VALUES (?, ?, ?, 1500, ?, ?, ?, ?, ?)",
                id, UUID.randomUUID(), UUID.randomUUID(), paymentMode, mobileMoneyRef,
                Timestamp.from(CREATED), Timestamp.from(CREATED), Timestamp.from(CREATED));
    }

    private static EndOfDayReportJpaEntity report(UUID id, String status, int attempts) {
        return new EndOfDayReportJpaEntity(id, "tenant", UUID.randomUUID(), "Boutique", null, null,
                "DAILY", LocalDate.of(2026, 10, 14), "x", status, attempts, null, 0, 0, true, CREATED);
    }

    /** First pull page of a provider, read in the tenant schema (as the request-path tenant filter does). */
    private List<Map<String, Object>> pull(DeltaEntityProvider provider, Instant since) {
        return inTenant(p -> p.queryDeltaPage(since, null, DeltaEntityProvider.PAGE_SIZE).rows(), provider);
    }

    private <R> R inTenant(Function<DeltaEntityProvider, R> work, DeltaEntityProvider provider) {
        return new TransactionTemplate(txManager).execute(s -> {
            em.createNativeQuery("SELECT set_config('search_path', :path, true)")
                    .setParameter("path", "\"" + SCHEMA + "\"")
                    .getSingleResult();
            return work.apply(provider);
        });
    }
}

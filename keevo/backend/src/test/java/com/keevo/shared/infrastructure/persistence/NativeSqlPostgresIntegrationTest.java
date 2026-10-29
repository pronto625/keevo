package com.keevo.shared.infrastructure.persistence;

import com.keevo.inventory.transfer.adapter.out.persistence.StockTransferSpringRepository;
import com.keevo.commerce.sale.adapter.out.persistence.jpa.SaleSpringRepository;
import com.keevo.commerce.sale.domain.model.ClosureWindow;
import com.keevo.sync.sync.adapter.out.persistence.jpa.SyncOperationsLogSpringRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the native / hand-written queries that guard sync idempotency, sale and transfer
 * transitions, and day-closure coverage against a real PostgreSQL 16 migrated by Flyway
 * (V1–V9). Mocks only see the query text; these tests see what PostgreSQL does with it.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@DisplayName("Native SQL on PostgreSQL")
class NativeSqlPostgresIntegrationTest extends PostgresIntegrationTest {


    @Autowired JdbcTemplate jdbc;
    @Autowired SyncOperationsLogSpringRepository syncLog;
    @Autowired SaleSpringRepository sales;
    @Autowired StockTransferSpringRepository transfers;

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    // ── sync_operations_log claim: the guard against applying an operation twice ──

    @Test
    @DisplayName("claim: 1 for a new operation, 1 for a REJECTED one, 0 once settled")
    void claim() {
        String fresh = UUID.randomUUID().toString();
        assertThat(syncLog.claim(fresh, "CREATE_SALE", "e1", NOW, NOW)).isEqualTo(1);

        String rejected = UUID.randomUUID().toString();
        insertLog(rejected, "REJECTED");
        assertThat(syncLog.claim(rejected, "CREATE_SALE", "e1", NOW, NOW)).isEqualTo(1);

        for (String status : new String[] {"APPLIED", "CONFLICT", "DUPLICATE"}) {
            String settled = UUID.randomUUID().toString();
            insertLog(settled, status);
            assertThat(syncLog.claim(settled, "CREATE_SALE", "e1", NOW, NOW))
                    .as("status %s", status).isZero();
        }
    }

    // ── sales: COMPLETED → CANCELLED compare-and-set ──

    @Test
    @DisplayName("updateStatusIfCurrent: first transition wins, second loses, updated_at moves")
    void saleStatusCompareAndSet() {
        UUID id = insertSale(NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        Instant later = NOW.plusSeconds(60);

        assertThat(sales.updateStatusIfCurrent(id, "COMPLETED", "CANCELLED", later)).isEqualTo(1);
        assertThat(sales.updateStatusIfCurrent(id, "COMPLETED", "CANCELLED", later)).isZero();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM sales WHERE id = ?", Timestamp.class, id)
                .toInstant()).isEqualTo(later);
    }

    // ── stock_transfers: status transition compare-and-set ──

    @Test
    @DisplayName("transitionStatusNative: first transition wins, second loses")
    void transferStatusCompareAndSet() {
        // updated_at exists in tenant schemas only (not in the public baseline).
        jdbc.execute("ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stock_transfers (id, actor_id, destination_store_id, occurred_at, product_id, "
                        + "quantity, source_store_id, status) VALUES (?, ?, ?, ?, ?, 1, ?, 'IN_TRANSIT')",
                id, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(NOW), UUID.randomUUID(), UUID.randomUUID());

        assertThat(transfers.transitionStatusNative(id, "IN_TRANSIT", "COMPLETED")).isEqualTo(1);
        assertThat(transfers.transitionStatusNative(id, "IN_TRANSIT", "COMPLETED")).isZero();
    }

    // ── day closure coverage: late offline sales go to the next closure ──

    @Test
    @DisplayName("findForClosure: covers the window's sales and late sales received after the previous closure")
    void findForClosure() {
        Instant previousClosedAt = NOW.minusSeconds(24 * 3600);
        Instant previousReceivedAt = previousClosedAt.plusSeconds(10);
        UUID inWindow = insertSale(NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        UUID lateOffline = insertSale(previousClosedAt.minusSeconds(3600), NOW.minusSeconds(600));
        UUID alreadyClosed = insertSale(previousClosedAt.minusSeconds(3600), previousClosedAt.minusSeconds(3600));
        UUID receivedAfterThisClosure = insertSale(NOW.minusSeconds(60), NOW.plusSeconds(60));

        var window = new ClosureWindow(previousClosedAt.plusNanos(1000), NOW, previousReceivedAt, NOW);
        var ids = sales.findForClosure(STORE, window.start(), window.end(), window.lateFrom(), window.receivedUntil())
                .stream().map(s -> s.getId()).toList();

        assertThat(ids).contains(inWindow, lateOffline)
                .doesNotContain(alreadyClosed, receivedAfterThisClosure);
    }

    @Test
    @DisplayName("V9: day_closures.received_at exists, without default")
    void receivedAtColumn() {
        assertThat(jdbc.queryForObject(
                "SELECT column_default IS NULL FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'day_closures' AND column_name = 'received_at'",
                Boolean.class)).isTrue();
    }

    private static final UUID STORE = UUID.randomUUID();

    private void insertLog(String id, String status) {
        jdbc.update("INSERT INTO sync_operations_log (id, operation_type, status, processed_at) VALUES (?, 'CREATE_SALE', ?, ?)",
                id, status, Timestamp.from(NOW));
    }

    private UUID insertSale(Instant occurredAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sales (id, created_at, employee_id, occurred_at, payment_mode, status, store_id, "
                        + "total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, 1000, 0)",
                id, Timestamp.from(createdAt), UUID.randomUUID(), Timestamp.from(occurredAt), STORE);
        return id;
    }
}

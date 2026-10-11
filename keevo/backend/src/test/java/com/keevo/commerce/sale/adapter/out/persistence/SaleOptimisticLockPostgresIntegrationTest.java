package com.keevo.commerce.sale.adapter.out.persistence;

import com.keevo.commerce.sale.adapter.out.persistence.impl.SaleRepositoryAdapter;
import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story v1s-13-10 — optimistic lock on Sale against a real PostgreSQL 16 migrated by Flyway
 * (V1–V12): the version column, the version bump of the bulk status claims, the version-checked
 * save used by corrections and the versioned COMPLETED→CANCELLED claim used by cancellations.
 *
 * <p>No test-managed transaction: each step runs in its own transaction, like two concurrent
 * requests that each read the sale before the other one commits.</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SaleRepositoryAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@DisplayName("Sale optimistic lock on PostgreSQL (Story v1s-13-10)")
class SaleOptimisticLockPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"));

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired SaleRepositoryAdapter repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a new sale is inserted with version 0")
    void newSaleInsertedWithVersionZero() {
        Sale sale = completedSale(2);

        inTx(() -> repository.save(sale));

        assertThat(dbVersion(sale.getId())).isZero();
        assertThat(inTx(() -> repository.findById(sale.getId()).orElseThrow()).getVersion()).isZero();
    }

    @Test
    @DisplayName("the status compare-and-set bumps the version")
    void statusClaimBumpsVersion() {
        Sale sale = completedSale(2);
        inTx(() -> repository.save(sale));

        assertThat(inTx(() -> repository.updateStatusIfCurrent(
                sale.getId(), SaleStatus.COMPLETED, SaleStatus.CANCELLED))).isTrue();

        assertThat(dbVersion(sale.getId())).isEqualTo(1L);
        assertThat(dbStatus(sale.getId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("correction read before a concurrent cancel → stale save throws, sale stays CANCELLED")
    void staleCorrectionAfterConcurrentCancelFails() {
        Sale sale = completedSale(2);
        inTx(() -> repository.save(sale));

        Sale readByCorrection = inTx(() -> repository.findById(sale.getId()).orElseThrow());
        // The cancel commits first (versioned claim, read at the same version).
        assertThat(inTx(() -> repository.updateStatusIfCurrent(
                sale.getId(), SaleStatus.COMPLETED, readByCorrection.getVersion(), SaleStatus.CANCELLED)))
                .isTrue();

        assertThatThrownBy(() -> inTx(() -> repository.save(corrected(readByCorrection, 1))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(dbStatus(sale.getId())).isEqualTo("CANCELLED");
        assertThat(dbVersion(sale.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT quantity FROM sale_items WHERE sale_id = ?",
                Integer.class, sale.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("two corrections read the same version → the second save throws")
    void secondConcurrentCorrectionFails() {
        Sale sale = completedSale(3);
        inTx(() -> repository.save(sale));

        Sale readA = inTx(() -> repository.findById(sale.getId()).orElseThrow());
        Sale readB = inTx(() -> repository.findById(sale.getId()).orElseThrow());

        inTx(() -> repository.save(corrected(readA, 2)));
        assertThat(dbVersion(sale.getId())).isEqualTo(1L);

        assertThatThrownBy(() -> inTx(() -> repository.save(corrected(readB, 1))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("SELECT quantity FROM sale_items WHERE sale_id = ?",
                Integer.class, sale.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("versioned claim: stale version → false (sale untouched), current version → true")
    void versionedClaim() {
        Sale sale = completedSale(3);
        inTx(() -> repository.save(sale));
        Sale readByCancel = inTx(() -> repository.findById(sale.getId()).orElseThrow());

        // A correction commits first (status unchanged, version 0 → 1).
        Sale readByCorrection = inTx(() -> repository.findById(sale.getId()).orElseThrow());
        inTx(() -> repository.save(corrected(readByCorrection, 1)));

        assertThat(inTx(() -> repository.updateStatusIfCurrent(
                sale.getId(), SaleStatus.COMPLETED, readByCancel.getVersion(), SaleStatus.CANCELLED)))
                .as("cancel that read pre-correction items").isFalse();
        assertThat(dbStatus(sale.getId())).isEqualTo("COMPLETED");

        long current = inTx(() -> repository.findById(sale.getId()).orElseThrow()).getVersion();
        assertThat(inTx(() -> repository.updateStatusIfCurrent(
                sale.getId(), SaleStatus.COMPLETED, current, SaleStatus.CANCELLED))).isTrue();
        assertThat(dbStatus(sale.getId())).isEqualTo("CANCELLED");
        assertThat(dbVersion(sale.getId())).isEqualTo(current + 1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Sale completedSale(int quantity) {
        UUID saleId = UUID.randomUUID();
        var item = new SaleItem(UUID.randomUUID(), saleId, UUID.randomUUID(), null,
                "Produit A", 1000, 1000, quantity);
        return new Sale(saleId, UUID.randomUUID(), UUID.randomUUID(), null, PaymentMode.CASH,
                1000 * quantity, 0, SaleStatus.COMPLETED, NOW, NOW, List.of(item));
    }

    /** Same as CorrectSaleService: same id/items, new quantity, the version that was read. */
    private static Sale corrected(Sale read, int newQuantity) {
        SaleItem item = read.getItems().get(0);
        var newItem = new SaleItem(item.getId(), item.getSaleId(), item.getProductId(), item.getVariantId(),
                item.getProductName(), item.getCatalogueUnitPrice(), item.getAppliedUnitPrice(), newQuantity);
        return new Sale(read.getId(), read.getStoreId(), read.getEmployeeId(), read.getClientId(),
                read.getPaymentMode(), newItem.getSubtotal(), read.getDiscountAmount(), SaleStatus.COMPLETED,
                read.getOccurredAt(), read.getCreatedAt(), List.of(newItem), read.getVersion());
    }

    private long dbVersion(UUID id) {
        return jdbc.queryForObject("SELECT version FROM sales WHERE id = ?", Long.class, id);
    }

    private String dbStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM sales WHERE id = ?", String.class, id);
    }

    private void inTx(Runnable body) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> body.run());
    }

    private <T> T inTx(Supplier<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> body.get());
    }
}

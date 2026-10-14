package com.keevo.commerce.sale.adapter.out.persistence;

import com.keevo.commerce.sale.adapter.out.persistence.impl.SaleRepositoryAdapter;
import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sales.mobile_money_ref on a real PostgreSQL migrated by Flyway (V24): the reference typed at
 * checkout is stored, read back, and survives a status change and a correction.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(SaleRepositoryAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Sale Mobile Money reference on PostgreSQL")
class SaleMobileMoneyRefPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired SaleRepositoryAdapter repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("the reference is stored and read back")
    void referenceIsPersisted() {
        Sale sale = momoSale("MP2603.1234");

        inTx(() -> repository.save(sale));

        assertThat(jdbc.queryForObject("SELECT mobile_money_ref FROM sales WHERE id = ?",
                String.class, sale.getId())).isEqualTo("MP2603.1234");
        assertThat(inTx(() -> repository.findById(sale.getId()).orElseThrow()).getMobileMoneyRef())
                .isEqualTo("MP2603.1234");
    }

    @Test
    @DisplayName("a status change and a correction keep the reference")
    void referenceSurvivesLaterWrites() {
        Sale sale = momoSale("MP2603.1234");
        inTx(() -> repository.save(sale));

        inTx(() -> repository.updateStatus(sale.getId(), SaleStatus.COMPLETED));
        Sale read = inTx(() -> repository.findById(sale.getId()).orElseThrow());
        // Same as CorrectSaleService: rebuilt from the sale read, reference carried over.
        inTx(() -> repository.save(new Sale(read.getId(), read.getStoreId(), read.getEmployeeId(),
                read.getClientId(), read.getPaymentMode(), read.getTotalAmount(), read.getDiscountAmount(),
                SaleStatus.COMPLETED, read.getOccurredAt(), read.getCreatedAt(), read.getItems(),
                read.getVersion(), read.getMobileMoneyRef())));

        assertThat(inTx(() -> repository.findById(sale.getId()).orElseThrow()).getMobileMoneyRef())
                .isEqualTo("MP2603.1234");
    }

    @Test
    @DisplayName("a blank reference is stored as null")
    void blankReferenceIsNull() {
        Sale sale = momoSale("   ");

        inTx(() -> repository.save(sale));

        assertThat(jdbc.queryForObject("SELECT mobile_money_ref FROM sales WHERE id = ?",
                String.class, sale.getId())).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Sale momoSale(String ref) {
        UUID saleId = UUID.randomUUID();
        var item = new SaleItem(UUID.randomUUID(), saleId, UUID.randomUUID(), null,
                "Produit A", 1000, 1000, 2);
        return new Sale(saleId, UUID.randomUUID(), UUID.randomUUID(), null, PaymentMode.MOBILE_MONEY,
                2000, 0, SaleStatus.PENDING_VALIDATION, NOW, NOW, List.of(item), 0L, ref);
    }

    private void inTx(Runnable body) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> body.run());
    }

    private <T> T inTx(Supplier<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> body.get());
    }
}

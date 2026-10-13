package com.keevo.commerce.sale.adapter.out.persistence;

import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleJpaEntity;
import com.keevo.commerce.sale.adapter.out.persistence.impl.SaleRepositoryAdapter;
import com.keevo.commerce.sale.adapter.out.persistence.jpa.SaleSpringRepository;
import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 11.5 (AC3/AC4) — the EMPLOYEE pending-sales list loads the sale items in the same SQL
 * statement ({@code LEFT JOIN FETCH} + {@code DISTINCT}), against a real PostgreSQL migrated by Flyway.
 *
 * <p>No test-managed transaction: the finder runs in its own transaction and the items are read
 * after it closed, so a lazy collection would throw {@code LazyInitializationException}.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Import(SaleRepositoryAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Pending sales fetch their items in one query (Story 11.5)")
class SalePendingFetchJoinPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired SaleRepositoryAdapter adapter;
    @Autowired SaleSpringRepository springRepository;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactionManager;

    /** Sales committed by the current test, deleted (with their items, by cascade) afterwards. */
    private final List<UUID> savedSaleIds = new ArrayList<>();

    @AfterEach
    void deleteSavedSales() {
        tx().executeWithoutResult(s -> springRepository.deleteAllById(savedSaleIds));
        savedSaleIds.clear();
    }

    @Test
    @DisplayName("2 pending sales × 3 items → 2 entities, items loaded, one SQL statement")
    void pendingSalesWithItems_oneStatement_oneRowPerSale() {
        UUID storeId = UUID.randomUUID();
        Sale a = pendingSale(storeId, 3, NOW);
        Sale b = pendingSale(storeId, 3, NOW.minusSeconds(60)); // older → listed first
        Sale completed = sale(storeId, SaleStatus.COMPLETED, 2, NOW);
        tx().executeWithoutResult(s -> {
            save(a);
            save(b);
            // another status in the same store must not leak into the result
            save(completed);
        });

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<SaleJpaEntity> found = tx().execute(s ->
                springRepository.findByStoreIdAndStatus(storeId, SaleStatus.PENDING_VALIDATION.name()));

        assertThat(stats.getPrepareStatementCount()).as("SQL statements for the finder").isEqualTo(1);
        assertThat(found).extracting(SaleJpaEntity::getId).containsExactly(b.getId(), a.getId());
        // read outside the transaction: a lazy collection would throw LazyInitializationException
        assertThat(found).allSatisfy(e -> assertThat(e.getItems()).hasSize(3));
        assertThat(stats.getPrepareStatementCount()).as("no lazy item load afterwards").isEqualTo(1);
    }

    @Test
    @DisplayName("adapter path (ValidateSaleService) maps each pending sale with its full item list, one SQL statement")
    void adapterMapsFullItemList() {
        UUID storeId = UUID.randomUUID();
        Sale a = pendingSale(storeId, 2, NOW);
        Sale b = pendingSale(storeId, 3, NOW);
        tx().executeWithoutResult(s -> {
            save(a);
            save(b);
        });

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<Sale> found = tx().execute(s ->
                adapter.findByStoreIdAndStatus(storeId, SaleStatus.PENDING_VALIDATION));

        assertThat(stats.getPrepareStatementCount()).as("SQL statements for the adapter path").isEqualTo(1);
        assertThat(found).extracting(Sale::getId).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(found).filteredOn(sale -> sale.getId().equals(b.getId()))
                .singleElement().satisfies(sale -> assertThat(sale.getItems()).hasSize(3));

        assertThat(found).filteredOn(sale -> sale.getId().equals(a.getId()))
                .singleElement().satisfies(sale -> assertThat(sale.getItems()).hasSize(2));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void save(Sale sale) {
        adapter.save(sale);
        savedSaleIds.add(sale.getId());
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static Sale pendingSale(UUID storeId, int itemCount, Instant occurredAt) {
        return sale(storeId, SaleStatus.PENDING_VALIDATION, itemCount, occurredAt);
    }

    private static Sale sale(UUID storeId, SaleStatus status, int itemCount, Instant occurredAt) {
        UUID saleId = UUID.randomUUID();
        List<SaleItem> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new SaleItem(UUID.randomUUID(), saleId, UUID.randomUUID(), null,
                    "Produit " + i, 1000, 1000, 1));
        }
        return new Sale(saleId, storeId, UUID.randomUUID(), null, PaymentMode.CASH,
                1000 * itemCount, 0, status, occurredAt, NOW, items);
    }
}

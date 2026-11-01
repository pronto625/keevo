package com.keevo.inventory.transfer.adapter.out.persistence;

import com.keevo.inventory.transfer.domain.model.StockTransfer;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 16.10 — transfer history with the EMPLOYEE store scope, on a real PostgreSQL:
 * data A→B (in transit), B→A, B→C, A→C, C→A. An employee of A sees every transfer A sent or
 * received; a filter on another store is ANDed with the scope (store=B → A↔B only, source=C →
 * C→A only, source=B + destination=C → empty page); the OWNER (no scope) sees all five.
 *
 * <p>Commits a tenant schema, dropped in {@link #dropSchema()}.</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({TenantSchemaProvisioner.class, StockTransferRepositoryAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Transfer history is scoped to the employee's store (Story 16.10)")
class StockTransferHistoryScopePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "kv_ths001";
    private static final String T = "\"" + SCHEMA + "\".";
    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    private static final PageRequest PAGE = PageRequest.of(0, 25,
            Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id")));

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired StockTransferRepositoryAdapter repository;

    private final UUID storeA = UUID.randomUUID();
    private final UUID storeB = UUID.randomUUID();
    private final UUID storeC = UUID.randomUUID();
    private UUID aToB;
    private UUID bToA;
    private UUID bToC;
    private UUID aToC;
    private UUID cToA;

    @BeforeEach
    void seed() {
        provisioner.provision("tenant-" + SCHEMA, SCHEMA);
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO " + T + "products (id, name, price, sku) VALUES (?, 'Produit', 100, 'SKU-THS-1')", product);
        aToB = insertTransfer(product, storeA, storeB, T0, "IN_TRANSIT");
        bToA = insertTransfer(product, storeB, storeA, T0.plusSeconds(60), "COMPLETED");
        bToC = insertTransfer(product, storeB, storeC, T0.plusSeconds(120), "COMPLETED");
        aToC = insertTransfer(product, storeA, storeC, T0.plusSeconds(180), "COMPLETED");
        cToA = insertTransfer(product, storeC, storeA, T0.plusSeconds(240), "COMPLETED");
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    @DisplayName("employee of A, no filter: everything A sent or received")
    void employeeNoFilter() {
        var page = history(null, null, null, null, null, null, storeA);
        assertThat(page.getContent()).extracting(StockTransfer::getId).containsExactly(cToA, aToC, bToA, aToB);
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("employee of A, store=B: only transfers between A and B")
    void employeeStoreFilterOnOtherStore() {
        var page = history(null, null, storeB, null, null, null, storeA);
        assertThat(page.getContent()).extracting(StockTransfer::getId).containsExactly(bToA, aToB);
    }

    @Test
    @DisplayName("employee of A, source=C: only C→A")
    void employeeSourceFilterOnOtherStore() {
        assertThat(history(storeC, null, null, null, null, null, storeA).getContent())
                .extracting(StockTransfer::getId).containsExactly(cToA);
    }

    @Test
    @DisplayName("employee of A, destination=C: only A→C")
    void employeeDestinationFilterOnOtherStore() {
        assertThat(history(null, storeC, null, null, null, null, storeA).getContent())
                .extracting(StockTransfer::getId).containsExactly(aToC);
    }

    @Test
    @DisplayName("employee of A, source=B and destination=C: empty page (B→C never leaks)")
    void employeeFilterOnTransferNotInvolvingOwnStore() {
        var page = history(storeB, storeC, null, null, null, null, storeA);
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("employee of A, status + from/to window: the scope still applies")
    void employeeScopeWithStatusAndWindow() {
        // Window T0+30 s .. T0+200 s holds B→A, B→C, A→C (all COMPLETED); B→C is out of scope.
        var completed = history(null, null, null, StockTransfer.TransferStatus.COMPLETED,
                T0.plusSeconds(30), T0.plusSeconds(200), storeA);
        assertThat(completed.getContent()).extracting(StockTransfer::getId).containsExactly(aToC, bToA);

        var inTransit = history(null, null, null, StockTransfer.TransferStatus.IN_TRANSIT, null, null, storeA);
        assertThat(inTransit.getContent()).extracting(StockTransfer::getId).containsExactly(aToB);
    }

    @Test
    @DisplayName("owner, no filter: all five")
    void ownerSeesAll() {
        var page = history(null, null, null, null, null, null, null);
        assertThat(page.getContent()).extracting(StockTransfer::getId).containsExactly(cToA, aToC, bToC, bToA, aToB);
    }

    private Page<StockTransfer> history(UUID source, UUID destination, UUID store,
                                        StockTransfer.TransferStatus status, Instant from, Instant to, UUID scope) {
        return inTenant(() -> repository.findByFilters(source, destination, from, to, store, status, scope, PAGE));
    }

    private <R> R inTenant(Supplier<R> work) {
        return new TransactionTemplate(txManager).execute(s -> {
            em.createNativeQuery("SELECT set_config('search_path', :path, true)")
                    .setParameter("path", "\"" + SCHEMA + "\"")
                    .getSingleResult();
            return work.get();
        });
    }

    private UUID insertTransfer(UUID product, UUID source, UUID destination, Instant at, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + T + "stock_transfers (id, source_store_id, destination_store_id, product_id, "
                        + "quantity, actor_id, occurred_at, updated_at, status) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)",
                id, source, destination, product, UUID.randomUUID(), Timestamp.from(at), Timestamp.from(at), status);
        return id;
    }
}

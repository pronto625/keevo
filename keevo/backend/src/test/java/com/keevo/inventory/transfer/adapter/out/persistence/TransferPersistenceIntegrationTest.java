package com.keevo.inventory.transfer.adapter.out.persistence;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.inventory.transfer.adapter.out.persistence.entity.StockTransferJpaEntity;
import com.keevo.inventory.transfer.domain.model.StockTransfer.TransferStatus;
import com.keevo.inventory.transfer.domain.model.StockTransfer;
import com.keevo.inventory.transfer.domain.port.in.CompleteTransferCommand;
import com.keevo.inventory.transfer.domain.port.in.CompleteTransferUseCase;
import com.keevo.inventory.transfer.domain.port.in.TransferStockCommand;
import com.keevo.inventory.transfer.domain.port.in.TransferStockUseCase;
import com.keevo.inventory.transfer.domain.port.out.StockTransferRepository;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.sync.sync.application.handler.TransferSyncHandler;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story v1s-15-11 — stock transfer persistence on a real PostgreSQL 16, in a tenant schema
 * provisioned by the production {@link TenantFactory} and reached through the production
 * multi-tenant routing ({@link TenantContext} → {@code search_path}).
 *
 * <ul>
 *   <li>{@link CompleteTransferUseCase}: N concurrent completions of the same IN_TRANSIT transfer
 *       credit the destination exactly once (Story v1s-13-1, atomic conditional UPDATE);</li>
 *   <li>{@code StockTransferSpringRepository.transitionStatusNative}
 *       ({@code @Modifying(clearAutomatically = true)}) + {@code StockTransferJpaEntity.version}
 *       ({@code @Version}): the re-read in the same transaction sees the new status/version and a
 *       stale domain object can no longer be saved;</li>
 *   <li>{@link TransferSyncHandler} + {@code ExecuteTransferService}: replaying the same
 *       {@code STOCK_TRANSFER} operation (sequentially or double-fired concurrently) returns the
 *       same transfer id and decrements the source once (clientId + transaction advisory lock).</li>
 * </ul>
 *
 * <p>No test-managed transaction: every step commits, like real requests. Each test seeds its
 * own stores and product; every worker thread sets and clears {@link TenantContext} and the
 * OWNER {@code SecurityContext}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Stock transfer persistence on PostgreSQL (Story v1s-15-11)")
class TransferPersistenceIntegrationTest extends FullContextIntegrationTest {

    private static final int SOURCE_STOCK = 100;
    private static final int TIMEOUT_SECONDS = 30;

    @Autowired TenantFactory tenantFactory;
    @Autowired TransferStockUseCase executeTransfer;
    @Autowired CompleteTransferUseCase completeTransfer;
    @Autowired StockTransferRepository transferRepository;
    @Autowired TransferSyncHandler transferSyncHandler;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private String schema;
    private final UUID ownerId = UUID.randomUUID();

    private UUID sourceStore;
    private UUID destinationStore;
    private UUID product;

    private ExecutorService pool;

    @BeforeAll
    void provisionTenant() {
        schema = tx.execute(status -> tenantFactory.create()).getSchemaName();
    }

    @BeforeEach
    void seed() {
        pool = Executors.newFixedThreadPool(8);
        sourceStore = UUID.randomUUID();
        destinationStore = UUID.randomUUID();
        product = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".stores (id, name, type) VALUES (?, 'Source', 'STORE'), (?, 'Destination', 'WAREHOUSE')",
                sourceStore, destinationStore);
        jdbc.update("INSERT INTO " + schema + ".products (id, name, sku) VALUES (?, ?, ?)",
                product, "Produit " + product, "KEV-" + product.toString().substring(0, 6).toUpperCase());
        jdbc.update("INSERT INTO " + schema + ".stock_levels (product_id, store_id, quantity) VALUES (?, ?, ?)",
                product, sourceStore, SOURCE_STOCK);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── CompleteTransferService: concurrent completion ───────────────────────

    @Test
    @DisplayName("8 concurrent completions of one IN_TRANSIT transfer: all COMPLETED, destination credited once, version 1")
    void concurrentCompleteCreditsDestinationOnce() throws Exception {
        StockTransfer transfer = asOwner(() -> executeTransfer.execute(command(10, null)));
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(version(transfer.getId())).isZero();

        List<StockTransfer> results = concurrently(8,
                () -> completeTransfer.execute(new CompleteTransferCommand(transfer.getId(), ownerId, null)));

        assertThat(results).hasSize(8).allSatisfy(r -> {
            assertThat(r.getId()).isEqualTo(transfer.getId());
            assertThat(r.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        });
        assertThat(quantity(destinationStore)).isEqualTo(10);
        assertThat(quantity(sourceStore)).isEqualTo(SOURCE_STOCK - 10);
        assertThat(movements(destinationStore, "TRANSFER_IN")).isEqualTo(1);
        assertThat(status(transfer.getId())).isEqualTo("COMPLETED");
        assertThat(version(transfer.getId())).isEqualTo(1L);
    }

    // ── @Modifying(clearAutomatically) + @Version ────────────────────────────

    @Test
    @DisplayName("@Modifying + @Version: same-tx re-read sees COMPLETED/v+1; saving the stale v0 object is refused")
    void modifyingClearsContextAndVersionRejectsStaleSave() {
        StockTransfer created = asOwner(() -> executeTransfer.execute(command(5, null)));

        StockTransfer stale = inTenant(() -> tx.execute(status -> {
            StockTransfer loaded = transferRepository.findById(created.getId()).orElseThrow();
            assertThat(loaded.getVersion()).isZero();

            assertThat(transferRepository.transitionStatus(
                    created.getId(), TransferStatus.IN_TRANSIT, TransferStatus.COMPLETED)).isTrue();

            // Without clearAutomatically the persistence context would hand back the cached v0 entity.
            StockTransfer reread = transferRepository.findById(created.getId()).orElseThrow();
            assertThat(reread.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(reread.getVersion()).isEqualTo(loaded.getVersion() + 1);
            return loaded;
        }));

        assertThatThrownBy(() -> inTenant(() -> tx.execute(status ->
                transferRepository.save(stale.withStatus(TransferStatus.IN_TRANSIT)))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(status(created.getId())).isEqualTo("COMPLETED");
        assertThat(version(created.getId())).isEqualTo(1L);
    }

    // ── TransferSyncHandler / ExecuteTransferService: idempotent replay ──────

    @Test
    @DisplayName("replayed STOCK_TRANSFER (sequential, then double-fired concurrently): same id, source decremented once per operation")
    void replayReturnsSameTransferAndDecrementsOnce() throws Exception {
        SyncOperation op = transferOperation(UUID.randomUUID(), 7);

        SyncOperationResult first = asOwner(() -> transferSyncHandler.handle(op, ownerId, schema));
        SyncOperationResult replay = asOwner(() -> transferSyncHandler.handle(op, ownerId, schema));

        assertThat(first.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(replay.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(first.serverEntityId()).isEqualTo(op.entityId());
        assertThat(replay.serverEntityId()).isEqualTo(op.entityId());
        assertThat(quantity(sourceStore)).isEqualTo(SOURCE_STOCK - 7);
        assertThat(transfers()).isEqualTo(1);
        assertThat(movements(sourceStore, "TRANSFER_OUT")).isEqualTo(1);

        // Double-fire: the same new operation pushed twice at the same time.
        SyncOperation doubleFired = transferOperation(UUID.randomUUID(), 3);
        List<SyncOperationResult> results = concurrently(2,
                () -> transferSyncHandler.handle(doubleFired, ownerId, schema));

        assertThat(results).allSatisfy(r -> {
            assertThat(r.status()).as(r.reason()).isEqualTo(SyncOperationStatus.APPLIED);
            assertThat(r.serverEntityId()).isEqualTo(doubleFired.entityId());
        });
        assertThat(quantity(sourceStore)).isEqualTo(SOURCE_STOCK - 7 - 3);
        assertThat(transfers()).isEqualTo(2);
        assertThat(movements(sourceStore, "TRANSFER_OUT")).isEqualTo(2);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TransferStockCommand command(int quantity, UUID clientId) {
        return new TransferStockCommand(sourceStore, destinationStore, product, null, quantity, ownerId,
                "it-transfer", clientId);
    }

    private SyncOperation transferOperation(UUID entityId, int quantity) {
        return new SyncOperation(UUID.randomUUID().toString(), "STOCK_TRANSFER", entityId.toString(),
                Map.of("sourceStoreId", sourceStore.toString(),
                        "destinationStoreId", destinationStore.toString(),
                        "productId", product.toString(),
                        "quantity", quantity),
                Instant.now());
    }

    /** Runs {@code task} on {@code threads} workers released together by one start gate. */
    private <T> List<T> concurrently(int threads, Supplier<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<T> worker = () -> {
                ready.countDown();
                if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate never opened");
                }
                return asOwner(task);
            };
            futures.add(pool.submit(worker));
        }
        assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        return results;
    }

    /** Tenant + OWNER security context for the current thread, both cleared afterwards. */
    private <T> T asOwner(Supplier<T> action) {
        var auth = new UsernamePasswordAuthenticationToken(ownerId, null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        auth.setDetails(AuthDetails.owner("Owner"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return inTenant(action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private <T> T inTenant(Supplier<T> action) {
        TenantContext.setCurrentTenant(schema);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private int quantity(UUID store) {
        return jdbc.queryForObject("SELECT quantity FROM " + schema + ".stock_levels WHERE product_id = ? AND store_id = ?",
                Integer.class, product, store);
    }

    private int movements(UUID store, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM " + schema + ".stock_movements "
                + "WHERE product_id = ? AND store_id = ? AND movement_type = ?", Integer.class, product, store, type);
    }

    private int transfers() {
        return jdbc.queryForObject("SELECT count(*) FROM " + schema + ".stock_transfers WHERE product_id = ?",
                Integer.class, product);
    }

    private String status(UUID transferId) {
        return jdbc.queryForObject("SELECT status FROM " + schema + ".stock_transfers WHERE id = ?",
                String.class, transferId);
    }

    private long version(UUID transferId) {
        return jdbc.queryForObject("SELECT version FROM " + schema + ".stock_transfers WHERE id = ?",
                Long.class, transferId);
    }
}

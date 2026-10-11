package com.keevo.shared.infrastructure.persistence;

import com.keevo.messaging.notification.adapter.out.persistence.impl.JdbcNotificationCooldownAdapter;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.infrastructure.config.SchedulerLockConfig;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 13.9 — the concurrency primitives on a real PostgreSQL 16 migrated by Flyway (V1–V11):
 * ShedLock table + provider (one runner per job name across instances), the transaction-scoped
 * advisory lock behind {@link TransactionLockPort}, and the atomic stock-alert cooldown upsert.
 *
 * <p>No test-managed transaction: each scenario opens its own transactions (several threads).</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTransactionLockAdapter.class, JdbcNotificationCooldownAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@DisplayName("Concurrency primitives on PostgreSQL (Story 13.9)")
class ConcurrencyPrimitivesPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"));

    private static final String SCHEMA = "kv_lock13";

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TransactionLockPort transactionLock;
    @Autowired JdbcNotificationCooldownAdapter cooldowns;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        TenantContext.clear();
    }

    // ── ShedLock ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V11 creates public.shedlock; a second instance cannot lock a job already held")
    void shedLockProviderRefusesSecondConcurrentLock() {
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.shedlock')::text", String.class))
                .isEqualTo("shedlock");

        // Two providers = two instances sharing the same database.
        var instanceA = provider();
        var instanceB = provider();
        var config = new LockConfiguration(Instant.now(), "test.job", Duration.ofMinutes(5), Duration.ZERO);

        Optional<SimpleLock> held = instanceA.lock(config);
        assertThat(held).isPresent();
        assertThat(instanceB.lock(config)).as("same job name while held").isEmpty();
        assertThat(instanceB.lock(new LockConfiguration(Instant.now(), "other.job", Duration.ofMinutes(5), Duration.ZERO)))
                .as("different job name").isPresent();

        held.get().unlock();
        assertThat(instanceB.lock(config)).as("after release").isPresent();
    }

    /** The shipped configuration (public.shedlock, DB time) — one instance per call. */
    private LockProvider provider() {
        return new SchedulerLockConfig().lockProvider(dataSource);
    }

    // ── Advisory transaction lock ────────────────────────────────────────────

    @Test
    @DisplayName("same key: the second transaction blocks until the first commits")
    void sameKeySerialises() throws Exception {
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);

        Future<?> first = pool.submit(() -> inTx(() -> {
            transactionLock.lock("day-closure:" + "store-1");
            firstHolds.countDown();
            await(releaseFirst);
        }));
        assertThat(firstHolds.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> second = pool.submit(() -> inTx(() -> {
            transactionLock.lock("day-closure:" + "store-1");
            secondAcquired.countDown();
        }));

        assertThat(secondAcquired.await(700, TimeUnit.MILLISECONDS))
                .as("second must wait while the first transaction holds the lock").isFalse();
        releaseFirst.countDown();                       // first commits → lock released
        assertThat(secondAcquired.await(10, TimeUnit.SECONDS)).isTrue();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("lock is released on rollback too")
    void releasedOnRollback() throws Exception {
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);

        Future<?> first = pool.submit(() -> {
            try {
                inTx(() -> {
                    transactionLock.lock("owners:kv_lock13");
                    firstHolds.countDown();
                    await(releaseFirst);
                    throw new IllegalStateException("boom → rollback");
                });
            } catch (IllegalStateException expected) {
                // rolled back
            }
        });
        assertThat(firstHolds.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> second = pool.submit(() -> inTx(() -> {
            transactionLock.lock("owners:kv_lock13");
            secondAcquired.countDown();
        }));
        assertThat(secondAcquired.await(500, TimeUnit.MILLISECONDS)).isFalse();
        releaseFirst.countDown();
        assertThat(secondAcquired.await(10, TimeUnit.SECONDS)).isTrue();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("different keys do not block each other")
    void differentKeysDoNotBlock() throws Exception {
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);

        Future<?> first = pool.submit(() -> inTx(() -> {
            transactionLock.lock("product-limit:kv_aaaaaa");
            firstHolds.countDown();
            await(releaseFirst);
        }));
        assertThat(firstHolds.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> second = pool.submit(() -> inTx(() -> {
            transactionLock.lock("product-limit:kv_bbbbbb");
            secondAcquired.countDown();
        }));

        assertThat(secondAcquired.await(10, TimeUnit.SECONDS)).isTrue();
        releaseFirst.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("lock outside a transaction → IllegalTransactionStateException (Propagation.MANDATORY)")
    void lockWithoutTransactionThrows() {
        assertThatThrownBy(() -> transactionLock.lock("transfer:" + UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ── Atomic cooldown ──────────────────────────────────────────────────────

    @Test
    @DisplayName("tryAcquireCooldown: true, then false while active, true again once the window has passed")
    void tryAcquireCooldownLifecycle() {
        createCooldownTable();
        TenantContext.setCurrentTenant(SCHEMA);
        UUID product = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        Duration window = Duration.ofHours(4);

        assertThat(cooldowns.tryAcquireCooldown("STOCK_ALERT", product, store, window)).isTrue();
        Instant firstSent = lastSentAt(product, store);
        assertThat(cooldowns.tryAcquireCooldown("STOCK_ALERT", product, store, window)).isFalse();
        assertThat(lastSentAt(product, store)).as("active cooldown unchanged").isEqualTo(firstSent);
        assertThat(cooldowns.existsActiveCooldown("STOCK_ALERT", product, store, window)).isTrue();

        jdbc.update("UPDATE " + SCHEMA + ".notification_cooldowns SET last_sent_at = NOW() - INTERVAL '5 hours' "
                + "WHERE product_id = ? AND store_id = ?", product, store);

        assertThat(cooldowns.tryAcquireCooldown("STOCK_ALERT", product, store, window)).isTrue();
        assertThat(lastSentAt(product, store)).as("refreshed to now").isAfterOrEqualTo(firstSent);
    }

    @Test
    @DisplayName("tryAcquireCooldown: concurrent callers on a fresh product → exactly one acquires")
    void tryAcquireCooldownConcurrentExactlyOne() throws Exception {
        createCooldownTable();
        for (int round = 0; round < 10; round++) {
            UUID product = UUID.randomUUID();
            UUID store = UUID.randomUUID();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    TenantContext.setCurrentTenant(SCHEMA);
                    try {
                        start.await();
                        return cooldowns.tryAcquireCooldown("STOCK_ALERT", product, store, Duration.ofHours(4));
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            start.countDown();
            int acquired = 0;
            for (Future<Boolean> r : results) {
                if (r.get(10, TimeUnit.SECONDS)) acquired++;
            }
            assertThat(acquired).as("round %d", round).isEqualTo(1);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void inTx(Runnable body) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> body.run());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void createCooldownTable() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        jdbc.execute(TenantSchemaProvisioner.DDL_NOTIFICATION_COOLDOWNS.replaceFirst(
                "notification_cooldowns", SCHEMA + ".notification_cooldowns"));
    }

    private Instant lastSentAt(UUID product, UUID store) {
        return jdbc.queryForObject("SELECT last_sent_at FROM " + SCHEMA + ".notification_cooldowns "
                + "WHERE product_id = ? AND store_id = ?", java.sql.Timestamp.class, product, store).toInstant();
    }
}

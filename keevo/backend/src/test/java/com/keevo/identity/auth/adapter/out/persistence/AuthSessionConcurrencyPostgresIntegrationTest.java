package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.adapter.out.persistence.jpa.PasswordResetTokenSpringRepository;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.sync.sync.adapter.out.persistence.impl.UserSyncStateRepositoryAdapter;
import com.keevo.sync.sync.domain.model.UserSyncState;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story v1s-15-11 — the single-statement atomic auth/session writes of Story 12.12, exercised from
 * several connections at once on a real PostgreSQL 16 (the sequential checks live in
 * {@link AuthSessionPostgresIntegrationTest}):
 * <ul>
 *   <li>{@code tryClaimAttempt}: 10 parallel wrong OTPs → exactly 5 claims, {@code attempts = 5};</li>
 *   <li>{@code markConsumed}: 2 parallel correct OTPs → exactly one consumes the token;</li>
 *   <li>guarded {@code user_sync_state} upsert: N users claiming the same device at once → one
 *       wins, the device is never reassigned afterwards.</li>
 * </ul>
 * Each worker runs its statement in its own {@link TransactionTemplate} transaction (own pooled
 * connection) and all workers are released by one start gate. Rows use unique ids and phones.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(UserSyncStateRepositoryAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Auth & session concurrency on PostgreSQL (Story v1s-15-11)")
class AuthSessionConcurrencyPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final int TIMEOUT_SECONDS = 30;
    private static final Instant NOW = Instant.now();

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordResetTokenSpringRepository resetTokens;
    @Autowired UserSyncStateRepositoryAdapter syncState;
    @Autowired PlatformTransactionManager transactionManager;

    private ExecutorService pool;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(10);
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("10 parallel wrong OTPs: exactly 5 attempt claims succeed, attempts stops at 5")
    void parallelWrongOtpsCapAttemptsAtFive() throws Exception {
        String phone = uniquePhone();
        UUID token = insertResetToken(insertUser(phone), phone);

        List<Integer> claims = concurrently(10, i -> tx.execute(s -> resetTokens.tryClaimAttempt(token, 5)));

        assertThat(claims).containsOnly(0, 1);
        assertThat(claims.stream().mapToInt(Integer::intValue).sum()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT attempts FROM public.password_reset_tokens WHERE id = ?",
                Integer.class, token)).isEqualTo(5);
    }

    @Test
    @DisplayName("2 parallel correct OTPs: exactly one markConsumed returns 1")
    void parallelCorrectOtpsConsumeOnce() throws Exception {
        String phone = uniquePhone();
        UUID token = insertResetToken(insertUser(phone), phone);

        List<Integer> consumed = concurrently(2,
                i -> tx.execute(s -> resetTokens.markConsumed(token, NOW.plusSeconds(i))));

        assertThat(consumed).containsExactlyInAnyOrder(0, 1);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM public.password_reset_tokens WHERE id = ?",
                Boolean.class, token)).isTrue();
    }

    @Test
    @DisplayName("8 users claiming the same device at once: one success, user_id = that owner, never reassigned")
    void concurrentDeviceClaimNeverReassigns() throws Exception {
        String device = UUID.randomUUID().toString();
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            users.add(UUID.randomUUID());
        }

        List<Boolean> written = concurrently(8, i -> tx.execute(s -> i % 2 == 0
                ? syncState.upsert(state(device, users.get(i)))
                : syncState.upsertOnPull(state(device, users.get(i)))));

        assertThat(written).containsOnlyOnce(true);
        UUID winner = users.get(written.indexOf(true));
        assertThat(owner(device)).isEqualTo(winner);

        // Losers retrying later still cannot take the device over; the owner can still write.
        for (UUID user : users) {
            if (!user.equals(winner)) {
                Boolean takenOver = tx.execute(s -> syncState.upsert(state(device, user)));
                assertThat(takenOver).isFalse();
            }
        }
        Boolean ownerWrite = tx.execute(s -> syncState.upsert(state(device, winner)));
        assertThat(ownerWrite).isTrue();
        assertThat(owner(device)).isEqualTo(winner);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Runs {@code task(i)} on {@code threads} workers released together by one start gate. */
    private <T> List<T> concurrently(int threads, IntFunction<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            Callable<T> worker = () -> {
                ready.countDown();
                if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate never opened");
                }
                return task.apply(index);
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

    private UUID owner(String device) {
        return syncState.findByDeviceId(device).map(UserSyncState::userId).orElseThrow();
    }

    private static UserSyncState state(String device, UUID user) {
        return new UserSyncState(device, user, "kv_conc15", null, null, null);
    }

    private static String uniquePhone() {
        return "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private UUID insertUser(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                        + "password_hash, phone_number, role) VALUES (?, ?, ?, true, 0, 'x', ?, 'OWNER')",
                id, Timestamp.from(NOW), Timestamp.from(NOW), phone);
        return id;
    }

    private UUID insertResetToken(UUID userId, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.password_reset_tokens (id, user_id, phone_number, code_hash, expires_at, "
                        + "consumed_at, attempts, created_at, updated_at) VALUES (?, ?, ?, 'h', ?, NULL, 0, ?, ?)",
                id, userId, phone, Timestamp.from(NOW.plusSeconds(600)), Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }
}

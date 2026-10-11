package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.shared.MutableTestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CachedTokenRevocationAdapterTest — Story 12.2 (AC7/AC8) + Story 12.12 (Caffeine, Clock-driven TTL,
 * atomic load, post-commit invalidation).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CachedTokenRevocationAdapter")
class CachedTokenRevocationAdapterTest {

    private static final String TENANT = "kv_abc123";

    @Mock JdbcTokenRevocationAdapter delegate;

    private MutableTestClock clock;
    private CachedTokenRevocationAdapter adapter;

    @BeforeEach
    void setUp() {
        clock = new MutableTestClock(Instant.parse("2026-10-11T10:00:00Z"));
        adapter = new CachedTokenRevocationAdapter(delegate, clock);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("same key before TTL → cache hit; after TTL → reloaded from delegate")
    void shouldHitBeforeTtlAndReloadAfter() {
        UUID userId = UUID.randomUUID();
        Instant cutoff1 = Instant.parse("2026-10-11T09:00:00Z");
        Instant cutoff2 = Instant.parse("2026-10-11T10:02:00Z");
        when(delegate.loadTokensValidAfter(userId, TENANT)).thenReturn(cutoff1, cutoff2);

        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isEqualTo(cutoff1);
        clock.advance(Duration.ofSeconds(299));
        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isEqualTo(cutoff1);
        verify(delegate, times(1)).loadTokensValidAfter(userId, TENANT);

        clock.advance(Duration.ofSeconds(2)); // 301 s > TTL
        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isEqualTo(cutoff2);
        verify(delegate, times(2)).loadTokensValidAfter(userId, TENANT);
    }

    @Test
    @DisplayName("null cutoff (never revoked) is cached")
    void shouldCacheNullCutoff() {
        UUID userId = UUID.randomUUID();
        when(delegate.loadTokensValidAfter(userId, TENANT)).thenReturn(null);

        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isNull();
        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isNull();
        verify(delegate, times(1)).loadTokensValidAfter(userId, TENANT);
    }

    @Test
    @DisplayName("parallel misses on one key → delegate loaded once")
    void shouldCoalesceConcurrentLoads() throws Exception {
        UUID userId = UUID.randomUUID();
        CountDownLatch inLoad = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(delegate.loadTokensValidAfter(userId, TENANT)).thenAnswer(inv -> {
            inLoad.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        });

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Instant>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> adapter.getTokensValidAfter(userId, TENANT)));
            assertThat(inLoad.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 7; i++) {
                futures.add(pool.submit(() -> adapter.getTokensValidAfter(userId, TENANT)));
            }
            Thread.sleep(100); // let the extra callers block on the in-flight load
            release.countDown();
            for (Future<Instant> f : futures) {
                assertThat(f.get(5, TimeUnit.SECONDS)).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
        verify(delegate, times(1)).loadTokensValidAfter(userId, TENANT);
    }

    @Test
    @DisplayName("revokeAllSessions invalidates cache → next lookup calls delegate")
    void shouldInvalidateCacheOnRevoke() {
        UUID userId = UUID.randomUUID();
        when(delegate.loadTokensValidAfter(userId, TENANT)).thenReturn(null);

        adapter.getTokensValidAfter(userId, TENANT);
        adapter.revokeAllSessions(userId, TENANT);
        verify(delegate).revokeAllSessions(userId, TENANT);

        adapter.getTokensValidAfter(userId, TENANT);
        verify(delegate, times(2)).loadTokensValidAfter(userId, TENANT);
    }

    @Test
    @DisplayName("revoke inside a transaction → stale value cached before commit is evicted after commit")
    void shouldInvalidateAgainAfterCommit() {
        UUID userId = UUID.randomUUID();
        Instant revokedAt = Instant.parse("2026-10-11T10:00:00Z");
        // 1st load (concurrent reader during the open tx) sees the old value; 2nd sees the commit.
        when(delegate.loadTokensValidAfter(userId, TENANT)).thenReturn(null, revokedAt);

        TransactionSynchronizationManager.initSynchronization();
        adapter.revokeAllSessions(userId, TENANT);
        // A request racing the uncommitted revoke re-populates the cache with the stale cutoff.
        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isNull();

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        syncs.forEach(TransactionSynchronization::afterCommit);

        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isEqualTo(revokedAt);
        verify(delegate, times(2)).loadTokensValidAfter(userId, TENANT);
    }

    @Test
    @DisplayName("revokeAllSessionsEverywhere invalidates every tenant of the user, not other users")
    void shouldInvalidateAllTenantsOfUser() {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        when(delegate.loadTokensValidAfter(any(), anyString())).thenReturn(null);

        adapter.getTokensValidAfter(userId, "kv_aaaaaa");
        adapter.getTokensValidAfter(userId, "kv_bbbbbb");
        adapter.getTokensValidAfter(otherUser, "kv_aaaaaa");

        adapter.revokeAllSessionsEverywhere(userId);
        verify(delegate).revokeAllSessionsEverywhere(userId);

        adapter.getTokensValidAfter(userId, "kv_aaaaaa");
        adapter.getTokensValidAfter(userId, "kv_bbbbbb");
        adapter.getTokensValidAfter(otherUser, "kv_aaaaaa");
        verify(delegate, times(2)).loadTokensValidAfter(userId, "kv_aaaaaa");
        verify(delegate, times(2)).loadTokensValidAfter(userId, "kv_bbbbbb");
        verify(delegate, times(1)).loadTokensValidAfter(otherUser, "kv_aaaaaa");
    }

    @Test
    @DisplayName("12.12 — DB failure is not cached: fail-open for that call, next call reloads")
    void dbFailureIsNotCached() {
        UUID userId = UUID.randomUUID();
        Instant cutoff = Instant.parse("2026-10-11T09:00:00Z");
        when(delegate.loadTokensValidAfter(userId, TENANT))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"))
                .thenReturn(cutoff);

        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isNull();       // fail-open
        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isEqualTo(cutoff); // retried
        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isEqualTo(cutoff); // now cached
        verify(delegate, times(2)).loadTokensValidAfter(userId, TENANT);
    }

    @Test
    @DisplayName("12.12 — revokeAllSessionsEverywhere waits for an in-flight load and evicts its stale value")
    void revokeEverywhereEvictsInFlightLoad() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant revokedAt = Instant.parse("2026-10-11T10:00:00Z");
        CountDownLatch inLoad = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(delegate.loadTokensValidAfter(userId, TENANT))
                .thenAnswer(inv -> {           // 1st load: reads the pre-commit (stale) cutoff
                    inLoad.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return null;
                })
                .thenReturn(revokedAt);        // 2nd load: committed cutoff

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Instant> reader = pool.submit(() -> adapter.getTokensValidAfter(userId, TENANT));
            assertThat(inLoad.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> revoker = pool.submit(() -> adapter.revokeAllSessionsEverywhere(userId));
            Thread.sleep(100); // revoker is now blocked on the in-flight load
            release.countDown();
            reader.get(5, TimeUnit.SECONDS);
            revoker.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(adapter.getTokensValidAfter(userId, TENANT)).isEqualTo(revokedAt);
        verify(delegate, times(2)).loadTokensValidAfter(userId, TENANT);
    }
}

package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.keevo.identity.api.TokenRevocationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * CachedTokenRevocationAdapter — {@code @Primary} caching decorator for token revocation
 * (Story 12.2, hardened by Story 12.12).
 *
 * <p><b>NFR12 — ≤5 min propagation window:</b> {@link #revokeAllSessions} invalidates the local
 * cache entry immediately (and again after the surrounding transaction commits), but other
 * instances will see the revocation only when their own cached entry expires (TTL 5 min). This
 * is the contractual guarantee of NFR12, not a bug.
 *
 * <p><b>Cache model (12.12):</b> Caffeine, {@code expireAfterWrite} 5 min, {@code maximumSize}
 * 100 000 entries, ticker driven by the injected {@link Clock} (TTL expiry is testable). Loads go
 * through {@link Cache#get(Object, java.util.function.Function)}: one delegate call per key under
 * concurrency (no stampede). A {@code null} cutoff (never revoked) is cached as
 * {@link Optional#empty()}.
 *
 * <p><b>Stale re-population:</b> a request that reads the cutoff while the revoking transaction
 * is still open would cache the pre-revocation value; the post-commit invalidation removes it, so
 * the next request after commit reloads the committed cutoff.
 *
 * <p><b>Wiring:</b> Injects the concrete {@link JdbcTokenRevocationAdapter} (NOT the
 * {@link TokenRevocationPort} interface) to avoid Spring {@code @Primary} self-reference.
 */
@Component
@Primary
public class CachedTokenRevocationAdapter implements TokenRevocationPort {

    private static final Logger log = LoggerFactory.getLogger(CachedTokenRevocationAdapter.class);

    /** NFR12 — worst-case revocation propagation window across instances. */
    static final Duration TTL = Duration.ofMinutes(5);
    static final long MAX_ENTRIES = 100_000;

    private final JdbcTokenRevocationAdapter delegate;
    private final Cache<String, Optional<Instant>> cache;
    /**
     * Per-user index of cache keys, populated by the loader (so it also covers loads still in
     * flight). Bounded like the cache; idle users age out after twice the TTL, which outlives
     * every entry they own (each load refreshes the index entry).
     */
    private final Cache<UUID, Set<String>> keysByUser;

    public CachedTokenRevocationAdapter(JdbcTokenRevocationAdapter delegate, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate);
        Objects.requireNonNull(clock);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
        this.keysByUser = Caffeine.newBuilder()
                .expireAfterAccess(TTL.multipliedBy(2))
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    @Override
    public Instant getTokensValidAfter(UUID userId, String tenantSchema) {
        Optional<Instant> cutoff = cache.get(key(userId, tenantSchema), k -> {
            keysByUser.get(userId, u -> ConcurrentHashMap.newKeySet()).add(k);
            log.debug("Revocation cache miss for {} — loading from DB", k);
            try {
                return Optional.ofNullable(delegate.loadTokensValidAfter(userId, tenantSchema));
            } catch (DataAccessException e) {
                // Fail-open for THIS request only: returning null stores nothing, so the next
                // request retries the DB instead of trusting a cached "never revoked".
                log.warn("Token revocation lookup failed for user {} tenant {}: {} — fail-open, not cached",
                        userId, tenantSchema, e.getMessage());
                return null;
            }
        });
        return cutoff != null ? cutoff.orElse(null) : null;
    }

    @Override
    public void revokeAllSessions(UUID userId, String tenantSchema) {
        delegate.revokeAllSessions(userId, tenantSchema);
        String key = key(userId, tenantSchema);
        invalidateNowAndAfterCommit(() -> {
            cache.invalidate(key);
            log.debug("Revocation cache invalidated for {}", key);
        });
    }

    @Override
    public void revokeAllSessionsEverywhere(UUID userId) {
        delegate.revokeAllSessionsEverywhere(userId);
        // Invalidate every cached cutoff for this user across tenants (local instance only;
        // other instances see the revoke when their own entry expires, ≤5min).
        // Each key goes through cache.invalidate(key), which waits for a load still in flight
        // (a prefix scan of asMap() would skip it and leave a pre-commit cutoff cached).
        invalidateNowAndAfterCommit(() -> {
            Set<String> keys = new HashSet<>();
            Set<String> indexed = keysByUser.getIfPresent(userId);
            if (indexed != null) {
                keys.addAll(indexed);
            }
            String prefix = userId + ":";
            cache.asMap().keySet().stream().filter(k -> k.startsWith(prefix)).forEach(keys::add);
            keys.forEach(cache::invalidate);
            log.debug("Revocation cache invalidated for user {} ({} tenant keys)", userId, keys.size());
        });
    }

    private static void invalidateNowAndAfterCommit(Runnable invalidation) {
        invalidation.run();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidation.run();
                }
            });
        }
    }

    private static String key(UUID userId, String tenantSchema) {
        return userId + ":" + tenantSchema;
    }
}

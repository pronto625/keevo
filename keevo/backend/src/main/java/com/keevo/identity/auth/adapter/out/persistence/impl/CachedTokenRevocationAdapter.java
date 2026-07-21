package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CachedTokenRevocationAdapter — {@code @Primary} caching decorator for token revocation (Story 12.2).
 *
 * <p><b>NFR12 — ≤5 min propagation window:</b> {@link #revokeAllSessions} invalidates the local
 * cache entry immediately, but other instances will see the revocation only when their own cached
 * entry expires (TTL ≤ 5 min). This is the contractual guarantee of NFR12, not a bug.
 *
 * <p><b>Cache model:</b> {@link ConcurrentHashMap} with time-based expiry (mirrors
 * {@code TenantSchemaSyncService:97}). No scheduled cleanup — the map is bounded by active
 * (userId × tenant) pairs and entries are refreshed on each cache miss.
 *
 * <p><b>Wiring:</b> Injects the concrete {@link JdbcTokenRevocationAdapter} (NOT the
 * {@link TokenRevocationPort} interface) to avoid Spring {@code @Primary} self-reference.
 */
@Component
@Primary
public class CachedTokenRevocationAdapter implements TokenRevocationPort {

    private static final Logger log = LoggerFactory.getLogger(CachedTokenRevocationAdapter.class);

    /** NFR12 — worst-case revocation propagation window across instances. */
    private static final long TTL_SECONDS = 300; // 5 minutes

    private record CachedCutoff(Instant tokensValidAfter, Instant fetchedAt) {}

    private final JdbcTokenRevocationAdapter delegate;
    private final ConcurrentHashMap<String, CachedCutoff> cache = new ConcurrentHashMap<>();

    public CachedTokenRevocationAdapter(JdbcTokenRevocationAdapter delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public Instant getTokensValidAfter(UUID userId, String tenantSchema) {
        String key = key(userId, tenantSchema);
        CachedCutoff cached = cache.get(key);
        Instant now = Instant.now();
        if (cached != null && cached.fetchedAt().plusSeconds(TTL_SECONDS).isAfter(now)) {
            return cached.tokensValidAfter();
        }
        Instant tva = delegate.getTokensValidAfter(userId, tenantSchema);
        cache.put(key, new CachedCutoff(tva, now));
        return tva;
    }

    @Override
    public void revokeAllSessions(UUID userId, String tenantSchema) {
        delegate.revokeAllSessions(userId, tenantSchema);
        cache.remove(key(userId, tenantSchema)); // invalidate local cache immediately
    }

    @Override
    public void revokeAllSessionsEverywhere(UUID userId) {
        delegate.revokeAllSessionsEverywhere(userId);
        // Invalidate every cached cutoff for this user across tenants (best-effort: local
        // instance only; other instances see the revoke when their own entry expires, ≤5min).
        String prefix = userId.toString() + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String key(UUID userId, String tenantSchema) {
        return userId + ":" + tenantSchema;
    }
}
package com.keevo.shared.infrastructure.security.access;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Story 16.11 — cached server-side access state per (user, account), replacing the 5-minute
 * revocation cache of Story 12.2.
 *
 * <ul>
 *   <li><b>TTL ≤ 30 s</b> ({@code keevo.access-state.cache-ttl}, capped at 30 s): the safety net when
 *       a broadcast is lost (listener reconnecting).</li>
 *   <li><b>Evicted on every change on all instances</b>: {@link AccessStateNotificationListener}
 *       receives the PostgreSQL {@code NOTIFY keevo_access_state} payloads (database triggers on
 *       memberships, tenants and refresh tokens; {@link AccessStateChangePublisher} for the employee
 *       rows) and calls {@link #invalidateUser} / {@link #invalidateTenant} / {@link #invalidateAll}.</li>
 *   <li><b>No stale re-population</b>: every load and every invalidation takes a ticket from one
 *       sequence; a load that started before an invalidation of its user / account is answered but
 *       not cached, and an entry older than such an invalidation is never served.</li>
 *   <li>A database failure propagates and caches nothing (the filter answers 500, fail closed: the
 *       role cannot be known).</li>
 * </ul>
 */
@Component
public class AccessStateService {

    private static final Logger log = LoggerFactory.getLogger(AccessStateService.class);

    /** Upper bound of the cache TTL (spec: ≤ 30 s). */
    public static final Duration MAX_TTL = Duration.ofSeconds(30);
    static final long MAX_ENTRIES = 100_000;

    private record Key(UUID userId, String schema) {
    }

    /** A cached state ({@code null} = no membership) and the ticket taken when its load started. */
    private record Entry(AccessState state, long ticket) {
    }

    private final AccessStateLoader loader;
    private final AtomicLong sequence = new AtomicLong();
    private final Cache<Key, Entry> cache;
    private final Cache<UUID, Long> userInvalidatedAt;
    private final Cache<String, Long> tenantInvalidatedAt;
    private volatile long allInvalidatedAt;

    @Autowired
    public AccessStateService(AccessStateLoader loader, Clock clock,
                              @Value("${keevo.access-state.cache-ttl:30s}") Duration ttl) {
        this.loader = Objects.requireNonNull(loader);
        Duration effective = ttl == null || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(effective)
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
        // An invalidation older than the TTL can no longer concern a cached entry.
        this.userInvalidatedAt = Caffeine.newBuilder()
                .expireAfterWrite(effective.multipliedBy(2))
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
        this.tenantInvalidatedAt = Caffeine.newBuilder()
                .expireAfterWrite(effective.multipliedBy(2))
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    /** Convenience for tests: 30 s TTL. */
    public AccessStateService(AccessStateLoader loader, Clock clock) {
        this(loader, clock, MAX_TTL);
    }

    /**
     * @return the access state of (user, account), or {@code null} when the user has no membership
     * @throws org.springframework.dao.DataAccessException when the database cannot be read
     */
    public AccessState get(UUID userId, String schema) {
        Key key = new Key(userId, schema);
        Entry entry = cache.getIfPresent(key);
        if (entry != null && isFresh(entry, key)) {
            return entry.state();
        }
        return reload(key);
    }

    /**
     * The access state to authorize with when {@code sessionId} is open, else {@code null}. A
     * session missing from a cached state (opened after the state was cached) is checked once
     * against the database, and the fresh state is returned.
     */
    public AccessState withOpenSession(UUID userId, String schema, AccessState state, UUID sessionId) {
        if (state.isSessionOpen(sessionId)) {
            return state;
        }
        AccessState fresh = reload(new Key(userId, schema));
        return fresh != null && fresh.isSessionOpen(sessionId) ? fresh : null;
    }

    private AccessState reload(Key key) {
        long ticket = sequence.incrementAndGet();
        AccessState state = loader.load(key.userId(), key.schema());
        Entry loaded = new Entry(state, ticket);
        if (isFresh(loaded, key)) {
            cache.put(key, loaded);
        }
        return state;
    }

    private boolean isFresh(Entry entry, Key key) {
        long ticket = entry.ticket();
        if (ticket < allInvalidatedAt) return false;
        Long user = userInvalidatedAt.getIfPresent(key.userId());
        if (user != null && ticket < user) return false;
        Long tenant = tenantInvalidatedAt.getIfPresent(key.schema());
        return tenant == null || ticket >= tenant;
    }

    /** Evicts every cached state of the user (all accounts). */
    public void invalidateUser(UUID userId) {
        userInvalidatedAt.put(userId, sequence.incrementAndGet());
        cache.asMap().keySet().removeIf(k -> k.userId().equals(userId));
        log.debug("Access state evicted for user {}", userId);
    }

    /** Evicts every cached state of the account. */
    public void invalidateTenant(String schema) {
        tenantInvalidatedAt.put(schema, sequence.incrementAndGet());
        cache.asMap().keySet().removeIf(k -> k.schema().equals(schema));
        log.debug("Access state evicted for account {}", schema);
    }

    /** Evicts everything (broadcast connection lost: notifications may have been missed). */
    public void invalidateAll() {
        allInvalidatedAt = sequence.incrementAndGet();
        cache.invalidateAll();
    }

    /** Applies one broadcast payload ({@code u:<user id>} or {@code t:<schema>}); unknown → evict all. */
    public void onNotification(String payload) {
        if (payload != null && payload.startsWith("u:")) {
            try {
                invalidateUser(UUID.fromString(payload.substring(2)));
                return;
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        } else if (payload != null && payload.startsWith("t:") && payload.length() > 2) {
            invalidateTenant(payload.substring(2));
            return;
        }
        invalidateAll();
    }

    /** Visible for tests. */
    long cachedEntries() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}

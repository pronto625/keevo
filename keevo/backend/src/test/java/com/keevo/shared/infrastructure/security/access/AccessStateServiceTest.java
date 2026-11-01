package com.keevo.shared.infrastructure.security.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("16.11 — AccessStateService cache (TTL ≤ 30 s, eviction, no stale re-population)")
class AccessStateServiceTest {

    private static final String SCHEMA = "kv_abc123";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final AccessStateLoader loader = mock(AccessStateLoader.class);
    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicReference<String> role = new AtomicReference<>("OWNER");

    private AccessStateService service(Duration ttl) {
        when(loader.load(any(), any())).thenAnswer(inv -> {
            loads.incrementAndGet();
            return new AccessState(role.get(), true, "ACTIVE", null, null, Set.of());
        });
        return new AccessStateService(loader, clock, ttl);
    }

    @Test
    @DisplayName("cached for the TTL, reloaded after; a TTL above 30 s is capped at 30 s")
    void ttlCappedAt30s() {
        AccessStateService s = service(Duration.ofMinutes(5));
        UUID user = UUID.randomUUID();
        s.get(user, SCHEMA);
        s.get(user, SCHEMA);
        assertThat(loads).hasValue(1);
        now.set(now.get().plusSeconds(31));
        s.get(user, SCHEMA);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("user / account / global eviction reloads at the next read")
    void eviction() {
        AccessStateService s = service(Duration.ofSeconds(30));
        UUID user = UUID.randomUUID();
        s.get(user, SCHEMA);
        role.set("EMPLOYEE");
        s.onNotification("u:" + user);
        assertThat(s.get(user, SCHEMA).role()).isEqualTo("EMPLOYEE");
        role.set("OWNER");
        s.onNotification("t:" + SCHEMA);
        assertThat(s.get(user, SCHEMA).role()).isEqualTo("OWNER");
        role.set("EMPLOYEE");
        s.onNotification("garbage");
        assertThat(s.get(user, SCHEMA).role()).isEqualTo("EMPLOYEE");
    }

    @Test
    @DisplayName("a load that started before an eviction is answered but never cached (no stale re-population)")
    void noStaleRepopulation() {
        UUID user = UUID.randomUUID();
        AtomicReference<AccessStateService> ref = new AtomicReference<>();
        when(loader.load(any(), any())).thenAnswer(inv -> {
            loads.incrementAndGet();
            if (loads.get() == 1) {
                // The change commits (and is broadcast) while this read is still in flight.
                ref.get().invalidateUser(user);
                return new AccessState("OWNER", true, "ACTIVE", null, null, Set.of());
            }
            return new AccessState("EMPLOYEE", true, "ACTIVE", null, null, Set.of());
        });
        AccessStateService s = new AccessStateService(loader, clock);
        ref.set(s);

        assertThat(s.get(user, SCHEMA).role()).isEqualTo("OWNER");
        assertThat(s.get(user, SCHEMA).role()).isEqualTo("EMPLOYEE");
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("a database failure propagates and caches nothing")
    void failureNotCached() {
        UUID user = UUID.randomUUID();
        when(loader.load(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"))
                .thenReturn(new AccessState("OWNER", true, "ACTIVE", null, null, Set.of()));
        AccessStateService s = new AccessStateService(loader, clock);
        assertThatThrownBy(() -> s.get(user, SCHEMA)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(s.get(user, SCHEMA).role()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("a session opened after the state was cached is checked once against the database")
    void newSessionReloadsOnce() {
        UUID user = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        when(loader.load(any(), any()))
                .thenReturn(new AccessState("OWNER", true, "ACTIVE", null, null, Set.of()))
                .thenReturn(new AccessState("OWNER", true, "ACTIVE", null, null, Set.of(sid)));
        AccessStateService s = new AccessStateService(loader, clock);
        AccessState cached = s.get(user, SCHEMA);
        AccessState fresh = s.withOpenSession(user, SCHEMA, cached, sid);
        assertThat(fresh).isNotNull().isNotSameAs(cached);
        assertThat(fresh.isSessionOpen(sid)).isTrue();
        assertThat(s.withOpenSession(user, SCHEMA, s.get(user, SCHEMA), sid)).isNotNull();
        assertThat(s.withOpenSession(user, SCHEMA, fresh, UUID.randomUUID())).isNull();
    }
}

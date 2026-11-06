package com.keevo.sync.sync.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * UserSyncState — domain record tracking the last push/pull timestamp per device.
 *
 * <p>Stored in PUBLIC schema ({@code public.user_sync_state}) — NOT per-tenant.
 * All active devices are tracked in one global table for monitoring and 7-day gate enforcement.
 *
 * <p>Story 5.4 — AC8/AC9/AC10.
 *
 * <p>Story 16.13 — {@code lastScopeRestricted}/{@code lastScopeStoreId}/{@code lastScopeKnown}: the scope of the
 * device's last completed pull (restricted = employee; null store = unrestricted or employee without a store). Unknown until the device completes
 * a pull; a later pull whose scope differs is answered from the beginning (re-seed). Story 20.2 —
 * {@code lastScopeClientStores}: fingerprint of the client visible stores of that pull (restricted only), null when
 * unknown.
 */
public record UserSyncState(
        String deviceId,
        UUID userId,
        String tenantId,
        Instant lastPushAt,
        Instant lastPullAt,
        Instant updatedAt,
        boolean lastScopeRestricted,
        UUID lastScopeStoreId,
        boolean lastScopeKnown,
        String lastScopeClientStores
) {
    /** Pre-20.2 arity — no client-stores fingerprint. */
    public UserSyncState(String deviceId, UUID userId, String tenantId, Instant lastPushAt, Instant lastPullAt,
                         Instant updatedAt, boolean lastScopeRestricted, UUID lastScopeStoreId,
                         boolean lastScopeKnown) {
        this(deviceId, userId, tenantId, lastPushAt, lastPullAt, updatedAt, lastScopeRestricted, lastScopeStoreId,
                lastScopeKnown, null);
    }

    /**
     * Without the remembered pull scope (Story 16.13): {@code lastScopeKnown = false}.
     */
    public UserSyncState(String deviceId, UUID userId, String tenantId, Instant lastPushAt, Instant lastPullAt,
                         Instant updatedAt) {
        this(deviceId, userId, tenantId, lastPushAt, lastPullAt, updatedAt, false, null, false);
    }
}

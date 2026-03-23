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
 */
public record UserSyncState(
        String deviceId,
        UUID userId,
        String tenantId,
        Instant lastPushAt,
        Instant lastPullAt,
        Instant updatedAt
) {}

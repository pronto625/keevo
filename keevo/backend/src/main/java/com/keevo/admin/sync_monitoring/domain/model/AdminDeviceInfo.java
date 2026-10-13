package com.keevo.admin.sync_monitoring.domain.model;

import java.time.Instant;

/**
 * @param userName trimmed "first last" of the tenant employee owning the device, null when no employee row
 * @param userRole membership role of the user in the tenant (e.g. OWNER), null when no membership
 */
public record AdminDeviceInfo(
        String deviceId,
        String userId,
        Instant lastPushAt,
        Instant lastPullAt,
        Instant updatedAt,
        String userName,
        String userRole
) {}

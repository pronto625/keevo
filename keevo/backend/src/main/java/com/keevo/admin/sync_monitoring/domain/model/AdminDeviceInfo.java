package com.keevo.admin.sync_monitoring.domain.model;

import java.time.Instant;

public record AdminDeviceInfo(
        String deviceId,
        String userId,
        Instant lastPushAt,
        Instant lastPullAt,
        Instant updatedAt
) {}

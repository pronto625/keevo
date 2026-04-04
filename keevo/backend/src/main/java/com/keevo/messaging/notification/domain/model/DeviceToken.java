package com.keevo.messaging.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

public record DeviceToken(
        UUID id,
        UUID userId,
        String token,
        DevicePlatform platform,
        String deviceName,
        String role,
        Instant createdAt,
        Instant updatedAt
) {
    public DeviceToken withUpdatedAt(Instant newUpdatedAt) {
        return new DeviceToken(id, userId, token, platform, deviceName, role, createdAt, newUpdatedAt);
    }
}

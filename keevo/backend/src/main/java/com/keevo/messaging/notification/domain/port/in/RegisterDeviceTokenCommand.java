package com.keevo.messaging.notification.domain.port.in;

import java.util.UUID;

public record RegisterDeviceTokenCommand(
        UUID actorId,
        String actorRole,
        String token,
        String platform,
        String deviceName
) {
    public RegisterDeviceTokenCommand {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("FCM token must not be blank");
        }
    }
}

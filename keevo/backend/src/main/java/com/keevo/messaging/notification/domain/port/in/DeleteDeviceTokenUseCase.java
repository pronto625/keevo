package com.keevo.messaging.notification.domain.port.in;

import java.util.UUID;

public interface DeleteDeviceTokenUseCase {
    /**
     * Deletes the token only if it belongs to {@code userId}: a user cannot
     * silence another user's device by guessing or leaking its FCM token.
     */
    boolean delete(UUID userId, String token);
}

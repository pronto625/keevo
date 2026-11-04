package com.keevo.messaging.notification.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Story 19.9 — one persisted in-app notification of a user ({@code notifications} of the tenant).
 *
 * @param data payload metadata + {@code deepLink} (empty map when none)
 */
public record InboxNotification(UUID id, UUID userId, String type, String title, String body,
                                Map<String, Object> data, Instant readAt, Instant createdAt) {

    public boolean read() {
        return readAt != null;
    }
}

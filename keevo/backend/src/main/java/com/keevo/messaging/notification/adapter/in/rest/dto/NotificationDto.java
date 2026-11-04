package com.keevo.messaging.notification.adapter.in.rest.dto;

import com.keevo.messaging.notification.domain.model.InboxNotification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Story 19.9 — one inbox notification. {@code data} = payload metadata + {@code deepLink} (empty object when none);
 * {@code readAt} null while unread ({@code read=false}).
 */
public record NotificationDto(UUID id, String type, String title, String body, Map<String, Object> data,
                              boolean read, Instant readAt, Instant createdAt) {

    public static NotificationDto from(InboxNotification n) {
        return new NotificationDto(n.id(), n.type(), n.title(), n.body(), n.data(), n.read(), n.readAt(),
                n.createdAt());
    }
}

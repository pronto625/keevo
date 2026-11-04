package com.keevo.messaging.notification.domain.port.in;

import com.keevo.messaging.notification.domain.model.InboxNotification;

import java.util.List;
import java.util.UUID;

/** Story 19.9 — the current user's notification inbox in the current tenant. */
public interface NotificationInboxUseCase {

    /** One page of the user's notifications, newest first, with the user's total. */
    Page list(String schema, UUID userId, int page, int size);

    long unreadCount(String schema, UUID userId);

    /**
     * Marks one of the user's notifications read; idempotent.
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code NOTIFICATION_NOT_FOUND} when unknown or of
     *         another user (both answered the same)
     */
    void markRead(String schema, UUID userId, UUID notificationId);

    void markAllRead(String schema, UUID userId);

    record Page(List<InboxNotification> items, long totalElements) {
    }
}

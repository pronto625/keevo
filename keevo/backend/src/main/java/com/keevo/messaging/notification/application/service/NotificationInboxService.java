package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.port.in.NotificationInboxUseCase;
import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Story 19.9 — inbox of the current user. List = 1 page query on {@code (user_id, created_at DESC, id DESC)} + 1
 * count; unread count = 1 query on the partial index; mark-all-read = 1 UPDATE. Another user's notification is
 * answered exactly like a missing one.
 */
@Service
public class NotificationInboxService implements NotificationInboxUseCase {

    private final NotificationInboxRepository inbox;

    public NotificationInboxService(NotificationInboxRepository inbox) {
        this.inbox = inbox;
    }

    @Override
    public Page list(String schema, UUID userId, int page, int size) {
        long total = inbox.countByUser(schema, userId);
        long offset = (long) page * size;
        if (total == 0 || offset >= total) {
            return new Page(List.of(), total);
        }
        return new Page(inbox.page(schema, userId, (int) offset, size), total);
    }

    @Override
    public long unreadCount(String schema, UUID userId) {
        return inbox.countUnread(schema, userId);
    }

    @Override
    public void markRead(String schema, UUID userId, UUID notificationId) {
        if (inbox.markRead(schema, notificationId, userId) == 0 && !inbox.exists(schema, notificationId, userId)) {
            throw new DomainException(ErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found");
        }
    }

    @Override
    public void markAllRead(String schema, UUID userId) {
        inbox.markAllRead(schema, userId);
    }
}

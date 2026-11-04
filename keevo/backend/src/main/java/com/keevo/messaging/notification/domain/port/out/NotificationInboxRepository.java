package com.keevo.messaging.notification.domain.port.out;

import com.keevo.messaging.notification.domain.model.InboxNotification;
import com.keevo.shared.capability.notification.NotificationPayload;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.9 — the tenant's notification inbox ({@code "<schema>".notifications}). Every statement is
 * schema-qualified (async listeners have no {@code search_path}); the schema is validated.
 */
public interface NotificationInboxRepository {

    /** One row per user, in one JDBC batch; title / body truncated to the column sizes. */
    void insertAll(String schema, Collection<UUID> userIds, NotificationPayload payload);

    /** Own rows, newest first ({@code created_at DESC, id DESC}). */
    List<InboxNotification> page(String schema, UUID userId, int offset, int limit);

    long countByUser(String schema, UUID userId);

    long countUnread(String schema, UUID userId);

    /** Sets {@code read_at} on the user's unread row; 0 when already read, of another user or unknown. */
    int markRead(String schema, UUID id, UUID userId);

    /** True when the row exists and belongs to the user. */
    boolean exists(String schema, UUID id, UUID userId);

    /** Sets {@code read_at} on every unread row of the user; the number of rows marked. */
    int markAllRead(String schema, UUID userId);
}

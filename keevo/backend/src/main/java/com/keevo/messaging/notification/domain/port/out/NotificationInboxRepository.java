package com.keevo.messaging.notification.domain.port.out;

import com.keevo.messaging.notification.domain.model.InboxNotification;
import com.keevo.shared.capability.notification.NotificationPayload;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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

    /**
     * Story 19.11 — the tenant schemas holding the inbox table (the messaging module's tables exist), read once per
     * retention run (one catalogue query); a live schema absent from the set is skipped by the job.
     */
    Set<String> schemasWithInbox();

    /**
     * Story 19.11 — retention of the inbox, two set-based statements on the caller's transaction: rows read before
     * {@code readBefore} (the partial {@code read_at} index drives it; unread rows are never purged by age), then
     * every row of a user without an active membership in this tenant: an anti-join on
     * {@code public.user_tenant_memberships} (probed through its {@code (user_id, tenant_id)} unique index) that
     * <b>scans the tenant's whole inbox</b> — acceptable because a tenant inbox stays small (read rows are purged
     * after the retention period).
     *
     * @param tenantId   {@code public.tenants.id} of the schema
     * @param readBefore read rows strictly older than this are deleted
     */
    Purge purge(String schema, UUID tenantId, Instant readBefore);

    /**
     * @param read     rows deleted because read before the cut-off
     * @param departed rows deleted because their user is no longer an active member of the tenant
     */
    record Purge(int read, int departed) {

        public int total() {
            return read + departed;
        }
    }
}

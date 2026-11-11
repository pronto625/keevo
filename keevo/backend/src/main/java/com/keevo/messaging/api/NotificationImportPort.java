package com.keevo.messaging.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.6 — writes a Bref owner's in-app notifications into the imported company's inbox ({@code notifications}),
 * as they were in Bref: same ids, type, texts, metadata, read state and dates — never pushed. Joins the caller's
 * transaction; rows already present are left untouched. An invalid row throws {@link IllegalArgumentException} and
 * nothing is written.
 */
public interface NotificationImportPort {

    int MAX_TYPE = 64;
    int MAX_TITLE = 150;
    int MAX_BODY = 1000;

    /**
     * @param data   JSON object text, or {@code null}
     * @param readAt {@code null} = unread
     */
    record ImportedNotification(UUID id, UUID userId, String type, String title, String body, String data,
                                Instant readAt, Instant createdAt) {

        public ImportedNotification {
            Objects.requireNonNull(id, "id");
        }
    }

    /** @return the ids inserted by this call */
    Set<UUID> importNotifications(String tenantSchema, List<ImportedNotification> notifications);

    /** A browser subscription of a Keevo account (endpoint ≤ 2048, p256dh ≤ 200, auth ≤ 100, user agent ≤ 500). */
    record ImportedWebPush(UUID id, UUID userId, String endpoint, String p256dh, String auth, String userAgent,
                           Instant createdAt, Instant updatedAt) {

        public ImportedWebPush {
            Objects.requireNonNull(id, "id");
        }

        @Override
        public String toString() {
            return "ImportedWebPush[id=" + id + ", keys=***]";
        }
    }

    /**
     * Story 24.6 — the browser (Web Push) subscriptions of the imported accounts ({@code public.web_push_subscriptions};
     * Keevo signs with Bref's VAPID keys, 19.9b, so they stay valid). An endpoint already registered (by anyone) is left
     * as it is. Joins the caller's transaction.
     *
     * @return the ids inserted by this call
     */
    Set<UUID> importWebPushSubscriptions(List<ImportedWebPush> subscriptions);
}

package com.keevo.messaging.notification.domain.model;

import java.util.Map;

/**
 * NotificationPayload — rich notification contract aligned with Epic 8.1 FCM requirements.
 *
 * <p>{@code type}     — machine-readable event type (e.g. {@code "DRAFT_PRODUCT_PENDING_VALIDATION"})<br>
 * {@code title}    — system-tray notification title<br>
 * {@code body}     — human-readable notification body (personalised with actorName)<br>
 * {@code deepLink} — Go Router path (e.g. {@code "/products/{id}/edit"}) — Epic 8.1 FcmNotificationAdapter
 *                   calls {@code GoRouter.of(context).go(deepLink)} on notification tap<br>
 * {@code metadata} — FCM data payload fields (always delivered, even in background);
 *                   required by Epic 8.1 {@code FcmNotificationAdapter} for typed handling.
 */
public record NotificationPayload(
        String              type,
        String              title,
        String              body,
        String              deepLink,
        Map<String, String> metadata
) {
    /** Convenience factory — no deepLink, no metadata. */
    public static NotificationPayload of(String type, String title, String body) {
        return new NotificationPayload(type, title, body, null, Map.of());
    }

    /** Convenience factory with deepLink and metadata (standard usage). */
    public static NotificationPayload of(String type, String title, String body,
                                         String deepLink, Map<String, String> metadata) {
        return new NotificationPayload(type, title, body, deepLink, metadata);
    }
}


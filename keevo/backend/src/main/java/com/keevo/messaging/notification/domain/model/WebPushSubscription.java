package com.keevo.messaging.notification.domain.model;

import java.util.UUID;

/** Story 19.9b — one browser push subscription of a user ({@code public.web_push_subscriptions}). */
public record WebPushSubscription(UUID id, UUID userId, String endpoint, String p256dh, String auth,
                                  String userAgent) {
}

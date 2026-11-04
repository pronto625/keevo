package com.keevo.messaging.notification.domain.port.in;

import java.util.UUID;

/** Story 19.9b — browser push subscriptions of the signed-in user. */
public interface WebPushSubscriptionUseCase {

    /** The VAPID public key handed to browsers; empty when Web Push is disabled. */
    String vapidPublicKey();

    /**
     * Links this browser to the user (upsert by endpoint).
     *
     * @throws com.keevo.shared.domain.exception.DomainException VALIDATION_FAILED (endpoint outside the push services,
     *         missing keys) or PUSH_ENDPOINT_OWNED (endpoint of another user with other keys)
     */
    void subscribe(UUID userId, String endpoint, String p256dh, String auth, String userAgent);

    /** Unlinks this browser from the user only. */
    void unsubscribe(UUID userId, String endpoint);
}

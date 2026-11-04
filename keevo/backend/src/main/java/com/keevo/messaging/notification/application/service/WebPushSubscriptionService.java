package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.model.PushEndpoints;
import com.keevo.messaging.notification.domain.model.PushKeys;
import com.keevo.messaging.notification.domain.model.WebPushSettings;
import com.keevo.messaging.notification.domain.model.WebPushSubscription;
import com.keevo.messaging.notification.domain.port.in.WebPushSubscriptionUseCase;
import com.keevo.messaging.notification.domain.port.out.WebPushSubscriptionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Story 19.9b — browser push subscriptions (port of Bref's {@code WebPushService.subscribe / unsubscribe}). The
 * endpoint must belong to a browser push service ({@link PushEndpoints}); an endpoint already linked to another user
 * is reassigned only when its {@code p256dh} and {@code auth} keys are identical (same browser, account switched),
 * otherwise {@code PUSH_ENDPOINT_OWNED} — nobody can take over someone else's subscription. One atomic upsert.
 * Subscriptions are stored even while Web Push is disabled (VAPID keys missing): they work once keys are set.
 */
@Service
public class WebPushSubscriptionService implements WebPushSubscriptionUseCase {

    static final int MAX_P256DH = 200;
    static final int MAX_AUTH = 100;
    static final int MAX_USER_AGENT = 500;

    private final WebPushSubscriptionRepository subscriptions;
    private final WebPushSettings settings;

    public WebPushSubscriptionService(WebPushSubscriptionRepository subscriptions, WebPushSettings settings) {
        this.subscriptions = subscriptions;
        this.settings = settings;
    }

    @Override
    public String vapidPublicKey() {
        return settings.exposedPublicKey();
    }

    @Override
    public void subscribe(UUID userId, String endpoint, String p256dh, String auth, String userAgent) {
        String key = p256dh == null ? "" : p256dh.strip();
        String secret = auth == null ? "" : auth.strip();
        if (!PushEndpoints.isAllowed(endpoint) || key.isEmpty() || key.length() > MAX_P256DH
                || secret.isEmpty() || secret.length() > MAX_AUTH || !PushKeys.isValid(key, secret)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "invalid web push subscription");
        }
        String agent = userAgent == null || userAgent.isBlank() ? null
                : userAgent.length() > MAX_USER_AGENT ? userAgent.substring(0, MAX_USER_AGENT) : userAgent;
        if (!subscriptions.upsert(new WebPushSubscription(UUID.randomUUID(), userId, endpoint, key, secret, agent))) {
            throw new DomainException(ErrorCode.PUSH_ENDPOINT_OWNED);
        }
    }

    @Override
    public void unsubscribe(UUID userId, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return;
        subscriptions.deleteByUserAndEndpoint(userId, endpoint);
    }
}

package com.keevo.messaging.notification.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.messaging.notification.domain.model.PushEndpoints;
import com.keevo.messaging.notification.domain.model.WebPushSettings;
import com.keevo.messaging.notification.domain.model.WebPushSubscription;
import com.keevo.messaging.notification.domain.port.out.WebPushSubscriptionRepository;
import com.keevo.shared.capability.notification.NotificationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 19.9b — Web Push delivery (port of Bref's {@code WebPushService.sendToUser}): the recipients' browser
 * subscriptions are read in one query, each one gets the notification encrypted for it ({@link WebPushCrypto}, RFC
 * 8291 {@code aes128gcm}) with a VAPID authorization (RFC 8292) and {@code TTL: 86400}. Payload
 * {@code {title, body, icon:"/icon.svg", badge:"/favicon-32x32.png", data}}. Subscriptions answered 404/410 (expired)
 * or outside the push services allow-list are deleted in one statement; other failures are logged at WARN.
 *
 * <p>Keyed by user, not by device token: a separate path of {@code PersistingPushNotifier}, not a
 * {@code PushDeliveryChannel}. Disabled (no call, no query) without VAPID keys. Never throws. Cost per notification:
 * 1 SELECT + 1 HTTP call per subscription + at most 1 DELETE.
 */
@Component
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String TTL_SECONDS = "86400";
    static final String ICON = "/icon.svg";
    static final String BADGE = "/favicon-32x32.png";

    private final RestTemplate http;
    private final WebPushSubscriptionRepository subscriptions;
    private final WebPushSettings settings;
    private final Clock clock;
    private final boolean enabled;

    public WebPushSender(@Qualifier("channelRestTemplate") RestTemplate http,
                         WebPushSubscriptionRepository subscriptions, WebPushSettings settings, Clock clock) {
        this.http = http;
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.clock = clock;
        if (!settings.isEnabled()) {
            log.warn("[WebPush] VAPID keys not set (VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY) — Web Push disabled");
            this.enabled = false;
        } else if (!WebPushCrypto.isValidKeyPair(settings.publicKey(), settings.privateKey())) {
            log.warn("[WebPush] VAPID keys are not a valid P-256 pair — Web Push disabled");
            this.enabled = false;
        } else {
            log.info("[WebPush] VAPID keys configured");
            this.enabled = true;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Sends to every browser of these users; best effort. */
    public void sendToUsers(Collection<UUID> userIds, NotificationPayload payload) {
        if (!enabled || userIds == null || userIds.isEmpty()) return;
        List<WebPushSubscription> targets;
        try {
            targets = subscriptions.findByUserIds(userIds);
        } catch (RuntimeException e) {
            log.warn("[WebPush] subscription lookup failed ({})", e.getClass().getSimpleName());
            return;
        }
        if (targets.isEmpty()) return;
        byte[] body = payload(payload);
        long now = clock.instant().getEpochSecond();
        Map<String, String> authorizationByOrigin = new HashMap<>();
        List<UUID> dead = new ArrayList<>();
        int delivered = 0;
        for (WebPushSubscription subscription : targets) {
            if (!PushEndpoints.isAllowed(subscription.endpoint())) {
                dead.add(subscription.id());
                continue;
            }
            try {
                String origin = PushEndpoints.origin(subscription.endpoint());
                String authorization = authorizationByOrigin.get(origin);
                if (authorization == null) {
                    authorization = WebPushCrypto.vapidAuthorization(origin, settings.subject(), settings.publicKey(),
                            settings.privateKey(), now);
                    authorizationByOrigin.put(origin, authorization);
                }
                byte[] encrypted = WebPushCrypto.encrypt(body, subscription.p256dh(), subscription.auth());
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                headers.set("Content-Encoding", "aes128gcm");
                headers.set("TTL", TTL_SECONDS);
                headers.set("Authorization", authorization);
                ResponseEntity<String> response = http.exchange(subscription.endpoint(), HttpMethod.POST,
                        new HttpEntity<>(encrypted, headers), String.class);
                if (isGone(response.getStatusCode().value())) {
                    dead.add(subscription.id());
                } else if (response.getStatusCode().is2xxSuccessful()) {
                    delivered++;
                } else {
                    log.warn("[WebPush] send failed (HTTP {})", response.getStatusCode().value());
                }
            } catch (RestClientResponseException e) {
                if (isGone(e.getStatusCode().value())) {
                    dead.add(subscription.id());
                } else {
                    log.warn("[WebPush] send failed (HTTP {})", e.getStatusCode().value());
                }
            } catch (Exception e) {
                log.warn("[WebPush] send failed ({})", e.getClass().getSimpleName());
            }
        }
        if (!dead.isEmpty()) {
            try {
                int deleted = subscriptions.deleteByIds(dead);
                log.info("[WebPush] removed {} expired subscription(s)", deleted);
            } catch (RuntimeException e) {
                log.warn("[WebPush] expired subscription cleanup failed ({})", e.getClass().getSimpleName());
            }
        }
        log.debug("[WebPush] {} of {} browser(s) reached, type={}", delivered, targets.size(), payload.type());
    }

    private static boolean isGone(int status) {
        return status == 404 || status == 410;
    }

    static byte[] payload(NotificationPayload payload) {
        Map<String, Object> data = new HashMap<>();
        if (payload.metadata() != null) data.putAll(payload.metadata());
        if (payload.deepLink() != null) data.put("deepLink", payload.deepLink());
        if (payload.type() != null) data.put("type", payload.type());
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", payload.title() == null ? "" : payload.title());
        notification.put("body", payload.body() == null ? "" : payload.body());
        notification.put("icon", ICON);
        notification.put("badge", BADGE);
        notification.put("data", data);
        try {
            return JSON.writeValueAsBytes(notification);
        } catch (Exception e) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }
}

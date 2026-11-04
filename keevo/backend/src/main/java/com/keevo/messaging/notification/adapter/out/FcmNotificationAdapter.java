package com.keevo.messaging.notification.adapter.out;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.keevo.messaging.notification.domain.model.PushTokens;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.messaging.notification.domain.port.out.PushDeliveryChannel;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FCM adapter — sends push notifications via Firebase Cloud Messaging.
 * Story 8.0 — Replaces LoggingNotificationAdapter when enabled.
 * Story 19.9 — a {@link PushDeliveryChannel} behind {@code PersistingPushNotifier}: the FCM tokens of the Flutter V1
 * app (every non-Expo token); multicast chunks of {@value #MULTICAST_CHUNK_SIZE}, unregistered tokens deleted.
 *
 * <p>Best-effort delivery: NEVER throws — catch and log internally.
 */
@Component
@ConditionalOnProperty(name = "keevo.fcm.enabled", havingValue = "true")
public class FcmNotificationAdapter implements PushDeliveryChannel {

    private static final Logger log = LoggerFactory.getLogger(FcmNotificationAdapter.class);

    /** FCM multicast limit. */
    static final int MULTICAST_CHUNK_SIZE = 500;

    private final FirebaseMessaging firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;

    public FcmNotificationAdapter(ObjectProvider<FirebaseMessaging> firebaseMessagingProvider,
                                  DeviceTokenRepository deviceTokenRepository) {
        this.firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        this.deviceTokenRepository = deviceTokenRepository;
    }

    @Override
    public boolean supports(String token) {
        return token != null && !PushTokens.isExpo(token);
    }

    @Override
    public boolean isAvailable() {
        return firebaseMessaging != null;
    }

    /**
     * Counted multicast send: chunks of {@link #MULTICAST_CHUNK_SIZE}, unregistered tokens removed from the tenant's
     * {@code device_tokens}. Never throws.
     */
    @Override
    public PushSendResult send(String tenantSchema, List<String> tokens, NotificationPayload payload) {
        if (tokens == null || tokens.isEmpty()) return PushSendResult.EMPTY;
        if (firebaseMessaging == null) {
            log.warn("[FCM] FirebaseMessaging is null (init failed) — {} token(s) of tenant={} not sent",
                    tokens.size(), tenantSchema);
            return new PushSendResult(0, tokens.size(), 1, 1);
        }
        Map<String, String> data = buildData(payload);
        PushSendResult total = PushSendResult.EMPTY;
        TenantContext.setCurrentTenant(tenantSchema);
        try {
            for (int from = 0; from < tokens.size(); from += MULTICAST_CHUNK_SIZE) {
                List<String> chunk = new ArrayList<>(
                        tokens.subList(from, Math.min(from + MULTICAST_CHUNK_SIZE, tokens.size())));
                total = total.plus(sendChunk(tenantSchema, chunk, payload, data));
            }
        } finally {
            TenantContext.clear();
        }
        return total;
    }

    private PushSendResult sendChunk(String tenantId, List<String> chunk, NotificationPayload payload,
                                     Map<String, String> data) {
        BatchResponse response;
        try {
            MulticastMessage message = MulticastMessage.builder()
                    .setNotification(Notification.builder()
                            .setTitle(payload.title())
                            .setBody(payload.body())
                            .build())
                    .putAllData(data)
                    .addAllTokens(chunk)
                    .build();
            response = firebaseMessaging.sendEachForMulticast(message);
        } catch (Exception e) {
            log.warn("[FCM] Multicast chunk of {} token(s) failed for tenant={} type={}: {}",
                    chunk.size(), tenantId, payload.type(), e.getMessage());
            return new PushSendResult(0, chunk.size(), 1, 1);
        }
        try {
            processResponse(response, chunk);
        } catch (Exception e) {
            // Token cleanup is best-effort: the counts of FCM stay valid.
            log.warn("[FCM] Stale token cleanup failed for tenant={}: {}", tenantId, e.getMessage());
        }
        return new PushSendResult(response.getSuccessCount(), response.getFailureCount(), 1, 0);
    }

    private Map<String, String> buildData(NotificationPayload payload) {
        Map<String, String> data = new HashMap<>();
        data.put("type", payload.type());
        if (payload.deepLink() != null) data.put("deepLink", payload.deepLink());
        if (payload.metadata() != null) data.putAll(payload.metadata());
        return data;
    }

    private void processResponse(BatchResponse response, List<String> tokens) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (!sendResponse.isSuccessful() && sendResponse.getException() != null) {
                FirebaseMessagingException ex = sendResponse.getException();
                String tokenValue = tokens.get(i);
                if (ex.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                    log.info("[FCM] Removing stale token: {}...", tokenValue.substring(0, Math.min(10, tokenValue.length())));
                    deviceTokenRepository.deleteByToken(tokenValue);
                } else {
                    log.warn("[FCM] Send failed for token {}...: {}",
                            tokenValue.substring(0, Math.min(10, tokenValue.length())),
                            ex.getMessagingErrorCode());
                }
            }
        }
    }
}

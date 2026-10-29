package com.keevo.messaging.notification.adapter.out;

import com.keevo.messaging.notification.domain.model.DeviceToken;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.shared.capability.notification.PushNotifier;
import com.google.firebase.messaging.*;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FCM adapter — sends push notifications via Firebase Cloud Messaging.
 * Story 8.0 — Replaces LoggingNotificationAdapter as @Primary when enabled.
 *
 * <p>Best-effort delivery: NEVER throws — catch and log internally.
 */
@Component
@Primary
@ConditionalOnProperty(name = "keevo.fcm.enabled", havingValue = "true")
public class FcmNotificationAdapter implements PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(FcmNotificationAdapter.class);

    private final FirebaseMessaging firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;

    public FcmNotificationAdapter(ObjectProvider<FirebaseMessaging> firebaseMessagingProvider,
                                  DeviceTokenRepository deviceTokenRepository) {
        this.firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        this.deviceTokenRepository = deviceTokenRepository;
    }

    @Override
    public void notifyOwners(String tenantId, NotificationPayload payload) {
        if (firebaseMessaging == null) {
            log.warn("[FCM] FirebaseMessaging is null (init failed) — skipping notification for tenant={}", tenantId);
            return;
        }
        TenantContext.setCurrentTenant(tenantId);
        try {
            List<DeviceToken> ownerTokens = deviceTokenRepository.findOwnerTokens();
            if (ownerTokens.isEmpty()) {
                log.debug("[FCM] No OWNER tokens found for tenant={} — skipping", tenantId);
                return;
            }

            List<String> tokens = ownerTokens.stream()
                    .map(DeviceToken::token)
                    .collect(Collectors.toList());

            MulticastMessage message = MulticastMessage.builder()
                    .setNotification(Notification.builder()
                            .setTitle(payload.title())
                            .setBody(payload.body())
                            .build())
                    .putAllData(buildData(payload))
                    .addAllTokens(tokens)
                    .build();

            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            processResponse(response, tokens);

        } catch (Exception e) {
            log.warn("[FCM] Failed to send notification for tenant={} type={}: {}",
                    tenantId, payload.type(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public void notifyUsers(String tenantId, List<UUID> userIds, NotificationPayload payload) {
        if (firebaseMessaging == null || userIds == null || userIds.isEmpty()) return;
        TenantContext.setCurrentTenant(tenantId);
        try {
            List<DeviceToken> tokens = deviceTokenRepository.findByUserIds(userIds);
            if (tokens.isEmpty()) {
                log.debug("[FCM] No tokens found for {} users in tenant={}", userIds.size(), tenantId);
                return;
            }
            Map<String, String> data = buildData(payload);
            List<String> tokenStrings = tokens.stream().map(DeviceToken::token).collect(Collectors.toList());
            MulticastMessage message = MulticastMessage.builder()
                    .setNotification(Notification.builder()
                            .setTitle(payload.title())
                            .setBody(payload.body())
                            .build())
                    .putAllData(data)
                    .addAllTokens(tokenStrings)
                    .build();
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            processResponse(response, tokenStrings);
        } catch (Exception e) {
            log.warn("[FCM] notifyUsers failed for tenant={} type={}: {}", tenantId, payload.type(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public boolean isPushAvailable() {
        return firebaseMessaging != null;
    }

    /**
     * Story 9-5 — counted multicast send of a global push: chunks of {@link #MULTICAST_CHUNK_SIZE},
     * unregistered tokens removed from the tenant's {@code device_tokens}. Never throws.
     */
    @Override
    public PushSendResult sendToTokens(String tenantId, List<String> tokens, NotificationPayload payload) {
        if (tokens == null || tokens.isEmpty()) return PushSendResult.EMPTY;
        if (firebaseMessaging == null) {
            log.warn("[FCM] FirebaseMessaging is null (init failed) — {} token(s) of tenant={} not sent",
                    tokens.size(), tenantId);
            return new PushSendResult(0, tokens.size(), 1, 1);
        }
        Map<String, String> data = buildData(payload);
        PushSendResult total = PushSendResult.EMPTY;
        TenantContext.setCurrentTenant(tenantId);
        try {
            for (int from = 0; from < tokens.size(); from += MULTICAST_CHUNK_SIZE) {
                List<String> chunk = new ArrayList<>(
                        tokens.subList(from, Math.min(from + MULTICAST_CHUNK_SIZE, tokens.size())));
                total = total.plus(sendChunk(tenantId, chunk, payload, data));
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

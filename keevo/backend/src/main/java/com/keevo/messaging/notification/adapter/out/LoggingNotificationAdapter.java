package com.keevo.messaging.notification.adapter.out;

import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;
import com.keevo.shared.capability.notification.PushNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Stub notification adapter — logs the notification payload.
 *
 * <p>Story 8.0: No longer @Primary. FcmNotificationAdapter is @Primary when FCM is enabled.
 * This adapter is active only when keevo.fcm.enabled is false or absent (matchIfMissing=true).
 */
@Component
@ConditionalOnProperty(name = "keevo.fcm.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingNotificationAdapter implements PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationAdapter.class);

    @Override
    public void notifyOwners(String tenantId, NotificationPayload payload) {
        log.info("[NOTIFICATION STUB] tenant={} type={} title='{}' body='{}' deepLink={} metadata={}",
                tenantId, payload.type(), payload.title(), payload.body(),
                payload.deepLink(), payload.metadata());
    }

    @Override
    public void notifyUsers(String tenantId, List<UUID> userIds, NotificationPayload payload) {
        if (userIds == null || userIds.isEmpty()) return;
        log.info("[NOTIFICATION STUB] tenant={} recipients={} type={} title='{}' deepLink={}",
                tenantId, userIds.size(), payload.type(), payload.title(), payload.deepLink());
    }

    /** No push channel: a global push is marked FAILED (Story 9-5). */
    @Override
    public boolean isPushAvailable() {
        return false;
    }

    /** Nothing is sent: every token counted as a failure of one failed chunk. */
    @Override
    public PushSendResult sendToTokens(String tenantId, List<String> tokens, NotificationPayload payload) {
        if (tokens == null || tokens.isEmpty()) return PushSendResult.EMPTY;
        log.info("[NOTIFICATION STUB] tenant={} tokens={} type={} title='{}' — not sent (FCM disabled)",
                tenantId, tokens.size(), payload.type(), payload.title());
        return new PushSendResult(0, tokens.size(), 1, 1);
    }
}

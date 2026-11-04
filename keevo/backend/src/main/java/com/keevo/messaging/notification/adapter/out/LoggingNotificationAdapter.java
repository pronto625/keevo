package com.keevo.messaging.notification.adapter.out;

import com.keevo.messaging.notification.domain.model.PushTokens;
import com.keevo.messaging.notification.domain.port.out.PushDeliveryChannel;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stub push channel — logs the notification, sends nothing.
 *
 * <p>Story 8.0: active only when keevo.fcm.enabled is false or absent (matchIfMissing=true).
 * Story 19.9 — the {@link PushDeliveryChannel} of the non-Expo tokens when FCM is off; never available (a global
 * push is marked FAILED, Story 9-5).
 */
@Component
@ConditionalOnProperty(name = "keevo.fcm.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingNotificationAdapter implements PushDeliveryChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationAdapter.class);

    @Override
    public boolean supports(String token) {
        return token != null && !PushTokens.isExpo(token);
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    /** Nothing is sent: every token counted as a failure of one failed chunk. */
    @Override
    public PushSendResult send(String tenantSchema, List<String> tokens, NotificationPayload payload) {
        if (tokens == null || tokens.isEmpty()) return PushSendResult.EMPTY;
        log.info("[NOTIFICATION STUB] tenant={} tokens={} type={} title='{}' deepLink={} — not sent (FCM disabled)",
                tenantSchema, tokens.size(), payload.type(), payload.title(), payload.deepLink());
        return new PushSendResult(0, tokens.size(), 1, 1);
    }
}

package com.keevo.messaging.notification.adapter.out;

import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
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
public class LoggingNotificationAdapter implements NotificationPort {

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
}


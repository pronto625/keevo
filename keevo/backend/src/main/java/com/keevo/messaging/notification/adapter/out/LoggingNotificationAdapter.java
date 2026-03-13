package com.keevo.messaging.notification.adapter.out;

import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Stub notification adapter — logs the notification payload.
 *
 * <p>Marked {@code @Primary} so it is preferred when multiple {@link NotificationPort}
 * implementations are present. Epic 8.1 replaces this with {@code FcmNotificationAdapter}.
 *
 * <p>TODO (Epic 8.1): Replace with FcmNotificationAdapter:
 * – load OWNER device tokens from {@code device_tokens} table
 * – build FCM MulticastMessage: notification={title,body} + data={type, deepLink, ...metadata}
 * – call {@code FirebaseMessaging.getInstance().sendEachForMulticast(message)}
 * – handle token expiry (UNREGISTERED → delete token), quota exceeded → retry with backoff
 * – Flutter: {@code FirebaseMessaging.onMessageOpenedApp} → {@code router.go(data['deepLink'])}
 */
@Primary
@Component
public class LoggingNotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationAdapter.class);

    @Override
    public void notifyOwners(String tenantId, NotificationPayload payload) {
        log.info("[NOTIFICATION STUB] tenant={} type={} title='{}' body='{}' deepLink={} metadata={}",
                tenantId, payload.type(), payload.title(), payload.body(),
                payload.deepLink(), payload.metadata());
    }
}


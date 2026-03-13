package com.keevo.messaging.notification.domain.port.out;

import com.keevo.messaging.notification.domain.model.NotificationPayload;

/**
 * Port — send a notification to all OWNER-role users of a given tenant.
 *
 * <p>Implementations are best-effort: callers MUST handle exceptions independently
 * and MUST NOT roll back their own transaction if notification delivery fails.
 *
 * <p>Only {@link com.keevo.messaging.notification.adapter.out.LoggingNotificationAdapter}
 * is wired (as {@code @Primary}) for Story 2.4.
 * {@code FcmNotificationAdapter} arrives in Epic 8.1 and replaces it via {@code @Primary}.
 */
public interface NotificationPort {

    /**
     * Notify all OWNER-role users of the given tenant.
     *
     * @param tenantId  tenant schema name (from {@code TenantContext})
     * @param payload   rich payload with type, title, body, deepLink, metadata
     */
    void notifyOwners(String tenantId, NotificationPayload payload);
}


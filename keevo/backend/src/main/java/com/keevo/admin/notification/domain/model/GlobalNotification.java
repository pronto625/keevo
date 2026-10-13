package com.keevo.admin.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * GlobalNotification — one announcement of the super admin (Story 9-5, FR82), row of
 * {@code public.global_notifications}.
 *
 * @param targetTenantName business name of the target company (TENANT only, read joined), else null
 * @param recipientCount   device tokens targeted (null until sent)
 * @param failureReason    why the row is FAILED, else null
 * @param sentAt           end of the send — set on every terminal state (SENT, FAILED, CANCELLED)
 */
public record GlobalNotification(
        UUID id,
        String title,
        String body,
        GlobalNotificationTarget target,
        UUID targetTenantId,
        String targetTenantName,
        Instant scheduledAt,
        GlobalNotificationStatus status,
        Integer recipientCount,
        Integer deliveredCount,
        Integer failureCount,
        GlobalNotificationFailureReason failureReason,
        UUID createdBy,
        Instant createdAt,
        Instant sentAt
) {}

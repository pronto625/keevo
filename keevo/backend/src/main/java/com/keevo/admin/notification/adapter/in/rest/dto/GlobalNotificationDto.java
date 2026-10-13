package com.keevo.admin.notification.adapter.in.rest.dto;

import com.keevo.admin.notification.domain.model.GlobalNotification;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the global push history. {@code id} is only used for status polling and « Annuler »
 * (never displayed); {@code target} / {@code status} are codes labelled by the dashboard;
 * {@code targetTenantName} is the business name of the company for target TENANT. Dates are ISO-8601 UTC.
 */
public record GlobalNotificationDto(
        UUID id,
        String title,
        String body,
        String target,
        String targetTenantName,
        String status,
        String scheduledAt,
        String createdAt,
        String sentAt,
        Integer recipientCount,
        Integer deliveredCount,
        Integer failureCount,
        /** PUSH_UNAVAILABLE | ALL_CHUNKS_FAILED | INTERRUPTED | NO_RECIPIENT when FAILED (labelled by the dashboard). */
        String failureReason
) {
    public static GlobalNotificationDto from(GlobalNotification n) {
        return new GlobalNotificationDto(n.id(), n.title(), n.body(), n.target().name(), n.targetTenantName(),
                n.status().name(), iso(n.scheduledAt()), iso(n.createdAt()), iso(n.sentAt()), n.recipientCount(),
                n.deliveredCount(), n.failureCount(),
                n.failureReason() == null ? null : n.failureReason().name());
    }

    /** ISO-8601 UTC string (dates as strings, same as the other admin DTOs). */
    private static String iso(Instant i) {
        return i == null ? null : i.toString();
    }
}

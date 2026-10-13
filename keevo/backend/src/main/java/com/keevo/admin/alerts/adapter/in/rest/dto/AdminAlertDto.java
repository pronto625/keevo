package com.keevo.admin.alerts.adapter.in.rest.dto;

import com.keevo.admin.alerts.domain.model.AdminAlert;

/**
 * AdminAlertDto — one alert of {@code GET /api/v1/admin/alerts} (Story 9-5). {@code key} is opaque to
 * the dashboard: only used in the « Marquer comme traité » request path, never displayed.
 */
public record AdminAlertDto(
        String key,
        String severity,
        String title,
        String description,
        String occurredAt,
        boolean handled,
        String handledAt
) {
    public static AdminAlertDto from(AdminAlert a) {
        return new AdminAlertDto(
                a.key(), a.severity().name(), a.title(), a.description(),
                a.occurredAt() != null ? a.occurredAt().toString() : null,
                a.handled(),
                a.handledAt() != null ? a.handledAt().toString() : null);
    }
}

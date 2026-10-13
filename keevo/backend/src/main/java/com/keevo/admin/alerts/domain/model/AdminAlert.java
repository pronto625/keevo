package com.keevo.admin.alerts.domain.model;

import java.time.Instant;

/**
 * AdminAlert — one entry of the platform alerts feed (Story 9-5), computed on each call.
 *
 * @param key         deterministic key of the alert (e.g. {@code feedback:<id>}): only used to mark it
 *                    handled, never displayed
 * @param severity    HIGH / MEDIUM / LOW
 * @param title       French title
 * @param description French description naming the company, never an id or a raw code
 * @param occurredAt  when the situation started (feed sorted newest first)
 * @param handled     true when Toor marked it « traité »
 * @param handledAt   when it was marked handled, null otherwise
 */
public record AdminAlert(
        String key,
        AlertSeverity severity,
        String title,
        String description,
        Instant occurredAt,
        boolean handled,
        Instant handledAt
) {
    public AdminAlert withHandledAt(Instant at) {
        return new AdminAlert(key, severity, title, description, occurredAt, at != null, at);
    }
}

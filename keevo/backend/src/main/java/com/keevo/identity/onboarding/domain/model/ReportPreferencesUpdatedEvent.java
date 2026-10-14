package com.keevo.identity.onboarding.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ReportPreferencesUpdatedEvent — published when the owner changes the tenant report /
 * alert preferences. {@code before} / {@code after} hold only the changed fields.
 * Consumed by AuditEventListener (REPORT_PREFERENCES_UPDATED).
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public record ReportPreferencesUpdatedEvent(
        UUID preferencesId,
        Map<String, Object> before,
        Map<String, Object> after,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}

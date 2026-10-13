package com.keevo.admin.alerts.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Raw facts read by the alert sources (Story 9-5); company names already resolved to labels. */
public final class AlertSourceRows {

    private AlertSourceRows() {}

    /** HIGH-priority feedback not handled yet. {@code type} is the app's code (BUG, SUGGESTION, PRAISE). */
    public record HighFeedback(UUID id, String tenantName, String type, String description, Instant submittedAt) {}

    /** Company whose owner requested account deletion. */
    public record DeletionPending(UUID tenantId, String tenantName, Instant deletionScheduledAt) {}

    /** Subscription past its expiry but still not FREE (integrity issue). {@code plan} is the plan code. */
    public record ExpiredPlan(UUID tenantId, String tenantName, String plan, Instant expiresAt) {}

    /** WhatsApp report delivery failures of all companies in a window. */
    public record ReportFailures(long failures, int companies, Instant latestAt) {}

    /** Company with at least one device, all silent since {@code lastPushAt}. */
    public record SilentTenant(UUID tenantId, String tenantName, String schemaName, Instant lastPushAt) {}
}

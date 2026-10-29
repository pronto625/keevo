package com.keevo.feedback.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of one feedback (Story 12.2e).
 *
 * @param tenantSchema schema name of the submitting company — never shown as such in a screen
 * @param handledAt    null while not handled (« Traité » flag, spec 9-1 completion)
 */
public record FeedbackView(UUID id, String type, String description, String tenantSchema, UUID userId,
                           String appVersion, String platform, String screenContext,
                           Instant submittedAt, String priority, Instant handledAt, UUID handledBy) {

    public boolean handled() {
        return handledAt != null;
    }
}

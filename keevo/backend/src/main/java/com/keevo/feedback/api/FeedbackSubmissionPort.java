package com.keevo.feedback.api;

import java.util.UUID;

/** Feedback submission for other modules (Story 12.2e — the sync push). */
public interface FeedbackSubmissionPort {

    /**
     * Stores a new feedback (priority derived as for the REST endpoint) and returns its id.
     *
     * <p>When a tenant context is bound (request, sync push), {@code input.tenantSchema()} must be
     * that tenant ({@code TenantContext.requireCurrent}, {@link IllegalStateException} otherwise).
     * A platform-level caller without a bound tenant passes the submitting tenant as data.
     */
    UUID submit(SubmitFeedbackInput input);
}

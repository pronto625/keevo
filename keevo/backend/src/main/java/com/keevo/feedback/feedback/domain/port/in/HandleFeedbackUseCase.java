package com.keevo.feedback.feedback.domain.port.in;

import com.keevo.feedback.feedback.domain.model.Feedback;

import java.util.UUID;

/**
 * HandleFeedbackUseCase — SUPER_ADMIN « Traité » flag of a feedback (spec 9-1 completion).
 * Feedback is never deleted; handling only hides it from the default inbox.
 */
public interface HandleFeedbackUseCase {

    /**
     * Idempotent: an already handled feedback keeps its first handler and date.
     *
     * @throws com.keevo.shared.domain.exception.DomainException NOT_FOUND for an unknown id
     */
    Feedback markHandled(UUID feedbackId, UUID adminId);

    /**
     * Clears the flag ({@code adminId} is only traced); no-op when not handled.
     *
     * @throws com.keevo.shared.domain.exception.DomainException NOT_FOUND for an unknown id
     */
    Feedback markUnhandled(UUID feedbackId, UUID adminId);
}

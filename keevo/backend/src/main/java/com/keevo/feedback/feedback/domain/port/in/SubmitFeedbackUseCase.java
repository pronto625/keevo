package com.keevo.feedback.feedback.domain.port.in;

import com.keevo.feedback.feedback.domain.model.Feedback;

import java.util.UUID;

/**
 * SubmitFeedbackUseCase — Inbound port for user feedback submission (Story 14.5, FR92).
 */
public interface SubmitFeedbackUseCase {

    Feedback submit(SubmitFeedbackCommand command);

    record SubmitFeedbackCommand(
            String type,
            String description,
            String appVersion,
            String platform,
            String screenContext,
            String tenantId,
            UUID userId
    ) {}
}

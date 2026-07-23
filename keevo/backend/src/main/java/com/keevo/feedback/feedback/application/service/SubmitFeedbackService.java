package com.keevo.feedback.feedback.application.service;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * SubmitFeedbackService — Application service for feedback submission (Story 14.5, FR92).
 *
 * <p>Determines priority automatically: HIGH if description contains any keyword,
 * NORMAL otherwise. Keywords are case-insensitive.
 */
@Service
@Transactional
public class SubmitFeedbackService implements SubmitFeedbackUseCase {

    private static final Set<String> HIGH_PRIORITY_KEYWORDS = Set.of(
            "bloqué", "erreur critique", "données perdues", "ne fonctionne pas"
    );

    private final FeedbackRepository feedbackRepository;

    public SubmitFeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    @Override
    public Feedback submit(SubmitFeedbackCommand command) {
        String priority = determinePriority(command.description());

        Feedback feedback = new Feedback(
                UUID.randomUUID(),
                command.type(),
                command.description(),
                command.tenantId(),
                command.userId(),
                command.appVersion(),
                command.platform(),
                command.screenContext(),
                Instant.now(),
                priority
        );

        return feedbackRepository.save(feedback);
    }

    /**
     * Determines priority based on keyword matching.
     * Package-private for testing.
     */
    String determinePriority(String description) {
        if (description == null) return "NORMAL";
        String lower = description.toLowerCase(java.util.Locale.FRENCH);
        return HIGH_PRIORITY_KEYWORDS.stream()
                .anyMatch(lower::contains) ? "HIGH" : "NORMAL";
    }
}

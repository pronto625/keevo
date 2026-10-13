package com.keevo.feedback.feedback.application.service;

import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.HandleFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * HandleFeedbackService — sets / clears the « Traité » flag of a feedback (spec 9-1 completion).
 *
 * <p>Idempotent: handling an already handled feedback keeps the first {@code handled_at} /
 * {@code handled_by} (no-op); un-handling a feedback that is not handled is a no-op too. Every
 * effective change is traced with an {@code [ADMIN AUDIT]} log line (actor + feedback id).
 */
@Service
@Transactional
public class HandleFeedbackService implements HandleFeedbackUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandleFeedbackService.class);

    private final FeedbackRepository feedbackRepository;
    private final Clock clock;

    public HandleFeedbackService(FeedbackRepository feedbackRepository, Clock clock) {
        this.feedbackRepository = feedbackRepository;
        this.clock = clock;
    }

    @Override
    public Feedback markHandled(UUID feedbackId, UUID adminId) {
        Objects.requireNonNull(feedbackId, "feedbackId");
        Feedback current = feedbackRepository.findById(feedbackId).orElseThrow(() -> notFound(feedbackId));
        if (current.isHandled()) return current; // first handler kept
        Feedback updated = feedbackRepository.setHandled(feedbackId, Instant.now(clock), adminId)
                .orElseThrow(() -> notFound(feedbackId));
        log.info("[ADMIN AUDIT] Feedback handled: actor={} feedback={}", adminId, feedbackId);
        return updated;
    }

    @Override
    public Feedback markUnhandled(UUID feedbackId, UUID adminId) {
        Objects.requireNonNull(feedbackId, "feedbackId");
        Feedback current = feedbackRepository.findById(feedbackId).orElseThrow(() -> notFound(feedbackId));
        if (!current.isHandled()) return current;
        Feedback updated = feedbackRepository.setHandled(feedbackId, null, null)
                .orElseThrow(() -> notFound(feedbackId));
        log.info("[ADMIN AUDIT] Feedback put back to handle: actor={} feedback={} previouslyHandledBy={}",
                adminId, feedbackId, current.getHandledBy());
        return updated;
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(ErrorCode.NOT_FOUND, "Feedback not found: " + id);
    }
}

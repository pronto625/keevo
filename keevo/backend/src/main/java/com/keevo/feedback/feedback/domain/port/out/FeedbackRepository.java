package com.keevo.feedback.feedback.domain.port.out;

import com.keevo.feedback.feedback.domain.model.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * FeedbackRepository — Driven port for feedback persistence (Story 14.5, FR92).
 */
public interface FeedbackRepository {

    Feedback save(Feedback feedback);

    Optional<Feedback> findById(UUID id);

    /** Filtered page; every non-null criterion is ANDed (spec 9-1 completion). */
    Page<Feedback> search(FeedbackCriteria criteria, Pageable pageable);

    /** Number of feedback matching the criteria (no row loaded). */
    long count(FeedbackCriteria criteria);

    /**
     * Sets or clears the « traité » flag. {@code handledAt == null} clears both columns.
     *
     * @return the updated feedback, empty when the id is unknown
     */
    Optional<Feedback> setHandled(UUID id, Instant handledAt, UUID handledBy);

    /**
     * @param priority      exact priority (HIGH | NORMAL), null = any
     * @param screenContext exact screen route, null = any
     * @param type          exact type (case-insensitive), null = any
     * @param handled       true = handled only, false = unhandled only, null = both
     */
    record FeedbackCriteria(String priority, String screenContext, String type, Boolean handled) {}
}

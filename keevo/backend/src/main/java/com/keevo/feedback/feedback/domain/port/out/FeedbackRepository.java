package com.keevo.feedback.feedback.domain.port.out;

import com.keevo.feedback.feedback.domain.model.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * FeedbackRepository — Driven port for feedback persistence (Story 14.5, FR92).
 */
public interface FeedbackRepository {

    Feedback save(Feedback feedback);

    Optional<Feedback> findById(UUID id);

    Page<Feedback> findAll(Pageable pageable);

    Page<Feedback> findByPriority(String priority, Pageable pageable);

    Page<Feedback> findByScreenContext(String screenContext, Pageable pageable);
}

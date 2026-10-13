package com.keevo.feedback.feedback.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * FeedbackSpringRepository — Spring Data JPA repository for feedback entities (Story 14.5, FR92).
 * Filtered listing goes through {@link JpaSpecificationExecutor} (spec 9-1 completion).
 */
public interface FeedbackSpringRepository extends JpaRepository<FeedbackJpaEntity, UUID>,
        JpaSpecificationExecutor<FeedbackJpaEntity> {
}

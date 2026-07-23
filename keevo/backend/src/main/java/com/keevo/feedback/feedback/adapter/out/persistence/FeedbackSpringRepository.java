package com.keevo.feedback.feedback.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * FeedbackSpringRepository — Spring Data JPA repository for feedback entities (Story 14.5, FR92).
 */
public interface FeedbackSpringRepository extends JpaRepository<FeedbackJpaEntity, UUID> {

    org.springframework.data.domain.Page<FeedbackJpaEntity> findByPriority(String priority,
            org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<FeedbackJpaEntity> findByScreenContext(String screenContext,
            org.springframework.data.domain.Pageable pageable);
}

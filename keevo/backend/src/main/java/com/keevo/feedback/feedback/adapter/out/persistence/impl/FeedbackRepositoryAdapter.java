package com.keevo.feedback.feedback.adapter.out.persistence.impl;

import com.keevo.feedback.feedback.adapter.out.persistence.FeedbackJpaEntity;
import com.keevo.feedback.feedback.adapter.out.persistence.FeedbackSpringRepository;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * FeedbackRepositoryAdapter — Adapter bridging the domain {@link FeedbackRepository}
 * port to the Spring Data JPA {@link FeedbackSpringRepository} (Story 14.5, FR92).
 */
@Component
public class FeedbackRepositoryAdapter implements FeedbackRepository {

    private final FeedbackSpringRepository springRepository;

    public FeedbackRepositoryAdapter(FeedbackSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public Feedback save(Feedback feedback) {
        FeedbackJpaEntity saved = springRepository.save(toEntity(feedback));
        return toDomain(saved);
    }

    @Override
    public Optional<Feedback> findById(UUID id) {
        return springRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Page<Feedback> findAll(Pageable pageable) {
        return springRepository.findAll(pageable).map(this::toDomain);
    }

    @Override
    public Page<Feedback> findByPriority(String priority, Pageable pageable) {
        return springRepository.findByPriority(priority, pageable).map(this::toDomain);
    }

    @Override
    public Page<Feedback> findByScreenContext(String screenContext, Pageable pageable) {
        return springRepository.findByScreenContext(screenContext, pageable).map(this::toDomain);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private FeedbackJpaEntity toEntity(Feedback f) {
        return new FeedbackJpaEntity(
                f.getId(), f.getType(), f.getDescription(),
                f.getTenantId(), f.getUserId(),
                f.getAppVersion(), f.getPlatform(), f.getScreenContext(),
                f.getSubmittedAt(), f.getPriority()
        );
    }

    private Feedback toDomain(FeedbackJpaEntity e) {
        return new Feedback(
                e.getId(), e.getType(), e.getDescription(),
                e.getTenantId(), e.getUserId(),
                e.getAppVersion(), e.getPlatform(), e.getScreenContext(),
                e.getSubmittedAt(), e.getPriority()
        );
    }
}

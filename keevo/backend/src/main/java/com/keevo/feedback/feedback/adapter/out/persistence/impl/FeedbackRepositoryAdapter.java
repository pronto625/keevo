package com.keevo.feedback.feedback.adapter.out.persistence.impl;

import com.keevo.feedback.feedback.adapter.out.persistence.FeedbackJpaEntity;
import com.keevo.feedback.feedback.adapter.out.persistence.FeedbackSpringRepository;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    public Page<Feedback> search(FeedbackCriteria criteria, Pageable pageable) {
        return springRepository.findAll(toSpecification(criteria), pageable).map(this::toDomain);
    }

    @Override
    public long count(FeedbackCriteria criteria) {
        return springRepository.count(toSpecification(criteria));
    }

    @Override
    public Optional<Feedback> setHandled(UUID id, Instant handledAt, UUID handledBy) {
        return springRepository.findById(id).map(entity -> {
            entity.setHandled(handledAt, handledAt == null ? null : handledBy);
            return toDomain(springRepository.save(entity));
        });
    }

    static Specification<FeedbackJpaEntity> toSpecification(FeedbackCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (c.priority() != null) predicates.add(cb.equal(root.get("priority"), c.priority()));
            if (c.screenContext() != null) predicates.add(cb.equal(root.get("screenContext"), c.screenContext()));
            if (c.type() != null) {
                predicates.add(cb.equal(cb.upper(root.get("type")), c.type().toUpperCase(Locale.ROOT)));
            }
            if (Boolean.TRUE.equals(c.handled())) predicates.add(cb.isNotNull(root.get("handledAt")));
            if (Boolean.FALSE.equals(c.handled())) predicates.add(cb.isNull(root.get("handledAt")));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private FeedbackJpaEntity toEntity(Feedback f) {
        FeedbackJpaEntity entity = new FeedbackJpaEntity(
                f.getId(), f.getType(), f.getDescription(),
                f.getTenantId(), f.getUserId(),
                f.getAppVersion(), f.getPlatform(), f.getScreenContext(),
                f.getSubmittedAt(), f.getPriority()
        );
        entity.setHandled(f.getHandledAt(), f.getHandledBy());
        return entity;
    }

    private Feedback toDomain(FeedbackJpaEntity e) {
        return new Feedback(
                e.getId(), e.getType(), e.getDescription(),
                e.getTenantId(), e.getUserId(),
                e.getAppVersion(), e.getPlatform(), e.getScreenContext(),
                e.getSubmittedAt(), e.getPriority(),
                e.getHandledAt(), e.getHandledBy()
        );
    }
}

package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.subscription.plan.adapter.out.persistence.entity.SubscriptionJpaEntity;
import com.keevo.subscription.plan.adapter.out.persistence.jpa.SubscriptionSpringRepository;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.model.SubscriptionStatus;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * SubscriptionRepositoryAdapter — JPA implementation of {@link SubscriptionRepository}.
 *
 * <p>Reads/writes the tenant-schema {@code subscriptions} table via Spring Data JPA.
 * Mapping between JPA entity and domain model is done here — domain model stays clean.
 */
@Component
public class SubscriptionRepositoryAdapter implements SubscriptionRepository {

    private final SubscriptionSpringRepository springRepository;

    public SubscriptionRepositoryAdapter(SubscriptionSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public Optional<Subscription> findActivePlan() {
        return springRepository.findFirstByOrderByCreatedAtAsc()
                .map(this::toDomain);
    }

    @Override
    public Subscription save(Subscription subscription) {
        SubscriptionJpaEntity entity = toJpaEntity(subscription);
        SubscriptionJpaEntity saved = springRepository.save(entity);
        return toDomain(saved);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private Subscription toDomain(SubscriptionJpaEntity e) {
        return new Subscription(
                e.getId(),
                PlanType.valueOf(e.getPlanType()),
                SubscriptionStatus.valueOf(e.getStatus()),
                e.getMaxStores(),
                e.getMaxProducts(),
                e.getMaxEmployees(),
                e.getCreatedAt(),
                e.getExpiresAt()
        );
    }

    private SubscriptionJpaEntity toJpaEntity(Subscription s) {
        return new SubscriptionJpaEntity(
                s.getId() != null ? s.getId() : UUID.randomUUID(),
                s.getPlanType().name(),
                s.getMaxStores(),
                s.getMaxProducts(),
                s.getMaxEmployees(),
                s.getStatus().name(),
                s.getCreatedAt(),
                s.getExpiresAt()
        );
    }
}

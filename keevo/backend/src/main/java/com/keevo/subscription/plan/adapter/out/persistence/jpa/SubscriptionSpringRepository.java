package com.keevo.subscription.plan.adapter.out.persistence.jpa;

import com.keevo.subscription.plan.adapter.out.persistence.entity.SubscriptionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * SubscriptionSpringRepository — Spring Data JPA interface for subscription persistence.
 *
 * <p>Infrastructure layer only — used exclusively by {@code SubscriptionRepositoryAdapter}.
 */
public interface SubscriptionSpringRepository extends JpaRepository<SubscriptionJpaEntity, UUID> {

    /** Find the tenant's single subscription row (first by creation date). */
    Optional<SubscriptionJpaEntity> findFirstByOrderByCreatedAtAsc();
}

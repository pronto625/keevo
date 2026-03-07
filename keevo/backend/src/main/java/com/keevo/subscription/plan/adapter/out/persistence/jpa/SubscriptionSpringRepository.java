package com.keevo.subscription.plan.adapter.out.persistence.jpa;

import com.keevo.subscription.plan.adapter.out.persistence.entity.SubscriptionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
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

    /**
     * Find all expired premium/trial subscriptions across the current schema.
     * Used by {@code SubscriptionExpiryScheduler} — runs in public schema context.
     */
    @Query("SELECT s FROM SubscriptionJpaEntity s " +
           "WHERE s.planType IN ('PREMIUM_TRIAL', 'PREMIUM') " +
           "AND s.status = 'ACTIVE' " +
           "AND s.expiresAt < :now")
    List<SubscriptionJpaEntity> findExpiredPremiumSubscriptions(Instant now);
}

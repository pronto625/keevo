package com.keevo.messaging.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DraftNotificationJpaEntity}.
 */
public interface DraftNotificationSpringRepository
        extends JpaRepository<DraftNotificationJpaEntity, UUID> {

    @Query("SELECT COUNT(d) FROM DraftNotificationJpaEntity d WHERE d.acknowledged = false")
    long countPending();

    Optional<DraftNotificationJpaEntity> findByProductId(UUID productId);
}

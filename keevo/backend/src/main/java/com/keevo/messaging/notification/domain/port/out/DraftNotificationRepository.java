package com.keevo.messaging.notification.domain.port.out;

import com.keevo.messaging.notification.domain.model.DraftPendingValidation;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for {@link DraftPendingValidation}.
 * Implemented in the persistence adapter.
 */
public interface DraftNotificationRepository {

    void save(DraftPendingValidation draft);

    /** Counts unacknowledged drafts for the current tenant schema. */
    long countPending();

    Optional<DraftPendingValidation> findByProductId(UUID productId);

    void saveUpdated(DraftPendingValidation draft);
}

package com.keevo.messaging.notification.adapter.out.persistence;

import com.keevo.messaging.notification.domain.model.DraftPendingValidation;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * DraftNotificationRepositoryAdapter — adapts the Spring Data JPA repository
 * to the domain port {@link DraftNotificationRepository}.
 *
 * <p>Story 2.4.
 */
@Component
public class DraftNotificationRepositoryAdapter implements DraftNotificationRepository {

    private final DraftNotificationSpringRepository springRepo;

    public DraftNotificationRepositoryAdapter(DraftNotificationSpringRepository springRepo) {
        this.springRepo = springRepo;
    }

    @Override
    public void save(DraftPendingValidation draft) {
        springRepo.save(toEntity(draft));
    }

    @Override
    public long countPending() {
        return springRepo.countPending();
    }

    @Override
    public Optional<DraftPendingValidation> findByProductId(UUID productId) {
        return springRepo.findByProductId(productId).map(this::toDomain);
    }

    @Override
    public void saveUpdated(DraftPendingValidation draft) {
        var entity = springRepo.findById(draft.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "DraftNotification not found: " + draft.getId()));
        entity.setAcknowledged(draft.isAcknowledged());
        springRepo.save(entity);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private DraftNotificationJpaEntity toEntity(DraftPendingValidation d) {
        return new DraftNotificationJpaEntity(
                d.getId(), d.getProductId(), d.getProductName(),
                d.getActorId(), d.getTenantId(),
                d.isAcknowledged(), d.getCreatedAt());
    }

    private DraftPendingValidation toDomain(DraftNotificationJpaEntity e) {
        return DraftPendingValidation.of(
                e.getId(), e.getProductId(), e.getProductName(),
                e.getActorId(), e.getTenantId(),
                e.getCreatedAt(), e.isAcknowledged());
    }
}

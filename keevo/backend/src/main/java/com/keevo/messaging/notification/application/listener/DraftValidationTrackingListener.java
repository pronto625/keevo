package com.keevo.messaging.notification.application.listener;

import com.keevo.messaging.notification.domain.model.DraftPendingValidation;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.event.catalog.product.DraftProductPendingEvent;
import com.keevo.shared.event.catalog.product.DraftProductValidatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Tracking of DRAFT products awaiting owner validation (Story 2.4), driven by catalogue events since 12.6a
 * (formerly {@code messaging.api.DraftValidationCommandPort}, called by the catalog).
 *
 * <p>Synchronous {@code @EventListener}s on purpose: they run where the former port call ran — on the
 * publisher's thread, inside its transaction when there is one — so a pending record commits or rolls back
 * with its draft and the badge counter is up to date as soon as the response is sent, as before.
 *
 * <p>Tenant convention: the repository reads the current tenant context, so the event's tenant must be that
 * schema; a mismatch fails fast ({@link IllegalStateException}).
 */
@Component
public class DraftValidationTrackingListener {

    private final DraftNotificationRepository draftRepository;

    public DraftValidationTrackingListener(DraftNotificationRepository draftRepository) {
        this.draftRepository = draftRepository;
    }

    @EventListener
    public void onDraftPending(DraftProductPendingEvent event) {
        TenantContext.requireCurrent(event.tenantId());
        // Deterministic UUID derived from the tenant schema name (same derivation as before 12.2e),
        // so the tracking record carries a meaningful tenant identifier instead of a nil UUID.
        UUID tenantUuid = UUID.nameUUIDFromBytes(event.tenantId().getBytes(StandardCharsets.UTF_8));
        draftRepository.save(DraftPendingValidation.create(
                event.productId(), event.productName(), event.actorId(), tenantUuid));
    }

    @EventListener
    public void onDraftValidated(DraftProductValidatedEvent event) {
        TenantContext.requireCurrent(event.tenantId());
        draftRepository.findByProductId(event.productId()).ifPresent(draft -> {
            draft.acknowledge();
            draftRepository.saveUpdated(draft);
        });
    }
}

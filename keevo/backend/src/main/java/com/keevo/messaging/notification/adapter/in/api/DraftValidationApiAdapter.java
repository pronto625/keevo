package com.keevo.messaging.notification.adapter.in.api;

import com.keevo.messaging.api.DraftValidationCommandPort;
import com.keevo.messaging.api.DraftValidationQueryPort;
import com.keevo.messaging.api.PendingDraftInput;
import com.keevo.messaging.notification.domain.model.DraftPendingValidation;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Implements the published draft-validation ports (Story 12.2e) over the
 * {@link DraftNotificationRepository} port. No transaction of
 * its own: every call joins the caller's transaction.
 *
 * <p>Tenant convention: the repository reads the current tenant context, so the explicit
 * {@code tenantSchema} must be that schema; a mismatch fails fast ({@link IllegalStateException}).
 */
@Component
class DraftValidationApiAdapter implements DraftValidationCommandPort, DraftValidationQueryPort {

    private final DraftNotificationRepository draftRepository;

    DraftValidationApiAdapter(DraftNotificationRepository draftRepository) {
        this.draftRepository = draftRepository;
    }

    @Override
    public void recordPending(String tenantSchema, PendingDraftInput input) {
        TenantContext.requireCurrent(tenantSchema);
        // Deterministic UUID derived from the tenant schema name (same derivation as before 12.2e),
        // so the tracking record carries a meaningful tenant identifier instead of a nil UUID.
        UUID tenantUuid = UUID.nameUUIDFromBytes(tenantSchema.getBytes(StandardCharsets.UTF_8));
        draftRepository.save(DraftPendingValidation.create(
                input.productId(), input.productName(), input.actorId(), tenantUuid));
    }

    @Override
    public void acknowledge(String tenantSchema, UUID productId) {
        TenantContext.requireCurrent(tenantSchema);
        draftRepository.findByProductId(productId).ifPresent(draft -> {
            draft.acknowledge();
            draftRepository.saveUpdated(draft);
        });
    }

    @Override
    public long countPending(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return draftRepository.countPending();
    }
}

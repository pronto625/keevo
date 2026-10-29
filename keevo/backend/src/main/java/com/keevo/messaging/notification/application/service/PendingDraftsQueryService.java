package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Pending-drafts badge counter (Story 2.4 AC6; served by messaging since 12.6a). No authorisation here: the
 * caller (the drafts counter endpoint) restricts it to the OWNER.
 *
 * <p>Tenant convention: the repository reads the current tenant context, so {@code tenantSchema} must be
 * that schema; null, blank or another schema fails fast ({@link IllegalStateException}).
 */
@Service
public class PendingDraftsQueryService {

    private final DraftNotificationRepository draftRepository;

    public PendingDraftsQueryService(DraftNotificationRepository draftRepository) {
        this.draftRepository = draftRepository;
    }

    /** Unacknowledged drafts of the tenant. */
    public long countPending(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return draftRepository.countPending();
    }
}

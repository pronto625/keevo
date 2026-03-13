package com.keevo.messaging.notification.application.usecase;

import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * GetPendingDraftsCountUseCase — returns the count of unacknowledged DRAFT products
 * for the current tenant.
 *
 * <p>OWNER-only (AC10): employees receive HTTP 403.
 *
 * <p>Drives the in-app badge counter visible to the OWNER on the product list screen.
 *
 * <p>Story 2.4 — AC10.
 */
@Service
public class GetPendingDraftsCountUseCase {

    private final DraftNotificationRepository draftRepository;

    public GetPendingDraftsCountUseCase(DraftNotificationRepository draftRepository) {
        this.draftRepository = draftRepository;
    }

    /**
     * @param actorRole "OWNER" or "EMPLOYEE" — extracted from JWT by the controller
     * @return count of unacknowledged draft notifications for the current tenant schema
     * @throws DomainException FORBIDDEN if actorRole is not "OWNER"
     */
    public long execute(String actorRole) {
        if (!"OWNER".equals(actorRole)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut consulter le compteur de brouillons");
        }
        return draftRepository.countPending();
    }
}


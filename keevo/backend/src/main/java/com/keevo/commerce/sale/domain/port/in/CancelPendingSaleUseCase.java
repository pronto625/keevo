package com.keevo.commerce.sale.domain.port.in;

import java.util.UUID;

/**
 * CancelPendingSaleUseCase — input port for cancelling a pending sale.
 * Story 4.3 AC10.
 */
public interface CancelPendingSaleUseCase {

    /**
     * @param pendingOnly true for the sync channel (Story v1s-13-5 AC5 / 13.9): only a
     *                    PENDING_VALIDATION sale may be cancelled; a COMPLETED sale — at load time
     *                    or validated concurrently — is refused with {@code FORBIDDEN}, because
     *                    cancelling it (stock restoration) needs an online OWNER request.
     */
    record CancelPendingSaleCommand(UUID saleId, UUID actorId, UUID assignedStoreId, String justification,
                                    boolean pendingOnly) {

        /** REST path: PENDING_VALIDATION and COMPLETED sales may be cancelled. */
        public CancelPendingSaleCommand(UUID saleId, UUID actorId, UUID assignedStoreId, String justification) {
            this(saleId, actorId, assignedStoreId, justification, false);
        }
    }

    void cancelPendingSale(CancelPendingSaleCommand command);
}

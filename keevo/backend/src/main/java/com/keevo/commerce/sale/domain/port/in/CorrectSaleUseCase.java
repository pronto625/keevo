package com.keevo.commerce.sale.domain.port.in;

import java.util.Map;
import java.util.UUID;

/**
 * CorrectSaleUseCase — input port for correcting item quantities on a COMPLETED sale.
 * Story v1s-13-5 (AC6-AC7).
 */
public interface CorrectSaleUseCase {

    /**
     * @param correctionId per-request id sent by the app (v1s-13-13), from which the ADJUSTMENT
     *                     movement ids are derived ({@code DeterministicIds.saleCorrectionMovementId});
     *                     null (older app) → random movement ids.
     */
    record CorrectSaleCommand(UUID saleId, UUID actorId, UUID assignedStoreId,
                              String justification, Map<UUID, Integer> itemQuantities,
                              UUID correctionId) {
        public CorrectSaleCommand(UUID saleId, UUID actorId, UUID assignedStoreId,
                                  String justification, Map<UUID, Integer> itemQuantities) {
            this(saleId, actorId, assignedStoreId, justification, itemQuantities, null);
        }
    }

    void correctSale(CorrectSaleCommand command);
}

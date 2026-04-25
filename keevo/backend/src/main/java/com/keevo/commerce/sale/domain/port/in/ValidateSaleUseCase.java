package com.keevo.commerce.sale.domain.port.in;

import java.util.Map;
import java.util.UUID;

/**
 * ValidateSaleUseCase — input port for manual force-validation of a pending sale.
 * Story 4.3 AC9.
 */
public interface ValidateSaleUseCase {

    record ValidateSaleCommand(UUID saleId, UUID actorId, UUID assignedStoreId, String justification,
                                Map<UUID, UUID> productIdRemappings,
                                Map<UUID, Integer> initialStockEntries) {
        public ValidateSaleCommand(UUID saleId, UUID actorId, String justification) {
            this(saleId, actorId, null, justification, null, null);
        }
        public ValidateSaleCommand(UUID saleId, UUID actorId, String justification, Map<UUID, UUID> productIdRemappings) {
            this(saleId, actorId, null, justification, productIdRemappings, null);
        }
    }

    void validateSale(ValidateSaleCommand command);
}

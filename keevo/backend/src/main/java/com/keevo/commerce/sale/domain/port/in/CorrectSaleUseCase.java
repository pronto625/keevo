package com.keevo.commerce.sale.domain.port.in;

import java.util.Map;
import java.util.UUID;

/**
 * CorrectSaleUseCase — input port for correcting item quantities on a COMPLETED sale.
 * Story v1s-13-5 (AC6-AC7).
 */
public interface CorrectSaleUseCase {

    record CorrectSaleCommand(UUID saleId, UUID actorId, UUID assignedStoreId,
                              String justification, Map<UUID, Integer> itemQuantities) {}

    void correctSale(CorrectSaleCommand command);
}

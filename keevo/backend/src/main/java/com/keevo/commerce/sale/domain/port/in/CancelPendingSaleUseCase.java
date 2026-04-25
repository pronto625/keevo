package com.keevo.commerce.sale.domain.port.in;

import java.util.UUID;

/**
 * CancelPendingSaleUseCase — input port for cancelling a pending sale.
 * Story 4.3 AC10.
 */
public interface CancelPendingSaleUseCase {

    record CancelPendingSaleCommand(UUID saleId, UUID actorId, UUID assignedStoreId, String justification) {}

    void cancelPendingSale(CancelPendingSaleCommand command);
}

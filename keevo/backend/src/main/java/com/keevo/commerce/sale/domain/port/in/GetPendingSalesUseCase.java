package com.keevo.commerce.sale.domain.port.in;

import com.keevo.commerce.sale.domain.model.Sale;

import java.util.List;
import java.util.UUID;

/**
 * GetPendingSalesUseCase — input port for listing pending sales (OWNER-only).
 * Story 4.3 AC6.
 */
public interface GetPendingSalesUseCase {
    List<Sale> getPendingSales();

    /** AC5: List pending sales for a specific store (for EMPLOYEE role). */
    List<Sale> getPendingSalesByStore(UUID storeId);
}

package com.keevo.commerce.sale.domain.port.in;

import com.keevo.commerce.sale.domain.model.Sale;

import java.util.List;

/**
 * GetPendingSalesUseCase — input port for listing pending sales (OWNER-only).
 * Story 4.3 AC6.
 */
public interface GetPendingSalesUseCase {
    List<Sale> getPendingSales();
}

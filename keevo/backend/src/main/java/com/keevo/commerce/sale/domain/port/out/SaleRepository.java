package com.keevo.commerce.sale.domain.port.out;

import com.keevo.commerce.sale.domain.model.Sale;

import java.util.Optional;
import java.util.UUID;

/**
 * SaleRepository — output port for sale persistence.
 * Pure Java — no framework imports.
 */
public interface SaleRepository {

    void save(Sale sale);

    boolean existsById(UUID saleId);

    Optional<Sale> findById(UUID saleId);
}

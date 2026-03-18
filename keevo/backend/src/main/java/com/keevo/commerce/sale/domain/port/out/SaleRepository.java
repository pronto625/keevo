package com.keevo.commerce.sale.domain.port.out;

import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleStatus;

import java.util.List;
import java.util.Map;
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

    List<Sale> findPendingByProductId(UUID productId);

    List<Sale> findByStatus(SaleStatus status);

    void updateStatus(UUID saleId, SaleStatus newStatus);

    void remapItemProductIds(UUID saleId, Map<UUID, UUID> remappings);
}

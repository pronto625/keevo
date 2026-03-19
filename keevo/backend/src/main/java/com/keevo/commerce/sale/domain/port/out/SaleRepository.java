package com.keevo.commerce.sale.domain.port.out;

import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
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

    // ── Story 4.4 — Sales History queries ─────────────────────────────────────

    /**
     * Find sales for a store within a date range, filtered by employee.
     * Used by EMPLOYEE role — sees only own sales.
     */
    Page<Sale> findByStoreIdAndEmployeeIdAndDateRange(UUID storeId, UUID employeeId,
                                                       Instant from, Instant to, Pageable pageable);

    /**
     * Find all sales for a store within a date range.
     * Used by OWNER role — sees all store sales.
     */
    Page<Sale> findByStoreIdAndDateRange(UUID storeId, Instant from, Instant to, Pageable pageable);

    /**
     * Find sales for a store within a date range, filtered by status.
     * Used for day closure to separate COMPLETED vs PENDING_VALIDATION.
     */
    Page<Sale> findByStoreIdAndDateRangeAndStatus(UUID storeId, Instant from, Instant to,
                                                   SaleStatus status, Pageable pageable);

    /**
     * Check if any sales exist for a store within a date range with given status.
     * Used by scheduler to check if closure already triggered.
     */
    boolean existsByStoreIdAndDateRangeAndStatus(UUID storeId, Instant from, Instant to,
                                                  SaleStatus status);
}

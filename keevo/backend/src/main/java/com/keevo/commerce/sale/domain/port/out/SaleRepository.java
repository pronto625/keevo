package com.keevo.commerce.sale.domain.port.out;

import com.keevo.commerce.api.ClosureWindow;

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

    /**
     * Compare-and-set status change: moves the sale to {@code newStatus} only if it is still in
     * {@code expected}. Returns true when this call made the transition, so that two concurrent
     * cancellations cannot both restore the stock. A successful transition also bumps the sale's
     * optimistic-lock version (Story v1s-13-10).
     *
     * <p>The default is a read-then-write and is NOT atomic; persistence adapters must override it
     * with a single conditional UPDATE ({@code ... WHERE id = :id AND status = :expected}) and
     * return whether a row was updated.
     */
    default boolean updateStatusIfCurrent(UUID saleId, SaleStatus expected, SaleStatus newStatus) {
        boolean matches = findById(saleId).map(s -> s.getStatus() == expected).orElse(false);
        if (matches) updateStatus(saleId, newStatus);
        return matches;
    }

    /**
     * Story v1s-13-10 — versioned compare-and-set: moves the sale to {@code newStatus} only if it
     * is still in {@code expected} AND its optimistic-lock version is still {@code expectedVersion}.
     * A successful transition also bumps the version. Returns false when 0 rows matched (status
     * changed, or the sale was modified — e.g. corrected — since it was read).
     */
    boolean updateStatusIfCurrent(UUID saleId, SaleStatus expected, long expectedVersion,
                                  SaleStatus newStatus);

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

    /** Sales a day closure covers, late offline sales included — see {@link ClosureWindow}. */
    List<Sale> findForClosure(UUID storeId, ClosureWindow window);

    /**
     * Find sales for a store within a date range, filtered by status.
     * Used for day closure to separate COMPLETED vs PENDING_VALIDATION.
     */
    Page<Sale> findByStoreIdAndDateRangeAndStatus(UUID storeId, Instant from, Instant to,
                                                   SaleStatus status, Pageable pageable);

    /** AC5: Find pending sales for a specific store (for EMPLOYEE role). */
    List<Sale> findByStoreIdAndStatus(UUID storeId, SaleStatus status);

    /**
     * Check if any sales exist for a store within a date range with given status.
     * Used by scheduler to check if closure already triggered.
     */
    boolean existsByStoreIdAndDateRangeAndStatus(UUID storeId, Instant from, Instant to,
                                                  SaleStatus status);
}

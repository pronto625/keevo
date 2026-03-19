package com.keevo.commerce.sale.domain.port.in;

import com.keevo.commerce.sale.domain.model.Sale;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.UUID;

/**
 * GetSalesHistoryUseCase — Port for retrieving sales history with filters.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>EMPLOYEE: sees only their own sales (employeeId forced to JWT actorId).
 * <p>OWNER: sees all sales in the store (employeeId filter is optional).
 */
public interface GetSalesHistoryUseCase {

    /**
     * Query for sales history.
     *
     * @param storeId    Store to query
     * @param employeeId Optional employee filter (ignored for EMPLOYEE role — forced to JWT actorId)
     * @param from       Start of date range (inclusive)
     * @param to         End of date range (inclusive)
     * @param role       User role: "OWNER" or "EMPLOYEE"
     * @param page       Page number (0-indexed)
     * @param size       Page size
     */
    record SalesHistoryQuery(UUID storeId, UUID employeeId, Instant from, Instant to,
                             String role, int page, int size) {}

    /**
     * Get paginated sales history.
     *
     * @param query search criteria
     * @return page of sales matching the criteria
     */
    Page<Sale> getSalesHistory(SalesHistoryQuery query);
}

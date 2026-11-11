package com.keevo.commerce.sale.domain.port.in;

import com.keevo.commerce.sale.domain.model.Sale;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.UUID;

/**
 * GetSalesHistoryUseCase — Port for retrieving sales history with filters.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Story 19.5b — the inbound adapter decides the store and the employee filter ({@code SalesReadScope}); the use
 * case only applies them: {@code employeeId == null} → every sale of the store, else that employee's.
 */
public interface GetSalesHistoryUseCase {

    /**
     * Query for sales history.
     *
     * @param storeId    Store to query
     * @param employeeId Optional employee filter, already decided by the adapter ({@code null} = whole store)
     * @param from       Start of date range (inclusive)
     * @param to         End of date range (inclusive)
     * @param page       Page number (0-indexed)
     * @param size       Page size
     */
    record SalesHistoryQuery(UUID storeId, UUID employeeId, Instant from, Instant to, int page, int size) {}

    /**
     * Get paginated sales history.
     *
     * @param query search criteria
     * @return page of sales matching the criteria
     */
    Page<Sale> getSalesHistory(SalesHistoryQuery query);
}

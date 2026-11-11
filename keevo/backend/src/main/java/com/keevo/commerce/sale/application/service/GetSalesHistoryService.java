package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GetSalesHistoryService — Retrieves sales history.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Story 19.5b — the actor's scope (whole store, or his own sales) is decided by the inbound adapter
 * ({@code SalesReadScope}) and arrives as the employee filter: {@code null} → every sale of the store, else that
 * employee's sales. No role is read here.
 */
@Service
@Transactional(readOnly = true)
public class GetSalesHistoryService implements GetSalesHistoryUseCase {

    private final SaleRepository saleRepository;

    public GetSalesHistoryService(SaleRepository saleRepository) {
        this.saleRepository = saleRepository;
    }

    @Override
    public Page<Sale> getSalesHistory(SalesHistoryQuery query) {
        var pageable = PageRequest.of(query.page(), query.size(),
                Sort.by(Sort.Direction.DESC, "occurredAt"));
        if (query.employeeId() != null) {
            return saleRepository.findByStoreIdAndEmployeeIdAndDateRange(
                    query.storeId(), query.employeeId(), query.from(), query.to(), pageable);
        }
        return saleRepository.findByStoreIdAndDateRange(query.storeId(), query.from(), query.to(), pageable);
    }
}

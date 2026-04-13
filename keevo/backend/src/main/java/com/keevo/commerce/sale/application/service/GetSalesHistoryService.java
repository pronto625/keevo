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
 * GetSalesHistoryService — Retrieves sales history with RBAC filtering.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>EMPLOYEE: always filters on their own employeeId (from JWT).
 * <p>OWNER: can see all sales or optionally filter by employeeId.
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

        if ("EMPLOYEE".equals(query.role())) {
            // EMPLOYEE always sees only their own sales
            // The employeeId in query should be their JWT actorId (forced by controller)
            return saleRepository.findByStoreIdAndEmployeeIdAndDateRange(
                    query.storeId(),
                    query.employeeId(),
                    query.from(),
                    query.to(),
                    pageable
            );
        } else {
            // OWNER role - can see all sales or filter by specific employee
            if (query.employeeId() != null) {
                return saleRepository.findByStoreIdAndEmployeeIdAndDateRange(
                        query.storeId(),
                        query.employeeId(),
                        query.from(),
                        query.to(),
                        pageable
                );
            } else {
                return saleRepository.findByStoreIdAndDateRange(
                        query.storeId(),
                        query.from(),
                        query.to(),
                        pageable
                );
            }
        }
    }
}

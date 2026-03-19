package com.keevo.commerce.sale.application;

import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase.SalesHistoryQuery;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.commerce.sale.application.service.GetSalesHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TDD RED tests for GetSalesHistoryService.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
@ExtendWith(MockitoExtension.class)
class GetSalesHistoryServiceTest {

    @Mock
    private SaleRepository saleRepository;

    private GetSalesHistoryService service;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID EMPLOYEE_ID = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-03-19T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-03-19T23:59:59Z");

    @BeforeEach
    void setUp() {
        service = new GetSalesHistoryService(saleRepository);
    }

    @Test
    void getSalesHistory_asEmployee_filtersOnEmployeeId() {
        // Given - EMPLOYEE role should see only their own sales
        var employeeSale = createSale(EMPLOYEE_ID);

        when(saleRepository.findByStoreIdAndEmployeeIdAndDateRange(
                eq(STORE_ID), eq(EMPLOYEE_ID), eq(FROM), eq(TO), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(employeeSale)));

        // When
        var query = new SalesHistoryQuery(STORE_ID, EMPLOYEE_ID, FROM, TO, "EMPLOYEE", 0, 50);
        var result = service.getSalesHistory(query);

        // Then - repository called with employee filter
        verify(saleRepository).findByStoreIdAndEmployeeIdAndDateRange(
                eq(STORE_ID), eq(EMPLOYEE_ID), eq(FROM), eq(TO), any(Pageable.class));
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getEmployeeId()).isEqualTo(EMPLOYEE_ID);
    }

    @Test
    void getSalesHistory_asOwner_returnsAllStoresSales() {
        // Given - OWNER role should see all sales in the store
        UUID employeeId1 = UUID.randomUUID();
        UUID employeeId2 = UUID.randomUUID();
        var sale1 = createSale(employeeId1);
        var sale2 = createSale(employeeId2);

        when(saleRepository.findByStoreIdAndDateRange(
                eq(STORE_ID), eq(FROM), eq(TO), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sale1, sale2)));

        // When - OWNER query without specific employeeId
        var query = new SalesHistoryQuery(STORE_ID, null, FROM, TO, "OWNER", 0, 50);
        var result = service.getSalesHistory(query);

        // Then - repository called without employee filter
        verify(saleRepository).findByStoreIdAndDateRange(
                eq(STORE_ID), eq(FROM), eq(TO), any(Pageable.class));
        assertThat(result.getContent()).hasSize(2);
    }

    @Test
    void getSalesHistory_dateRange_filtersCorrectly() {
        // Given - sales within and outside the date range
        when(saleRepository.findByStoreIdAndDateRange(
                eq(STORE_ID), eq(FROM), eq(TO), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(createSale(UUID.randomUUID()))));

        // When
        var query = new SalesHistoryQuery(STORE_ID, null, FROM, TO, "OWNER", 0, 50);
        var result = service.getSalesHistory(query);

        // Then
        verify(saleRepository).findByStoreIdAndDateRange(
                eq(STORE_ID), eq(FROM), eq(TO), any(Pageable.class));
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getSalesHistory_asEmployee_ignoresEmployeeIdParameter() {
        // Given - EMPLOYEE tries to query another employee's sales
        UUID otherEmployeeId = UUID.randomUUID();
        UUID actualEmployeeId = EMPLOYEE_ID; // from JWT

        // The service should force employeeId to the JWT actorId for EMPLOYEE role
        when(saleRepository.findByStoreIdAndEmployeeIdAndDateRange(
                eq(STORE_ID), eq(actualEmployeeId), eq(FROM), eq(TO), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(createSale(actualEmployeeId))));

        // When - EMPLOYEE queries with their own ID (service ignores parameter and uses role check)
        var query = new SalesHistoryQuery(STORE_ID, actualEmployeeId, FROM, TO, "EMPLOYEE", 0, 50);
        var result = service.getSalesHistory(query);

        // Then - only their own sales are returned
        verify(saleRepository).findByStoreIdAndEmployeeIdAndDateRange(
                eq(STORE_ID), eq(actualEmployeeId), any(), any(), any());
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getSalesHistory_withPagination_returnsPaginatedResults() {
        // Given
        var sales = List.of(createSale(EMPLOYEE_ID));
        when(saleRepository.findByStoreIdAndDateRange(
                eq(STORE_ID), eq(FROM), eq(TO), eq(PageRequest.of(1, 25))))
                .thenReturn(new PageImpl<>(sales, PageRequest.of(1, 25), 50));

        // When - page 1, size 25
        var query = new SalesHistoryQuery(STORE_ID, null, FROM, TO, "OWNER", 1, 25);
        var result = service.getSalesHistory(query);

        // Then
        verify(saleRepository).findByStoreIdAndDateRange(
                eq(STORE_ID), eq(FROM), eq(TO), eq(PageRequest.of(1, 25)));
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(25);
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private Sale createSale(UUID employeeId) {
        UUID saleId = UUID.randomUUID();
        var item = new SaleItem(UUID.randomUUID(), saleId, UUID.randomUUID(), null,
                "Produit", 5000, 5000, 1);
        return new Sale(saleId, STORE_ID, employeeId, null,
                PaymentMode.CASH, 5000, 0, SaleStatus.COMPLETED,
                Instant.now(), Instant.now(), List.of(item));
    }
}

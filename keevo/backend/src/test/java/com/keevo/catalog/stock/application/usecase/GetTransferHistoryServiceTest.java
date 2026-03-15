package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.port.in.GetTransferHistoryQuery;
import com.keevo.catalog.stock.domain.port.out.StockTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GetTransferHistoryServiceTest — TDD RED phase first.
 * Task 6.3 — Story 3.3.
 */
@ExtendWith(MockitoExtension.class)
class GetTransferHistoryServiceTest {

    @Mock private StockTransferRepository transferRepository;

    private GetTransferHistoryService service;

    @BeforeEach
    void setUp() {
        service = new GetTransferHistoryService(transferRepository);
    }

    @Test
    void execute_shouldDelegateToRepository_withCorrectFilters() {
        var query = new GetTransferHistoryQuery(null, null, null, null, 0, 25);
        when(transferRepository.findByFilters(any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());

        service.execute(query);

        verify(transferRepository).findByFilters(isNull(), isNull(), isNull(), isNull(),
            argThat(p -> p.getPageNumber() == 0 && p.getPageSize() == 25));
    }

    @Test
    void execute_shouldDefaultToPage0Size25_whenNotSpecified() {
        var query = new GetTransferHistoryQuery(null, null, null, null, 0, 25);
        when(transferRepository.findByFilters(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of()));

        var result = service.execute(query);

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transferRepository).findByFilters(any(), any(), any(), any(), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(25);
    }
}

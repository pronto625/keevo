package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import com.keevo.catalog.stock.domain.port.out.StockMovementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GetStockMovementHistoryUseCaseTest — TDD for paginated movement history.
 * Story 2.3.
 */
@ExtendWith(MockitoExtension.class)
class GetStockMovementHistoryUseCaseTest {

    @Mock private StockMovementRepository stockMovementRepository;
    @InjectMocks private GetStockMovementHistoryUseCase useCase;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID   = UUID.randomUUID();
    private static final UUID ACTOR_ID   = UUID.randomUUID();

    private StockMovement fakeMovement() {
        return new StockMovement(
            UUID.randomUUID(), PRODUCT_ID, null, STORE_ID,
            MovementType.STOCK_ENTRY, 0, 10, 10,
            ACTOR_ID, null, Instant.now());
    }

    @Test
    void should_return_page_with_all_optional_filters_null() {
        Page<StockMovement> expected = new PageImpl<>(List.of(fakeMovement()));
        when(stockMovementRepository.findByProductId(
                eq(PRODUCT_ID), eq(null), eq(null), eq(null), eq(null), any()))
            .thenReturn(expected);

        Page<StockMovement> result = useCase.execute(PRODUCT_ID, null, null, null, null, 0, 20);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(stockMovementRepository).findByProductId(
            eq(PRODUCT_ID), eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    void should_pass_movement_type_filter() {
        when(stockMovementRepository.findByProductId(
                eq(PRODUCT_ID), eq(MovementType.ADJUSTMENT), any(), any(), any(), any()))
            .thenReturn(Page.empty());

        useCase.execute(PRODUCT_ID, MovementType.ADJUSTMENT, null, null, null, 0, 20);

        verify(stockMovementRepository).findByProductId(
            eq(PRODUCT_ID), eq(MovementType.ADJUSTMENT), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void should_clamp_page_size_to_maximum_100() {
        when(stockMovementRepository.findByProductId(any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        useCase.execute(PRODUCT_ID, null, null, null, null, 0, 9999);

        verify(stockMovementRepository).findByProductId(any(), any(), any(), any(), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void should_clamp_page_size_minimum_to_1() {
        when(stockMovementRepository.findByProductId(any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        useCase.execute(PRODUCT_ID, null, null, null, null, 0, 0);

        verify(stockMovementRepository).findByProductId(any(), any(), any(), any(), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void should_sort_by_occurredAt_desc() {
        when(stockMovementRepository.findByProductId(any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        useCase.execute(PRODUCT_ID, null, null, null, null, 0, 20);

        verify(stockMovementRepository).findByProductId(any(), any(), any(), any(), any(), pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("occurredAt");
        assertThat(order).isNotNull();
        assertThat(order.isDescending()).isTrue();
    }

    @Test
    void should_pass_date_range_and_store_filters() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to   = Instant.parse("2026-03-01T00:00:00Z");

        when(stockMovementRepository.findByProductId(
                eq(PRODUCT_ID), eq(null), eq(from), eq(to), eq(STORE_ID), any()))
            .thenReturn(Page.empty());

        useCase.execute(PRODUCT_ID, null, from, to, STORE_ID, 0, 20);

        verify(stockMovementRepository).findByProductId(
            eq(PRODUCT_ID), eq(null), eq(from), eq(to), eq(STORE_ID), any(Pageable.class));
    }
}

package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import com.keevo.catalog.stock.domain.port.in.GetStoreStockDetailQuery;
import com.keevo.catalog.stock.domain.port.in.GetStoreStockDetailUseCase;
import com.keevo.catalog.stock.domain.port.out.MultiStoreStockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GetStoreStockDetailServiceTest — unit tests for store stock detail service.
 * Story 3.2. TDD RED phase.
 */
@ExtendWith(MockitoExtension.class)
class GetStoreStockDetailServiceTest {

    @Mock
    private MultiStoreStockRepository repo;

    @InjectMocks
    private GetStoreStockDetailService service;

    private final UUID storeId = UUID.randomUUID();

    private StoreProductStockEntry entry(int qty, int threshold) {
        return new StoreProductStockEntry(UUID.randomUUID(), "Prod", null, null, storeId, qty, threshold);
    }

    @Test
    void execute_shouldReturnPagedEntries_forValidStore() {
        var page = new PageImpl<>(List.of(entry(5, 10)));
        when(repo.getStoreStockDetail(eq(storeId), anyBoolean(), any(Pageable.class)))
            .thenReturn(page);

        var result = service.execute(new GetStoreStockDetailQuery(storeId, 0, 25, true));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void execute_shouldSortLowStockFirst_whenFlagSet() {
        ArgumentCaptor<Boolean> sortCaptor = ArgumentCaptor.forClass(Boolean.class);
        when(repo.getStoreStockDetail(any(), sortCaptor.capture(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.execute(new GetStoreStockDetailQuery(storeId, 0, 25, true));

        assertThat(sortCaptor.getValue()).isTrue();
    }

    @Test
    void execute_shouldDelegateToRepository_withCorrectPageable() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(repo.getStoreStockDetail(any(), anyBoolean(), pageableCaptor.capture()))
            .thenReturn(new PageImpl<>(List.of()));

        service.execute(new GetStoreStockDetailQuery(storeId, 2, 10, false));

        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(2);
        assertThat(captured.getPageSize()).isEqualTo(10);
    }
}

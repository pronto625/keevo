package com.keevo.inventory.stock.application.usecase;

import com.keevo.inventory.stock.domain.model.StoreStockSummary;
import com.keevo.inventory.stock.domain.port.in.GetMultiStoreOverviewQuery;
import com.keevo.inventory.stock.domain.port.in.GetMultiStoreOverviewUseCase;
import com.keevo.inventory.stock.domain.port.out.MultiStoreStockRepository;
import com.keevo.store.api.StoreType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.keevo.inventory.stock.domain.model.StockReadScope;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GetMultiStoreOverviewServiceTest — unit tests for overview service.
 * Story 3.2. TDD RED phase.
 */
@ExtendWith(MockitoExtension.class)
class GetMultiStoreOverviewServiceTest {

    @Mock
    private MultiStoreStockRepository repo;

    @InjectMocks
    private GetMultiStoreOverviewService service;

    @Test
    void execute_shouldReturnAllActiveStoresSummaries() {
        var summary1 = new StoreStockSummary(UUID.randomUUID(), "Boutique A", StoreType.STORE, 5, 100000L, 1);
        var summary2 = new StoreStockSummary(UUID.randomUUID(), "Entrepôt", StoreType.WAREHOUSE, 20, 500000L, 0);
        when(repo.getStoreOverviews(null)).thenReturn(List.of(summary1, summary2));

        var result = service.execute(new GetMultiStoreOverviewQuery(StockReadScope.ALL));

        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(summary1, summary2);
    }

    @Test
    void execute_shouldReturnEmptyList_whenNoStores() {
        when(repo.getStoreOverviews(null)).thenReturn(List.of());

        var result = service.execute(new GetMultiStoreOverviewQuery(StockReadScope.ALL));

        assertThat(result).isEmpty();
    }

    @Test
    void execute_shouldDelegateToRepository() {
        when(repo.getStoreOverviews(null)).thenReturn(List.of());

        service.execute(new GetMultiStoreOverviewQuery(StockReadScope.ALL));

        verify(repo).getStoreOverviews(null);
    }

    // ── Story 16.13 (reprise) ──

    @org.junit.jupiter.api.Test
    void member_readsOnlyOwnStore_inSql() {
        java.util.UUID own = java.util.UUID.randomUUID();
        when(repo.getStoreOverviews(own)).thenReturn(List.of());
        service.execute(new GetMultiStoreOverviewQuery(StockReadScope.store(own)));
        verify(repo).getStoreOverviews(own);
    }

    @org.junit.jupiter.api.Test
    void memberWithoutStore_orNullScope_readsNothing_noQuery() {
        org.assertj.core.api.Assertions.assertThat(
                service.execute(new GetMultiStoreOverviewQuery(StockReadScope.store(null)))).isEmpty();
        org.assertj.core.api.Assertions.assertThat(service.execute(new GetMultiStoreOverviewQuery(null))).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(repo);
    }
}

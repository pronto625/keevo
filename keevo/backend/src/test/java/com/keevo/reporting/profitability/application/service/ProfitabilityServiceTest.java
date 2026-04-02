package com.keevo.reporting.profitability.application.service;

import com.keevo.reporting.profitability.domain.model.*;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase.ProfitabilityQuery;
import com.keevo.reporting.profitability.domain.port.in.GetStorePerformanceUseCase.StorePerformanceQuery;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.*;
import com.keevo.reporting.profitability.domain.service.ProfitabilitySortStrategyFactory;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 2.2 — TDD RED: ProfitabilityService tests.
 * Story 7.4.
 */
@ExtendWith(MockitoExtension.class)
class ProfitabilityServiceTest {

    @Mock
    private ProfitabilityRepository repo;
    @Mock
    private ProfitabilityReportBuilder reportBuilder;

    private ProfitabilityService service;

    private static final String TENANT = "tenant_test";
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 1, 31);

    @BeforeEach
    void setUp() {
        service = new ProfitabilityService(repo, reportBuilder);
    }

    private ProductProfitabilityEntry testEntry(String name, double marginPct, boolean isLoss) {
        long rev = isLoss ? 500 : 1200;
        long cost = 1000;
        long gross = rev - cost;
        return new ProductProfitabilityEntry(
                UUID.randomUUID(), name, null, 1, rev, cost, gross, marginPct, isLoss, null
        );
    }

    @Test
    void getProductProfitability_delegatesToBuilder_andAppliesSortStrategy() {
        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        var entries = List.of(
                testEntry("B", 15.0, false),
                testEntry("A", 25.0, false)
        );
        when(reportBuilder.buildEntries(query)).thenReturn(entries);

        var result = service.getEntries(query);

        assertThat(result).hasSize(2);
        // Strategy should have sorted: A (25%) → B (15%)
        assertThat(result.get(0).productName()).isEqualTo("A");
    }

    @Test
    void getProductProfitability_emptyPeriod_returnsEmptyList() {
        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        when(reportBuilder.buildEntries(query)).thenReturn(List.of());

        var result = service.getEntries(query);

        assertThat(result).isEmpty();
    }

    @Test
    void getProductProfitabilityDetail_returnsDetailWithSparkline() {
        UUID productId = UUID.randomUUID();
        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        var detail = new ProductProfitabilityDetail(
                productId, "Prod", null, 3, 15000, 4500, 10500, 233.3, false, null,
                5000, 1000, 500, 1000, 5000, 2500.0,
                List.of(new ProductProfitabilityDetail.DailyMarginEntry("2026-01-01", 1500)),
                null, null, 0
        );
        when(reportBuilder.buildDetail(query, productId)).thenReturn(detail);

        var result = service.getDetail(query, productId);

        assertThat(result.dailyMarginLast7()).hasSize(1);
        assertThat(result.dailyMarginLast7().get(0).date()).isEqualTo("2026-01-01");
    }

    @Test
    void getProductProfitabilityDetail_unknownProduct_throwsDomainException() {
        UUID unknownId = UUID.randomUUID();
        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        when(reportBuilder.buildDetail(query, unknownId))
                .thenThrow(new DomainException("PRODUCT_NOT_FOUND"));

        assertThatThrownBy(() -> service.getDetail(query, unknownId))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("PRODUCT_NOT_FOUND");
    }

    @Test
    void getStorePerformance_delegatesToStoreRankingStrategy() {
        var query = new StorePerformanceQuery(TENANT, FROM, TO, RankingMetric.CA);
        var rawRows = List.of(
                new RawStorePerformanceRow(UUID.randomUUID(), "Store_A", 90000L, 10, 9000L, "Prod1"),
                new RawStorePerformanceRow(UUID.randomUUID(), "Store_B", 50000L, 5,  10000L, "Prod2")
        );
        when(repo.findRawStoreByPeriod(eq(TENANT), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        when(repo.findRawStoreByPeriod(eq(TENANT), eq(FROM), eq(TO))).thenReturn(rawRows);

        var result = service.getRanking(query);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).storeName()).isEqualTo("Store_A");
        assertThat(result.get(0).rank()).isEqualTo(1);
    }

    @Test
    void getStorePerformance_computesDeltaVsPrecedingEquivalentPeriod() {
        // Period: Jan 1–31 (31 days), preceding: Dec 1–31
        var query = new StorePerformanceQuery(TENANT, FROM, TO, RankingMetric.CA);
        UUID storeId = UUID.randomUUID();
        var currentRows = List.of(
                new RawStorePerformanceRow(storeId, "Store", 100_000L, 10, 10000L, "Top")
        );
        var prevRows = List.of(
                new RawStorePerformanceRow(storeId, "Store", 80_000L, 8, 10000L, "Top")
        );
        // current period query
        when(repo.findRawStoreByPeriod(TENANT, FROM, TO)).thenReturn(currentRows);
        // preceding period query (Dec 1 to Dec 31)
        var prevFrom = LocalDate.of(2025, 12, 1);
        var prevTo   = LocalDate.of(2025, 12, 31);
        when(repo.findRawStoreByPeriod(TENANT, prevFrom, prevTo)).thenReturn(prevRows);

        var result = service.getRanking(query);

        // delta = (100000 - 80000) / 80000 * 100 = +25%
        assertThat(result.get(0).deltaPercent()).isCloseTo(25.0, org.assertj.core.data.Offset.offset(0.1));
    }
}

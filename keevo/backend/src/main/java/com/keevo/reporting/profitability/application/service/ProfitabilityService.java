package com.keevo.reporting.profitability.application.service;

import com.keevo.reporting.profitability.domain.model.*;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase;
import com.keevo.reporting.profitability.domain.port.in.GetStorePerformanceUseCase;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.RawStorePerformanceRow;
import com.keevo.reporting.profitability.domain.service.ProfitabilitySortStrategyFactory;
import com.keevo.reporting.profitability.domain.service.StoreRankingStrategyFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ProfitabilityService — implements both inbound use cases.
 *
 * <p>Delegates aggregation to {@link ProfitabilityReportBuilder} (Builder pattern)
 * and sorting to {@link ProfitabilitySortStrategyFactory} (Strategy + Factory).
 *
 * Story 7.4, Task 7.2.
 */
@Service
@Transactional(readOnly = true)
public class ProfitabilityService
        implements GetProductProfitabilityUseCase, GetStorePerformanceUseCase {

    private final ProfitabilityRepository repo;
    private final ProfitabilityReportBuilder reportBuilder;

    public ProfitabilityService(ProfitabilityRepository repo,
                                ProfitabilityReportBuilder reportBuilder) {
        this.repo = repo;
        this.reportBuilder = reportBuilder;
    }

    // ── GetProductProfitabilityUseCase ────────────────────────────────────────

    @Override
    public List<ProductProfitabilityEntry> getEntries(ProfitabilityQuery query) {
        var entries = reportBuilder.buildEntries(query);
        var strategy = ProfitabilitySortStrategyFactory.of(query.sort());
        return strategy.sort(entries);
    }

    @Override
    public ProductProfitabilityDetail getDetail(ProfitabilityQuery query, UUID productId) {
        return reportBuilder.buildDetail(query, productId);
    }

    // ── GetStorePerformanceUseCase ─────────────────────────────────────────────

    @Override
    public List<StorePerformanceEntry> getRanking(StorePerformanceQuery query) {
        var currentRows = repo.findRawStoreByPeriod(
                query.tenantId(), query.from(), query.to());

        // Compute preceding period of same length
        long periodDays = ChronoUnit.DAYS.between(query.from(), query.to()) + 1;
        LocalDate prevTo   = query.from().minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(periodDays - 1);

        var prevRows = repo.findRawStoreByPeriod(query.tenantId(), prevFrom, prevTo);
        Map<UUID, Long> prevRevenueByStore = prevRows.stream()
                .collect(Collectors.toMap(
                        RawStorePerformanceRow::storeId,
                        RawStorePerformanceRow::totalRevenue
                ));

        // Build unranked StorePerformanceEntry list with delta
        var unranked = currentRows.stream()
                .map(row -> {
                    long prevRev = prevRevenueByStore.getOrDefault(row.storeId(), 0L);
                    double delta = prevRev == 0 ? 0.0
                            : ((double)(row.totalRevenue() - prevRev) / prevRev) * 100.0;
                    return new StorePerformanceEntry(
                            0, row.storeId(), row.storeName(), row.totalRevenue(),
                            row.salesCount(), row.averageBasket(), row.topProductName(), delta
                    );
                })
                .toList();

        // Apply ranking strategy
        var rankingStrategy = StoreRankingStrategyFactory.of(query.metric());
        return rankingStrategy.rank(unranked);
    }
}

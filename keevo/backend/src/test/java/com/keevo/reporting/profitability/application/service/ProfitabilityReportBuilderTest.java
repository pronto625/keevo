package com.keevo.reporting.profitability.application.service;

import com.keevo.reporting.profitability.domain.model.SortOption;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase.ProfitabilityQuery;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository;
import com.keevo.reporting.profitability.domain.port.out.ProfitabilityRepository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Task 2.1 — TDD RED: ProfitabilityReportBuilder tests.
 * Story 7.4.
 */
@ExtendWith(MockitoExtension.class)
class ProfitabilityReportBuilderTest {

    @Mock
    private ProfitabilityRepository repo;

    private ProfitabilityReportBuilder builder;

    private static final String TENANT = "tenant_test";
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 1, 31);

    @BeforeEach
    void setUp() {
        builder = new ProfitabilityReportBuilder(repo);
    }

    @Test
    void builder_withSalesInPeriod_computesMarginPerProduct() {
        // productA: buyPrice=1000, transport=500, unitsSold=3, appliedPrice=5000
        // totalRevenue = 5000*3 = 15000, totalCost = (1000+500)*3 = 4500
        // grossMarginXaf = 10500, marginPct = 10500/4500*100 = 233.33%
        UUID productId = UUID.randomUUID();
        var row = new RawProfitabilityRow(productId, "ProductA", "Cat", 1000, 500, 3, 15000L, 4500L);
        when(repo.findRawByPeriod(TENANT, FROM, TO, null)).thenReturn(List.of(row));

        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        var entries = builder.buildEntries(query);

        assertThat(entries).hasSize(1);
        var e = entries.get(0);
        assertThat(e.totalRevenue()).isEqualTo(15000);
        assertThat(e.totalCost()).isEqualTo(4500);
        assertThat(e.grossMarginXaf()).isEqualTo(10500);
        assertThat(e.marginPercent()).isCloseTo(233.33, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void builder_withMultipleSaleItems_aggregatesCorrectly() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        var rows = List.of(
                new RawProfitabilityRow(p1, "Prod1", null, 500, 200, 5, 10000L, 3500L),
                new RawProfitabilityRow(p2, "Prod2", null, 800, 100, 2, 5000L,  1800L)
        );
        when(repo.findRawByPeriod(TENANT, FROM, TO, null)).thenReturn(rows);

        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        var entries = builder.buildEntries(query);

        assertThat(entries).hasSize(2);
    }

    @Test
    void builder_archivedProduct_stillIncludedIfHadSales() {
        UUID productId = UUID.randomUUID();
        var row = new RawProfitabilityRow(productId, "ArchivedProd", null, 500, 0, 2, 3000L, 1000L);
        when(repo.findRawByPeriod(TENANT, FROM, TO, null)).thenReturn(List.of(row));

        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        var entries = builder.buildEntries(query);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).productName()).isEqualTo("ArchivedProd");
    }

    @Test
    void builder_lossProduct_flaggedAsLoss() {
        UUID productId = UUID.randomUUID();
        var row = new RawProfitabilityRow(productId, "LossProd", null, 2000, 500, 1, 1000L, 2500L);
        when(repo.findRawByPeriod(TENANT, FROM, TO, null)).thenReturn(List.of(row));

        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        var entries = builder.buildEntries(query);

        assertThat(entries.get(0).isLoss()).isTrue();
        assertThat(entries.get(0).grossMarginXaf()).isNegative();
    }

    @Test
    void builder_emptyPeriod_returnsEmptyList() {
        when(repo.findRawByPeriod(TENANT, FROM, TO, null)).thenReturn(List.of());

        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        var entries = builder.buildEntries(query);

        assertThat(entries).isEmpty();
    }

    @Test
    void builder_multiStore_aggregatesAcrossAllStores_whenStoreIdIsNull() {
        UUID productId = UUID.randomUUID();
        var row = new RawProfitabilityRow(productId, "P1", null, 500, 0, 10, 20000L, 5000L);
        when(repo.findRawByPeriod(TENANT, FROM, TO, null)).thenReturn(List.of(row));

        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null);
        var entries = builder.buildEntries(query);

        assertThat(entries.get(0).storeId()).isNull();
    }

    @Test
    void builder_singleStore_filtersBySingleStoreId() {
        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        var row = new RawProfitabilityRow(productId, "P1", null, 500, 0, 1, 2000L, 500L);
        when(repo.findRawByPeriod(TENANT, FROM, TO, storeId)).thenReturn(List.of(row));

        var query = new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, storeId);
        var entries = builder.buildEntries(query);

        assertThat(entries).hasSize(1);
    }

    // Every figure of the detail (metrics, sparkline, applied price range) follows the store filter.
    @Test
    void buildDetail_withStore_scopesEveryQueryToThatStore() {
        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(repo.findProductCosts(productId, storeId)).thenReturn(Optional.of(
                new RawProductCostRow("ProductA", "Cat", 5000, 1000, 500, 4000, 5000, 4500.0)));
        when(repo.findDailyMarginLast7(TENANT, productId, TO, storeId)).thenReturn(
                List.of(new RawDailyMarginRow("2026-01-31", 700L)));
        when(repo.findRawByPeriod(TENANT, FROM, TO, storeId)).thenReturn(List.of(
                new RawProfitabilityRow(productId, "ProductA", "Cat", 1000, 500, 2, 9000L, 3000L)));

        var detail = builder.buildDetail(
                new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, storeId), productId);

        assertThat(detail.totalRevenue()).isEqualTo(9000);
        assertThat(detail.dailyMarginLast7()).hasSize(1);
        assertThat(detail.minAppliedPrice()).isEqualTo(4000);
    }

    // AC4: the detail names the store that sold the most units in the period.
    @Test
    void buildDetail_fillsTopStore_fromRepository() {
        UUID productId = UUID.randomUUID();
        UUID bestStore = UUID.randomUUID();
        when(repo.findProductCosts(productId, null)).thenReturn(Optional.of(
                new RawProductCostRow("ProductA", "Cat", 5000, 1000, 500, 4000, 5000, 4500.0)));
        when(repo.findRawByPeriod(TENANT, FROM, TO, null)).thenReturn(List.of(
                new RawProfitabilityRow(productId, "ProductA", "Cat", 1000, 500, 5, 25000L, 7500L)));
        when(repo.findTopStoreByUnits(TENANT, productId, FROM, TO, null)).thenReturn(Optional.of(
                new RawTopStoreRow(bestStore, "Boutique Nord", 3)));

        var detail = builder.buildDetail(
                new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null), productId);

        assertThat(detail.topStoreId()).isEqualTo(bestStore);
        assertThat(detail.topStoreName()).isEqualTo("Boutique Nord");
        assertThat(detail.topStoreUnitsSold()).isEqualTo(3);
    }

    @Test
    void buildDetail_noSalesInPeriod_hasNoTopStore() {
        UUID productId = UUID.randomUUID();
        when(repo.findProductCosts(productId, null)).thenReturn(Optional.of(
                new RawProductCostRow("ProductA", "Cat", 5000, 1000, 500, 0, 0, 0.0)));
        when(repo.findRawByPeriod(TENANT, FROM, TO, null)).thenReturn(List.of());
        when(repo.findTopStoreByUnits(TENANT, productId, FROM, TO, null)).thenReturn(Optional.empty());

        var detail = builder.buildDetail(
                new ProfitabilityQuery(TENANT, FROM, TO, SortOption.MARGIN_PCT_DESC, null), productId);

        assertThat(detail.unitsSold()).isZero();
        assertThat(detail.topStoreId()).isNull();
        assertThat(detail.topStoreName()).isNull();
        assertThat(detail.topStoreUnitsSold()).isZero();
    }
}

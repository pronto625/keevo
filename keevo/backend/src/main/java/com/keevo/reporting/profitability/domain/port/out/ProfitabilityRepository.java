package com.keevo.reporting.profitability.domain.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ProfitabilityRepository — outbound port for raw profitability data.
 * Story 7.4, Task 6.3.
 */
public interface ProfitabilityRepository {

    /** Raw row returned by the profitability aggregation query. */
    record RawProfitabilityRow(
            UUID   productId,
            String productName,
            String categoryName,
            int    buyPrice,
            int    transportCost,
            int    unitsSold,
            long   totalRevenue,   // SUM(applied_unit_price * quantity)
            long   totalCost       // SUM((buyPrice + transportCost) * quantity)
    ) {}

    /** Raw row returned by the store performance aggregation query. */
    record RawStorePerformanceRow(
            UUID   storeId,
            String storeName,
            long   totalRevenue,
            int    salesCount,
            long   averageBasket,
            String topProductName
    ) {}

    /** Current catalogue cost snapshot for a product (includes price-range stats). */
    record RawProductCostRow(
            String productName,
            String categoryName,
            int    cataloguePrice,
            int    buyPrice,
            int    transportCost,
            int    minAppliedPrice,
            int    maxAppliedPrice,
            double avgAppliedPrice
    ) {}

    /** Daily margin aggregation for the 7-day sparkline. */
    record RawDailyMarginRow(String date, long dailyMargin) {}

    /** Store that sold the most units of a product over a period (AC4 "top store"). */
    record RawTopStoreRow(UUID storeId, String storeName, int unitsSold) {}

    /**
     * Aggregate profitability by product for the given period.
     * If {@code storeId} is null, aggregates across all stores.
     */
    List<RawProfitabilityRow> findRawByPeriod(
            String tenantId, LocalDate from, LocalDate to, UUID storeId);

    /**
     * Aggregate store performance for the given period.
     */
    List<RawStorePerformanceRow> findRawStoreByPeriod(
            String tenantId, LocalDate from, LocalDate to);

    /**
     * Fetch current catalogue cost + applied price range for a product.
     * Returns empty if no sales match the product in the current tenant.
     */
    default Optional<RawProductCostRow> findProductCosts(UUID productId) {
        return findProductCosts(productId, null);
    }

    /**
     * Same as {@link #findProductCosts(UUID)}; when {@code storeId} is not null the applied
     * price range only covers that store's sales.
     */
    Optional<RawProductCostRow> findProductCosts(UUID productId, UUID storeId);

    /**
     * Fetch daily margin aggregation for the last 7 calendar days ending at {@code endDate}.
     */
    default List<RawDailyMarginRow> findDailyMarginLast7(
            String tenantId, UUID productId, LocalDate endDate) {
        return findDailyMarginLast7(tenantId, productId, endDate, null);
    }

    /** Same as above, limited to {@code storeId}'s sales when not null. */
    List<RawDailyMarginRow> findDailyMarginLast7(
            String tenantId, UUID productId, LocalDate endDate, UUID storeId);

    /**
     * Store with the most units sold for {@code productId} in [from, to].
     * {@code storeId} (nullable) restricts the search to that store, like the other queries.
     */
    Optional<RawTopStoreRow> findTopStoreByUnits(
            String tenantId, UUID productId, LocalDate from, LocalDate to, UUID storeId);
}

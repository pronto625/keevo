package com.keevo.reporting.leaderboard.domain.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * SellerLeaderboardRepository — outbound port aggregating COMPLETED sales per seller (v1s-14-2).
 */
public interface SellerLeaderboardRepository {

    record RawSellerRow(
            UUID sellerId,
            String sellerName,
            boolean owner,
            int salesCount,
            long totalRevenue
    ) {}

    /**
     * COMPLETED sales grouped by seller in {@code [from 00:00 WAT, to+1 00:00 WAT)}, plus the active
     * employees without sale (0/0). {@code storeId} null = all stores.
     */
    List<RawSellerRow> findRawSellers(LocalDate from, LocalDate to, UUID storeId);
}

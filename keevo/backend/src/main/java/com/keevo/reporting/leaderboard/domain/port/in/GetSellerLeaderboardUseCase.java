package com.keevo.reporting.leaderboard.domain.port.in;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;
import com.keevo.reporting.profitability.domain.model.RankingMetric;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * GetSellerLeaderboardUseCase — inbound port of the FR44 seller leaderboard (v1s-14-2, OWNER only).
 */
public interface GetSellerLeaderboardUseCase {

    /**
     * @param storeId null = all stores
     */
    record SellerLeaderboardQuery(
            String tenantId,
            LocalDate from,
            LocalDate to,
            UUID storeId,
            RankingMetric metric
    ) {}

    /** Ranked sellers (COMPLETED sales in the WAT window + idle active employees). */
    List<SellerLeaderboardEntry> getLeaderboard(SellerLeaderboardQuery query);
}

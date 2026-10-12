package com.keevo.reporting.leaderboard.adapter.in.rest.dto;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;

import java.util.UUID;

/** SellerLeaderboardEntryDto — REST row of the seller leaderboard (v1s-14-2). Amounts in XAF. */
public record SellerLeaderboardEntryDto(
        int rank,
        UUID sellerId,
        String sellerName,
        boolean owner,
        int salesCount,
        long totalRevenue,
        long averageBasket
) {
    public static SellerLeaderboardEntryDto from(SellerLeaderboardEntry e) {
        return new SellerLeaderboardEntryDto(
                e.rank(), e.sellerId(), e.sellerName(), e.owner(),
                e.salesCount(), e.totalRevenue(), e.averageBasket());
    }
}

package com.keevo.reporting.leaderboard.domain.model;

import java.util.UUID;

/**
 * SellerLeaderboardEntry — one ranked seller of the FR44 leaderboard (v1s-14-2).
 *
 * @param rank          1-based rank, no gaps (0 before ranking)
 * @param sellerId      user id of the seller ({@code sales.employee_id})
 * @param sellerName    employee name, « Propriétaire » or « Vendeur inconnu »
 * @param owner         true when the seller is the tenant owner
 * @param salesCount    COMPLETED sales in the window
 * @param totalRevenue  sum of the sales totals, XAF
 * @param averageBasket totalRevenue / salesCount (integer division, 0 without sale), XAF
 */
public record SellerLeaderboardEntry(
        int rank,
        UUID sellerId,
        String sellerName,
        boolean owner,
        int salesCount,
        long totalRevenue,
        long averageBasket
) {
    public SellerLeaderboardEntry withRank(int newRank) {
        return new SellerLeaderboardEntry(newRank, sellerId, sellerName, owner, salesCount, totalRevenue, averageBasket);
    }
}

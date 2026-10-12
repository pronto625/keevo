package com.keevo.reporting.leaderboard.domain.service;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;

import java.util.Comparator;

/** BySellerRevenueStrategy — rank sellers by totalRevenue (CA) descending (v1s-14-2). */
public class BySellerRevenueStrategy implements SellerRankingStrategy {

    @Override
    public Comparator<SellerLeaderboardEntry> primary() {
        return Comparator.comparingLong(SellerLeaderboardEntry::totalRevenue).reversed();
    }
}

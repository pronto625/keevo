package com.keevo.reporting.leaderboard.domain.service;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;

import java.util.Comparator;

/** BySellerSalesCountStrategy — rank sellers by salesCount descending (v1s-14-2). */
public class BySellerSalesCountStrategy implements SellerRankingStrategy {

    @Override
    public Comparator<SellerLeaderboardEntry> primary() {
        return Comparator.comparingInt(SellerLeaderboardEntry::salesCount).reversed();
    }
}

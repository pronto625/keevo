package com.keevo.reporting.leaderboard.domain.service;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;

import java.util.Comparator;

/** BySellerAvgBasketStrategy — rank sellers by averageBasket descending (v1s-14-2). */
public class BySellerAvgBasketStrategy implements SellerRankingStrategy {

    @Override
    public Comparator<SellerLeaderboardEntry> primary() {
        return Comparator.comparingLong(SellerLeaderboardEntry::averageBasket).reversed();
    }
}

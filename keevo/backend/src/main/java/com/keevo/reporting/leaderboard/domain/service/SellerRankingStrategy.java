package com.keevo.reporting.leaderboard.domain.service;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SellerRankingStrategy — GoF Strategy for the seller leaderboard (v1s-14-2), mirroring
 * {@code StoreRankingStrategy}. Each strategy supplies its primary metric; ties are broken the
 * same way for all: revenue desc, then name asc (case-insensitive), then sellerId.
 */
public interface SellerRankingStrategy {

    /** Primary ordering (best first). */
    Comparator<SellerLeaderboardEntry> primary();

    /**
     * Ranks the given entries, assigning rank 1 to the best seller. Returns a new list; the input
     * is not modified.
     */
    default List<SellerLeaderboardEntry> rank(List<SellerLeaderboardEntry> entries) {
        var sorted = entries.stream().sorted(primary().thenComparing(TIE_BREAK)).toList();
        return assignRanks(sorted);
    }

    /** Shared tie-break: revenue desc, name asc (case-insensitive), sellerId. */
    Comparator<SellerLeaderboardEntry> TIE_BREAK = Comparator
            .comparingLong(SellerLeaderboardEntry::totalRevenue).reversed()
            .thenComparing(e -> e.sellerName() == null ? "" : e.sellerName(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.sellerId() == null ? "" : e.sellerId().toString());

    static List<SellerLeaderboardEntry> assignRanks(List<SellerLeaderboardEntry> sorted) {
        var rank = new AtomicInteger(1);
        return sorted.stream().map(e -> e.withRank(rank.getAndIncrement())).toList();
    }
}

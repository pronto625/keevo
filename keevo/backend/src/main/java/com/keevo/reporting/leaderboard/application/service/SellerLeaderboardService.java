package com.keevo.reporting.leaderboard.application.service;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase;
import com.keevo.reporting.leaderboard.domain.port.out.SellerLeaderboardRepository;
import com.keevo.reporting.leaderboard.domain.service.SellerRankingStrategyFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SellerLeaderboardService — FR44 seller leaderboard (v1s-14-2). Computes the average basket and
 * applies the ranking strategy picked by {@link SellerRankingStrategyFactory}.
 */
@Service
@Transactional(readOnly = true)
public class SellerLeaderboardService implements GetSellerLeaderboardUseCase {

    private final SellerLeaderboardRepository repo;

    public SellerLeaderboardService(SellerLeaderboardRepository repo) {
        this.repo = repo;
    }

    @Override
    public List<SellerLeaderboardEntry> getLeaderboard(SellerLeaderboardQuery query) {
        var unranked = repo.findRawSellers(query.from(), query.to(), query.storeId()).stream()
                .map(row -> new SellerLeaderboardEntry(
                        0, row.sellerId(), row.sellerName(), row.owner(),
                        row.salesCount(), row.totalRevenue(),
                        row.salesCount() == 0 ? 0L : row.totalRevenue() / row.salesCount()))
                .toList();
        return SellerRankingStrategyFactory.of(query.metric()).rank(unranked);
    }
}

package com.keevo.reporting.leaderboard.application.service;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase.SellerLeaderboardQuery;
import com.keevo.reporting.leaderboard.domain.port.out.SellerLeaderboardRepository;
import com.keevo.reporting.leaderboard.domain.port.out.SellerLeaderboardRepository.RawSellerRow;
import com.keevo.reporting.profitability.domain.model.RankingMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SellerLeaderboardService — v1s-14-2")
class SellerLeaderboardServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    @Mock SellerLeaderboardRepository repo;

    @Test
    @DisplayName("computes the average basket (integer division, 0 without sale) and ranks by the metric")
    void computesAverageAndRanks() {
        UUID store = UUID.randomUUID();
        UUID awa = UUID.randomUUID();
        UUID bello = UUID.randomUUID();
        UUID idle = UUID.randomUUID();
        when(repo.findRawSellers(DAY, DAY, store)).thenReturn(List.of(
                new RawSellerRow(awa, "Awa", false, 3, 10_000),
                new RawSellerRow(bello, "Propriétaire", true, 1, 8_000),
                new RawSellerRow(idle, "Chantal", false, 0, 0)));

        var service = new SellerLeaderboardService(repo);
        var ranked = service.getLeaderboard(
                new SellerLeaderboardQuery("t", DAY, DAY, store, RankingMetric.AVG_BASKET));

        assertThat(ranked).extracting(SellerLeaderboardEntry::sellerId).containsExactly(bello, awa, idle);
        assertThat(ranked).extracting(SellerLeaderboardEntry::averageBasket).containsExactly(8_000L, 3_333L, 0L);
        assertThat(ranked).extracting(SellerLeaderboardEntry::rank).containsExactly(1, 2, 3);
        assertThat(ranked.get(0).owner()).isTrue();
    }

    @Test
    @DisplayName("no data → empty list")
    void empty() {
        when(repo.findRawSellers(DAY, DAY, null)).thenReturn(List.of());
        assertThat(new SellerLeaderboardService(repo).getLeaderboard(
                new SellerLeaderboardQuery("t", DAY, DAY, null, RankingMetric.CA))).isEmpty();
    }
}

package com.keevo.reporting.leaderboard.domain.service;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;
import com.keevo.reporting.profitability.domain.model.RankingMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SellerRankingStrategy — v1s-14-2")
class SellerRankingStrategyTest {

    private static SellerLeaderboardEntry e(String name, int count, long revenue) {
        return new SellerLeaderboardEntry(0, UUID.randomUUID(), name, false, count, revenue,
                count == 0 ? 0 : revenue / count);
    }

    @Test
    @DisplayName("CA: sorted by revenue desc, ranks 1..n")
    void shouldRankEmployeesByRevenue() {
        var low = e("Awa", 5, 10_000);
        var high = e("Bello", 2, 50_000);
        var idle = e("Chantal", 0, 0);

        var ranked = SellerRankingStrategyFactory.of(RankingMetric.CA).rank(List.of(low, idle, high));

        assertThat(ranked).extracting(SellerLeaderboardEntry::sellerName).containsExactly("Bello", "Awa", "Chantal");
        assertThat(ranked).extracting(SellerLeaderboardEntry::rank).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("SALES_COUNT: sorted by sales count desc")
    void shouldRankBySalesCount() {
        var ranked = SellerRankingStrategyFactory.of(RankingMetric.SALES_COUNT)
                .rank(List.of(e("Bello", 2, 50_000), e("Awa", 5, 10_000)));
        assertThat(ranked).extracting(SellerLeaderboardEntry::sellerName).containsExactly("Awa", "Bello");
    }

    @Test
    @DisplayName("AVG_BASKET: sorted by average basket desc")
    void shouldRankByAverageBasket() {
        var ranked = SellerRankingStrategyFactory.of(RankingMetric.AVG_BASKET)
                .rank(List.of(e("Awa", 5, 10_000), e("Bello", 2, 50_000), e("Chantal", 1, 30_000)));
        assertThat(ranked).extracting(SellerLeaderboardEntry::sellerName).containsExactly("Chantal", "Bello", "Awa");
    }

    @Test
    @DisplayName("tie-break: metric, then revenue desc, then name asc (case-insensitive), then sellerId")
    void shouldBreakTiesDeterministically() {
        // Same sales count: revenue decides.
        var ranked = SellerRankingStrategyFactory.of(RankingMetric.SALES_COUNT)
                .rank(List.of(e("Awa", 2, 1_000), e("Bello", 2, 9_000)));
        assertThat(ranked).extracting(SellerLeaderboardEntry::sellerName).containsExactly("Bello", "Awa");

        // Same metric and revenue: name, case-insensitive.
        ranked = SellerRankingStrategyFactory.of(RankingMetric.CA)
                .rank(List.of(e("bello", 0, 0), e("Awa", 0, 0), e("chantal", 0, 0)));
        assertThat(ranked).extracting(SellerLeaderboardEntry::sellerName).containsExactly("Awa", "bello", "chantal");

        // Everything equal but the id: sellerId order.
        var a = new SellerLeaderboardEntry(0, UUID.fromString("00000000-0000-0000-0000-000000000002"), "X", false, 1, 1, 1);
        var b = new SellerLeaderboardEntry(0, UUID.fromString("00000000-0000-0000-0000-000000000001"), "X", false, 1, 1, 1);
        ranked = SellerRankingStrategyFactory.of(RankingMetric.CA).rank(List.of(a, b));
        assertThat(ranked).extracting(SellerLeaderboardEntry::sellerId).containsExactly(b.sellerId(), a.sellerId());
        assertThat(ranked).extracting(SellerLeaderboardEntry::rank).containsExactly(1, 2);
    }

    @Test
    @DisplayName("null metric falls back to CA")
    void nullMetricIsRevenue() {
        assertThat(SellerRankingStrategyFactory.of(null)).isInstanceOf(BySellerRevenueStrategy.class);
    }
}

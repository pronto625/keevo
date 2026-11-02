package com.keevo.reporting.leaderboard.adapter.in.rest.dto;

import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * SellerLeaderboardEntryDto — REST row of the seller leaderboard (v1s-14-2). Amounts in XAF.
 *
 * <p>Story 17.2 — {@code me} (additive): {@code true} only on the calling employee's own row; always {@code false}
 * in the owner's view. In the employee view every other row is anonymised ({@link #anonymisedFor}).
 */
public record SellerLeaderboardEntryDto(
        int rank,
        @Schema(nullable = true, description = "User id of the seller; null on an anonymised row of the employee "
                + "view (every row except the caller's own)")
        UUID sellerId,
        String sellerName,
        boolean owner,
        int salesCount,
        long totalRevenue,
        long averageBasket,
        @Schema(description = "Employee view: true on the caller's own row only; always false in the owner view")
        boolean me
) {
    /** The owner's view: the row as is, {@code me = false}. */
    public static SellerLeaderboardEntryDto from(SellerLeaderboardEntry e) {
        return new SellerLeaderboardEntryDto(
                e.rank(), e.sellerId(), e.sellerName(), e.owner(),
                e.salesCount(), e.totalRevenue(), e.averageBasket(), false);
    }

    /**
     * The employee's view (Story 17.2): the caller's own row keeps its name and id ({@code me = true}); every other
     * row becomes « Vendeur &lt;rank&gt; » without id and without the owner marker. Rank and numbers are kept.
     */
    public static SellerLeaderboardEntryDto anonymisedFor(SellerLeaderboardEntry e, UUID callerId) {
        if (callerId != null && callerId.equals(e.sellerId())) {
            return new SellerLeaderboardEntryDto(
                    e.rank(), e.sellerId(), e.sellerName(), e.owner(),
                    e.salesCount(), e.totalRevenue(), e.averageBasket(), true);
        }
        return new SellerLeaderboardEntryDto(
                e.rank(), null, "Vendeur " + e.rank(), false,
                e.salesCount(), e.totalRevenue(), e.averageBasket(), false);
    }
}

package com.keevo.subscription.offer.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OfferPricer — best single discount (Story 21.10)")
class OfferPricerTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Instant IN_A_WEEK = NOW.plus(7, ChronoUnit.DAYS);

    private static Discount percent(DiscountScope scope, String percent, Set<String> plans, Instant startsAt,
                                    Instant endsAt) {
        return new Discount(UUID.randomUUID(), scope, scope.label(), new BigDecimal(percent), null, plans, Set.of(),
                startsAt, endsAt, scope == DiscountScope.TENANT ? UUID.randomUUID() : null,
                scope == DiscountScope.PROMO_CODE ? "BREF10" : null, null, NOW);
    }

    private static Discount amount(DiscountScope scope, long amount, Instant endsAt) {
        return new Discount(UUID.randomUUID(), scope, scope.label(), null, amount, Set.of(), Set.of(), null, endsAt,
                scope == DiscountScope.TENANT ? UUID.randomUUID() : null,
                scope == DiscountScope.PROMO_CODE ? "BREF10" : null, null, NOW);
    }

    @Test
    @DisplayName("matrix: PROMOTION 10 % on PRO + TENANT 1 000 XAF → on 4 000, max(400, 1 000) = the tenant one, 3 000")
    void bestDiscountWins() {
        Discount promotion = percent(DiscountScope.PROMOTION, "10", Set.of("PRO"), null, IN_A_WEEK);
        Discount tenant = amount(DiscountScope.TENANT, 1_000, null);

        PricedOffer priced = OfferPricer.price(4_000, "PRO", "MONTHLY", List.of(promotion, tenant), NOW);

        assertThat(priced.reductionXaf()).isEqualTo(1_000);
        assertThat(priced.effectiveXaf()).isEqualTo(3_000);
        assertThat(priced.discount()).isSameAs(tenant);
        // on a bigger price the percentage wins (no stacking either way)
        PricedOffer annual = OfferPricer.price(32_760, "PRO", "ANNUAL", List.of(promotion, tenant), NOW);
        assertThat(annual.reductionXaf()).isEqualTo(3_276);
        assertThat(annual.discount()).isSameAs(promotion);
    }

    @Test
    @DisplayName("matrix: an amount above the price caps at the price → effective 0")
    void amountCappedAtPrice() {
        PricedOffer priced = OfferPricer.price(3_500, "PRO", "MONTHLY",
                List.of(amount(DiscountScope.PROMOTION, 5_000, IN_A_WEEK)), NOW);
        assertThat(priced.reductionXaf()).isEqualTo(3_500);
        assertThat(priced.effectiveXaf()).isZero();
    }

    @Test
    @DisplayName("matrix: expired (ends_at ≤ now), future (starts_at > now), archived or other-plan discounts ignored")
    void windowAndScopeOfPlans() {
        Discount expired = percent(DiscountScope.PROMOTION, "50", Set.of(), null, NOW);
        Discount future = percent(DiscountScope.PROMOTION, "50", Set.of(), NOW.plusSeconds(1), IN_A_WEEK);
        Discount archived = percent(DiscountScope.PROMOTION, "50", Set.of(), null, IN_A_WEEK).withArchivedAt(NOW);
        Discount otherPlan = percent(DiscountScope.PROMOTION, "50", Set.of("TEAM"), null, IN_A_WEEK);
        Discount otherCycle = new Discount(UUID.randomUUID(), DiscountScope.PROMOTION, "Annuel", new BigDecimal("50"),
                null, Set.of(), Set.of("ANNUAL"), null, IN_A_WEEK, null, null, null, NOW);

        PricedOffer priced = OfferPricer.price(3_500, "PRO", "MONTHLY",
                List.of(expired, future, archived, otherPlan, otherCycle), NOW);

        assertThat(priced.discount()).isNull();
        assertThat(priced.effectiveXaf()).isEqualTo(3_500);
        // starts exactly now: applies
        Discount startsNow = percent(DiscountScope.PROMOTION, "50", Set.of(), NOW, IN_A_WEEK);
        assertThat(OfferPricer.price(3_500, "PRO", "MONTHLY", List.of(startsNow), NOW).effectiveXaf())
                .isEqualTo(1_750);
    }

    @Test
    @DisplayName("percent rounding is half-up to the XAF")
    void percentRounding() {
        Discount d = percent(DiscountScope.PROMOTION, "12.5", Set.of(), null, IN_A_WEEK);
        assertThat(OfferPricer.price(3_500, "PRO", "MONTHLY", List.of(d), NOW).reductionXaf()).isEqualTo(438);
        assertThat(OfferPricer.price(3_501, "PRO", "MONTHLY", List.of(d), NOW).reductionXaf()).isEqualTo(438);
    }

    @Test
    @DisplayName("tie: promo code, then tenant, then promotion; between equals the one ending first")
    void tieBreak() {
        Discount promotion = amount(DiscountScope.PROMOTION, 500, IN_A_WEEK);
        Discount tenant = amount(DiscountScope.TENANT, 500, null);
        Discount code = amount(DiscountScope.PROMO_CODE, 500, null);

        assertThat(OfferPricer.price(3_500, "PRO", "MONTHLY", List.of(promotion, tenant, code), NOW).discount())
                .isSameAs(code);
        assertThat(OfferPricer.price(3_500, "PRO", "MONTHLY", List.of(promotion, tenant), NOW).discount())
                .isSameAs(tenant);
        Discount later = amount(DiscountScope.PROMOTION, 500, IN_A_WEEK.plusSeconds(60));
        assertThat(OfferPricer.price(3_500, "PRO", "MONTHLY", List.of(later, promotion), NOW).discount())
                .isSameAs(promotion);
    }

    @Test
    @DisplayName("per month: effective / months, rounded half-up")
    void perMonth() {
        PricedOffer annual = OfferPricer.price(32_760, "PRO", "ANNUAL", List.of(), NOW);
        assertThat(annual.perMonthXaf(12)).isEqualTo(2_730);
        assertThat(OfferPricer.price(9_500, "PRO", "QUARTERLY", List.of(), NOW).perMonthXaf(3)).isEqualTo(3_167);
    }
}

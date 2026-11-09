package com.keevo.subscription.offer.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.10 — a discount of the offer ({@code public.discounts}): exactly one of a percentage ({@code ]0, 100]}) or an
 * amount in XAF ({@code > 0}), on some plans and cycles (empty = all), inside an optional window.
 *
 * @param id           {@code null} before insertion
 * @param planCodes    plans it applies to; empty = every plan
 * @param cycleCodes   cycles it applies to; empty = every cycle
 * @param startsAt     first instant it applies, {@code null} = at once
 * @param endsAt       first instant it no longer applies, {@code null} = no end (a {@link DiscountScope#PROMOTION}
 *                     always has one)
 * @param tenantId     the company of a {@link DiscountScope#TENANT} discount, {@code null} otherwise
 * @param promoCode    the code of a {@link DiscountScope#PROMO_CODE} discount (upper case), {@code null} otherwise
 * @param archivedAt   archive instant, {@code null} when not archived
 * @param maxRedemptions Story 21.1 — cap of confirmed uses of a {@link DiscountScope#PROMO_CODE}, {@code null} = no cap
 * @param redemptionCount Story 21.1 — confirmed payments that used it (written by the payment confirmation only)
 */
public record Discount(UUID id, DiscountScope scope, String label, BigDecimal percentOff, Long amountOffXaf,
                       Set<String> planCodes, Set<String> cycleCodes, Instant startsAt, Instant endsAt,
                       UUID tenantId, String promoCode, Instant archivedAt, Instant createdAt,
                       Integer maxRedemptions, int redemptionCount) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Discount {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(label, "label");
        planCodes = planCodes == null ? Set.of() : Set.copyOf(planCodes);
        cycleCodes = cycleCodes == null ? Set.of() : Set.copyOf(cycleCodes);
    }

    /** A discount without use cap nor use (before Story 21.1, tests). */
    public Discount(UUID id, DiscountScope scope, String label, BigDecimal percentOff, Long amountOffXaf,
                    Set<String> planCodes, Set<String> cycleCodes, Instant startsAt, Instant endsAt,
                    UUID tenantId, String promoCode, Instant archivedAt, Instant createdAt) {
        this(id, scope, label, percentOff, amountOffXaf, planCodes, cycleCodes, startsAt, endsAt, tenantId, promoCode,
                archivedAt, createdAt, null, 0);
    }

    /** Story 21.1 — the cap is reached: the code is no longer offered. */
    public boolean exhausted() {
        return maxRedemptions != null && redemptionCount >= maxRedemptions;
    }

    public boolean archived() {
        return archivedAt != null;
    }

    /**
     * Not archived, not {@link #exhausted()} (21.1) and inside its window at {@code now}
     * ({@code starts_at <= now < ends_at}).
     */
    public boolean liveAt(Instant now) {
        return archivedAt == null && !exhausted() && (startsAt == null || !startsAt.isAfter(now))
                && (endsAt == null || endsAt.isAfter(now));
    }

    /** Whether it covers this plan and this cycle (an empty list covers all). */
    public boolean covers(String planCode, String cycleCode) {
        return (planCodes.isEmpty() || planCodes.contains(planCode))
                && (cycleCodes.isEmpty() || cycleCodes.contains(cycleCode));
    }

    /**
     * The reduction on a base price: {@code round-half-up(base × percent / 100)}, or {@code min(amount, base)}; never
     * above the base, never negative.
     */
    public long reductionOn(long base) {
        if (base <= 0) {
            return 0;
        }
        long reduction = percentOff != null
                ? BigDecimal.valueOf(base).multiply(percentOff).divide(HUNDRED, 0, RoundingMode.HALF_UP).longValue()
                : Math.min(amountOffXaf == null ? 0 : amountOffXaf, base);
        return Math.max(0, Math.min(reduction, base));
    }

    public Discount withArchivedAt(Instant at) {
        return new Discount(id, scope, label, percentOff, amountOffXaf, planCodes, cycleCodes, startsAt, endsAt,
                tenantId, promoCode, at, createdAt, maxRedemptions, redemptionCount);
    }
}

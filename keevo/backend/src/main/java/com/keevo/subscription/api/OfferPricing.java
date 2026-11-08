package com.keevo.subscription.api;

import com.keevo.shared.module.PlanProfileAdmin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.10 — prices of the offer for a customer, with the best single discount applied (no stacking). The owner's
 * « Mon plan » listing, the super-admin preview and the payment of 21.1/21.2 all price through it, so the price shown
 * is the price charged. Implemented by {@code subscription.offer}.
 */
public interface OfferPricing {

    /**
     * The effective price of a plan for a cycle at this instant (the caller stores it with the payment it starts).
     *
     * @param tenantId  the paying company ({@code null}: no company discount — super-admin preview)
     * @param promoCode code typed by the owner, {@code null} when none
     * @throws com.keevo.shared.domain.exception.DomainException 422 {@code VALIDATION_ERROR}: plan not offered (unknown,
     *         archived, hidden or not paid), cycle not offered, no price, promo code invalid or expired
     */
    Quote quote(UUID tenantId, String planCode, String cycleCode, String promoCode);

    /** The offers of a company: visible live free and paid plans, every live cycle's price, the current plan. */
    OffersView offersFor(UUID tenantId, String promoCode);

    /**
     * @param baseXaf      catalogue price
     * @param reductionXaf reduction of the discount applied (0 when none)
     * @param effectiveXaf price to charge
     * @param perMonthXaf  effective price per month (rounded half-up)
     * @param discount     the discount applied, {@code null} when none
     * @param pricedAt     the instant of the quote
     */
    record Quote(String planCode, String planLabel, String cycleCode, String cycleLabel, int months, long baseXaf,
                 long reductionXaf, long effectiveXaf, long perMonthXaf, AppliedDiscount discount, Instant pricedAt) {
    }

    /**
     * @param scope   {@code PROMOTION | TENANT | PROMO_CODE}
     * @param endsAt  last instant (exclusive) of the discount, {@code null} = no end
     */
    record AppliedDiscount(String scope, String scopeLabel, String label, BigDecimal percentOff, Long amountOffXaf,
                           Instant endsAt) {
    }

    /**
     * @param currentPlan the company's plan
     * @param promoCode   the typed code and whether it is valid, {@code null} when none was typed
     * @param plans       offered plans in display order
     */
    record OffersView(PlanRef currentPlan, PromoCodeStatus promoCode, List<OfferPlan> plans) {
    }

    /** @param kind {@code FREE | TRIAL | PAID}; {@code null} for a plan the catalogue does not know */
    record PlanRef(String code, String label, String kind) {
    }

    /** @param message French reason when not valid (« Code promo invalide ou expiré »), {@code null} when valid */
    record PromoCodeStatus(String code, boolean valid, String message) {
    }

    /**
     * An offered plan: limits ({@code null} = unlimited), paid modules, usage quotas and the price of every live cycle
     * (empty for the free plan).
     */
    record OfferPlan(String code, String label, String description, String kind, boolean current, Integer maxStores,
                     Integer maxProducts, Integer maxEmployees, Integer maxEmployeesPerStore,
                     List<PlanProfileAdmin.ModuleRef> modules, List<PlanProfileAdmin.QuotaRef> quotas,
                     List<OfferPrice> prices) {
    }

    record OfferPrice(String cycleCode, String cycleLabel, int months, long baseXaf, long effectiveXaf,
                      long perMonthXaf, AppliedDiscount discount) {
    }
}

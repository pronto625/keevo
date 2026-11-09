package com.keevo.subscription.offer.application.service;

import com.keevo.identity.api.PlanKind;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.module.PlanProfileAdmin;
import com.keevo.subscription.api.OfferPricing;
import com.keevo.subscription.api.UsagePacks;
import com.keevo.subscription.offer.domain.model.BillingCycle;
import com.keevo.subscription.offer.domain.model.Discount;
import com.keevo.subscription.offer.domain.model.DiscountScope;
import com.keevo.subscription.offer.domain.model.OfferPricer;
import com.keevo.subscription.offer.domain.model.PlanPrice;
import com.keevo.subscription.offer.domain.model.PricedOffer;
import com.keevo.subscription.offer.domain.model.TenantPlanRef;
import com.keevo.subscription.offer.domain.port.out.OfferStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.keevo.subscription.offer.application.service.OfferText.*;

/**
 * Story 21.10 — prices of the offer for a customer ({@link OfferPricing}): the catalogue price with the best single
 * discount the customer is entitled to ({@link OfferPricer}). Used by {@code GET /api/v1/subscription/offers}, the
 * super-admin preview and — through the quote — by the payments of 21.1/21.2, which store the quoted price at
 * initiation (a payment already started keeps the price shown).
 *
 * <p>Hidden plans are not offered (listing, quote) but stay attributable by the super-admin (private deal); archived
 * plans and cycles are closed to any new sale. Nothing here is cached: a price or discount change is seen by the next
 * request. Cost of the listing: catalogue cache + one profiles batch + cycles + prices + one live-discounts query +
 * one company read; pricing in memory O(plans × cycles × discounts).
 */
@Service
public class OfferPricingService implements OfferPricing {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OfferPricingService.class);

    private final PlanCatalog catalog;
    private final OfferStore store;
    private final PlanProfileAdmin profiles;
    private final Clock clock;
    /** Story 21.3 — the packs listed with the offers ({@code null}: none listed). */
    private final UsagePacks usagePacks;

    @org.springframework.beans.factory.annotation.Autowired
    public OfferPricingService(PlanCatalog catalog, OfferStore store, PlanProfileAdmin profiles, Clock clock,
                               UsagePacks usagePacks) {
        this.catalog = catalog;
        this.store = store;
        this.profiles = profiles;
        this.clock = clock;
        this.usagePacks = usagePacks;
    }

    /** Without usage packs (unit tests of the 21.10 pricing). */
    public OfferPricingService(PlanCatalog catalog, OfferStore store, PlanProfileAdmin profiles, Clock clock) {
        this(catalog, store, profiles, clock, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Quote quote(UUID tenantId, String planCode, String cycleCode, String promoCode) {
        String planKey = code(planCode);
        PlanDefinition plan = (planKey == null ? java.util.Optional.<PlanDefinition>empty() : catalog.find(planKey))
                .filter(p -> p.kind() == PlanKind.PAID && p.visible() && !p.archived())
                .orElseThrow(() -> invalid("planCode", OFFER_NOT_OFFERED));
        String cycleKey = code(cycleCode);
        BillingCycle cycle = (cycleKey == null ? java.util.Optional.<BillingCycle>empty() : store.cycle(cycleKey))
                .filter(c -> !c.archived())
                .orElseThrow(() -> invalid("cycleCode", CYCLE_NOT_OFFERED));
        PlanPrice price = store.price(plan.code().code(), cycle.code())
                .orElseThrow(() -> invalid("cycleCode", NO_PRICE));
        String promo = code(promoCode);
        Instant now = clock.instant();
        List<Discount> candidates = store.liveCandidates(tenantId, promo, now);
        if (promo != null && !promoMatched(candidates, promo)) {
            throw invalid("promoCode", PROMO_INVALID);
        }
        PricedOffer priced = OfferPricer.price(price.amountXaf(), plan.code().code(), cycle.code(), candidates, now);
        return new Quote(plan.code().code(), plan.label(), cycle.code(), cycle.label(), cycle.months(),
                priced.baseXaf(), priced.reductionXaf(), priced.effectiveXaf(), priced.perMonthXaf(cycle.months()),
                applied(priced.discount()), now, priced.discount() == null ? null : priced.discount().id());
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean recordRedemption(UUID discountId, boolean withinCap) {
        return discountId == null || store.recordRedemption(discountId, withinCap);
    }

    @Override
    @Transactional(readOnly = true)
    public OffersView offersFor(UUID tenantId, String promoCode) {
        TenantPlanRef tenant = (tenantId == null ? java.util.Optional.<TenantPlanRef>empty() : store.tenant(tenantId))
                .orElseThrow(() -> notFound(TENANT_NOT_FOUND));
        return offers(tenant, promoCode);
    }

    /** The offers of the company of a tenant schema (the caller's own: {@code GET /api/v1/subscription/offers}). */
    @Transactional(readOnly = true)
    public OffersView offersForSchema(String tenantSchema, String promoCode) {
        TenantPlanRef tenant = (tenantSchema == null ? java.util.Optional.<TenantPlanRef>empty()
                : store.tenantBySchema(tenantSchema)).orElseThrow(() -> notFound(TENANT_NOT_FOUND));
        return offers(tenant, promoCode);
    }

    private OffersView offers(TenantPlanRef tenant, String promoCode) {
        Instant now = clock.instant();
        String promo = code(promoCode);
        List<Discount> candidates = store.liveCandidates(tenant.tenantId(), promo, now);
        PromoCodeStatus promoStatus = promo == null ? null
                : promoMatched(candidates, promo) ? new PromoCodeStatus(promo, true, null)
                : new PromoCodeStatus(promo, false, PROMO_INVALID);

        List<BillingCycle> cycles = store.cycles().stream().filter(c -> !c.archived()).toList();
        Map<String, Long> prices = new HashMap<>();
        store.prices().forEach(p -> prices.put(p.planCode() + '\u0000' + p.cycleCode(), p.amountXaf()));
        Map<String, PlanProfileAdmin.ProfileView> profilesByPlan = new HashMap<>();
        profiles.list().forEach(v -> profilesByPlan.put(v.planType(), v));

        List<OfferPlan> plans = new ArrayList<>();
        for (PlanDefinition plan : catalog.all()) {
            if (!plan.visible() || plan.archived() || plan.kind() == PlanKind.TRIAL) {
                continue;
            }
            String code = plan.code().code();
            List<OfferPrice> offerPrices = new ArrayList<>();
            if (plan.kind() == PlanKind.PAID) {
                for (BillingCycle cycle : cycles) {
                    Long base = prices.get(code + '\u0000' + cycle.code());
                    if (base == null) {
                        continue;
                    }
                    PricedOffer priced = OfferPricer.price(base, code, cycle.code(), candidates, now);
                    offerPrices.add(new OfferPrice(cycle.code(), cycle.label(), cycle.months(), priced.baseXaf(),
                            priced.effectiveXaf(), priced.perMonthXaf(cycle.months()), applied(priced.discount())));
                }
            }
            PlanProfileAdmin.ProfileView profile = profilesByPlan.get(code);
            plans.add(new OfferPlan(code, plan.label(), plan.description(), plan.kind().name(),
                    code.equals(tenant.planCode()),
                    profile == null ? null : profile.maxStores(), profile == null ? null : profile.maxProducts(),
                    profile == null ? null : profile.maxEmployees(),
                    profile == null ? null : profile.maxEmployeesPerStore(),
                    profile == null ? List.of() : profile.modules(), profile == null ? List.of() : profile.quotas(),
                    offerPrices));
        }
        PlanRef current = catalog.find(tenant.planCode())
                .map(p -> new PlanRef(p.code().code(), p.label(), p.kind().name()))
                .orElseGet(() -> {
                    // never guess « free » for a company that may be paying: kind unknown
                    log.warn("Offers: company {} is on a plan the catalogue does not know — kind left empty",
                            tenant.tenantId());
                    return new PlanRef(tenant.planCode(), "Plan inconnu", null);
                });
        return new OffersView(current, promoStatus, plans, usagePacks == null ? List.of() : usagePacks.offered());
    }

    private static boolean promoMatched(List<Discount> candidates, String promo) {
        return candidates.stream().anyMatch(d -> d.scope() == DiscountScope.PROMO_CODE
                && promo.equalsIgnoreCase(d.promoCode()));
    }

    private static AppliedDiscount applied(Discount d) {
        return d == null ? null : new AppliedDiscount(d.scope().name(), d.scope().label(), d.label(), d.percentOff(),
                d.amountOffXaf(), d.endsAt());
    }
}

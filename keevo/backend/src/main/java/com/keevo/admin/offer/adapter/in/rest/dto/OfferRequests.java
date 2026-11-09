package com.keevo.admin.offer.adapter.in.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.10 — request bodies of the super-admin offer catalogue ({@code /api/v1/admin/offers/**}). Validation lives
 * in the service (French messages, 422 {@code VALIDATION_ERROR} with {@code details.<field>}).
 */
public final class OfferRequests {

    private OfferRequests() {
    }

    /**
     * @param kind            {@code FREE | TRIAL | PAID}
     * @param visible         offered to customers (default true)
     * @param copyProfileFrom plan whose limits, modules and quotas are copied (default: the free plan)
     */
    public record PlanRequest(String code, String label, String description, String kind, Boolean visible,
                              String copyProfileFrom) {
    }

    /** Absent fields keep the stored value. */
    public record PlanUpdateRequest(String label, String description, String kind, Boolean visible) {
    }

    /** Every plan code, each once, in the new display order. */
    public record PlanOrderRequest(List<String> codes) {
    }

    /** {@code code} is ignored on update; absent fields keep the stored value on update. */
    public record CycleRequest(String code, String label, Integer months, Integer sortOrder) {
    }

    /** Price of a paid plan for a cycle, XAF, 1..100 000 000. */
    public record PriceRequest(Long amountXaf) {
    }

    /** Absent fields keep the stored value. */
    public record SettingsRequest(String freePlanCode, String trialPlanCode, Integer trialMonths, Boolean trialEnabled,
                                  String defaultPaidPlanCode) {
    }

    /**
     * @param scope      {@code PROMOTION | TENANT | PROMO_CODE}
     * @param percentOff exactly one of percentOff ]0, 100] and amountOffXaf (&gt; 0)
     * @param planCodes  empty or absent = every plan
     * @param cycleCodes empty or absent = every cycle
     * @param startsAt   ISO instant, absent = now
     * @param endsAt     ISO instant (exclusive), required for a PROMOTION
     * @param tenantId   the company of a TENANT discount
     * @param promoCode  the code of a PROMO_CODE discount (3..40 letters, digits, - or _; case-insensitive)
     * @param maxRedemptions Story 21.1 — cap of confirmed uses of a promo code (1..1 000 000), absent = no cap
     */
    public record DiscountRequest(String scope, String label, BigDecimal percentOff, Long amountOffXaf,
                                  List<String> planCodes, List<String> cycleCodes, Instant startsAt, Instant endsAt,
                                  UUID tenantId, String promoCode, Integer maxRedemptions) {
    }

    /**
     * Story 21.2 — the mapping of an App Store product.
     *
     * @param planCode         paid plan granted by the product
     * @param cycleCode        billing cycle of the product
     * @param declaredPriceXaf Apple price read in App Store Connect (1..100 000 000), absent = not entered
     * @param active           offered to new buyers (default true; an inactive product still renews)
     */
    public record StoreProductRequest(String planCode, String cycleCode, Long declaredPriceXaf, Boolean active) {
    }

    /**
     * Story 21.3 — a usage pack to create or update (absent fields of an existing pack are kept).
     *
     * @param metric    {@code DOCUMENTS | SMS | EMAILS | AI_CREDITS}
     * @param quantity  1..1 000 000 units
     * @param priceXaf  1..100 000 000 FCFA
     * @param active    offered to owners (default true on a creation)
     * @param sortOrder display order 0..100 000
     */
    public record UsagePackRequest(String label, String metric, Integer quantity, Long priceXaf, Boolean active,
                                   Integer sortOrder) {
    }
}

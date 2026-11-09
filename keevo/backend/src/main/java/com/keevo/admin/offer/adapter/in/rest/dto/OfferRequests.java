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
}

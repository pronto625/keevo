package com.keevo.subscription.plan.adapter.in.rest.dto;

/**
 * SubscriptionResponse — API response DTO for subscription info and usage counts.
 *
 * <p>Null values for maxStores/maxProducts/maxEmployees indicate "unlimited"
 * (as configured in the plan's profile).
 *
 * <p>Story 19.7b — additive fields: the plan's own code, French label and kind ({@code FREE|TRIAL|PAID}) and the
 * per-store employee limit. {@code planType} stays the V1 value.
 */
public record SubscriptionResponse(
        String planType,           // V1 code of the plan's kind (19.7a): FREE | PREMIUM_TRIAL | PREMIUM
        String status,             // "ACTIVE" | "SUSPENDED" | "EXPIRED"
        String expiresAt,          // ISO 8601; null for FREE plan
        Integer maxStores,         // null = unlimited
        Integer maxProducts,       // null = unlimited
        Integer maxEmployees,      // null = unlimited
        int currentStores,
        Integer currentProducts,   // 16.8: null = the count could not be read (unknown)
        int currentEmployees,
        String planCode,           // 19.7b: the plan's own code (TEAM, BUSINESS_TRIAL…)
        String planLabel,          // 19.7b: French label of the plan
        String planKind,           // 19.7b: FREE | TRIAL | PAID
        Integer maxEmployeesPerStore // 19.7b: null = unlimited
) {

    /** The V1 fields only (additive fields null). */
    public SubscriptionResponse(String planType, String status, String expiresAt, Integer maxStores,
                                Integer maxProducts, Integer maxEmployees, int currentStores,
                                Integer currentProducts, int currentEmployees) {
        this(planType, status, expiresAt, maxStores, maxProducts, maxEmployees, currentStores, currentProducts,
                currentEmployees, null, null, null, null);
    }
}

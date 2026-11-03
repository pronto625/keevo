package com.keevo.subscription.plan.adapter.in.rest.dto;

/**
 * SubscriptionResponse — API response DTO for subscription info and usage counts.
 *
 * <p>Null values for maxStores/maxProducts/maxEmployees indicate "unlimited"
 * (as configured in the plan's profile).
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
        int currentEmployees
) {}

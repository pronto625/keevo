package com.keevo.subscription.plan.adapter.in.rest.dto;

/**
 * SubscriptionResponse — API response DTO for subscription info and usage counts.
 *
 * <p>Null values for maxStores/maxProducts/maxEmployees indicate "unlimited"
 * (applies to PREMIUM_TRIAL and PREMIUM plans).
 */
public record SubscriptionResponse(
        String planType,           // "FREE" | "PREMIUM_TRIAL" | "PREMIUM"
        String status,             // "ACTIVE" | "SUSPENDED" | "EXPIRED"
        String expiresAt,          // ISO 8601; null for FREE plan
        Integer maxStores,         // null = unlimited
        Integer maxProducts,       // null = unlimited
        Integer maxEmployees,      // null = unlimited
        int currentStores,
        int currentProducts,
        int currentEmployees
) {}

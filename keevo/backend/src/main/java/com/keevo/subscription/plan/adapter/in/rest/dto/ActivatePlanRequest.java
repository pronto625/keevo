package com.keevo.subscription.plan.adapter.in.rest.dto;

import java.time.Instant;

/**
 * ActivatePlanRequest — Request DTO for admin plan activation endpoint.
 */
public record ActivatePlanRequest(
        String planType,    // "PREMIUM" | "PREMIUM_TRIAL" | "FREE"
        Instant expiresAt   // required for PREMIUM/PREMIUM_TRIAL; null for FREE
) {}

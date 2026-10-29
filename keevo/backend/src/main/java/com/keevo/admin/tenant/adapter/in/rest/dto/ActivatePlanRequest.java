package com.keevo.admin.tenant.adapter.in.rest.dto;

import java.time.Instant;

/**
 * ActivatePlanRequest — Request DTO for admin plan activation endpoint (owned by admin since 12.2d).
 */
public record ActivatePlanRequest(
        String planType,    // "PREMIUM" | "PREMIUM_TRIAL" | "FREE"
        Instant expiresAt   // required for PREMIUM/PREMIUM_TRIAL; null for FREE
) {}

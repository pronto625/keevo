package com.keevo.admin.tenant.adapter.in.rest.dto;

import java.time.Instant;

/**
 * ActivatePlanRequest — Request DTO for admin plan activation endpoint (owned by admin since 12.2d).
 */
public record ActivatePlanRequest(
        String planType,    // a plan code of the catalogue (public.plans, Story 19.7a)
        Instant expiresAt   // required for a trial or paid plan; null for a free plan
) {}

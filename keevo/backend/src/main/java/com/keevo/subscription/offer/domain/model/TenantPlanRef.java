package com.keevo.subscription.offer.domain.model;

import java.util.UUID;

/** Story 21.10 — a company and its current plan code ({@code public.tenants.plan_type}). */
public record TenantPlanRef(UUID tenantId, String planCode) {
}

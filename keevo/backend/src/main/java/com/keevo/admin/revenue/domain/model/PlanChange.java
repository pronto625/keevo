package com.keevo.admin.revenue.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code public.tenant_plan_events} (Story 9-2): plan codes {@code FREE | PREMIUM_TRIAL | PREMIUM},
 * cause {@code ADMIN_ACTIVATION | ADMIN_DOWNGRADE | EXPIRY | REGISTRATION}. {@code fromPlan} is null for a
 * registration.
 */
public record PlanChange(UUID tenantId, String fromPlan, String toPlan, String cause, Instant occurredAt) {}

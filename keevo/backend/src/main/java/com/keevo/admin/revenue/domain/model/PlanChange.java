package com.keevo.admin.revenue.domain.model;

import com.keevo.identity.api.PlanKind;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code public.tenant_plan_events} (Story 9-2): plan codes of the catalogue and their kinds (Story 19.7a,
 * joined from {@code public.plans}: the revenue arithmetic reads the kinds, never the codes), cause
 * {@code ADMIN_ACTIVATION | ADMIN_DOWNGRADE | EXPIRY | REGISTRATION | REALIGNMENT | PLAN_MIGRATION |
 * SELF_SERVICE_PAYMENT}. {@code fromPlan} / {@code fromKind}
 * are null for a registration.
 */
public record PlanChange(UUID tenantId, String fromPlan, String toPlan, PlanKind fromKind, PlanKind toKind,
                         String cause, Instant occurredAt) {}

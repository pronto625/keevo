package com.keevo.subscription.plan.domain.port.in;

import com.keevo.identity.auth.domain.model.PlanType;

import java.time.Instant;

/** Tenant's current plan read from its schema's {@code subscriptions} row (Story 9-2). {@code expiresAt} null for FREE. */
public record CurrentPlan(PlanType planType, Instant expiresAt) {}

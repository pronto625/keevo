package com.keevo.subscription.offer.domain.model;

import java.time.Instant;

/**
 * Story 21.10 — the price of a paid plan for a billing cycle ({@code public.plan_prices}), in XAF.
 *
 * @param updatedAt last change
 */
public record PlanPrice(String planCode, String cycleCode, long amountXaf, Instant updatedAt) {
}

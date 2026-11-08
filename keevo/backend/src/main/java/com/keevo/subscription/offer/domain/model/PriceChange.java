package com.keevo.subscription.offer.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.10 — one row of {@code public.plan_price_history}.
 *
 * @param oldAmountXaf  {@code null} for a price created (or seeded)
 * @param newAmountXaf  {@code null} for a price removed
 * @param changedBy     {@code null} for the seed (« Données de départ »)
 * @param changedByName French name of the author, {@code null} when unknown
 */
public record PriceChange(String planCode, String cycleCode, Long oldAmountXaf, Long newAmountXaf, UUID changedBy,
                          String changedByName, Instant changedAt) {
}

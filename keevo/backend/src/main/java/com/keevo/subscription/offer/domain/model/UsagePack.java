package com.keevo.subscription.offer.domain.model;

import com.keevo.shared.capability.entitlement.UsageMetric;

import java.time.Instant;

/**
 * Story 21.3 — a pack of a usage metric sold to owners ({@code public.usage_packs}): commercial offer data edited by
 * the super-admin (ADR-05), never a constant.
 *
 * @param quantity units added to the company's pack balance once paid
 * @param priceXaf price charged (frozen on the payment request at initiation)
 * @param active   offered to owners; an inactive pack stays for the requests already started
 */
public record UsagePack(String code, String label, UsageMetric metric, int quantity, long priceXaf, boolean active,
                        int sortOrder, Instant updatedAt) {
}

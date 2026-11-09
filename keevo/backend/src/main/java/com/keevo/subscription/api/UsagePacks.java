package com.keevo.subscription.api;

import java.util.List;
import java.util.Optional;

/**
 * Story 21.3 — the usage packs of the offer catalogue (SMS packs first), commercial data edited by the super-admin
 * (ADR-05). Read by {@code payments} to price a pack checkout (the price is frozen on the request at initiation).
 * Implemented by {@code subscription.offer}.
 */
public interface UsagePacks {

    /** The packs offered to owners (active), in display order. */
    List<UsagePackOffer> offered();

    /** An offered (active) pack; empty when unknown or inactive. */
    Optional<UsagePackOffer> offered(String code);

    /**
     * @param metric      {@code DOCUMENTS | SMS | EMAILS | AI_CREDITS} (app logic)
     * @param metricLabel French name of the metric (« SMS »)
     * @param quantity    units added once paid
     * @param priceXaf    price charged
     */
    record UsagePackOffer(String code, String label, String metric, String metricLabel, int quantity,
                          long priceXaf) {
    }
}

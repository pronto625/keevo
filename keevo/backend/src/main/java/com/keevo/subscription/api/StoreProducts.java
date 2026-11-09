package com.keevo.subscription.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Story 21.2 — the App Store products of the offer catalogue (commercial data, edited by the super-admin): which plan
 * and billing cycle an Apple product grants. Read by {@code payments} (RevenueCat events, owner product list), which
 * also records the price Apple charged, for the gap with the configurable grid. Implemented by
 * {@code subscription.offer}.
 */
public interface StoreProducts {

    /** The only store handled for now. */
    String APP_STORE = "APP_STORE";

    /**
     * A product, active or not (an inactive product still grants the renewals of its existing subscribers); empty when
     * the product is not mapped.
     */
    Optional<StoreProduct> find(String store, String productId);

    /** The active products of a store whose plan is live (offered to new buyers), in plan then cycle order. */
    List<StoreProduct> active(String store);

    /**
     * Records the price of a purchase seen in a store event (one UPDATE; requires the caller's transaction or runs in
     * its own).
     *
     * @param amountXaf the XAF equivalent when known, else {@code null}
     */
    void recordObservedPrice(String store, String productId, BigDecimal amount, String currency, Long amountXaf,
                             Instant at);

    /**
     * @param planLabel        French label of the plan
     * @param cycleLabel       French label of the cycle
     * @param declaredPriceXaf the Apple price read in App Store Connect by the super-admin, {@code null} if not entered
     * @param gridPriceXaf     the configurable grid price of the plan × cycle, {@code null} when none
     */
    record StoreProduct(String store, String productId, String planCode, String planLabel, String cycleCode,
                        String cycleLabel, int months, boolean active, Long declaredPriceXaf, Long gridPriceXaf) {
    }
}

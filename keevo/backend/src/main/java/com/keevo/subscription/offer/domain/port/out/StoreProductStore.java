package com.keevo.subscription.offer.domain.port.out;

import com.keevo.subscription.offer.domain.model.StoreProductRow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Story 21.2 — persistence of the App Store products ({@code public.store_products}). Every read joins the cycle and the
 * grid price in the same statement (one query).
 */
public interface StoreProductStore {

    /** Every product, in store, plan, cycle, product order. */
    List<StoreProductRow> all();

    Optional<StoreProductRow> find(String store, String productId);

    /** The product row locked {@code FOR UPDATE} (serialises the writes of one product), empty when absent. */
    Optional<StoreProductRow> lock(String store, String productId);

    /** Inserts or replaces the mapping (plan, cycle, declared price, active). */
    void upsert(String store, String productId, String planCode, String cycleCode, Long declaredPriceXaf,
                boolean active);

    /**
     * Keeps the most recent purchase price seen (older observations and an unchanged price are ignored, so a renewal
     * at the same price takes no lock). One UPDATE.
     */
    void recordObservedPrice(String store, String productId, BigDecimal amount, String currency, Long amountXaf,
                             Instant at);
}

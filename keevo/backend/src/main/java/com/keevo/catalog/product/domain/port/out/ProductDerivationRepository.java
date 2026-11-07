package com.keevo.catalog.product.domain.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.6 — the set-based persistence of the product derivation from a document (one statement per step, whatever
 * the number of lines): candidates by key or name, merge targets, price updates, creation in one batch.
 */
public interface ProductDerivationRepository {

    /**
     * How a stored product matched: {@code byKey} (its {@code product_key} is {@code match}) or by name (its
     * {@code lower(name)} equals {@code lower(match)}, {@code match} being the requested name as given).
     */
    record Candidate(boolean byKey, String match, UUID id, boolean archived, int price, boolean priceManual,
                     UUID mergedIntoId, Instant createdAt) {
    }

    /** The current state of a product (a merge target). */
    record Target(UUID id, boolean archived, int price, boolean priceManual) {
    }

    /** A product to create (untracked, automatic price). */
    record NewProduct(UUID id, String name, String sku, int price, String barcode, String productKey) {
    }

    /** Every product whose key is in {@code keys} or whose lower-cased name is one of {@code names} — one query. */
    List<Candidate> findCandidates(Collection<String> keys, Collection<String> names);

    /** The products of these ids (merge targets) — one query. */
    Map<UUID, Target> findTargets(Collection<UUID> ids);

    /** Sets {@code price} (and {@code updated_at}) of each product of the map — one statement. */
    void updatePrices(Map<UUID, Integer> prices, Instant now);

    /** Among {@code skus}, those already used — one query. */
    List<String> existingSkus(Collection<String> skus);

    /**
     * Creates the products in one statement; a row that would break a unique rule (a name taken meanwhile) is skipped
     * instead of failing the transaction.
     *
     * @return the ids actually created
     */
    List<UUID> insertAll(List<NewProduct> products, Instant now);
}

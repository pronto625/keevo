package com.keevo.store.api;

/**
 * The Bref fields of a store as sent by a client (Story 19.12), raw: each value {@code null} (or absent) leaves the
 * stored value unchanged; an empty / blank string clears {@code category}, {@code description}, {@code niu} and
 * {@code rccm}; {@code country} can never be cleared. Validated by the store module (one rule set for REST and sync):
 * a bad value → {@code VALIDATION_ERROR}.
 *
 * @param category    one of the {@link StoreCategory} keys
 * @param description ≤ 1000 characters once trimmed
 * @param niu         ≤ 30 characters once trimmed
 * @param rccm        ≤ 50 characters once trimmed
 * @param country     ISO 3166-1 alpha-2, any case (upper-cased)
 */
public record StoreProfilePatch(String category, String description, String niu, String rccm, String country) {

    /** Nothing to change — what a V1 client sends. */
    public static final StoreProfilePatch NONE = new StoreProfilePatch(null, null, null, null, null);

    /** Whether no field is given. */
    public boolean isEmpty() {
        return category == null && description == null && niu == null && rccm == null && country == null;
    }
}

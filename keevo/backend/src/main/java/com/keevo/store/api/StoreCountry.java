package com.keevo.store.api;

/** The country of a store (Story 19.12): ISO 3166-1 alpha-2, upper case. */
public final class StoreCountry {

    /** Every store is in Cameroon unless told otherwise (the column default). */
    public static final String DEFAULT = "CM";

    private StoreCountry() {
    }
}

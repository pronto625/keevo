package com.keevo.shared.event.catalog.store;

/**
 * StoreKind — store type carried by {@link StoreCreatedEvent} (Story 12.3).
 *
 * <p>Same constants, same names as {@code store.api.StoreType}; the producer maps by name, so audit
 * details ({@code type}) are unchanged.
 */
public enum StoreKind {
    STORE,
    WAREHOUSE
}

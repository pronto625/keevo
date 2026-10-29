package com.keevo.store.api;

/**
 * StoreType — Differentiates a regular store from a central warehouse.
 *
 * <p>GoF Pattern: Factory (consumed by StoreFactory) — StoreType drives
 * creation rules (e.g., warehouse uniqueness check).
 *
 * <p>Story 3.1 — Store & Warehouse management.
 */
public enum StoreType {
    /** Standard point-of-sale store. */
    STORE,
    /** Central warehouse — only one allowed per tenant. */
    WAREHOUSE
}

package com.keevo.inventory.api;

/**
 * Scope of an inventory counting session (Story 12.2b); same constants as the internal scope.
 */
public enum CountingScope {
    /** All active products of the store. */
    FULL,
    /** Only the products of the given categories. */
    PARTIAL
}

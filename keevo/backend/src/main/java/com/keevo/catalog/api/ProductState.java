package com.keevo.catalog.api;

/**
 * Lifecycle state of a product as exposed by the catalog API.
 *
 * <p>Mirrors the internal product status one-to-one (same constant names, so JSON payloads built
 * from it are unchanged).
 */
public enum ProductState {
    /** Visible in the POS catalogue. */
    ACTIVE,
    /** Created on the fly (POS sale / offline), awaiting owner validation. */
    DRAFT
}

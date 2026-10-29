package com.keevo.commerce.api;

/**
 * Lifecycle state of a sale as published by {@code commerce :: api} (Story 12.2c). Same constants
 * as the internal {@code SaleStatus} (mapped by name).
 */
public enum SaleState {
    COMPLETED,
    CANCELLED,
    PENDING_VALIDATION
}

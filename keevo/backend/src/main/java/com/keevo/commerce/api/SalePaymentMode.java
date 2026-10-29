package com.keevo.commerce.api;

/**
 * Payment mode of a sale as published by {@code commerce :: api} (Story 12.2c). Same constants as
 * the internal {@code PaymentMode} (mapped by name), so payloads are unchanged.
 */
public enum SalePaymentMode {
    CASH,
    MOBILE_MONEY
}

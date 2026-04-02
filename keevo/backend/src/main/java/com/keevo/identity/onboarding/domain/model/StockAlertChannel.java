package com.keevo.identity.onboarding.domain.model;

/**
 * StockAlertChannel — Delivery channel for stock alerts.
 * Story 7.5 — Task 2.2
 */
public enum StockAlertChannel {
    PUSH,
    WHATSAPP,
    BOTH;

    /** Case-insensitive parse, defaults to PUSH on unknown value. */
    public static StockAlertChannel fromString(String s) {
        if (s == null) return PUSH;
        try {
            return valueOf(s.toUpperCase());
        } catch (Exception e) {
            return PUSH;
        }
    }
}

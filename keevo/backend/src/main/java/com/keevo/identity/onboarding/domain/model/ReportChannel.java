package com.keevo.identity.onboarding.domain.model;

/**
 * ReportChannel — Delivery channel for a report type.
 * Story 7.5 — Task 2.1
 */
public enum ReportChannel {
    WHATSAPP,
    IN_APP_ONLY;

    /** Case-insensitive parse, defaults to WHATSAPP on unknown value. */
    public static ReportChannel fromString(String s) {
        if (s == null) return WHATSAPP;
        try {
            return valueOf(s.toUpperCase());
        } catch (Exception e) {
            return WHATSAPP;
        }
    }
}

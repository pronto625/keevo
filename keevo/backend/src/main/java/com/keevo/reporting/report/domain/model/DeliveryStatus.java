package com.keevo.reporting.report.domain.model;

/**
 * DeliveryStatus — Enum for WhatsApp delivery lifecycle.
 * Story 7.2 — Rapport End-of-Day
 */
public enum DeliveryStatus {
    PENDING,
    SENT,
    FAILED,
    IN_APP_ONLY
}

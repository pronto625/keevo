package com.keevo.payments.appstore.application.service;

import java.time.Instant;

/**
 * Story 21.2 — the App Store subscription of the company as the owner sees it (labels only on screen).
 *
 * @param status         {@code ACTIVE | CANCELLED | BILLING_ISSUE | EXPIRED | REVOKED | TRANSFERRED}
 * @param statusLabel    French label of the status
 * @param productId      App Store product identifier (for the app's StoreKit calls, never shown)
 * @param autoRenew      Apple will renew at {@code expiresAt}
 * @param graceExpiresAt end of Apple's grace period during a billing issue, {@code null} otherwise
 * @param sandbox        a test purchase (dev / App Review only)
 * @param message        the French sentence to show
 */
public record AppStoreSubscriptionView(String status, String statusLabel, String productId, String planCode,
                                       String planLabel, String cycleCode, String cycleLabel, Instant expiresAt,
                                       Instant graceExpiresAt, boolean autoRenew, boolean sandbox, String message) {
}

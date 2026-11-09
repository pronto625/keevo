package com.keevo.payments.appstore.domain.event;

import java.time.Instant;

/**
 * Story 21.2 — an App Store subscription changed in a way the owners are told about (published inside the processing
 * transaction, consumed after commit inside the module: push + inbox).
 *
 * @param schemaName company schema ({@code kv_xxxxxx})
 * @param key        de-duplication key of the delivery receipt (RevenueCat event id, or sync id)
 * @param kind       what happened
 * @param planLabel  French label of the plan
 * @param until      the date shown (expiry or grace end), {@code null} when none
 */
public record AppStoreSubscriptionNotice(String schemaName, String key, Kind kind, String planLabel, Instant until) {

    public enum Kind { ACTIVE, RENEWAL_RESUMED, CANCELLED, BILLING_ISSUE, EXPIRED, REFUNDED, TRANSFERRED }
}

package com.keevo.payments.appstore.domain.port.out;

import com.keevo.payments.appstore.domain.model.AppStoreEvent;
import com.keevo.payments.appstore.domain.model.EventOutcome;
import com.keevo.payments.appstore.domain.model.ExternalSubscription;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.2 — persistence of the App Store subscriptions, of the processed RevenueCat events and of the App Store
 * ledger rows ({@code public.external_subscriptions}, {@code public.revenuecat_webhook_event},
 * {@code public.tenant_payments}). Every method is one statement on a unique key or an index.
 */
public interface ExternalSubscriptionStore {

    /** Whether the event was already processed (pre-check before a RevenueCat call; the claim is the real guard). */
    boolean eventExists(String eventId);

    /**
     * Claims the event inside the caller's transaction ({@code INSERT … ON CONFLICT DO NOTHING}): false when it already
     * exists (a concurrent claim waits for the other transaction, then sees it). A rollback releases the claim.
     */
    boolean claimEvent(AppStoreEvent event);

    /** Records the outcome and the company of a claimed event. */
    void finishEvent(String eventId, UUID tenantId, EventOutcome outcome);

    /** The subscription of an original transaction locked {@code FOR UPDATE}, empty when unknown. */
    Optional<ExternalSubscription> lock(String originalTransactionId);

    /** Inserts the subscription unless the original transaction exists, then returns the row locked. */
    ExternalSubscription lockOrCreate(ExternalSubscription candidate);

    /** Writes back the state, product, owner, grant and last event of a locked subscription. */
    void update(ExternalSubscription subscription);

    /** The live (else latest) subscription of a company. */
    Optional<ExternalSubscription> currentOfTenant(UUID tenantId);

    /** The live subscriptions of a RevenueCat customer, locked {@code FOR UPDATE} (TRANSFER). */
    List<ExternalSubscription> lockLiveByAppUser(UUID appUserId);

    /**
     * One App Store ledger row; false when that store transaction is already recorded (unique key).
     *
     * @param note French note shown to the super-admin
     */
    boolean insertLedger(UUID tenantId, long amountXaf, Instant paidAt, LocalDate periodStart, LocalDate periodEnd,
                         String note, UUID recordedBy, String planCode, String cycleCode, String storeTransactionId);

    /** Voids the ledger row of a refunded store transaction (not yet voided). */
    void voidLedger(String storeTransactionId, String reason, Instant at);

    /** Cancels the void of a refund Apple reversed (only a void with that reason). */
    void unvoidLedger(String storeTransactionId, String reason);

    /**
     * Purchases (INITIAL_PURCHASE / RENEWAL) of an original transaction left UNRESOLVED or UNKNOWN_PRODUCT, oldest
     * first (index {@code ix_revenuecat_webhook_event_pending}).
     */
    List<PendingPurchase> pendingPurchases(String originalTransactionId);

    /** Marks a pending purchase recorded (outcome APPLIED, company set). */
    void markReplayed(String eventId, UUID tenantId);

    /** A purchase kept from an event that could not be applied when it arrived. */
    record PendingPurchase(String eventId, String transactionId, String productId, java.math.BigDecimal price,
                           String currency, String periodType, Instant purchasedAt, Instant expiresAt) {
    }
}

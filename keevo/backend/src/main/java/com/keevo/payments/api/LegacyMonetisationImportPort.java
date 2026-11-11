package com.keevo.payments.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.5 — payments writes of the Bref migration tool: the owner's plan checkouts ({@code payment_requests}), the
 * ledger ({@code tenant_payments}), the App Store subscriptions ({@code external_subscriptions}), the RevenueCat events
 * Bref already processed ({@code revenuecat_webhook_event}) and the account credit. Every insert keeps the given id,
 * is one statement per table ({@code ON CONFLICT DO NOTHING}: a row already there — same id, or the same provider
 * reference / original transaction held by another company — is left as it is and not returned), publishes no event
 * and notifies no one. Every method joins the caller's transaction (MANDATORY).
 */
public interface LegacyMonetisationImportPort {

    /** @return the ids inserted by this call */
    Set<UUID> importPaymentRequests(List<ImportedPaymentRequest> requests);

    /** @return the ids inserted by this call (insert the requests they point to first) */
    Set<UUID> importLedger(List<ImportedPayment> payments);

    /** @return the ids inserted by this call */
    Set<UUID> importExternalSubscriptions(List<ImportedExternalSubscription> subscriptions);

    /** @return the event ids inserted by this call */
    Set<String> importRevenueCatEvents(List<ImportedRevenueCatEvent> events);

    /**
     * Adds {@code amountXaf} to the company's account credit, journaled as a {@code MIGRATION} movement.
     *
     * @param amountXaf {@code > 0}
     * @return the balance after
     */
    long creditAccount(UUID tenantId, long amountXaf, String reason);

    /**
     * A plan checkout as imported (kind PLAN; never in flight).
     *
     * @param provider {@code MYCOOLPAY} | {@code NONE}
     * @param method   {@code ORANGE_MONEY} | {@code MTN_MOMO} | {@code null}
     * @param status   {@code CONFIRMED} | {@code FAILED} | {@code CANCELLED} | {@code EXPIRED}
     */
    record ImportedPaymentRequest(UUID id, UUID tenantId, String tenantSchema, UUID requestedBy, String provider,
                                  String method, String planCode, String cycleCode, String planLabel,
                                  String cycleLabel, int months, long baseXaf, long reductionXaf,
                                  long creditAppliedXaf, long amountDueXaf, Instant pricedAt, String status,
                                  String appTransactionRef, String providerTransactionRef, String providerStatus,
                                  String failureReason, UUID tenantPaymentId, LocalDate periodStart,
                                  LocalDate periodEnd, Instant expiresAt, Instant confirmedAt, Instant createdAt,
                                  Instant updatedAt) {

        public ImportedPaymentRequest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(tenantId, "tenantId");
            if (baseXaf <= 0 || reductionXaf < 0 || creditAppliedXaf < 0
                    || amountDueXaf != baseXaf - reductionXaf - creditAppliedXaf || amountDueXaf < 0) {
                throw new IllegalArgumentException("inconsistent amounts");
            }
            if ("CONFIRMED".equals(status) != (confirmedAt != null)) {
                throw new IllegalArgumentException("confirmed date iff confirmed");
            }
        }
    }

    /**
     * A ledger row as imported.
     *
     * @param amountXaf  cash collected ({@code >= 0})
     * @param creditXaf  part paid with the account credit ({@code >= 0}); {@code amountXaf + creditXaf > 0}
     * @param method     {@code MOBILE_MONEY} | {@code APP_STORE} | {@code ACCOUNT_CREDIT} | {@code OTHER}
     */
    record ImportedPayment(UUID id, UUID tenantId, long amountXaf, long creditXaf, String method, Instant paidAt,
                           LocalDate periodStart, LocalDate periodEnd, String note, UUID recordedBy,
                           String planCode, String cycleCode, UUID paymentRequestId, String storeTransactionId) {

        public ImportedPayment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(recordedBy, "recordedBy");
            if (amountXaf < 0 || creditXaf < 0 || amountXaf + creditXaf <= 0 || amountXaf > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid amounts");
            }
            if (periodEnd.isBefore(periodStart)) {
                throw new IllegalArgumentException("period ends before it starts");
            }
        }
    }

    /**
     * An App Store subscription as imported.
     *
     * @param status      {@code ACTIVE} | {@code BILLING_ISSUE} | {@code EXPIRED} | {@code REVOKED}
     * @param environment {@code PRODUCTION} | {@code SANDBOX}
     */
    record ImportedExternalSubscription(UUID id, UUID tenantId, String tenantSchema, UUID appUserId,
                                        String originalTransactionId, String productId, String planCode,
                                        String cycleCode, String status, boolean autoRenew, String environment,
                                        Instant expiresAt, Instant graceExpiresAt, Instant grantedExpiresAt,
                                        long lastEventMs, Instant createdAt, Instant updatedAt) {

        public ImportedExternalSubscription {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(originalTransactionId, "originalTransactionId");
        }
    }

    /** A RevenueCat event already processed by api-bref (outcome {@code APPLIED}). */
    record ImportedRevenueCatEvent(String id, String type, String appUserId, String originalTransactionId,
                                   long eventTimestampMs, UUID tenantId, Instant processedAt) {

        public ImportedRevenueCatEvent {
            Objects.requireNonNull(id, "id");
        }
    }
}

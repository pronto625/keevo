package com.keevo.migration.domain.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 24.5 — the monetisation section of one Bref owner, as read from the source, unchanged (field names of the
 * api-bref entities, {@code origin/develop} 6faa8b4): subscriptions, plan payment requests (Mobile Money / credit
 * checkouts), App Store transactions, the RevenueCat events already processed, the account credit, the SMS recharge
 * requests of the owner and the members, the usage counters. Both section sources (restored database, JSON export) give
 * the same rows; a table an older Bref schema lacks reads as empty.
 *
 * <p>Personal or secret columns the import does not use are not read: the payment requests' {@code providerMetadata},
 * the SMS recharges' {@code phone} / {@code cardNumber}.
 */
public record BrefMonetisation(List<Subscription> subscriptions, List<PlanPaymentRequest> paymentRequests,
                               List<AppleTransaction> appleTransactions, List<RevenueCatEvent> revenueCatEvents,
                               AccountCredit accountCredit, List<SmsRecharge> smsRecharges,
                               List<UsageCounter> usageCounters) {

    /** Bref source tables (record {@code source_table}). */
    public static final String BREF_SUBSCRIPTION = "subscription";
    public static final String BREF_PLAN_PAYMENT_REQUEST = "plan_payment_request";
    public static final String BREF_APPLE_TRANSACTION = "apple_iap_transaction";
    public static final String BREF_REVENUECAT_EVENT = "revenuecat_webhook_event";
    public static final String BREF_ACCOUNT_CREDIT = "account_credit";
    public static final String BREF_SMS_RECHARGE = "sms_recharge_request";
    public static final String BREF_USAGE_COUNTER = "usage_counter";

    public BrefMonetisation {
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
        paymentRequests = paymentRequests == null ? List.of() : List.copyOf(paymentRequests);
        appleTransactions = appleTransactions == null ? List.of() : List.copyOf(appleTransactions);
        revenueCatEvents = revenueCatEvents == null ? List.of() : List.copyOf(revenueCatEvents);
        smsRecharges = smsRecharges == null ? List.of() : List.copyOf(smsRecharges);
        usageCounters = usageCounters == null ? List.of() : List.copyOf(usageCounters);
    }

    /** An owner without any monetisation row. */
    public static BrefMonetisation empty() {
        return new BrefMonetisation(List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of());
    }

    /**
     * A Bref {@code subscription} row.
     *
     * @param status        {@code active | scheduled | expired | cancelled}
     * @param paymentMethod {@code admin_grant | apple | orange_money | momo | card | credit…}; {@code null} = not paid
     */
    public record Subscription(UUID id, UUID ownerId, String plan, String status, String billingCycle,
                               Instant startedAt, Instant endsAt, long amountPaid, long creditUsed,
                               String paymentMethod, String appleOriginalTransactionId, Instant gracePeriodEndsAt,
                               Instant createdAt, Instant updatedAt) {

        public Subscription {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public String canonical() {
            return Canonical.of("id", id, "ownerId", ownerId, "plan", plan, "status", status, "billingCycle",
                    billingCycle, "startedAt", startedAt, "endsAt", endsAt, "amountPaid", amountPaid, "creditUsed",
                    creditUsed, "paymentMethod", paymentMethod, "appleOriginalTransactionId",
                    appleOriginalTransactionId, "gracePeriodEndsAt", gracePeriodEndsAt, "createdAt", createdAt,
                    "updatedAt", updatedAt);
        }
    }

    /**
     * A Bref {@code plan_payment_request} row (a checkout).
     *
     * @param status {@code pending | confirmed | failed | cancelled | expired}
     */
    public record PlanPaymentRequest(UUID id, UUID ownerId, String targetPlan, String targetBillingCycle, long amount,
                                     long creditApplied, long amountDue, String currency, String status,
                                     String paymentMethod, Instant startsAt, Instant endsAt, Instant createdAt,
                                     Instant updatedAt, String paymentProvider, String providerTransactionRef,
                                     String providerStatus) {

        public PlanPaymentRequest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        public String canonical() {
            return Canonical.of("id", id, "ownerId", ownerId, "targetPlan", targetPlan, "targetBillingCycle",
                    targetBillingCycle, "amount", amount, "creditApplied", creditApplied, "amountDue", amountDue,
                    "currency", currency, "status", status, "paymentMethod", paymentMethod, "startsAt", startsAt,
                    "endsAt", endsAt, "createdAt", createdAt, "updatedAt", updatedAt, "paymentProvider",
                    paymentProvider, "providerTransactionRef", providerTransactionRef, "providerStatus",
                    providerStatus);
        }
    }

    /** A Bref {@code apple_iap_transaction} row (one App Store transaction of an original subscription). */
    public record AppleTransaction(UUID id, String ownerId, String originalTransactionId, String transactionId,
                                   String productId, String bundleId, String environment, String status,
                                   Instant purchaseDate, Instant expiresDate, Integer revocationReason,
                                   Instant revocationDate, String subscriptionId, Instant createdAt,
                                   Instant updatedAt) {

        public AppleTransaction {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(purchaseDate, "purchaseDate");
        }

        public String canonical() {
            return Canonical.of("id", id, "ownerId", ownerId, "originalTransactionId", originalTransactionId,
                    "transactionId", transactionId, "productId", productId, "bundleId", bundleId, "environment",
                    environment, "status", status, "purchaseDate", purchaseDate, "expiresDate", expiresDate,
                    "revocationReason", revocationReason, "revocationDate", revocationDate, "subscriptionId",
                    subscriptionId, "createdAt", createdAt, "updatedAt", updatedAt);
        }
    }

    /**
     * A Bref {@code revenuecat_webhook_event} row (develop): an event claimed by api-bref, processed when
     * {@code processedAt} is set. {@code subjectKey} is the original transaction id, else the RevenueCat app user id.
     */
    public record RevenueCatEvent(String id, String type, String subjectKey, long eventTimestampMs,
                                  Instant processedAt, Instant createdAt) {

        public RevenueCatEvent {
            Objects.requireNonNull(id, "id");
        }

        /** The record source id of an event (its id is text): a name-based UUID, stable across runs and sources. */
        public UUID sourceId() {
            return UUID.nameUUIDFromBytes((BREF_REVENUECAT_EVENT + ":" + id).getBytes(StandardCharsets.UTF_8));
        }

        public String canonical() {
            return Canonical.of("id", id, "type", type, "subjectKey", subjectKey, "eventTimestampMs",
                    eventTimestampMs, "processedAt", processedAt, "createdAt", createdAt);
        }
    }

    /** The Bref {@code account_credit} row of the owner (FCFA). */
    public record AccountCredit(UUID id, UUID ownerId, long amount, Instant updatedAt) {

        public AccountCredit {
            Objects.requireNonNull(id, "id");
        }

        public String canonical() {
            return Canonical.of("id", id, "ownerId", ownerId, "amount", amount, "updatedAt", updatedAt);
        }
    }

    /** A Bref {@code sms_recharge_request} row (phone and card number not read). */
    public record SmsRecharge(UUID id, UUID userId, long smsQuantity, long amountDue, String currency, String status,
                              String paymentMethod, Instant completedAt, Instant createdAt, Instant updatedAt) {

        public SmsRecharge {
            Objects.requireNonNull(id, "id");
        }

        public String canonical() {
            return Canonical.of("id", id, "userId", userId, "smsQuantity", smsQuantity, "amountDue", amountDue,
                    "currency", currency, "status", status, "paymentMethod", paymentMethod, "completedAt",
                    completedAt, "createdAt", createdAt, "updatedAt", updatedAt);
        }
    }

    /**
     * A Bref {@code usage_counter} row.
     *
     * @param metric      {@code invoices | sms | email | ai_credits | sms_free | daily_<metric>}
     * @param periodMonth {@code YYYY-MM} (month), {@code YYYY-MM-DD} (day, {@code daily_*}), {@code all} (lifetime)
     */
    public record UsageCounter(UUID id, UUID ownerId, String metric, String periodMonth, long used,
                               Instant updatedAt) {

        public UsageCounter {
            Objects.requireNonNull(id, "id");
        }

        public String canonical() {
            return Canonical.of("id", id, "ownerId", ownerId, "metric", metric, "periodMonth", periodMonth, "used",
                    used, "updatedAt", updatedAt);
        }
    }
}

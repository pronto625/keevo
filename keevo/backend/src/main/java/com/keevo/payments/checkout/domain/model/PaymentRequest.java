package com.keevo.payments.checkout.domain.model;

import com.keevo.shared.capability.entitlement.UsageMetric;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Story 21.1 — one checkout of an owner ({@code public.payment_requests}): the quote frozen at initiation (base,
 * reduction, amount due, discount, labels), the provider references and the state. Story 21.3 — a request buys a plan
 * or a usage pack ({@link #kind}) and may be paid partly or fully by the account credit ({@link #creditAppliedXaf},
 * reserved with the request).
 *
 * @param tenantSchema          schema of the company ({@code kv_xxxxxx}) — the plan is applied there
 * @param requestedBy           the owner who started it (actor of the plan event)
 * @param method                {@code null} when nothing is charged to the provider
 * @param phone                 E.164 number charged, {@code null} when nothing is charged to the provider
 * @param planCode              plan bought ({@code null} for a pack); also {@code planLabel}, {@code cycleCode},
 *                              {@code cycleLabel}, {@code months}
 * @param amountDueXaf          charged to the provider ({@code base − reduction − credit}); 0 = nothing to collect
 * @param discountId            the discount applied (its use is counted at confirmation), {@code null} when none
 * @param appTransactionRef     our reference sent to the provider (unique)
 * @param providerTransactionRef the provider's reference (webhook routing), {@code null} before the provider answered
 * @param failureReason         French reason of a FAILED / CANCELLED / EXPIRED request
 * @param periodStart           first covered day (WAT), set at confirmation of a plan
 * @param expiresAt             plan expiry granted, set at confirmation of a plan
 * @param creditAppliedXaf      account credit reserved for this request (0 when none)
 * @param packCode              pack bought ({@code null} for a plan); also {@code packLabel}, {@code packMetric},
 *                              {@code packQuantity}
 */
public record PaymentRequest(UUID id, UUID tenantId, String tenantSchema, UUID requestedBy, PaymentProvider provider,
                             MobileMoneyMethod method, String phone, String planCode, String planLabel,
                             String cycleCode, String cycleLabel, int months, long baseXaf, long reductionXaf,
                             long amountDueXaf, String currency, UUID discountId, String discountLabel,
                             String promoCode, Instant pricedAt, PaymentStatus status, NextStep nextStep,
                             String ussdCode, String appTransactionRef, String providerTransactionRef,
                             String failureReason, UUID tenantPaymentId, LocalDate periodStart, LocalDate periodEnd,
                             Instant expiresAt, Instant confirmedAt, Instant createdAt, PaymentKind kind,
                             long creditAppliedXaf, String packCode, String packLabel, UsageMetric packMetric,
                             int packQuantity) {

    /** Nothing to collect from the provider (free offer or fully covered by the credit). */
    public boolean free() {
        return amountDueXaf == 0;
    }

    /** A free offer of the catalogue (a 100 % discount), whatever the credit. */
    public boolean freeOffer() {
        return reductionXaf == baseXaf;
    }

    public boolean pack() {
        return kind == PaymentKind.USAGE_PACK;
    }

    /** French name of what is bought: « Pro · Mensuel », « Pack 100 SMS ». */
    public String itemLabel() {
        return pack() ? packLabel : planLabel + " · " + cycleLabel;
    }
}

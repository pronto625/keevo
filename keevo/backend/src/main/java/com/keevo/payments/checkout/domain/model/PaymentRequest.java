package com.keevo.payments.checkout.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Story 21.1 — one checkout of an owner ({@code public.payment_requests}): the quote frozen at initiation (base,
 * reduction, amount due, discount, labels), the provider references and the state.
 *
 * @param tenantSchema          schema of the company ({@code kv_xxxxxx}) — the plan is applied there
 * @param requestedBy           the owner who started it (actor of the plan event)
 * @param method                {@code null} for a free offer
 * @param phone                 E.164 number charged, {@code null} for a free offer
 * @param amountDueXaf          price charged ({@code base − reduction}); 0 = free offer
 * @param discountId            the discount applied (its use is counted at confirmation), {@code null} when none
 * @param appTransactionRef     our reference sent to the provider (unique)
 * @param providerTransactionRef the provider's reference (webhook routing), {@code null} before the provider answered
 * @param failureReason         French reason of a FAILED / CANCELLED / EXPIRED request
 * @param periodStart           first covered day (WAT), set at confirmation
 * @param expiresAt             plan expiry granted, set at confirmation
 */
public record PaymentRequest(UUID id, UUID tenantId, String tenantSchema, UUID requestedBy, PaymentProvider provider,
                             MobileMoneyMethod method, String phone, String planCode, String planLabel,
                             String cycleCode, String cycleLabel, int months, long baseXaf, long reductionXaf,
                             long amountDueXaf, String currency, UUID discountId, String discountLabel,
                             String promoCode, Instant pricedAt, PaymentStatus status, NextStep nextStep,
                             String ussdCode, String appTransactionRef, String providerTransactionRef,
                             String failureReason, UUID tenantPaymentId, LocalDate periodStart, LocalDate periodEnd,
                             Instant expiresAt, Instant confirmedAt, Instant createdAt) {

    public boolean free() {
        return amountDueXaf == 0;
    }
}

package com.keevo.payments.checkout.application.service;

import com.keevo.payments.checkout.domain.model.NextStep;
import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.1 — a payment request as the owner's app sees it. {@code id} is only the target of the next calls (status,
 * code, cancel), never shown; codes ({@code status}, {@code nextStep}, {@code method}, plan and cycle codes) are for the
 * app's logic, the {@code …Label} fields and {@code message} are the French texts to show.
 *
 * @param status        {@code INITIATING | PENDING | CONFIRMED | FAILED | CANCELLED | EXPIRED}
 * @param nextStep      {@code OTP} (type the SMS code), {@code USSD} (validate on the phone, dial {@code ussdCode} when
 *                      given), {@code NONE}
 * @param amountXaf     price charged (frozen at initiation); 0 = free offer
 * @param message       what to tell the owner now (next step, failure reason or confirmation), French
 * @param planExpiresAt new plan expiry once CONFIRMED, else {@code null}
 */
public record PaymentView(UUID id, String status, String statusLabel, String nextStep, String ussdCode,
                          String method, String methodLabel, String planCode, String planLabel, String cycleCode,
                          String cycleLabel, int months, long baseXaf, long reductionXaf, long amountXaf,
                          String discountLabel, String message, Instant createdAt, Instant confirmedAt,
                          Instant planExpiresAt) {

    public static PaymentView of(PaymentRequest r) {
        return new PaymentView(r.id(), r.status().name(), r.status().label(),
                r.nextStep() == null ? null : r.nextStep().name(), r.ussdCode(),
                r.method() == null ? null : r.method().name(), r.method() == null ? null : r.method().label(),
                r.planCode(), r.planLabel(), r.cycleCode(), r.cycleLabel(), r.months(), r.baseXaf(),
                r.reductionXaf(), r.amountDueXaf(), r.discountLabel(), message(r), r.createdAt(), r.confirmedAt(),
                r.status() == PaymentStatus.CONFIRMED ? r.expiresAt() : null);
    }

    static String message(PaymentRequest r) {
        return switch (r.status()) {
            case INITIATING -> PaymentStatus.INITIATING.label();
            case PENDING -> r.nextStep() == NextStep.OTP ? PaymentText.ENTER_CODE
                    : r.ussdCode() != null ? PaymentText.dialThenValidate(r.ussdCode())
                    : PaymentText.VALIDATE_ON_PHONE;
            case CONFIRMED -> r.expiresAt() == null ? PaymentStatus.CONFIRMED.label()
                    : PaymentText.activeUntil(r.planLabel(), r.expiresAt());
            case FAILED, CANCELLED, EXPIRED -> r.failureReason() != null ? r.failureReason() : r.status().label();
        };
    }
}

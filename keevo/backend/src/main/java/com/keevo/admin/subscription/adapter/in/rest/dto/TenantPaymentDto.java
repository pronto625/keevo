package com.keevo.admin.subscription.adapter.in.rest.dto;

import com.keevo.admin.subscription.domain.model.TenantPayment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * TenantPaymentDto — one row of the payment history (Story 9-2). The recorder is shown as the
 * « Super admin » label; {@code id} is only the target of the « Annuler ce paiement » action (never
 * displayed). A voided payment carries its void date and reason. Story 21.10 — additive: the plan and the billing
 * cycle paid for (codes to act on, null when unknown; labels to show — « Plan non précisé » / « Durée libre » when
 * unknown). Story 21.1 — additive {@code online}: a payment the owner made online (Mobile Money), its recorder label is
 * « Paiement en ligne (Mobile Money) ». Story 24.5 — additive {@code creditXaf}: the part paid with the account credit
 * ({@code amountXaf} is the cash); method {@code ACCOUNT_CREDIT} when the credit paid it all.
 */
public record TenantPaymentDto(
        UUID id,
        Instant paidAt,
        long amountXaf,
        String method,
        LocalDate periodStart,
        LocalDate periodEnd,
        String note,
        String recordedByLabel,
        Instant voidedAt,
        String voidReason,
        String planCode,
        String planLabel,
        String cycleCode,
        String cycleLabel,
        boolean online,
        long creditXaf
) {
    public static final String SUPER_ADMIN_LABEL = "Super admin";
    /** Story 21.1 — recorder label of an online payment of the owner. */
    public static final String ONLINE_LABEL = "Paiement en ligne (Mobile Money)";
    /** Story 21.2 — recorder label of an App Store purchase of the owner. */
    public static final String APP_STORE_LABEL = "Paiement en ligne (App Store)";
    /** Story 24.5 — recorder label of a plan the owner paid entirely with the account credit. */
    public static final String ACCOUNT_CREDIT_LABEL = "Paiement en ligne (crédit du compte)";

    /** Story 21.10 — with the French labels of the plan and of the cycle paid for. */
    public static TenantPaymentDto from(TenantPayment p, String planLabel, String cycleLabel) {
        return new TenantPaymentDto(p.id(), p.paidAt(), p.amountXaf(), p.method().name(),
                p.periodStart(), p.periodEnd(), p.note(), recorder(p), p.voidedAt(),
                p.voidReason(), p.planCode(), planLabel, p.cycleCode(), cycleLabel, p.online(), p.creditXaf());
    }

    private static String recorder(TenantPayment p) {
        if (!p.online()) {
            return SUPER_ADMIN_LABEL;
        }
        return switch (p.method()) {
            case APP_STORE -> APP_STORE_LABEL;
            case ACCOUNT_CREDIT -> ACCOUNT_CREDIT_LABEL;
            default -> ONLINE_LABEL;
        };
    }
}

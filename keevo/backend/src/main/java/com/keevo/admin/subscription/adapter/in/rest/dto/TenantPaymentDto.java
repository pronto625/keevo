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
 * « Paiement en ligne (Mobile Money) ».
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
        boolean online
) {
    public static final String SUPER_ADMIN_LABEL = "Super admin";
    /** Story 21.1 — recorder label of an online payment of the owner. */
    public static final String ONLINE_LABEL = "Paiement en ligne (Mobile Money)";

    /** Story 21.10 — with the French labels of the plan and of the cycle paid for. */
    public static TenantPaymentDto from(TenantPayment p, String planLabel, String cycleLabel) {
        return new TenantPaymentDto(p.id(), p.paidAt(), p.amountXaf(), p.method().name(),
                p.periodStart(), p.periodEnd(), p.note(), p.online() ? ONLINE_LABEL : SUPER_ADMIN_LABEL, p.voidedAt(),
                p.voidReason(), p.planCode(), planLabel, p.cycleCode(), cycleLabel, p.online());
    }
}

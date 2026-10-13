package com.keevo.admin.subscription.adapter.in.rest.dto;

import com.keevo.admin.subscription.domain.model.TenantPayment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * TenantPaymentDto — one row of the payment history (Story 9-2). The recorder is shown as the
 * « Super admin » label; {@code id} is only the target of the « Annuler ce paiement » action (never
 * displayed). A voided payment carries its void date and reason.
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
        String voidReason
) {
    public static final String SUPER_ADMIN_LABEL = "Super admin";

    public static TenantPaymentDto from(TenantPayment p) {
        return new TenantPaymentDto(p.id(), p.paidAt(), p.amountXaf(), p.method().name(),
                p.periodStart(), p.periodEnd(), p.note(), SUPER_ADMIN_LABEL, p.voidedAt(), p.voidReason());
    }
}

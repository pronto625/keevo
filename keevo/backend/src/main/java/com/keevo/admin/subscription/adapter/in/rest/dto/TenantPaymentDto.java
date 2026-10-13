package com.keevo.admin.subscription.adapter.in.rest.dto;

import com.keevo.admin.subscription.domain.model.TenantPayment;

import java.time.Instant;
import java.time.LocalDate;

/**
 * TenantPaymentDto — one row of the payment history (Story 9-2). No ids: the recorder is shown as
 * the « Super admin » label.
 */
public record TenantPaymentDto(
        Instant paidAt,
        long amountXaf,
        String method,
        LocalDate periodStart,
        LocalDate periodEnd,
        String note,
        String recordedByLabel
) {
    public static final String SUPER_ADMIN_LABEL = "Super admin";

    public static TenantPaymentDto from(TenantPayment p) {
        return new TenantPaymentDto(p.paidAt(), p.amountXaf(), p.method().name(),
                p.periodStart(), p.periodEnd(), p.note(), SUPER_ADMIN_LABEL);
    }
}

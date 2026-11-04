package com.keevo.admin.subscription.domain.port.in;

import com.keevo.admin.subscription.domain.model.PaymentMethod;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ActivatePremiumCommand — activate/renew Premium after an off-platform payment (Story 9-2).
 *
 * @param expiresAt last Premium day (WAT), must be after today
 * @param paidAt    payment time, null → now
 * @param planCode  Story 19.7b — the paid plan granted; null → {@code plan_settings.default_paid_plan_code}
 */
public record ActivatePremiumCommand(
        UUID actorId,
        UUID tenantId,
        LocalDate expiresAt,
        long amountXaf,
        PaymentMethod method,
        Instant paidAt,
        String note,
        String planCode
) {

    /** The default paid plan ({@code plan_settings.default_paid_plan_code}). */
    public ActivatePremiumCommand(UUID actorId, UUID tenantId, LocalDate expiresAt, long amountXaf,
                                  PaymentMethod method, Instant paidAt, String note) {
        this(actorId, tenantId, expiresAt, amountXaf, method, paidAt, note, null);
    }
}

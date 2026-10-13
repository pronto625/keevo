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
 */
public record ActivatePremiumCommand(
        UUID actorId,
        UUID tenantId,
        LocalDate expiresAt,
        long amountXaf,
        PaymentMethod method,
        Instant paidAt,
        String note
) {}

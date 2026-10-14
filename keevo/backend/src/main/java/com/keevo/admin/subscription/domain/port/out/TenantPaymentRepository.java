package com.keevo.admin.subscription.domain.port.out;

import com.keevo.admin.subscription.domain.model.TenantPayment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TenantPaymentRepository — store of off-platform payments ({@code public.tenant_payments}), Story 9-2.
 * Append-only: the only change allowed on a row is voiding it.
 */
public interface TenantPaymentRepository {

    /** Inserts the payment in the caller's transaction. */
    void insert(TenantPayment payment);

    /** Payments of a tenant (voided included), newest {@code paid_at} first. */
    List<TenantPayment> findByTenant(UUID tenantId);

    /** One payment of a tenant (voided included). */
    Optional<TenantPayment> findById(UUID tenantId, UUID paymentId);

    /** Marks a non-voided payment voided; false when it is unknown or already voided. */
    boolean voidPayment(UUID tenantId, UUID paymentId, UUID actorId, String reason, Instant at);
}

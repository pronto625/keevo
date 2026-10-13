package com.keevo.admin.subscription.domain.port.out;

import com.keevo.admin.subscription.domain.model.TenantPayment;

import java.util.List;
import java.util.UUID;

/** TenantPaymentRepository — append-only store of off-platform payments ({@code public.tenant_payments}), Story 9-2. */
public interface TenantPaymentRepository {

    /** Inserts the payment in the caller's transaction. */
    void insert(TenantPayment payment);

    /** Payments of a tenant, newest {@code paid_at} first. */
    List<TenantPayment> findByTenant(UUID tenantId);
}

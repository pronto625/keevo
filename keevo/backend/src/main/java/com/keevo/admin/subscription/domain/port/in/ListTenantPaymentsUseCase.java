package com.keevo.admin.subscription.domain.port.in;

import com.keevo.admin.subscription.domain.model.TenantPayment;

import java.util.List;
import java.util.UUID;

/** ListTenantPaymentsUseCase — payment history of a tenant, newest first (Story 9-2). */
public interface ListTenantPaymentsUseCase {

    List<TenantPayment> listPayments(UUID tenantId);
}

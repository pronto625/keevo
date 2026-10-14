package com.keevo.admin.subscription.domain.port.in;

import java.util.UUID;

/** VoidTenantPaymentCommand — void a payment entered by mistake, reason required (Story 9-2). */
public record VoidTenantPaymentCommand(UUID actorId, UUID tenantId, UUID paymentId, String reason) {}

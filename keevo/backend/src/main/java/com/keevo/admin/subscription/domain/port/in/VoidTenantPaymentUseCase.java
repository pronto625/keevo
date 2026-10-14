package com.keevo.admin.subscription.domain.port.in;

/**
 * VoidTenantPaymentUseCase — corrects a payment entered by mistake (Story 9-2). The row is kept
 * (append-only history) and marked voided; a voided payment is excluded from every count.
 */
public interface VoidTenantPaymentUseCase {
    void voidPayment(VoidTenantPaymentCommand command);
}

package com.keevo.payments.checkout.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.1 — an online payment of the owner was confirmed and the plan applied (published inside the confirmation
 * transaction, consumed after commit inside the module: push + inbox of the owners).
 *
 * @param schemaName   company schema ({@code kv_xxxxxx})
 * @param requestId    the payment request (de-duplication of the delivery receipt)
 * @param planLabel    French label of the plan bought
 * @param expiresAt    new plan expiry
 */
public record PlanPaymentConfirmedEvent(String schemaName, UUID requestId, String planLabel, Instant expiresAt) {
}

package com.keevo.payments.credit.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.3 — one line of the account credit journal ({@code public.tenant_credit_movements}) with what the payment
 * concerned bought and the actor's name, joined in the same query.
 *
 * @param amountXaf   signed: + added, − used
 * @param itemLabel   « Pro · Mensuel » / « Pack 100 SMS » for a payment movement, {@code null} otherwise
 * @param actorName   first and last name of the actor, {@code null} when unknown or none
 * @param reason      the support's reason (admin adjustment), {@code null} otherwise
 */
public record CreditMovement(UUID id, CreditMovementKind kind, long amountXaf, long balanceAfterXaf,
                             UUID paymentRequestId, String itemLabel, UUID actorId, String actorName, String reason,
                             Instant at) {
}

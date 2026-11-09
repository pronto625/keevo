package com.keevo.payments.credit.domain.port.out;

import com.keevo.payments.credit.domain.model.CreditMovement;
import com.keevo.payments.credit.domain.model.CreditMovementKind;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Story 21.3 — the account credit of the companies ({@code public.tenant_credits}) and its append-only journal
 * ({@code public.tenant_credit_movements}). Every change of the balance and its journal row are ONE statement; the
 * balance never goes negative (guarded update + CHECK). Joins the caller's transaction.
 */
public interface AccountCreditStore {

    /** The balance ({@code 0} when the company never had credit). */
    long balance(UUID tenantId);

    /** The balance read {@code FOR UPDATE} (serialises the uses of one company's credit until the transaction ends). */
    long lockBalance(UUID tenantId);

    /**
     * Takes {@code amount} when at least that much remains, and journals it — one statement.
     *
     * @return the balance after, empty when the balance did not cover it (nothing written)
     */
    OptionalLong debit(UUID tenantId, long amount, CreditMovementKind kind, UUID paymentRequestId, UUID actorId,
                       String reason);

    /**
     * Adds {@code amount} (upsert) and journals it — one statement.
     *
     * @return the balance after
     * @throws org.springframework.dao.DuplicateKeyException a second {@code PAYMENT_TO_CREDIT} of the same request
     */
    long credit(UUID tenantId, long amount, CreditMovementKind kind, UUID paymentRequestId, UUID actorId,
                String reason);

    /** Whether the money of this request was already added to the credit ({@code PAYMENT_TO_CREDIT}). */
    boolean convertedToCredit(UUID paymentRequestId);

    /**
     * The journal of a company, newest first, strictly before the keyset {@code (beforeAt, beforeId)} when given; the
     * item bought and the actor names joined (one query).
     */
    List<CreditMovement> movements(UUID tenantId, Instant beforeAt, UUID beforeId, int limit);

    /** Whether the company exists. */
    boolean tenantExists(UUID tenantId);
}

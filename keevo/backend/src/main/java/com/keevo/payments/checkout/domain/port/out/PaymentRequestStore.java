package com.keevo.payments.checkout.domain.port.out;

import com.keevo.payments.checkout.domain.model.NextStep;
import com.keevo.payments.checkout.domain.model.PaymentProvider;
import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.model.PlanPeriod;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.1 — {@code public.payment_requests} and the ledger row of a confirmed payment
 * ({@code public.tenant_payments}). Every state change is one guarded statement ({@code … WHERE status IN (…)}).
 *
 * <p>Story 21.3 — every transition out of the in-flight states (expiry, failure, cancellation) gives the account credit
 * reserved by the request back to the company IN THE SAME STATEMENT (balance + journal row {@code PAYMENT_RELEASE}),
 * so a transition and its release can never diverge.
 */
public interface PaymentRequestStore {

    /**
     * Expires the stale in-flight requests of a company (one statement, credit released): INITIATING last touched
     * before {@code initiatingBefore}, PENDING before {@code pendingBefore}.
     */
    int expireStale(UUID tenantId, Instant initiatingBefore, Instant pendingBefore, String reason);

    /**
     * Inserts a request.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the company already has one in flight
     */
    void insert(PaymentRequest request);

    /** The provider answered: its reference and the next step; INITIATING becomes PENDING (other states kept). */
    void recordProviderAnswer(UUID id, String providerRef, String providerStatus, NextStep nextStep, String ussdCode);

    /** Next step after an accepted OTP (PENDING only). */
    void recordStep(UUID id, NextStep nextStep, String ussdCode, String providerStatus);

    /**
     * {@code from} (in-flight states only) → FAILED with a French reason, credit released; false when the request was
     * in none of those states.
     */
    boolean markFailed(UUID id, Set<PaymentStatus> from, String reason, String providerStatus);

    /** In flight → CANCELLED (company checked), credit released; false otherwise. */
    boolean cancel(UUID id, UUID tenantId, String reason);

    /**
     * Story 21.3 — a payment received late was added to the account credit instead of being applied: the request keeps
     * its status, the reason tells the owner (never on a CONFIRMED request).
     */
    void markCredited(UUID id, String reason);

    Optional<PaymentRequest> find(UUID id);

    /** The request of this company (another company's id → empty). */
    Optional<PaymentRequest> find(UUID id, UUID tenantId);

    /** The in-flight request of a company, if any. */
    Optional<PaymentRequest> inFlight(UUID tenantId);

    /** The request, locked {@code FOR UPDATE} until the caller's transaction ends. */
    Optional<PaymentRequest> lock(UUID id);

    /** Webhook routing: the request of a provider reference. */
    Optional<PaymentRequest> findByProviderRef(PaymentProvider provider, String providerRef);

    /** Webhook fallback: the request of our own reference ({@code app_transaction_ref}). */
    Optional<PaymentRequest> findByAppRef(String appTransactionRef);

    /** Stores the provider reference of a request that has none yet; false when it already had one. */
    boolean attachProviderRef(UUID id, String providerRef);

    /** Requests of a company created since {@code since} (checkout throttle). */
    int countSince(UUID tenantId, Instant since);

    /** Whether the company already took this free offer (a CONFIRMED 0 FCFA request with this discount). */
    boolean freeOfferTaken(UUID tenantId, UUID discountId);

    /**
     * CONFIRMED with the covered period ({@code null} for a pack), the ledger row ({@code null} when nothing was
     * collected or for a pack) and the instant.
     */
    void confirm(UUID id, UUID tenantPaymentId, PlanPeriod period, Instant at);

    /** The ledger row of a confirmed paid request ({@code tenant_payments}, MOBILE_MONEY, plan and cycle). */
    void insertLedger(UUID tenantPaymentId, PaymentRequest request, PlanPeriod period, Instant paidAt);
}

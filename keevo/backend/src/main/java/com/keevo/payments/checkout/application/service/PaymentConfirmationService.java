package com.keevo.payments.checkout.application.service;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.payments.checkout.domain.event.PlanPaymentConfirmedEvent;
import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.model.PlanPeriod;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.plan.PlanActivatedEvent;
import com.keevo.subscription.api.ChangePlanInput;
import com.keevo.subscription.api.CurrentPlan;
import com.keevo.subscription.api.OfferPricing;
import com.keevo.subscription.api.PlanAdministrationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.1 — confirms a payment in ONE transaction (idempotent consumer): the request row is locked
 * ({@code FOR UPDATE}) and a CONFIRMED request is a no-op, so a replayed webhook or a webhook racing a status poll never
 * pays twice ({@code tenant_payments.payment_request_id} is unique as a second guard). Then: the company's plan is read
 * locked, the covered period computed ({@link PlanPeriod}: renewal from the day after a running paid plan), the ledger
 * row written (MOBILE_MONEY, plan, cycle, the amount frozen at initiation — never the price of the moment), the plan
 * applied through the 9-2 unit of work ({@link PlanAdministrationPort#changePlan}: subscription row, public plan,
 * {@code tenant_plan_events} SELF_SERVICE_PAYMENT, paid-module rows), the discount use counted, the request CONFIRMED;
 * the owner is told after commit (WhatsApp through {@link PlanActivatedEvent}, push through
 * {@link PlanPaymentConfirmedEvent}).
 *
 * <p>Callers verify the payment with the provider BEFORE calling (no HTTP inside this transaction). Cost: constant —
 * 2 locked reads, ≤ 1 ledger INSERT, the plan change, ≤ 1 counter UPDATE, 1 request UPDATE.
 */
@Service
public class PaymentConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmationService.class);

    /** What a confirmation did. */
    public enum Outcome { CONFIRMED, ALREADY_CONFIRMED, NOT_FOUND }

    private final PaymentRequestStore store;
    private final PlanAdministrationPort plans;
    private final OfferPricing pricing;
    private final PlanCatalog catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PaymentConfirmationService(PaymentRequestStore store, PlanAdministrationPort plans, OfferPricing pricing,
                                      PlanCatalog catalog, ApplicationEventPublisher events, Clock clock) {
        this.store = store;
        this.plans = plans;
        this.pricing = pricing;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    /** Confirms a request the provider reported paid (verified by the caller). */
    @Transactional
    public Outcome confirm(UUID requestId) {
        PaymentRequest request = store.lock(requestId).orElse(null);
        if (request == null) {
            return Outcome.NOT_FOUND;
        }
        if (request.status() == PaymentStatus.CONFIRMED) {
            return Outcome.ALREADY_CONFIRMED;
        }
        pricing.recordRedemption(request.discountId(), false); // money received: always counted (soft cap)
        apply(request);
        return Outcome.CONFIRMED;
    }

    /**
     * A free offer (quote 0 FCFA): the request is stored and confirmed in the same transaction, without the provider. A
     * company takes a given free offer once; the use of a capped promo code is taken atomically (nothing is stored when
     * the cap is reached meanwhile).
     *
     * @throws org.springframework.dao.DuplicateKeyException when a request of the company is in flight
     * @throws DomainException 422 when the offer was already taken or the promo code is used up
     */
    @Transactional
    public void confirmFree(PaymentRequest request, Instant initiatingBefore, Instant pendingBefore) {
        if (request.discountId() != null && store.freeOfferTaken(request.tenantId(), request.discountId())) {
            throw PaymentText.invalid("promoCode", PaymentText.FREE_OFFER_TAKEN);
        }
        if (!pricing.recordRedemption(request.discountId(), true)) {
            throw PaymentText.invalid("promoCode", PaymentText.PROMO_USED_UP);
        }
        store.expireStale(request.tenantId(), initiatingBefore, pendingBefore, PaymentText.EXPIRED);
        store.insert(request);
        apply(request);
    }

    private void apply(PaymentRequest r) {
        Instant now = clock.instant();
        CurrentPlan current = plans.currentPlan(r.tenantSchema())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "No subscription for tenant " + r.tenantId()));
        // a renewal (same paid plan) extends the running period; another plan starts today (no unpaid upgrade days)
        boolean renewal = r.planCode().equals(current.planType().code()) && catalog.find(current.planType())
                .map(PlanDefinition::kind).map(k -> k == PlanKind.PAID).orElse(false);
        PlanPeriod period = PlanPeriod.of(renewal, current.expiresAt(), now, r.months());

        UUID ledgerId = null;
        if (!r.free()) {
            ledgerId = UUID.randomUUID();
            store.insertLedger(ledgerId, r, period, now);
        }
        PlanType bought = PlanType.of(r.planCode());
        CurrentPlan previous = plans.changePlan(new ChangePlanInput(r.tenantId(), r.tenantSchema(), bought,
                period.expiresAt(), PlanChangeCause.SELF_SERVICE_PAYMENT, r.requestedBy(), null));
        store.confirm(r.id(), ledgerId, period, now);

        log.info("[PAYMENT] Plan paid online: tenant={} request={} plan={} cycle={} amountXaf={} until={} "
                        + "previousPlan={}", r.tenantId(), r.id(), r.planCode(), r.cycleCode(), r.amountDueXaf(),
                period.end(), previous.planType());
        events.publishEvent(new PlanActivatedEvent(r.requestedBy(), r.tenantId(), r.tenantSchema(),
                previous.planType(), period.expiresAt(), r.amountDueXaf(), now, bought));
        events.publishEvent(new PlanPaymentConfirmedEvent(r.tenantSchema(), r.id(), r.planLabel(),
                period.expiresAt()));
    }
}

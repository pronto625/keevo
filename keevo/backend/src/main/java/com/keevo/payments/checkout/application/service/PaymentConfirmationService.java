package com.keevo.payments.checkout.application.service;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.payments.checkout.domain.event.PaymentNoticeEvent;
import com.keevo.payments.checkout.domain.event.PlanPaymentConfirmedEvent;
import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.model.PlanPeriod;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import com.keevo.payments.credit.domain.model.CreditMovementKind;
import com.keevo.payments.credit.domain.port.out.AccountCreditStore;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.UsageCredits;
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
import org.springframework.transaction.annotation.Propagation;
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
 *
 * <p>Story 21.3 — a request buys a plan or a usage pack (the pack confirmation credits {@code usage_credits} and journals
 * the purchase in one statement, no plan change, no {@code tenant_payments} row). The account credit reserved by a
 * request is spent with it; when the request had ended unpaid (its credit given back) and the provider confirms it
 * later, the credit is taken again — or, when the balance no longer covers it, the money received is added to the
 * credit ({@code PAYMENT_TO_CREDIT}, at most once per request) and nothing is applied: never a negative balance, never
 * a plan or pack paid only in part.
 */
@Service
public class PaymentConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmationService.class);

    /** What a confirmation did. */
    public enum Outcome { CONFIRMED, ALREADY_CONFIRMED, NOT_FOUND, CREDITED }

    private final PaymentRequestStore store;
    private final PlanAdministrationPort plans;
    private final OfferPricing pricing;
    private final PlanCatalog catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final AccountCreditStore credits;
    private final UsageCredits usageCredits;

    public PaymentConfirmationService(PaymentRequestStore store, PlanAdministrationPort plans, OfferPricing pricing,
                                      PlanCatalog catalog, ApplicationEventPublisher events, Clock clock,
                                      AccountCreditStore credits, UsageCredits usageCredits) {
        this.store = store;
        this.plans = plans;
        this.pricing = pricing;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
        this.credits = credits;
        this.usageCredits = usageCredits;
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
        if (request.creditAppliedXaf() > 0 && !PaymentStatus.IN_FLIGHT.contains(request.status())) {
            // the request ended unpaid and its credit went back: take it again, or keep the money as credit
            if (credits.convertedToCredit(request.id())) {
                return Outcome.CREDITED; // a replay of the first delivery: same outcome, nothing written
            }
            if (credits.debit(request.tenantId(), request.creditAppliedXaf(), CreditMovementKind.PAYMENT_USE,
                    request.id(), request.requestedBy(), null).isEmpty()) {
                return toCredit(request);
            }
        }
        pricing.recordRedemption(request.discountId(), false); // money received: always counted (soft cap)
        apply(request);
        return Outcome.CONFIRMED;
    }

    /**
     * Story 21.3 — a price fully covered by the account credit: stored, credit taken and applied in the caller's
     * transaction (which holds the credit row lock since it read the balance), without the provider. A capped promo
     * code is taken within its cap (nothing is stored when it is used up meanwhile).
     *
     * @throws org.springframework.dao.DuplicateKeyException when a request of the company is in flight
     * @throws DomainException 422 when the promo code is used up
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmCovered(PaymentRequest request) {
        if (!pricing.recordRedemption(request.discountId(), true)) {
            throw PaymentText.invalid("promoCode", PaymentText.PROMO_USED_UP);
        }
        store.insert(request);
        reserve(request);
        apply(request);
    }

    /**
     * Story 21.3 — takes the credit applied to a request just inserted ({@code PAYMENT_USE}). The caller's transaction
     * read the balance {@code FOR UPDATE}, so the guarded debit cannot miss.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(PaymentRequest request) {
        if (request.creditAppliedXaf() > 0 && credits.debit(request.tenantId(), request.creditAppliedXaf(),
                CreditMovementKind.PAYMENT_USE, request.id(), request.requestedBy(), null).isEmpty()) {
            throw new IllegalStateException("Credit changed under its row lock");
        }
    }

    private Outcome toCredit(PaymentRequest r) {
        credits.credit(r.tenantId(), r.amountDueXaf(), CreditMovementKind.PAYMENT_TO_CREDIT, r.id(), r.requestedBy(),
                null);
        store.markCredited(r.id(), PaymentText.creditedLate(r.status(), r.amountDueXaf()));
        log.info("[PAYMENT] Late payment added to the account credit: tenant={} request={} amountXaf={}",
                r.tenantId(), r.id(), r.amountDueXaf());
        events.publishEvent(new PaymentNoticeEvent(r.tenantSchema(), r.id(), PaymentNoticeEvent.PAYMENT_CREDITED,
                PaymentText.PAYMENT_RECEIVED, PaymentText.xaf(r.amountDueXaf()) + " ajoutés à votre crédit."));
        return Outcome.CREDITED;
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
        if (r.pack()) {
            long remaining = usageCredits.add(r.tenantId(), r.packMetric(), r.packQuantity(),
                    UsageCredits.Grant.purchase(r.id(), r.requestedBy()));
            store.confirm(r.id(), null, null, now);
            log.info("[PAYMENT] Pack paid online: tenant={} request={} pack={} units={} remaining={} amountXaf={} "
                    + "creditXaf={}", r.tenantId(), r.id(), r.packCode(), r.packQuantity(), remaining,
                    r.amountDueXaf(), r.creditAppliedXaf());
            events.publishEvent(new PaymentNoticeEvent(r.tenantSchema(), r.id(), PaymentNoticeEvent.PACK_CREDITED,
                    PaymentText.PACK_CREDITED, PaymentText.packCredited(r)));
            return;
        }
        CurrentPlan current = plans.currentPlan(r.tenantSchema())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "No subscription for tenant " + r.tenantId()));
        // a renewal (same paid plan) extends the running period; another plan starts today (no unpaid upgrade days)
        boolean renewal = r.planCode().equals(current.planType().code()) && catalog.find(current.planType())
                .map(PlanDefinition::kind).map(k -> k == PlanKind.PAID).orElse(false);
        PlanPeriod period = PlanPeriod.of(renewal, current.expiresAt(), now, r.months());

        UUID ledgerId = null;
        if (!r.free()) { // cash collected only (the credit part is in the credit journal)
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

package com.keevo.payments.checkout.application.service;

import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantView;
import com.keevo.payments.checkout.domain.model.MobileMoneyMethod;
import com.keevo.payments.checkout.domain.model.NextStep;
import com.keevo.payments.checkout.domain.model.PaymentProvider;
import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.AuthorizeResult;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.GatewayException;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.PayinResult;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.ProviderStatus;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.api.OfferPricing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Story 21.1 — the owner's Mobile Money checkout (port of Bref's {@code PaymentGatewayController}), one company at a
 * time:
 * <ol>
 *   <li><b>start</b>: the price is quoted through the offer catalogue ({@link OfferPricing#quote}) and frozen with the
 *       request; a 0 FCFA quote is a free offer confirmed at once. Otherwise, in one short transaction, the stale
 *       in-flight requests of the company are expired and the request inserted INITIATING — the partial unique index
 *       is the lock (a concurrent checkout gets 409 {@code PAYMENT_IN_PROGRESS}). The provider is called AFTER the
 *       commit (no HTTP inside a transaction); its answer moves the request to PENDING with the next step (OTP or
 *       USSD), its failure to FAILED (lock released, 502).</li>
 *   <li><b>authorize</b>: the OTP is sent with the provider reference stored for THIS request (never one from the
 *       client).</li>
 *   <li><b>status</b>: a PENDING request asks the provider (safety net of the webhook, as in Bref).</li>
 *   <li><b>cancel</b>: releases the lock; a payment the provider confirms later is still honoured.</li>
 * </ol>
 * Cost per call: constant (indexed reads/updates, ≤ 1 HTTP call).
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** A Cameroonian mobile number in E.164 (MyCoolPay collects in Cameroon only). */
    static final Pattern CM_MOBILE = Pattern.compile("^\\+2376[0-9]{8}$");
    static final Pattern OTP = Pattern.compile("^[0-9]{4,8}$");
    static final String CURRENCY = "XAF";
    /** A request with a provider reference is re-checked with the provider by the status poll for this long. */
    static final int RECHECK_HOURS = 24;
    /** Prefix of our references sent to the provider ({@code app_transaction_ref}). */
    static final String APP_REF_PREFIX = "KV-";

    /**
     * @param tenantSchema the caller's company ({@code TenantContext})
     * @param actorId      the owner
     * @param method       {@code ORANGE_MONEY | MTN_MOMO}; not required for a free offer
     * @param phoneNumber  number charged; not required for a free offer
     */
    public record StartCommand(String tenantSchema, UUID actorId, String planCode, String cycleCode, String promoCode,
                               String method, String phoneNumber) {
    }

    private final OfferPricing pricing;
    private final TenantQueryPort tenants;
    private final PaymentRequestStore store;
    private final MobileMoneyGateway gateway;
    private final PaymentConfirmationService confirmation;
    private final PaymentSettlementService settlement;
    private final PaymentSettings settings;
    private final TransactionOperations tx;
    private final Clock clock;

    public CheckoutService(OfferPricing pricing, TenantQueryPort tenants, PaymentRequestStore store,
                           MobileMoneyGateway gateway, PaymentConfirmationService confirmation,
                           PaymentSettlementService settlement, PaymentSettings settings, TransactionOperations tx,
                           Clock clock) {
        this.pricing = pricing;
        this.tenants = tenants;
        this.store = store;
        this.gateway = gateway;
        this.confirmation = confirmation;
        this.settlement = settlement;
        this.settings = settings;
        this.tx = tx;
        this.clock = clock;
    }

    public PaymentView start(StartCommand cmd) {
        TenantView tenant = company(cmd.tenantSchema());
        Instant now = clock.instant();
        if (store.countSince(tenant.id(), now.minus(Duration.ofHours(1))) >= settings.maxCheckoutsPerHour()) {
            throw DomainException.userFacing(ErrorCode.RATE_LIMIT_EXCEEDED, PaymentText.TOO_MANY_CHECKOUTS);
        }
        OfferPricing.Quote quote = pricing.quote(tenant.id(), cmd.planCode(), cmd.cycleCode(), cmd.promoCode());
        Instant initiatingBefore = now.minus(settings.initiatingTimeout());
        Instant pendingBefore = now.minus(settings.pendingTimeout());
        UUID id = UUID.randomUUID();

        if (quote.effectiveXaf() == 0) { // free offer: no provider, confirmed at once
            PaymentRequest free = request(id, tenant, cmd.actorId(), quote, cmd.promoCode(), PaymentProvider.NONE,
                    null, null, now);
            try {
                confirmation.confirmFree(free, initiatingBefore, pendingBefore);
            } catch (DuplicateKeyException e) {
                throw inProgress();
            }
            log.info("Free offer taken: tenant={} request={} plan={}", tenant.id(), id, quote.planCode());
            return view(id, tenant.id());
        }

        if (!gateway.available()) {
            throw DomainException.userFacing(ErrorCode.PAYMENT_UNAVAILABLE,
                    "Le paiement Mobile Money est momentanément indisponible");
        }
        MobileMoneyMethod method = MobileMoneyMethod.parse(cmd.method())
                .orElseThrow(() -> PaymentText.invalid("method", PaymentText.METHOD_REQUIRED));
        String phone = phone(cmd.phoneNumber());
        PaymentRequest request = request(id, tenant, cmd.actorId(), quote, cmd.promoCode(), gateway.provider(),
                method, phone, now);
        try {
            tx.executeWithoutResult(status -> {
                store.expireStale(tenant.id(), initiatingBefore, pendingBefore, PaymentText.EXPIRED);
                store.insert(request);
            });
        } catch (DuplicateKeyException e) {
            throw inProgress();
        }

        PayinResult answer;
        try {
            answer = gateway.initiate(new MobileMoneyGateway.Payin(request.amountDueXaf(), CURRENCY, phone, method,
                    request.appTransactionRef(), "Abonnement " + quote.planLabel() + " — " + quote.cycleLabel()));
        } catch (GatewayException | IllegalStateException e) {
            store.markFailed(id, PaymentStatus.IN_FLIGHT, PaymentText.START_FAILED, null);
            throw DomainException.userFacing(ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Le paiement n'a pas pu démarrer. Vérifiez le numéro et réessayez.");
        }
        NextStep step = answer.status() == ProviderStatus.REQUIRE_OTP ? NextStep.OTP : NextStep.USSD;
        store.recordProviderAnswer(id, answer.providerRef(), answer.status().name(), step, answer.ussdCode());
        log.info("Checkout started: tenant={} request={} plan={} cycle={} amountXaf={} step={}", tenant.id(), id,
                quote.planCode(), quote.cycleCode(), request.amountDueXaf(), step);
        return view(id, tenant.id());
    }

    /** Sends the OTP of a PENDING request waiting for one. */
    public PaymentView authorize(String tenantSchema, UUID id, String code) {
        TenantView tenant = company(tenantSchema);
        PaymentRequest r = owned(id, tenant.id());
        if (r.status() != PaymentStatus.PENDING || r.nextStep() != NextStep.OTP || r.providerTransactionRef() == null
                || r.provider() != gateway.provider()) {
            throw PaymentText.conflict(PaymentText.NO_CODE_EXPECTED);
        }
        String otp = code == null ? "" : code.trim();
        if (!OTP.matcher(otp).matches()) {
            throw PaymentText.invalid("code", PaymentText.CODE_INVALID);
        }
        AuthorizeResult result;
        try {
            result = gateway.authorize(r.providerTransactionRef(), otp);
        } catch (GatewayException e) {
            throw DomainException.userFacing(ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "La validation du code a échoué. Réessayez.");
        }
        if (result.accepted()) {
            store.recordStep(id, NextStep.USSD, result.ussdCode(), ProviderStatus.PENDING.name());
        } else {
            store.markFailed(id, PaymentStatus.IN_FLIGHT, PaymentText.CODE_REFUSED, ProviderStatus.FAILED.name());
        }
        return view(id, tenant.id());
    }

    /**
     * The request, refreshed from the provider while it is not confirmed and holds a provider reference less than
     * {@value #RECHECK_HOURS} h old (a lost webhook, an expired or cancelled request paid late: money received is
     * applied).
     */
    public PaymentView status(String tenantSchema, UUID id) {
        TenantView tenant = company(tenantSchema);
        PaymentRequest r = owned(id, tenant.id());
        if (r.status() != PaymentStatus.CONFIRMED && r.providerTransactionRef() != null
                && r.createdAt().isAfter(clock.instant().minus(Duration.ofHours(RECHECK_HOURS)))) {
            settlement.settle(r);
        }
        return view(id, tenant.id());
    }

    public PaymentView cancel(String tenantSchema, UUID id) {
        TenantView tenant = company(tenantSchema);
        owned(id, tenant.id());
        if (!store.cancel(id, tenant.id(), PaymentText.CANCELLED)) {
            throw PaymentText.conflict(PaymentText.NOT_CANCELLABLE);
        }
        return view(id, tenant.id());
    }

    /** The in-flight request of the company, {@code null} when none (stale ones are expired first). */
    public PaymentView current(String tenantSchema) {
        TenantView tenant = company(tenantSchema);
        Instant now = clock.instant();
        store.expireStale(tenant.id(), now.minus(settings.initiatingTimeout()), now.minus(settings.pendingTimeout()),
                PaymentText.EXPIRED);
        return store.inFlight(tenant.id()).map(PaymentView::of).orElse(null);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TenantView company(String tenantSchema) {
        return (tenantSchema == null ? java.util.Optional.<TenantView>empty() : tenants.findBySchemaName(tenantSchema))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.TENANT_NOT_FOUND,
                        PaymentText.COMPANY_NOT_FOUND));
    }

    private PaymentRequest owned(UUID id, UUID tenantId) {
        return (id == null ? java.util.Optional.<PaymentRequest>empty() : store.find(id, tenantId))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.PAYMENT_NOT_FOUND, "Paiement introuvable"));
    }

    private PaymentView view(UUID id, UUID tenantId) {
        return PaymentView.of(owned(id, tenantId));
    }

    private static String phone(String raw) {
        String e164 = raw == null || raw.isBlank() ? null : PhoneNumbers.toE164(raw, PhoneNumbers.CAMEROON);
        if (e164 == null || !CM_MOBILE.matcher(e164).matches()) {
            throw PaymentText.invalid("phoneNumber", PaymentText.PHONE_INVALID);
        }
        return e164;
    }

    private static DomainException inProgress() {
        return DomainException.userFacing(ErrorCode.PAYMENT_IN_PROGRESS,
                "Un paiement est déjà en cours. Terminez-le ou annulez-le.", Map.of());
    }

    private static PaymentRequest request(UUID id, TenantView tenant, UUID actorId, OfferPricing.Quote q,
                                          String promoCode, PaymentProvider provider, MobileMoneyMethod method, String phone,
                                          Instant now) {
        OfferPricing.AppliedDiscount d = q.discount();
        boolean free = q.effectiveXaf() == 0;
        return new PaymentRequest(id, tenant.id(), tenant.schemaName(), actorId, provider, method, phone,
                q.planCode(), q.planLabel(), q.cycleCode(), q.cycleLabel(), q.months(), q.baseXaf(),
                q.reductionXaf(), q.effectiveXaf(), CURRENCY, q.discountId(), d == null ? null : d.label(),
                d != null && "PROMO_CODE".equals(d.scope()) && promoCode != null
                        ? promoCode.trim().toUpperCase(java.util.Locale.ROOT) : null, q.pricedAt(),
                PaymentStatus.INITIATING, free ? NextStep.NONE : null, null,
                APP_REF_PREFIX + id.toString().replace("-", ""), null, null, null, null, null, null, null, now);
    }
}

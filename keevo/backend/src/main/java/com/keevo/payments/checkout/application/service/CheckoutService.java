package com.keevo.payments.checkout.application.service;

import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantView;
import com.keevo.payments.checkout.domain.model.MobileMoneyMethod;
import com.keevo.payments.checkout.domain.model.NextStep;
import com.keevo.payments.checkout.domain.model.PaymentKind;
import com.keevo.payments.checkout.domain.model.PaymentProvider;
import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.AuthorizeResult;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.GatewayException;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.PayinResult;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.ProviderStatus;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import com.keevo.payments.credit.domain.port.out.AccountCreditStore;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.api.OfferPricing;
import com.keevo.subscription.api.UsagePacks;
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
 *
 * <p>Story 21.3 — the same path buys a usage pack ({@code packCode}, catalogue price frozen on the request, no
 * discount) and may apply the account credit first ({@code useCredit}): in the insert transaction the balance is read
 * {@code FOR UPDATE} (after the stale requests released theirs), {@code min(balance, price)} is reserved with the
 * request and the provider is asked for the rest; a price fully covered is confirmed at once without the provider.
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
     * @param planCode     plan bought (with {@code cycleCode}); {@code null} when a pack is bought
     * @param packCode     Story 21.3 — usage pack bought; {@code null} when a plan is bought
     * @param method       {@code ORANGE_MONEY | MTN_MOMO}; not required when nothing is left to collect
     * @param phoneNumber  number charged; not required when nothing is left to collect
     * @param useCredit    Story 21.3 — apply the account credit first ({@code null} = no)
     */
    public record StartCommand(String tenantSchema, UUID actorId, String planCode, String cycleCode, String promoCode,
                               String method, String phoneNumber, String packCode, Boolean useCredit) {

        /** A plan checkout without credit (Story 21.1 callers). */
        public StartCommand(String tenantSchema, UUID actorId, String planCode, String cycleCode, String promoCode,
                            String method, String phoneNumber) {
            this(tenantSchema, actorId, planCode, cycleCode, promoCode, method, phoneNumber, null, null);
        }
    }

    private final OfferPricing pricing;
    private final UsagePacks packs;
    private final TenantQueryPort tenants;
    private final PaymentRequestStore store;
    private final AccountCreditStore credits;
    private final MobileMoneyGateway gateway;
    private final PaymentConfirmationService confirmation;
    private final PaymentSettlementService settlement;
    private final PaymentSettings settings;
    private final TransactionOperations tx;
    private final Clock clock;
    /** Story 21.3 — the plan's quotas (a pack is sold only when the plan limits its metric); null in unit tests. */
    private final UsageQuotaPort quotas;

    public CheckoutService(OfferPricing pricing, UsagePacks packs, TenantQueryPort tenants, PaymentRequestStore store,
                           AccountCreditStore credits, MobileMoneyGateway gateway,
                           PaymentConfirmationService confirmation, PaymentSettlementService settlement,
                           PaymentSettings settings, TransactionOperations tx, Clock clock, UsageQuotaPort quotas) {
        this.quotas = quotas;
        this.pricing = pricing;
        this.packs = packs;
        this.tenants = tenants;
        this.store = store;
        this.credits = credits;
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
        boolean pack = cmd.packCode() != null && !cmd.packCode().isBlank();
        if (pack && cmd.planCode() != null && !cmd.planCode().isBlank()) {
            throw PaymentText.invalid("packCode", PaymentText.PLAN_OR_PACK);
        }
        Instant initiatingBefore = now.minus(settings.initiatingTimeout());
        Instant pendingBefore = now.minus(settings.pendingTimeout());
        UUID id = UUID.randomUUID();
        PaymentRequest priced = pack ? packRequest(id, tenant, cmd, now)
                : planRequest(id, tenant, cmd, pricing.quote(tenant.id(), cmd.planCode(), cmd.cycleCode(),
                        cmd.promoCode()), now);

        if (priced.freeOffer()) { // free offer: no provider, no credit, confirmed at once
            PaymentRequest free = withPayment(priced, 0, PaymentProvider.NONE, null, null);
            try {
                confirmation.confirmFree(free, initiatingBefore, pendingBefore);
            } catch (DuplicateKeyException e) {
                throw inProgress();
            }
            log.info("Free offer taken: tenant={} request={} plan={}", tenant.id(), id, free.planCode());
            return view(id, tenant.id());
        }

        boolean useCredit = Boolean.TRUE.equals(cmd.useCredit());
        if (!useCredit && !gateway.available()) {
            throw unavailable();
        }
        // method and phone are checked before any write when given (or when the credit cannot cover everything)
        MobileMoneyMethod method = cmd.method() == null || cmd.method().isBlank() ? null
                : MobileMoneyMethod.parse(cmd.method())
                .orElseThrow(() -> PaymentText.invalid("method", PaymentText.METHOD_REQUIRED));
        String phone = cmd.phoneNumber() == null || cmd.phoneNumber().isBlank() ? null : phone(cmd.phoneNumber());
        if (!useCredit) {
            requireProvider(method, phone);
        }
        PaymentRequest request;
        try {
            request = tx.execute(status -> {
                store.expireStale(tenant.id(), initiatingBefore, pendingBefore, PaymentText.EXPIRED); // releases credit
                long price = priced.baseXaf() - priced.reductionXaf();
                long credit = useCredit ? Math.min(credits.lockBalance(tenant.id()), price) : 0L;
                if (credit == price) { // fully covered by the account credit: confirmed at once, no provider
                    PaymentRequest covered = withPayment(priced, credit, PaymentProvider.NONE, null, null);
                    confirmation.confirmCovered(covered);
                    return covered;
                }
                requireProvider(method, phone);
                PaymentRequest paid = withPayment(priced, credit, gateway.provider(), method, phone);
                store.insert(paid);
                confirmation.reserve(paid);
                return paid;
            });
        } catch (DuplicateKeyException e) {
            throw inProgress();
        }
        if (request.free()) {
            log.info("Checkout covered by the account credit: tenant={} request={} item={} creditXaf={}",
                    tenant.id(), id, request.pack() ? request.packCode() : request.planCode(),
                    request.creditAppliedXaf());
            return view(id, tenant.id());
        }

        PayinResult answer;
        try {
            answer = gateway.initiate(new MobileMoneyGateway.Payin(request.amountDueXaf(), CURRENCY, phone, method,
                    request.appTransactionRef(), payinReason(request)));
        } catch (GatewayException | IllegalStateException e) {
            store.markFailed(id, PaymentStatus.IN_FLIGHT, PaymentText.START_FAILED, null); // releases the credit
            throw DomainException.userFacing(ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Le paiement n'a pas pu démarrer. Vérifiez le numéro et réessayez.");
        }
        NextStep step = answer.status() == ProviderStatus.REQUIRE_OTP ? NextStep.OTP : NextStep.USSD;
        store.recordProviderAnswer(id, answer.providerRef(), answer.status().name(), step, answer.ussdCode());
        log.info("Checkout started: tenant={} request={} kind={} item={} amountXaf={} creditXaf={} step={}",
                tenant.id(), id, request.kind(), request.pack() ? request.packCode() : request.planCode() + "/"
                        + request.cycleCode(), request.amountDueXaf(), request.creditAppliedXaf(), step);
        return view(id, tenant.id());
    }

    /** Story 21.3 — a late payment already added to the credit: nothing more to ask the provider. */
    private boolean settledToCredit(PaymentRequest r) {
        return r.creditAppliedXaf() > 0 && !PaymentStatus.IN_FLIGHT.contains(r.status())
                && credits.convertedToCredit(r.id());
    }

    /** The provider must collect something: gateway on, method and phone given. */
    private void requireProvider(MobileMoneyMethod method, String phone) {
        if (!gateway.available()) {
            throw unavailable();
        }
        if (method == null) {
            throw PaymentText.invalid("method", PaymentText.METHOD_REQUIRED);
        }
        if (phone == null) {
            throw PaymentText.invalid("phoneNumber", PaymentText.PHONE_INVALID);
        }
    }

    private static DomainException unavailable() {
        return DomainException.userFacing(ErrorCode.PAYMENT_UNAVAILABLE,
                "Le paiement Mobile Money est momentanément indisponible");
    }

    private static String payinReason(PaymentRequest r) {
        return r.pack() ? r.packLabel() : "Abonnement " + r.planLabel() + " — " + r.cycleLabel();
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
                && r.createdAt().isAfter(clock.instant().minus(Duration.ofHours(RECHECK_HOURS)))
                && !settledToCredit(r)) {
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

    /** A plan request priced by the catalogue (provider, credit, method and phone set later). */
    private static PaymentRequest planRequest(UUID id, TenantView tenant, StartCommand cmd, OfferPricing.Quote q,
                                              Instant now) {
        OfferPricing.AppliedDiscount d = q.discount();
        String promoCode = cmd.promoCode();
        return new PaymentRequest(id, tenant.id(), tenant.schemaName(), cmd.actorId(), PaymentProvider.NONE, null,
                null, q.planCode(), q.planLabel(), q.cycleCode(), q.cycleLabel(), q.months(), q.baseXaf(),
                q.reductionXaf(), q.effectiveXaf(), CURRENCY, q.discountId(), d == null ? null : d.label(),
                d != null && "PROMO_CODE".equals(d.scope()) && promoCode != null
                        ? promoCode.trim().toUpperCase(java.util.Locale.ROOT) : null, q.pricedAt(),
                PaymentStatus.INITIATING, null, null, APP_REF_PREFIX + id.toString().replace("-", ""), null, null,
                null, null, null, null, null, now, PaymentKind.PLAN, 0L, null, null, null, 0);
    }

    /** Story 21.3 — a pack request at the catalogue price (no discount, no promo code). */
    private PaymentRequest packRequest(UUID id, TenantView tenant, StartCommand cmd, Instant now) {
        if (cmd.promoCode() != null && !cmd.promoCode().isBlank()) {
            throw PaymentText.invalid("promoCode", PaymentText.PACK_NO_PROMO);
        }
        UsagePacks.UsagePackOffer p = packs.offered(cmd.packCode())
                .orElseThrow(() -> PaymentText.invalid("packCode", PaymentText.PACK_NOT_OFFERED));
        UsageMetric metric = UsageMetric.valueOf(p.metric());
        if (quotas != null && !packUsable(tenant.schemaName(), metric)) {
            // packs pay only once a MONTH or LIFETIME quota is used up: without one the units would never be used
            throw PaymentText.invalid("packCode", PaymentText.PACK_USELESS);
        }
        return new PaymentRequest(id, tenant.id(), tenant.schemaName(), cmd.actorId(), PaymentProvider.NONE, null,
                null, null, null, null, null, 0, p.priceXaf(), 0L, p.priceXaf(), CURRENCY, null, null, null, now,
                PaymentStatus.INITIATING, null, null, APP_REF_PREFIX + id.toString().replace("-", ""), null, null,
                null, null, null, null, null, now, PaymentKind.USAGE_PACK, 0L, p.code(), p.label(),
                metric, p.quantity());
    }

    /** Whether the company's plan limits this metric by month or for life (one status read). */
    private boolean packUsable(String tenantSchema, UsageMetric metric) {
        return quotas.statusForSchema(tenantSchema).quotas().stream()
                .anyMatch(q -> q.metric() == metric && q.limit() != null && q.period() != QuotaPeriod.DAY);
    }

    /** The priced request with the credit applied, the provider and the number charged. */
    private static PaymentRequest withPayment(PaymentRequest r, long credit, PaymentProvider provider,
                                              MobileMoneyMethod method, String phone) {
        long due = r.baseXaf() - r.reductionXaf() - credit;
        return new PaymentRequest(r.id(), r.tenantId(), r.tenantSchema(), r.requestedBy(), provider, method, phone,
                r.planCode(), r.planLabel(), r.cycleCode(), r.cycleLabel(), r.months(), r.baseXaf(), r.reductionXaf(),
                due, r.currency(), r.discountId(), r.discountLabel(), r.promoCode(), r.pricedAt(), r.status(),
                due == 0 ? NextStep.NONE : null, null, r.appTransactionRef(), null, null, null, null, null, null,
                null, r.createdAt(), r.kind(), credit, r.packCode(), r.packLabel(), r.packMetric(), r.packQuantity());
    }
}

package com.keevo.payments.appstore.application.service;

import com.keevo.identity.api.OwnedTenantRef;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TenantView;
import com.keevo.identity.api.UserQueryPort;
import com.keevo.payments.appstore.domain.event.AppStoreSubscriptionNotice;
import com.keevo.payments.appstore.domain.event.AppStoreSubscriptionNotice.Kind;
import com.keevo.payments.appstore.domain.model.AppStoreEvent;
import com.keevo.payments.appstore.domain.model.EventOutcome;
import com.keevo.payments.appstore.domain.model.ExternalSubscription;
import com.keevo.payments.appstore.domain.model.SubscriptionStatus;
import com.keevo.payments.appstore.domain.model.XafAmount;
import com.keevo.payments.appstore.domain.port.out.ExternalSubscriptionStore;
import com.keevo.payments.appstore.domain.port.out.ExternalSubscriptionStore.PendingPurchase;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient.RevenueCatUnavailableException;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient.StoreSubscription;
import com.keevo.payments.checkout.domain.model.PaidPlanPeriod;
import com.keevo.payments.checkout.domain.port.out.PaidPlanPeriods;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.api.ChangePlanInput;
import com.keevo.subscription.api.CurrentPlan;
import com.keevo.subscription.api.PlanAdministrationPort;
import com.keevo.subscription.api.StoreProducts;
import com.keevo.subscription.api.StoreProducts.StoreProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.keevo.shared.domain.time.KeevoZones.WAT;

/**
 * Story 21.2 — App Store subscriptions (port of Bref's {@code StoreKitService}): a RevenueCat event is processed in ONE
 * transaction — claimed by its id (idempotent consumer: a replay or a concurrent duplicate finds the row), the
 * subscription locked and ordered by the event timestamp (stale events dropped), then dispatched by type; the plan
 * follows through the 9-2 unit of work ({@link PlanAdministrationPort#changePlan}), exactly like the Mobile Money
 * payments of 21.1.
 *
 * <p>Grant: the plan of the product until Apple's expiry (grace end during a billing issue), unless the same plan
 * already runs at least as long (no duplicate plan event). Revocation (expiration, refund, transfer): only when the
 * company's plan is still the one this subscription granted (never shortening a later Mobile Money payment), the company
 * goes back to the period of its latest confirmed Mobile Money plan payment while that period still runs (correctif
 * post-test appareil du 10/11/2026: the Mobile Money days are never lost), else to the free plan. Cost per event:
 * constant (see the spec's Design Notes; one indexed read of the payments on a revocation); no loop over tenants.
 */
@Service
public class AppStoreSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AppStoreSubscriptionService.class);

    static final Set<String> STATE_TYPES = Set.of("INITIAL_PURCHASE", "RENEWAL", "UNCANCELLATION",
            "SUBSCRIPTION_EXTENDED", "PRODUCT_CHANGE", "CANCELLATION", "EXPIRATION", "BILLING_ISSUE",
            "REFUND_REVERSED");
    /** Tolerance of the « still the grant of this subscription » check (clock skew between writes). */
    static final Duration GRANT_TOLERANCE = Duration.ofMinutes(1);
    /** Highest ledger amount accepted (the catalogue's price cap). */
    static final long MAX_LEDGER_XAF = 100_000_000L;
    /** Highest store price kept as an observation (NUMERIC(14, 2) and sanity). */
    static final BigDecimal MAX_OBSERVED_PRICE = new BigDecimal("1000000000");

    /** Result of a sync. */
    public enum SyncOutcome { GRANTED, NOTHING_ACTIVE, NOT_OFFERED, BOUND_ELSEWHERE }

    /** A product the owner can buy in the app (StoreKit identifier and the plan it grants). */
    public record ProductView(String productId, String planCode, String planLabel, String cycleCode,
                              String cycleLabel, int months) {
    }

    private final ExternalSubscriptionStore store;
    private final StoreProducts products;
    private final PlanAdministrationPort plans;
    private final PlanCatalog catalog;
    private final PaidPlanPeriods paidPeriods;
    private final TenantQueryPort tenants;
    private final UserQueryPort users;
    private final RevenueCatClient revenueCat;
    private final AppStoreSettings settings;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AppStoreSubscriptionService(ExternalSubscriptionStore store, StoreProducts products,
                                       PlanAdministrationPort plans, PlanCatalog catalog, PaidPlanPeriods paidPeriods,
                                       TenantQueryPort tenants, UserQueryPort users, RevenueCatClient revenueCat,
                                       AppStoreSettings settings, ApplicationEventPublisher events,
                                       PlatformTransactionManager txManager, Clock clock) {
        this.store = store;
        this.products = products;
        this.plans = plans;
        this.catalog = catalog;
        this.paidPeriods = paidPeriods;
        this.tenants = tenants;
        this.users = users;
        this.revenueCat = revenueCat;
        this.settings = settings;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ── webhook ───────────────────────────────────────────────────────────────

    /**
     * Processes one event in one transaction. {@code transfers} holds the RevenueCat subscriptions of every customer of
     * a TRANSFER event, read before the transaction (empty for other types).
     */
    @Transactional
    public EventOutcome process(AppStoreEvent e, Map<UUID, List<StoreSubscription>> transfers) {
        if (!store.claimEvent(e)) {
            return EventOutcome.DUPLICATE;
        }
        UUID[] tenant = new UUID[1];
        EventOutcome outcome;
        if ("TEST".equals(e.type())) {
            outcome = EventOutcome.TEST;
        } else if ("TRANSFER".equals(e.type())) {
            outcome = transfer(e, transfers);
        } else if (!STATE_TYPES.contains(e.type()) || e.originalTransactionId() == null) {
            outcome = EventOutcome.IGNORED_TYPE;
        } else if (!AppStoreEvent.APP_STORE.equals(e.store())) {
            outcome = EventOutcome.IGNORED_STORE;
        } else {
            outcome = stateEvent(e, tenant);
        }
        store.finishEvent(e.id(), tenant[0], outcome);
        log.info("[AppStore] Event processed: type={} outcome={} tenant={}", e.type(), outcome, tenant[0]);
        return outcome;
    }

    private EventOutcome stateEvent(AppStoreEvent e, UUID[] tenantOut) {
        Optional<UUID> user = e.appUser();
        Optional<ExternalSubscription> existing = store.lock(e.originalTransactionId());
        if (existing.isEmpty() && user.isEmpty()) {
            return EventOutcome.UNKNOWN_USER;
        }
        existing.ifPresent(s -> tenantOut[0] = s.tenantId());
        if (existing.isPresent() && e.eventTimestampMs() > 0 && e.eventTimestampMs() < existing.get().lastEventMs()) {
            return EventOutcome.STALE;
        }
        UUID buyer = user.orElse(existing.map(ExternalSubscription::appUserId).orElse(null));
        if (e.sandbox() && !sandboxAllowed(buyer)) {
            return EventOutcome.IGNORED_SANDBOX;
        }
        Optional<StoreProduct> product = e.productId() == null ? Optional.empty()
                : products.find(StoreProducts.APP_STORE, e.productId());
        if (product.isEmpty()) {
            log.warn("[AppStore] Product not mapped in the offer catalogue (event {})", e.type());
            return EventOutcome.UNKNOWN_PRODUCT;
        }
        ExternalSubscription sub;
        if (existing.isPresent()) {
            sub = existing.get();
        } else {
            Resolution resolution = resolve(user.get(), e.tenantCode());
            if (resolution.failure() != null) {
                return resolution.failure();
            }
            sub = store.lockOrCreate(newSubscription(resolution.tenant(), user.get(), e.originalTransactionId(),
                    product.get(), e.sandbox(), e.expiresAt()));
            tenantOut[0] = sub.tenantId();
        }
        sub = dispatch(e, sub.withSandbox(e.sandbox()), product.get()).withLastEvent(e.eventTimestampMs());
        store.update(sub);
        replayPending(sub, clock.instant());
        return EventOutcome.APPLIED;
    }

    private ExternalSubscription dispatch(AppStoreEvent e, ExternalSubscription sub, StoreProduct product) {
        Instant now = clock.instant();
        switch (e.type()) {
            case "INITIAL_PURCHASE", "RENEWAL", "UNCANCELLATION", "SUBSCRIPTION_EXTENDED", "REFUND_REVERSED" -> {
                boolean autoRenew = !"SUBSCRIPTION_EXTENDED".equals(e.type()) || sub.autoRenew();
                ExternalSubscription s = sub.withProduct(product.productId(), product.planCode(), product.cycleCode())
                        .withState(SubscriptionStatus.ACTIVE, autoRenew, e.expiresAt(), null);
                Instant grantedBefore = s.grantedExpiresAt();
                s = grant(s, e.expiresAt(), now);
                if ("INITIAL_PURCHASE".equals(e.type()) || "RENEWAL".equals(e.type())) {
                    ledger(s, product, e.transactionId(), e.priceInPurchasedCurrency(), e.currency(),
                            e.periodType(), e.purchasedAt(), e.expiresAt(), now);
                    if (s.grantedExpiresAt() != null && !s.grantedExpiresAt().equals(grantedBefore)) {
                        // a purchase the owner's sync already applied is not announced twice
                        notice(s, e.id(), Kind.ACTIVE, product.planLabel(), e.expiresAt());
                    }
                } else if ("REFUND_REVERSED".equals(e.type())) {
                    if (e.transactionId() != null) {
                        store.unvoidLedger(e.transactionId(), AppStoreText.REFUND_REASON);
                    }
                    notice(s, e.id(), Kind.ACTIVE, product.planLabel(), e.expiresAt());
                } else if ("UNCANCELLATION".equals(e.type())) {
                    notice(s, e.id(), Kind.RENEWAL_RESUMED, product.planLabel(), e.expiresAt());
                }
                return s;
            }
            case "CANCELLATION" -> {
                if (e.refund()) {
                    ExternalSubscription s = sub.withState(SubscriptionStatus.REVOKED, false, e.expiresAt(), null);
                    revoke(s, AppStoreText.REFUND_PLAN_REASON);
                    if (e.transactionId() != null) {
                        store.voidLedger(e.transactionId(), AppStoreText.REFUND_REASON, now);
                    }
                    notice(s, e.id(), Kind.REFUNDED, planLabel(s.planCode()), null);
                    return s;
                }
                Instant expires = e.expiresAt() != null ? e.expiresAt() : sub.expiresAt();
                SubscriptionStatus status = sub.status() == SubscriptionStatus.BILLING_ISSUE
                        ? SubscriptionStatus.BILLING_ISSUE : SubscriptionStatus.CANCELLED;
                ExternalSubscription s = sub.withState(status, false, expires, sub.graceExpiresAt());
                notice(s, e.id(), Kind.CANCELLED, planLabel(s.planCode()), expires);
                return s;
            }
            case "BILLING_ISSUE" -> {
                Instant grace = e.graceExpiresAt();
                ExternalSubscription s = sub.withState(SubscriptionStatus.BILLING_ISSUE, sub.autoRenew(),
                        e.expiresAt() != null ? e.expiresAt() : sub.expiresAt(), grace);
                if (grace != null) {
                    s = grant(s, grace, now); // Apple's grace period: access kept until its end
                }
                notice(s, e.id(), Kind.BILLING_ISSUE, planLabel(s.planCode()), grace);
                return s;
            }
            case "EXPIRATION" -> {
                ExternalSubscription s = sub.withState(SubscriptionStatus.EXPIRED, false,
                        e.expiresAt() != null ? e.expiresAt() : sub.expiresAt(), null);
                revoke(s, AppStoreText.EXPIRY_REASON);
                notice(s, e.id(), Kind.EXPIRED, planLabel(s.planCode()), null);
                return s;
            }
            default -> { // PRODUCT_CHANGE: informational, the next RENEWAL carries the new product
                return sub;
            }
        }
    }

    /** TRANSFER: the subscriptions moved from the old customers to the new ones, read from RevenueCat. */
    private EventOutcome transfer(AppStoreEvent e, Map<UUID, List<StoreSubscription>> transfers) {
        Set<UUID> to = uuids(e.transferredTo());
        Set<UUID> from = uuids(e.transferredFrom());
        from.removeAll(to);
        Instant now = clock.instant();
        Set<String> movedIn = new LinkedHashSet<>(); // what the new customers now hold: never kept by an old one
        for (UUID newUser : to) {
            for (StoreSubscription s : transfers.getOrDefault(newUser, List.of())) {
                if (s.givesAccess()) {
                    movedIn.add(s.originalTransactionId());
                }
            }
        }
        for (UUID oldUser : from) {
            Set<String> stillActive = new LinkedHashSet<>();
            for (StoreSubscription s : transfers.getOrDefault(oldUser, List.of())) {
                if (s.givesAccess() && !movedIn.contains(s.originalTransactionId())) {
                    stillActive.add(s.originalTransactionId());
                }
            }
            for (ExternalSubscription row : store.lockLiveByAppUser(oldUser)) {
                if (stillActive.contains(row.originalTransactionId())) {
                    continue;
                }
                ExternalSubscription moved = row.withState(SubscriptionStatus.TRANSFERRED, false, row.expiresAt(),
                        null);
                revoke(moved, AppStoreText.TRANSFER_REASON);
                store.update(moved.withLastEvent(e.eventTimestampMs()));
                notice(moved, e.id(), Kind.TRANSFERRED, planLabel(moved.planCode()), null);
            }
        }
        for (UUID newUser : to) {
            boolean sandboxOk = sandboxAllowed(newUser);
            List<StoreSubscription> active = transfers.getOrDefault(newUser, List.of()).stream()
                    .filter(s -> s.givesAccess() && AppStoreEvent.APP_STORE.equals(s.store()))
                    .filter(s -> !s.sandbox() || sandboxOk)
                    .toList();
            if (active.isEmpty()) {
                continue;
            }
            Resolution resolution = resolve(newUser, null);
            if (resolution.failure() != null) {
                log.info("[AppStore] Transfer to a customer without a single company: {}", resolution.failure());
                continue;
            }
            for (StoreSubscription s : active) {
                apply(newUser, resolution.tenant(), s, true, e.id(), e.eventTimestampMs(), now);
            }
        }
        return EventOutcome.APPLIED;
    }

    // ── owner ─────────────────────────────────────────────────────────────────

    /**
     * The owner asks Keevo to read their App Store purchases (after a purchase or a restore): RevenueCat is read for the
     * user (outside any transaction), then every active App Store subscription mapped to an offer is bound to this
     * company and granted. Grants only: revocations come from the webhook.
     *
     * @throws DomainException 503 PAYMENT_UNAVAILABLE (no keys), 502 PAYMENT_PROVIDER_ERROR (RevenueCat error), 404
     *                         PAYMENT_NOT_FOUND (nothing active), 409 INVALID_STATE (not offered / bound elsewhere)
     */
    public AppStoreSubscriptionView sync(String schema, UUID userId) {
        TenantView tenant = tenants.findBySchemaName(schema)
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.TENANT_NOT_FOUND,
                        AppStoreText.COMPANY_NOT_FOUND));
        if (!revenueCat.enabled()) {
            throw DomainException.userFacing(ErrorCode.PAYMENT_UNAVAILABLE, AppStoreText.UNAVAILABLE);
        }
        List<StoreSubscription> all;
        try {
            all = revenueCat.subscriptions(userId.toString());
        } catch (RevenueCatUnavailableException e) {
            throw e.disabled() ? DomainException.userFacing(ErrorCode.PAYMENT_UNAVAILABLE, AppStoreText.UNAVAILABLE)
                    : DomainException.userFacing(ErrorCode.PAYMENT_PROVIDER_ERROR, AppStoreText.PROVIDER_FAILED);
        }
        boolean sandboxOk = all.stream().anyMatch(StoreSubscription::sandbox) && sandboxAllowed(userId);
        List<StoreSubscription> active = all.stream()
                .filter(s -> s.givesAccess() && AppStoreEvent.APP_STORE.equals(s.store()))
                .filter(s -> !s.sandbox() || sandboxOk)
                .sorted(Comparator.comparing(StoreSubscription::expiresAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        if (active.isEmpty()) {
            throw DomainException.userFacing(ErrorCode.PAYMENT_NOT_FOUND, AppStoreText.NOTHING_ACTIVE);
        }
        String syncKey = "sync:" + UUID.randomUUID();
        Instant now = clock.instant();
        List<SyncOutcome> outcomes = tx.execute(st -> {
            List<SyncOutcome> out = new ArrayList<>();
            for (StoreSubscription s : active) { // latest expiry last: it decides the plan
                out.add(apply(userId, tenant, s, false, syncKey, 0L, now));
            }
            return out;
        });
        if (outcomes == null || !outcomes.contains(SyncOutcome.GRANTED)) {
            if (outcomes != null && outcomes.stream().allMatch(o -> o == SyncOutcome.NOTHING_ACTIVE)) {
                throw DomainException.userFacing(ErrorCode.PAYMENT_NOT_FOUND, AppStoreText.NOTHING_ACTIVE);
            }
            throw DomainException.userFacing(ErrorCode.INVALID_STATE,
                    outcomes != null && outcomes.contains(SyncOutcome.BOUND_ELSEWHERE) ? AppStoreText.BOUND_ELSEWHERE
                            : AppStoreText.NOT_OFFERED);
        }
        return current(schema).orElseThrow();
    }

    /** The App Store subscription of the company (live first, else the latest), empty when none. */
    @Transactional(readOnly = true)
    public Optional<AppStoreSubscriptionView> current(String schema) {
        TenantView tenant = tenants.findBySchemaName(schema)
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.TENANT_NOT_FOUND,
                        AppStoreText.COMPANY_NOT_FOUND));
        return store.currentOfTenant(tenant.id()).map(this::view);
    }

    /** The App Store products offered to new buyers (the app asks StoreKit for these identifiers). */
    @Transactional(readOnly = true)
    public List<ProductView> products() {
        return products.active(StoreProducts.APP_STORE).stream()
                .map(p -> new ProductView(p.productId(), p.planCode(), p.planLabel(), p.cycleCode(), p.cycleLabel(),
                        p.months()))
                .toList();
    }

    // ── shared steps ──────────────────────────────────────────────────────────

    /**
     * Binds (or re-binds on a TRANSFER, or when the row was transferred away from its company) one active RevenueCat
     * subscription to the company and grants it; the purchases left pending for it are recorded in the ledger.
     *
     * @param eventMs the TRANSFER event timestamp (0 for a sync): older events of the previous owner become stale
     */
    private SyncOutcome apply(UUID user, TenantView tenant, StoreSubscription s, boolean transfer, String key,
                              long eventMs, Instant now) {
        Optional<StoreProduct> product = products.find(StoreProducts.APP_STORE, s.productId());
        if (product.isEmpty()) {
            return SyncOutcome.NOT_OFFERED;
        }
        ExternalSubscription sub = store.lockOrCreate(newSubscription(tenant, user, s.originalTransactionId(),
                product.get(), s.sandbox(), s.expiresAt()));
        if (!sub.tenantId().equals(tenant.id())) {
            boolean movedAway = sub.status() == SubscriptionStatus.TRANSFERRED && !user.equals(sub.appUserId());
            if (!transfer && !movedAway) {
                return SyncOutcome.BOUND_ELSEWHERE;
            }
            sub = sub.withOwner(tenant.id(), tenant.schemaName(), user);
        }
        // RevenueCat's own state: renewal on or off, billing issue (grace / retry), else active
        SubscriptionStatus status = s.inGrace() ? SubscriptionStatus.BILLING_ISSUE
                : s.autoRenew() ? SubscriptionStatus.ACTIVE : SubscriptionStatus.CANCELLED;
        boolean autoRenew = s.autoRenew();
        Instant grace = status == SubscriptionStatus.BILLING_ISSUE ? sub.graceExpiresAt() : null;
        sub = sub.withProduct(product.get().productId(), product.get().planCode(), product.get().cycleCode())
                .withSandbox(s.sandbox())
                .withState(status, autoRenew, s.expiresAt(), grace);
        Instant until = grace != null && (s.expiresAt() == null || grace.isAfter(s.expiresAt())) ? grace
                : s.expiresAt();
        if (until == null || !until.isAfter(now)) {
            store.update(sub.withLastEvent(eventMs));
            return SyncOutcome.NOTHING_ACTIVE; // nothing left to grant locally
        }
        Instant before = sub.grantedExpiresAt();
        sub = grant(sub, until, now).withLastEvent(eventMs);
        store.update(sub);
        replayPending(sub, now);
        if (before == null || !before.equals(sub.grantedExpiresAt())) {
            notice(sub, key + ":" + sub.originalTransactionId(), Kind.ACTIVE, product.get().planLabel(),
                    s.expiresAt());
        }
        return SyncOutcome.GRANTED;
    }

    /** Grants the subscription's plan until {@code until} (see the class comment); returns the subscription updated. */
    private ExternalSubscription grant(ExternalSubscription sub, Instant until, Instant now) {
        if (until == null || !until.isAfter(now)) {
            return sub; // a past period never re-grants
        }
        CurrentPlan current = plans.currentPlan(sub.tenantSchema())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "No subscription for tenant " + sub.tenantId()));
        PlanType plan = PlanType.of(sub.planCode());
        boolean covered = plan.code().equals(current.planType().code()) && current.expiresAt() != null
                && !current.expiresAt().isBefore(until);
        if (!covered) {
            plans.changePlan(new ChangePlanInput(sub.tenantId(), sub.tenantSchema(), plan, until,
                    PlanChangeCause.SELF_SERVICE_PAYMENT, sub.appUserId(), AppStoreText.SOURCE));
        }
        return sub.withGranted(until);
    }

    /**
     * When the company's plan is still this subscription's grant: back to the running Mobile Money period, else to the
     * free plan.
     *
     * <p>The period restored is the one of the LATEST confirmed Mobile Money plan payment, not the one ending last: a
     * payment of another plan starts that day and ends the previous plan (21.1, ADR-06), so an older period of another
     * plan is no longer the company's even if it ends later; renewals of one plan are chained, so the latest confirmed
     * payment also carries the last day of its plan. The restore is the effect of that self-service payment (cause
     * SELF_SERVICE_PAYMENT, like its confirmation: neither a churn nor an expiry downgrade); the revenue metrics count
     * the ledger rows, never this event.
     */
    private void revoke(ExternalSubscription sub, String reason) {
        if (sub.grantedExpiresAt() == null) {
            return;
        }
        CurrentPlan current = plans.currentPlan(sub.tenantSchema()).orElse(null);
        if (current == null || !sub.planCode().equals(current.planType().code()) || current.expiresAt() == null
                || current.expiresAt().isAfter(sub.grantedExpiresAt().plus(GRANT_TOLERANCE))) {
            return; // another plan or a longer payment took over
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), WAT);
        Optional<PaidPlanPeriod> paid = paidPeriods.latestConfirmed(sub.tenantId())
                .filter(p -> p.runningOn(today));
        if (paid.isPresent()) {
            PaidPlanPeriod p = paid.get();
            if (p.planCode().equals(current.planType().code()) && p.expiresAt().equals(current.expiresAt())) {
                return; // already on the Mobile Money period (nothing to write, no plan event)
            }
            plans.changePlan(new ChangePlanInput(sub.tenantId(), sub.tenantSchema(), PlanType.of(p.planCode()),
                    p.expiresAt(), PlanChangeCause.SELF_SERVICE_PAYMENT, null, AppStoreText.MOBILE_MONEY_RESUMED));
            log.info("[AppStore] Subscription revoked, Mobile Money period resumed: tenant={} plan={} until={}",
                    sub.tenantId(), p.planCode(), p.end());
            return;
        }
        plans.changePlan(new ChangePlanInput(sub.tenantId(), sub.tenantSchema(), catalog.freePlan(), null,
                PlanChangeCause.EXPIRY, null, reason));
    }

    /** The ledger row of a paid App Store transaction (idempotent on the store transaction id). */
    private void ledger(ExternalSubscription sub, StoreProduct product, String transactionId, BigDecimal price,
                        String rawCurrency, String periodType, Instant purchasedAt, Instant expiresAt, Instant now) {
        if (transactionId == null || price == null || price.signum() <= 0 || "TRIAL".equals(periodType)) {
            return;
        }
        Long observedXaf = XafAmount.of(price, rawCurrency);
        Instant paidAt = purchasedAt != null ? purchasedAt : now;
        if (price.compareTo(MAX_OBSERVED_PRICE) <= 0) {
            products.recordObservedPrice(StoreProducts.APP_STORE, product.productId(), price, rawCurrency,
                    observedXaf, paidAt);
        }
        Long amount = XafAmount.ledger(price, rawCurrency, product.declaredPriceXaf());
        if (amount == null || amount <= 0 || amount > MAX_LEDGER_XAF) {
            log.warn("[AppStore] Purchase not recorded in the ledger: no XAF amount (currency {})", rawCurrency);
            return;
        }
        LocalDate start = LocalDate.ofInstant(paidAt, WAT);
        LocalDate end = expiresAt == null ? start : LocalDate.ofInstant(expiresAt, WAT);
        if (end.isBefore(start)) {
            end = start;
        }
        String currency = rawCurrency == null ? "" : rawCurrency.toUpperCase(Locale.ROOT);
        String note = AppStoreText.LEDGER_NOTE + ("XAF".equals(currency) ? ""
                : " (" + price.stripTrailingZeros().toPlainString().replace('.', ',') + " " + currency + ")");
        store.insertLedger(sub.tenantId(), amount, paidAt, start, end, note, sub.appUserId(), sub.planCode(),
                sub.cycleCode(), transactionId);
    }

    /**
     * Purchases of this subscription that arrived before it could be bound or before its product was mapped
     * (UNRESOLVED / UNKNOWN_PRODUCT): recorded in the ledger now, idempotent on the store transaction id.
     */
    private void replayPending(ExternalSubscription sub, Instant now) {
        for (PendingPurchase p : store.pendingPurchases(sub.originalTransactionId())) {
            products.find(StoreProducts.APP_STORE, p.productId() != null ? p.productId() : sub.productId())
                    .ifPresent(product -> ledger(sub, product, p.transactionId(), p.price(), p.currency(),
                            p.periodType(), p.purchasedAt(), p.expiresAt(), now));
            store.markReplayed(p.eventId(), sub.tenantId());
        }
    }

    private void notice(ExternalSubscription sub, String key, Kind kind, String planLabel, Instant until) {
        events.publishEvent(new AppStoreSubscriptionNotice(sub.tenantSchema(), key, kind, planLabel, until));
    }

    private boolean sandboxAllowed(UUID user) {
        if (settings.acceptSandbox()) {
            return true;
        }
        if (settings.reviewAccountEmail() == null || user == null) {
            return false;
        }
        return users.findEmail(user).map(m -> m.trim().toLowerCase(Locale.ROOT))
                .filter(settings.reviewAccountEmail()::equals).isPresent();
    }

    /** The company of a new subscription: the designated one, else the single one the user owns. */
    private Resolution resolve(UUID user, String tenantCode) {
        List<OwnedTenantRef> owned = users.findOwnedTenants(user);
        if (owned.isEmpty()) {
            return new Resolution(null, EventOutcome.UNKNOWN_USER);
        }
        OwnedTenantRef chosen = null;
        if (tenantCode != null) {
            chosen = owned.stream().filter(t -> tenantCode.equalsIgnoreCase(t.tenantCode())).findFirst().orElse(null);
        }
        if (chosen == null && owned.size() == 1) {
            chosen = owned.get(0);
        }
        if (chosen == null) {
            return new Resolution(null, EventOutcome.UNRESOLVED);
        }
        TenantView tenant = tenants.findBySchemaName(chosen.schemaName()).orElse(null);
        if (tenant == null || tenant.status() == TenantStatus.DELETED) {
            return new Resolution(null, EventOutcome.UNKNOWN_USER);
        }
        return new Resolution(tenant, null);
    }

    private record Resolution(TenantView tenant, EventOutcome failure) {
    }

    private static ExternalSubscription newSubscription(TenantView tenant, UUID user, String originalTransactionId,
                                                        StoreProduct product, boolean sandbox, Instant expiresAt) {
        return new ExternalSubscription(UUID.randomUUID(), tenant.id(), tenant.schemaName(), user,
                originalTransactionId, product.productId(), product.planCode(), product.cycleCode(),
                SubscriptionStatus.ACTIVE, true, sandbox, expiresAt, null, null, 0L);
    }

    private static Set<UUID> uuids(List<String> raw) {
        Set<UUID> out = new LinkedHashSet<>();
        for (String id : raw) {
            AppStoreEvent.uuid(id).ifPresent(out::add);
        }
        return out;
    }

    private String planLabel(String planCode) {
        return catalog.find(planCode).map(PlanDefinition::label).orElse("");
    }

    private AppStoreSubscriptionView view(ExternalSubscription s) {
        String planLabel = planLabel(s.planCode());
        String cycleLabel = products.find(StoreProducts.APP_STORE, s.productId()).map(StoreProduct::cycleLabel)
                .orElse("");
        return new AppStoreSubscriptionView(s.status().name(), s.status().label(), s.productId(), s.planCode(),
                planLabel, s.cycleCode(), cycleLabel, s.expiresAt(), s.graceExpiresAt(), s.autoRenew(), s.sandbox(),
                AppStoreText.message(s.status(), planLabel, s.expiresAt(), s.graceExpiresAt()));
    }
}

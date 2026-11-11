package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefMonetisation;
import com.keevo.migration.domain.model.BrefMonetisation.AppleTransaction;
import com.keevo.migration.domain.model.BrefMonetisation.PlanPaymentRequest;
import com.keevo.migration.domain.model.BrefMonetisation.RevenueCatEvent;
import com.keevo.migration.domain.model.BrefMonetisation.SmsRecharge;
import com.keevo.migration.domain.model.BrefMonetisation.Subscription;
import com.keevo.migration.domain.model.BrefMonetisation.UsageCounter;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.keevo.migration.domain.model.BrefMonetisation.BREF_ACCOUNT_CREDIT;
import static com.keevo.migration.domain.model.BrefMonetisation.BREF_PLAN_PAYMENT_REQUEST;
import static com.keevo.migration.domain.model.BrefMonetisation.BREF_SUBSCRIPTION;

/**
 * Story 24.5 — the pure rules of the monetisation import (no I/O): which plan the company has at the cutover instant,
 * until when, and how every Bref row is carried over (ADR-07). Deterministic for the same section and instant.
 *
 * <p><b>Plan in force</b> — Bref's {@code SUBSCRIPTION_IN_FORCE} ({@code entitlement.service.ts}): status
 * {@code active}, started (or no start), not ended (or no end, or still in the Apple grace), a payment method set; the
 * latest start wins. Without one, the scheduled subscription Bref's reconcile would activate (earliest due start that
 * would then be in force); else the catalogue's free plan, no grace. {@code user.plan} is never read.
 *
 * <p><b>Future scheduled periods</b> (Decision D4) — paid in advance: the same plan extends the expiry; another plan is
 * converted to the account credit (Bref's own rule at the next checkout); with no plan in force the earliest one
 * starts now; Apple product changes are left to Apple's next renewal.
 *
 * <p>Cost: O(n log n) over the section's rows (sorts), maps for the groupings.
 */
public final class BrefMonetisationPlanner {

    public static final ZoneId WAT = ZoneId.of("Africa/Douala");

    /** Bref plan codes ({@code UserPlan}) → Keevo plan codes; {@code free} is the catalogue's free plan. */
    private static final Map<String, String> PAID_PLANS = Map.of("pro", "PRO", "team", "TEAM", "business",
            "BUSINESS");
    /** Bref billing cycles → Keevo billing cycles (V60). */
    private static final Map<String, String> CYCLES = Map.of("monthly", "MONTHLY", "quarterly", "QUARTERLY",
            "yearly", "ANNUAL");
    /** Bref usage metrics → Keevo metrics. */
    private static final Map<String, UsageMetric> METRICS = Map.of("invoices", UsageMetric.DOCUMENTS,
            "sms", UsageMetric.SMS, "email", UsageMetric.EMAILS, "ai_credits", UsageMetric.AI_CREDITS);
    /** Bref's lifetime counter of the free plan's SMS ({@code quota.service.ts}). */
    private static final String SMS_FREE = "sms_free";
    private static final String DAILY = "daily_";
    private static final String LIFETIME_KEY = "all";
    private static final String APPLE = "apple";
    /** Length of the App Store identifiers in Keevo ({@code original_transaction_id}, {@code store_transaction_id}). */
    private static final int MAX_STORE_ID = 100;

    private BrefMonetisationPlanner() {
    }

    /** What the catalogue says about a Keevo plan / cycle / App Store product (lookups of the step). */
    public interface Catalogue {

        /** The catalogue's free plan code. */
        String freePlan();

        /** The French label of an existing plan, empty when the plan is unknown. */
        Optional<String> planLabel(String planCode);

        /** An existing billing cycle (label, months), empty when unknown. */
        Optional<Cycle> cycle(String cycleCode);

        /** The App Store product mapping, empty when the product is not in the catalogue. */
        Optional<StoreProduct> storeProduct(String productId);
    }

    public record Cycle(String code, String label, int months) {
    }

    /** @param declaredPriceXaf the Apple price entered by the super-admin, {@code null} when not entered */
    public record StoreProduct(String planCode, String cycleCode, Long declaredPriceXaf) {
    }

    // ── the plan ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The plan decision.
     *
     * @param planCode     Keevo plan code
     * @param expiresAt    {@code null} = no expiry (free plan, or a Bref period without end)
     * @param source       the Bref subscription in force ({@code null} = free plan by default)
     * @param inGrace      in force only through the Apple grace
     * @param creditXaf    paid scheduled periods of another plan, converted to the account credit
     * @param creditBySubscription the same, per Bref subscription id
     * @param subscriptionNotes per Bref subscription id, the French note of its record
     */
    public record PlanDecision(String planCode, Instant expiresAt, Subscription source, boolean inGrace,
                               long creditXaf, Map<UUID, Long> creditBySubscription,
                               Map<UUID, String> subscriptionNotes, List<ImportNote.Kind> notes) {
    }

    /** A Keevo plan code of a Bref plan, empty when Bref's code is unknown. */
    public static Optional<String> keevoPlan(String brefPlan, String freePlan) {
        String code = brefPlan == null ? null : brefPlan.strip().toLowerCase(Locale.ROOT);
        if ("free".equals(code)) {
            return Optional.of(freePlan);
        }
        return Optional.ofNullable(code == null ? null : PAID_PLANS.get(code));
    }

    public static Optional<String> keevoCycle(String brefCycle) {
        return Optional.ofNullable(brefCycle == null ? null : CYCLES.get(brefCycle.strip().toLowerCase(Locale.ROOT)));
    }

    static boolean isApple(Subscription s) {
        return APPLE.equalsIgnoreCase(s.paymentMethod());
    }

    /** Bref's {@code SUBSCRIPTION_IN_FORCE} for a row seen as active (status checked by the caller). */
    static boolean inForceAt(Subscription s, Instant t) {
        return (s.startedAt() == null || !s.startedAt().isAfter(t))
                && (s.endsAt() == null || s.endsAt().isAfter(t)
                || (s.gracePeriodEndsAt() != null && s.gracePeriodEndsAt().isAfter(t)))
                && s.paymentMethod() != null;
    }

    private static Instant start(Subscription s) {
        return s.startedAt() != null ? s.startedAt() : s.createdAt();
    }

    private static boolean status(Subscription s, String status) {
        return status.equalsIgnoreCase(s.status() == null ? "" : s.status().strip());
    }

    public static PlanDecision decidePlan(BrefMonetisation m, Instant t, String freePlan) {
        Comparator<Subscription> byStart = Comparator.comparing(BrefMonetisationPlanner::start)
                .thenComparing(Subscription::id);
        Map<UUID, String> notesById = new LinkedHashMap<>();
        List<ImportNote.Kind> notes = new ArrayList<>();
        Subscription source = m.subscriptions().stream()
                .filter(s -> status(s, "active") && inForceAt(s, t))
                .max(byStart)
                .orElse(null);
        if (source == null) { // Bref's reconcile: the earliest due scheduled one that would then be in force
            source = m.subscriptions().stream()
                    .filter(s -> status(s, "scheduled") && !isApple(s) && inForceAt(s, t))
                    .min(byStart)
                    .orElse(null);
        }
        List<Subscription> future = m.subscriptions().stream()
                .filter(s -> status(s, "scheduled") && s.startedAt() != null && s.startedAt().isAfter(t))
                .sorted(byStart)
                .toList();
        boolean freeSource = source != null && keevoPlan(source.plan(), freePlan).filter(freePlan::equals).isPresent();
        if (source == null || freeSource) { // a period already paid that has not started: it starts now (D4)
            Optional<Subscription> early = future.stream()
                    .filter(s -> !isApple(s) && s.paymentMethod() != null && keevoPlan(s.plan(), freePlan)
                            .filter(p -> !p.equals(freePlan)).isPresent()
                            && (s.endsAt() == null || s.endsAt().isAfter(t)))
                    .findFirst();
            if (early.isPresent()) {
                source = early.get();
                notes.add(ImportNote.Kind.PLAN_SCHEDULED_STARTED);
                notesById.put(source.id(), ImportNote.Kind.PLAN_SCHEDULED_STARTED.label());
            }
        }
        String plan = source == null ? freePlan : keevoPlan(source.plan(), freePlan).orElse(null);
        boolean free = freePlan.equals(plan);
        boolean inGrace = source != null && source.endsAt() != null && !source.endsAt().isAfter(t);
        Instant expiresAt = free || source == null ? null : inGrace ? source.gracePeriodEndsAt() : source.endsAt();
        if (source != null && !notesById.containsKey(source.id())) {
            notesById.put(source.id(), "Abonnement en vigueur à la migration");
        }
        if (inGrace && !free) {
            notes.add(ImportNote.Kind.PLAN_IN_GRACE);
        }
        if (!free && source != null && expiresAt == null) {
            notes.add(ImportNote.Kind.PLAN_WITHOUT_END);
        }
        long credit = 0;
        Map<UUID, Long> creditBySubscription = new LinkedHashMap<>();
        for (Subscription s : future) {
            if (source != null && s.id().equals(source.id())) {
                continue;
            }
            if (isApple(s)) {
                notes.add(ImportNote.Kind.APPLE_PRODUCT_CHANGE_IGNORED);
                notesById.put(s.id(), ImportNote.Kind.APPLE_PRODUCT_CHANGE_IGNORED.label());
                continue;
            }
            Optional<String> its = keevoPlan(s.plan(), freePlan);
            boolean samePlan = !free && its.isPresent() && its.get().equals(plan)
                    && (expiresAt == null || !s.startedAt().isAfter(expiresAt.plusSeconds(86_400)));
            if (samePlan) { // a plan without end already covers it
                if (expiresAt != null && s.endsAt() != null && s.endsAt().isAfter(expiresAt)) {
                    expiresAt = s.endsAt();
                }
                notes.add(ImportNote.Kind.PLAN_SCHEDULED_EXTENDED);
                notesById.put(s.id(), ImportNote.Kind.PLAN_SCHEDULED_EXTENDED.label());
                continue;
            }
            long paid = Math.max(0, s.amountPaid()) + Math.max(0, s.creditUsed());
            if (paid > 0) {
                credit += paid;
                creditBySubscription.put(s.id(), paid);
                notes.add(ImportNote.Kind.PLAN_SCHEDULED_TO_CREDIT);
                notesById.put(s.id(), ImportNote.Kind.PLAN_SCHEDULED_TO_CREDIT.label());
            } else {
                notesById.put(s.id(), "Abonnement programmé sans paiement : non repris");
            }
        }
        for (Subscription s : m.subscriptions()) {
            notesById.putIfAbsent(s.id(), "Abonnement terminé ou remplacé (historique)");
        }
        return new PlanDecision(plan, expiresAt, source, inGrace && !free, credit, creditBySubscription, notesById,
                notes);
    }

    /** The blocking cases of the section: what the import cannot write without guessing. */
    public static List<BlockedCase> validate(BrefMonetisation m, Instant t, Catalogue catalogue) {
        List<BlockedCase> cases = new ArrayList<>();
        PlanDecision decision = decidePlan(m, t, catalogue.freePlan());
        if (decision.source() != null && (decision.planCode() == null
                || catalogue.planLabel(decision.planCode()).isEmpty())) {
            cases.add(BlockedCase.of(BlockedCase.Kind.MONETISATION_INVALID, "plan de l'abonnement en vigueur inconnu",
                    BREF_SUBSCRIPTION, decision.source().id()));
        }
        for (PlanPaymentRequest r : m.paymentRequests()) {
            if (!isConfirmed(r)) {
                continue;
            }
            if (r.amountDue() + r.creditApplied() > 0 && mapRequest(r, catalogue).isEmpty()) {
                cases.add(BlockedCase.of(BlockedCase.Kind.MONETISATION_INVALID,
                        "paiement confirmé illisible (plan, durée, montants ou devise)", BREF_PLAN_PAYMENT_REQUEST,
                        r.id()));
            }
        }
        if (m.accountCredit() != null && m.accountCredit().amount() < 0) {
            cases.add(BlockedCase.of(BlockedCase.Kind.MONETISATION_INVALID, "crédit du compte négatif",
                    BREF_ACCOUNT_CREDIT, m.accountCredit().id()));
        }
        return cases;
    }

    // ── the payment requests and their ledger rows ───────────────────────────────────────────────────────────────

    static boolean isConfirmed(PlanPaymentRequest r) {
        return "confirmed".equalsIgnoreCase(r.status() == null ? "" : r.status().strip());
    }

    /**
     * A Bref checkout as imported, with its ledger row when money or credit was spent.
     *
     * @param status Keevo status ({@code CONFIRMED}, {@code FAILED}, {@code CANCELLED}, {@code EXPIRED})
     */
    public record MappedRequest(PlanPaymentRequest source, String planCode, String planLabel, String cycleCode,
                                String cycleLabel, int months, long reductionXaf, String status, String provider,
                                String method, String providerRef, String providerStatus, String appRef,
                                String failureReason,
                                LocalDate periodStart, LocalDate periodEnd, Instant expiresAt, Instant confirmedAt,
                                Ledger ledger, ImportNote.Kind note) {
    }

    /** A ledger row ({@code tenant_payments}). */
    /** @param appleSubscriptionId the imported App Store subscription of a purchase, {@code null} otherwise */
    public record Ledger(UUID id, long amountXaf, long creditXaf, String method, Instant paidAt, LocalDate periodStart,
                         LocalDate periodEnd, String note, String planCode, String cycleCode, UUID paymentRequestId,
                         String storeTransactionId, UUID appleSubscriptionId) {
    }

    /**
     * A Bref checkout as imported, empty when it cannot be (no amount, inconsistent amounts, another currency, plan or
     * cycle unknown — note {@code PAYMENT_REQUEST_SKIPPED}; a confirmed one with an unknown plan is blocking instead).
     */
    public static Optional<MappedRequest> mapRequest(PlanPaymentRequest r, Catalogue catalogue) {
        Optional<String> plan = keevoPlan(r.targetPlan(), catalogue.freePlan());
        Optional<Cycle> cycle = keevoCycle(r.targetBillingCycle()).flatMap(catalogue::cycle);
        Optional<String> planLabel = plan.flatMap(catalogue::planLabel);
        long reduction = r.amount() - r.creditApplied() - r.amountDue();
        if (r.amount() <= 0 || r.creditApplied() < 0 || r.amountDue() < 0 || reduction < 0 || plan.isEmpty()
                || planLabel.isEmpty() || cycle.isEmpty()
                || (r.currency() != null && !"XAF".equalsIgnoreCase(r.currency().strip()))) {
            return Optional.empty();
        }
        String brefStatus = r.status() == null ? "" : r.status().strip().toLowerCase(Locale.ROOT);
        String status = switch (brefStatus) {
            case "confirmed" -> "CONFIRMED";
            case "failed" -> "FAILED";
            case "cancelled", "canceled" -> "CANCELLED";
            default -> "EXPIRED"; // pending (Bref stopped), expired, unknown: never in flight in Keevo
        };
        ImportNote.Kind note = "pending".equals(brefStatus) ? ImportNote.Kind.PAYMENT_PENDING_EXPIRED : null;
        String failure = note == null ? null
                : "Paiement en attente lors du passage à Keevo : il sera validé si l'opérateur le confirme";
        String provider = "mycoolpay".equalsIgnoreCase(trim(r.paymentProvider())) ? "MYCOOLPAY" : "NONE";
        String providerRef = "MYCOOLPAY".equals(provider) ? trim(r.providerTransactionRef()) : null;
        if (providerRef != null && providerRef.length() > 100) {
            providerRef = null;
        }
        String method = switch (trim(r.paymentMethod()) == null ? "" : trim(r.paymentMethod()).toLowerCase(Locale.ROOT)) {
            case "orange_money" -> "ORANGE_MONEY";
            case "momo", "mtn_momo" -> "MTN_MOMO";
            default -> null;
        };
        String providerStatus = trim(r.providerStatus());
        if (providerStatus != null && providerStatus.length() > 20) {
            providerStatus = providerStatus.substring(0, 20);
        }
        Instant confirmedAt = null;
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        Instant expiresAt = null;
        Ledger ledger = null;
        if ("CONFIRMED".equals(status)) {
            confirmedAt = r.updatedAt();
            Instant from = r.startsAt() != null ? r.startsAt() : r.createdAt();
            periodStart = from.atZone(WAT).toLocalDate();
            periodEnd = r.endsAt() != null ? lastDay(periodStart, r.endsAt())
                    : periodStart.plusMonths(cycle.get().months()).minusDays(1);
            expiresAt = r.endsAt();
            long cash = r.amountDue();
            long credit = r.creditApplied();
            if (cash + credit > 0) {
                boolean card = "card".equalsIgnoreCase(trim(r.paymentMethod()));
                String ledgerMethod = cash > 0 ? (card ? "OTHER" : "MOBILE_MONEY") : "ACCOUNT_CREDIT";
                String text = cash > 0 ? "Paiement Bref repris" + (credit > 0 ? " (crédit du compte utilisé)" : "")
                        : "Paiement Bref repris (payé avec le crédit du compte)";
                ledger = new Ledger(r.id(), cash, credit, ledgerMethod, r.updatedAt(), periodStart, periodEnd, text,
                        plan.get(), cycle.get().code(), r.id(), null, null);
            }
        }
        String appRef = "BREF-" + r.id(); // 41 characters, unique like the Bref id
        return Optional.of(new MappedRequest(r, plan.get(), planLabel.get(), cycle.get().code(), cycle.get().label(),
                cycle.get().months(), reduction, status, provider, method, providerRef, providerStatus, appRef, failure,
                periodStart,
                periodEnd, expiresAt, confirmedAt, ledger, note));
    }

    /** The last paid day (inclusive) of a period ending at {@code end}: the day before the end's day, never before the start. */
    static LocalDate lastDay(LocalDate start, Instant end) {
        LocalDate last = end.atZone(WAT).toLocalDate().minusDays(1);
        return last.isBefore(start) ? start : last;
    }

    // ── the App Store ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One App Store subscription (an original transaction) as imported.
     *
     * @param id          the id of its latest Bref transaction
     * @param status      {@code ACTIVE} | {@code BILLING_ISSUE} | {@code EXPIRED} | {@code REVOKED}
     * @param environment {@code PRODUCTION} | {@code SANDBOX}
     */
    public record MappedAppleSubscription(UUID id, String originalTransactionId, String productId, String planCode,
                                          String cycleCode, String status, boolean autoRenew, String environment,
                                          Instant expiresAt, Instant graceExpiresAt, Instant grantedExpiresAt,
                                          long lastEventMs, Instant createdAt, Instant updatedAt,
                                          List<AppleTransaction> transactions) {
    }

    public record AppleImport(List<MappedAppleSubscription> subscriptions, List<Ledger> ledger,
                              Map<UUID, ImportNote.Kind> transactionNotes, List<ImportNote.Kind> notes) {
    }

    public static AppleImport mapApple(BrefMonetisation m, PlanDecision decision, Instant t, Catalogue catalogue) {
        Map<String, List<AppleTransaction>> byOriginal = new LinkedHashMap<>();
        Map<UUID, ImportNote.Kind> txNotes = new LinkedHashMap<>();
        List<ImportNote.Kind> notes = new ArrayList<>();
        for (AppleTransaction a : m.appleTransactions()) {
            String original = trim(a.originalTransactionId());
            if (original != null && original.length() <= MAX_STORE_ID) {
                byOriginal.computeIfAbsent(original, k -> new ArrayList<>()).add(a);
            } else { // the subscription cannot be bound (column of 100): noted, never silently dropped
                notes.add(ImportNote.Kind.APPLE_TRANSACTION_UNREADABLE);
                txNotes.put(a.id(), ImportNote.Kind.APPLE_TRANSACTION_UNREADABLE);
            }
        }
        Map<String, Long> lastEvent = new LinkedHashMap<>();
        for (RevenueCatEvent e : m.revenueCatEvents()) {
            String key = trim(e.subjectKey());
            if (e.processedAt() != null && key != null) {
                lastEvent.merge(key, e.eventTimestampMs(), Math::max);
            }
        }
        String sourceOriginal = decision.source() == null ? null
                : trim(decision.source().appleOriginalTransactionId());
        List<MappedAppleSubscription> subs = new ArrayList<>();
        List<Ledger> ledger = new ArrayList<>();
        for (Map.Entry<String, List<AppleTransaction>> group : byOriginal.entrySet()) {
            List<AppleTransaction> txs = group.getValue().stream()
                    .sorted(Comparator.comparing(AppleTransaction::purchaseDate).thenComparing(AppleTransaction::id))
                    .toList();
            AppleTransaction latest = txs.get(txs.size() - 1);
            Optional<StoreProduct> product = Optional.ofNullable(trim(latest.productId()))
                    .flatMap(catalogue::storeProduct);
            if (product.isEmpty()) {
                notes.add(ImportNote.Kind.APPLE_PRODUCT_UNKNOWN);
                txs.forEach(a -> txNotes.put(a.id(), ImportNote.Kind.APPLE_PRODUCT_UNKNOWN));
                continue;
            }
            boolean revoked = revoked(latest);
            boolean granting = group.getKey().equals(sourceOriginal);
            // a live Apple subscription that does not give the plan (another payment won) stays ACTIVE: Apple renews it
            boolean live = latest.expiresDate() != null && latest.expiresDate().isAfter(t);
            String status = revoked ? "REVOKED"
                    : granting ? (decision.inGrace() ? "BILLING_ISSUE" : "ACTIVE")
                    : live ? "ACTIVE" : "EXPIRED";
            Instant grace = "BILLING_ISSUE".equals(status) ? decision.expiresAt() : null;
            Instant granted = granting && !revoked ? decision.expiresAt() : latest.expiresDate();
            subs.add(new MappedAppleSubscription(latest.id(), group.getKey(), trim(latest.productId()),
                    product.get().planCode(), product.get().cycleCode(), status,
                    "ACTIVE".equals(status) || "BILLING_ISSUE".equals(status), environment(latest),
                    latest.expiresDate(), grace, granted, lastEvent.getOrDefault(group.getKey(), 0L),
                    txs.get(0).createdAt() != null ? txs.get(0).createdAt() : txs.get(0).purchaseDate(),
                    latest.updatedAt() != null ? latest.updatedAt() : latest.purchaseDate(), txs));
            for (AppleTransaction a : txs) {
                Optional<StoreProduct> its = Optional.ofNullable(trim(a.productId())).flatMap(catalogue::storeProduct);
                String txId = trim(a.transactionId());
                if (revoked(a) || !"PRODUCTION".equals(environment(a)) || txId == null || txId.length() > MAX_STORE_ID) {
                    notes.add(ImportNote.Kind.APPLE_PURCHASE_NOT_COUNTED);
                    txNotes.put(a.id(), ImportNote.Kind.APPLE_PURCHASE_NOT_COUNTED);
                } else if (its.isEmpty() || its.get().declaredPriceXaf() == null || its.get().declaredPriceXaf() <= 0) {
                    notes.add(ImportNote.Kind.APPLE_PRICE_UNKNOWN);
                    txNotes.put(a.id(), ImportNote.Kind.APPLE_PRICE_UNKNOWN);
                } else {
                    LocalDate start = a.purchaseDate().atZone(WAT).toLocalDate();
                    // without an Apple expiry, the product's cycle (never a one-day period that would inflate the MRR)
                    int months = catalogue.cycle(its.get().cycleCode()).map(Cycle::months).orElse(1);
                    LocalDate end = a.expiresDate() == null ? start.plusMonths(months).minusDays(1)
                            : lastDay(start, a.expiresDate());
                    ledger.add(new Ledger(a.id(), its.get().declaredPriceXaf(), 0, "APP_STORE", a.purchaseDate(),
                            start, end, "Achat App Store repris de Bref", its.get().planCode(), its.get().cycleCode(),
                            null, txId, latest.id()));
                }
            }
        }
        return new AppleImport(subs, ledger, txNotes, notes);
    }

    private static boolean revoked(AppleTransaction a) {
        return a.revocationDate() != null || "revoked".equalsIgnoreCase(trim(a.status()))
                || "refunded".equalsIgnoreCase(trim(a.status()));
    }

    private static String environment(AppleTransaction a) {
        return "production".equalsIgnoreCase(trim(a.environment())) ? "PRODUCTION" : "SANDBOX";
    }

    // ── RevenueCat events, credit, SMS, counters ─────────────────────────────────────────────────────────────────

    /** @return the events Bref processed (the others are left for a redelivery) */
    public static List<RevenueCatEvent> processedEvents(BrefMonetisation m) {
        return m.revenueCatEvents().stream().filter(e -> e.processedAt() != null).toList();
    }

    /** The original transaction id of an event's subject, {@code null} when its subject is the app user. */
    public static String originalOf(RevenueCatEvent e, UUID ownerId) {
        String key = trim(e.subjectKey());
        return key == null || key.equalsIgnoreCase(ownerId.toString()) || key.length() > 100 ? null : key;
    }

    /** The SMS recharges Bref marked paid (Bref never credits them: D7). */
    public static List<SmsRecharge> paidRecharges(BrefMonetisation m) {
        return m.smsRecharges().stream()
                .filter(r -> "completed".equalsIgnoreCase(trim(r.status())) && r.smsQuantity() > 0)
                .toList();
    }

    /** A Bref counter carried over: Keevo metric, period and key. */
    public record MappedCounter(UsageCounter source, UsageMetric metric, QuotaPeriod period, String periodKey) {
    }

    /** The counters of the current WAT month, today and for life (D8); the others are past periods. */
    public static List<MappedCounter> mapCounters(BrefMonetisation m, Instant t) {
        LocalDate today = t.atZone(WAT).toLocalDate();
        String month = YearMonth.from(today).toString();
        String day = today.toString();
        Map<String, MappedCounter> byKey = new LinkedHashMap<>();
        for (UsageCounter c : m.usageCounters()) {
            String metric = trim(c.metric());
            String key = trim(c.periodMonth());
            if (metric == null || key == null || c.used() < 0) {
                continue;
            }
            metric = metric.toLowerCase(Locale.ROOT);
            MappedCounter mapped = null;
            if (SMS_FREE.equals(metric) && LIFETIME_KEY.equals(key)) {
                mapped = new MappedCounter(c, UsageMetric.SMS, QuotaPeriod.LIFETIME, QuotaPeriod.LIFETIME_KEY);
            } else if (metric.startsWith(DAILY) && METRICS.containsKey(metric.substring(DAILY.length()))
                    && day.equals(key)) {
                mapped = new MappedCounter(c, METRICS.get(metric.substring(DAILY.length())), QuotaPeriod.DAY, day);
            } else if (METRICS.containsKey(metric) && month.equals(key)) {
                mapped = new MappedCounter(c, METRICS.get(metric), QuotaPeriod.MONTH, month);
            }
            if (mapped != null) { // Bref's (owner, metric, period) is unique: one row per Keevo key
                byKey.merge(mapped.metric() + "|" + mapped.periodKey(), mapped,
                        (a, b) -> a.source().used() >= b.source().used() ? a : b);
            }
        }
        return List.copyOf(byKey.values());
    }

    static String trim(String v) {
        if (v == null) {
            return null;
        }
        String s = v.strip();
        return s.isEmpty() ? null : s;
    }
}

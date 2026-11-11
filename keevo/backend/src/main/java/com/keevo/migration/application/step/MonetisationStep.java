package com.keevo.migration.application.step;

import com.keevo.identity.api.PlanType;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefMonetisation;
import com.keevo.migration.domain.model.BrefMonetisation.AppleTransaction;
import com.keevo.migration.domain.model.BrefMonetisation.RevenueCatEvent;
import com.keevo.migration.domain.model.BrefMonetisation.SmsRecharge;
import com.keevo.migration.domain.model.BrefMonetisation.Subscription;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefMonetisationSourcePort;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMonetisationPlanner;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.AppleImport;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.Catalogue;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.Ledger;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.MappedAppleSubscription;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.MappedCounter;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.MappedRequest;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.PlanDecision;
import com.keevo.payments.api.LegacyMonetisationImportPort;
import com.keevo.payments.api.LegacyMonetisationImportPort.ImportedExternalSubscription;
import com.keevo.payments.api.LegacyMonetisationImportPort.ImportedPayment;
import com.keevo.payments.api.LegacyMonetisationImportPort.ImportedPaymentRequest;
import com.keevo.payments.api.LegacyMonetisationImportPort.ImportedRevenueCatEvent;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.UsageCredits;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.subscription.api.ChangePlanInput;
import com.keevo.subscription.api.OfferAdministration;
import com.keevo.subscription.api.PlanAdministrationPort;
import com.keevo.subscription.api.StoreProducts;
import com.keevo.subscription.api.UsageCounterImportPort;
import com.keevo.subscription.api.UsageCounterImportPort.ImportedCounter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.BrefMonetisation.BREF_ACCOUNT_CREDIT;
import static com.keevo.migration.domain.model.BrefMonetisation.BREF_APPLE_TRANSACTION;
import static com.keevo.migration.domain.model.BrefMonetisation.BREF_PLAN_PAYMENT_REQUEST;
import static com.keevo.migration.domain.model.BrefMonetisation.BREF_REVENUECAT_EVENT;
import static com.keevo.migration.domain.model.BrefMonetisation.BREF_SMS_RECHARGE;
import static com.keevo.migration.domain.model.BrefMonetisation.BREF_SUBSCRIPTION;
import static com.keevo.migration.domain.model.BrefMonetisation.BREF_USAGE_COUNTER;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;

/**
 * Story 24.5, §10.2 step 9 — the monetisation (ADR-07): the plan in force at the cutover replaces the registration
 * plan ({@code MIGRATION_BREF}), the checkouts, the ledger, the App Store subscriptions and the RevenueCat events Bref
 * processed, the account credit, the SMS recharges paid and the counters of the current periods — all in ONE platform
 * transaction with their records. The section is read and validated in {@link #check} (before any write of the run),
 * the rules are {@link BrefMonetisationPlanner}'s.
 *
 * <p>Idempotent: the plan is decided once (its record: owner → {@value #TENANT_PLAN}); every other row is written only
 * when its record is absent. Cost: the section read (see the sources), one catalogue query per distinct billing
 * cycle and App Store product (memoised per run), then a constant number of statements (plan change, ≤ 1 insert per
 * target table — 2 for the ledger —, ≤ 2 credit movements, ≤ 1 pack credit, 1 counters upsert, 1 records batch).
 */
@Component
@Order(110)
class MonetisationStep implements ImportStep {

    /** Record target tables of 24.5. */
    static final String TENANT_PLAN = "tenant_plan";
    static final String PAYMENT_REQUESTS = "payment_requests";
    static final String TENANT_PAYMENTS = "tenant_payments";
    static final String EXTERNAL_SUBSCRIPTIONS = "external_subscriptions";
    static final String REVENUECAT_EVENTS = "revenuecat_webhook_event";
    static final String TENANT_CREDITS = "tenant_credits";
    static final String USAGE_CREDITS = "usage_credits";
    static final String USAGE_COUNTERS = "usage_counters";

    /** Counter keys of the batch (stable: the dashboard reads them). */
    static final String PLANS_APPLIED = "plansApplied";
    static final String PAYMENT_REQUESTS_IMPORTED = "paymentRequestsImported";
    static final String PAYMENTS_RECORDED = "paymentsRecorded";
    static final String APP_STORE_SUBSCRIPTIONS = "appStoreSubscriptionsImported";
    static final String APP_STORE_EVENTS = "appStoreEventsImported";
    static final String CREDIT_IMPORTED_XAF = "creditImportedXaf";
    static final String SMS_UNITS_IMPORTED = "smsPackUnitsImported";
    static final String COUNTERS_IMPORTED = "usageCountersImported";

    static final String PLAN_REASON = "Import Bref : plan en vigueur à la migration";
    static final String CREDIT_REASON = "Crédit du compte Bref repris";
    static final String SCHEDULED_REASON = "Période programmée d'un autre plan payée dans Bref, versée au crédit";

    /** The section, the instant and the catalogue lookups (memoised) the check used, kept for the run. */
    record Loaded(BrefMonetisation section, Instant at, Catalogue catalogue) {
    }

    private final Map<SourceKind, BrefMonetisationSourcePort> sources = new EnumMap<>(SourceKind.class);
    private final PlanAdministrationPort plans;
    private final LegacyMonetisationImportPort payments;
    private final UsageCounterImportPort counters;
    private final UsageCredits usageCredits;
    private final PlanCatalog planCatalog;
    private final OfferAdministration offers;
    private final StoreProducts storeProducts;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;
    private final Clock clock;

    MonetisationStep(List<BrefMonetisationSourcePort> sources, PlanAdministrationPort plans,
                     LegacyMonetisationImportPort payments, UsageCounterImportPort counters,
                     UsageCredits usageCredits, PlanCatalog planCatalog, OfferAdministration offers,
                     StoreProducts storeProducts, MigrationRecordStore records, MigrationUnitOfWork uow,
                     Clock clock) {
        sources.forEach(s -> this.sources.put(s.kind(), s));
        this.plans = plans;
        this.payments = payments;
        this.counters = counters;
        this.usageCredits = usageCredits;
        this.planCatalog = planCatalog;
        this.offers = offers;
        this.storeProducts = storeProducts;
        this.records = records;
        this.uow = uow;
        this.clock = clock;
    }

    @Override
    public String label() {
        return "abonnement et paiements";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(TENANT_PLAN, PAYMENT_REQUESTS, TENANT_PAYMENTS, EXTERNAL_SUBSCRIPTIONS, REVENUECAT_EVENTS,
                TENANT_CREDITS, USAGE_CREDITS, USAGE_COUNTERS);
    }

    @Override
    public List<BlockedCase> check(ImportRun run) {
        Loaded loaded = load(run);
        return BrefMonetisationPlanner.validate(loaded.section(), loaded.at(), loaded.catalogue());
    }

    private Loaded load(ImportRun run) {
        Loaded loaded = run.attachment(Loaded.class);
        if (loaded == null) {
            BrefMonetisationSourcePort source = sources.get(run.snapshot().source());
            if (source == null) {
                throw new IllegalStateException("No monetisation source for " + run.snapshot().source());
            }
            Set<UUID> accounts = new LinkedHashSet<>();
            accounts.add(run.ownerId());
            run.snapshot().members().stream().map(BrefAccount::id).forEach(accounts::add);
            loaded = new Loaded(source.load(run.ownerId(), accounts), clock.instant(), catalogue());
            run.attach(Loaded.class, loaded);
        }
        return loaded;
    }

    /**
     * The catalogue lookups of one run, memoised: one query per distinct cycle and App Store product whatever the
     * owner's history (the plan catalogue is already cached).
     */
    Catalogue catalogue() {
        Map<String, Optional<BrefMonetisationPlanner.Cycle>> cycles = new java.util.HashMap<>();
        Map<String, Optional<BrefMonetisationPlanner.StoreProduct>> products = new java.util.HashMap<>();
        return new Catalogue() {
            @Override
            public String freePlan() {
                return planCatalog.settings().freePlan().code();
            }

            @Override
            public Optional<String> planLabel(String planCode) {
                return planCatalog.find(planCode).map(PlanDefinition::label);
            }

            @Override
            public Optional<BrefMonetisationPlanner.Cycle> cycle(String cycleCode) {
                return cycles.computeIfAbsent(cycleCode, code -> offers.cycle(code)
                        .map(c -> new BrefMonetisationPlanner.Cycle(c.code(), c.label(), c.months())));
            }

            @Override
            public Optional<BrefMonetisationPlanner.StoreProduct> storeProduct(String productId) {
                return products.computeIfAbsent(productId, id -> storeProducts.find(StoreProducts.APP_STORE, id)
                        .map(p -> new BrefMonetisationPlanner.StoreProduct(p.planCode(), p.cycleCode(),
                                p.declaredPriceXaf())));
            }
        };
    }

    @Override
    public void run(ImportRun run) {
        for (String key : List.of(PLANS_APPLIED, PAYMENT_REQUESTS_IMPORTED, PAYMENTS_RECORDED, APP_STORE_SUBSCRIPTIONS,
                APP_STORE_EVENTS, CREDIT_IMPORTED_XAF, SMS_UNITS_IMPORTED, COUNTERS_IMPORTED)) {
            run.count(key, 0); // the keys show even when nothing is written
        }
        Loaded loaded = load(run);
        BrefMonetisation m = loaded.section();
        Instant t = loaded.at();
        Catalogue catalogue = loaded.catalogue();
        UUID owner = run.ownerId();
        UUID tenantId = run.tenantId();
        String schema = run.tenantSchema();
        // Story 24.7 — the Keevo account of the owner (its Bref id, or the existing account it was linked to)
        UUID account = run.accountId(owner) != null ? run.accountId(owner) : owner;
        Work w = new Work();
        int already = 0;

        // ── the plan (decided once)
        PlanDecision decision = BrefMonetisationPlanner.decidePlan(m, t, catalogue.freePlan());
        boolean planDone = run.imported(BREF_USER, owner, TENANT_PLAN);
        if (planDone) {
            already++;
        } else {
            w.notes.addAll(decision.notes());
            w.records.add(new MigrationRecord(TENANT_PLAN, tenantId, BREF_USER, owner,
                    Canonical.sha256(Canonical.of("owner", owner, "subscription",
                            decision.source() == null ? null : decision.source().id(), "plan", decision.planCode(),
                            "expiresAt", decision.expiresAt())), "Plan en vigueur à la migration"));
        }
        for (Subscription s : m.subscriptions()) {
            if (run.imported(BREF_SUBSCRIPTION, s.id(), TENANT_PLAN)) {
                already++;
                continue;
            }
            String note = decision.subscriptionNotes().get(s.id());
            w.scheduledCredit += decision.creditBySubscription().getOrDefault(s.id(), 0L);
            w.records.add(new MigrationRecord(TENANT_PLAN, tenantId, BREF_SUBSCRIPTION, s.id(),
                    Canonical.sha256(s.canonical()), note));
        }

        // ── checkouts and their ledger rows
        Set<String> providerRefs = new java.util.HashSet<>(); // unique per provider in Keevo: a duplicate keeps none
        for (BrefMonetisation.PlanPaymentRequest r : m.paymentRequests()) {
            Optional<MappedRequest> mapped = BrefMonetisationPlanner.mapRequest(r, catalogue);
            if (mapped.isEmpty()) {
                if (!run.imported(BREF_PLAN_PAYMENT_REQUEST, r.id(), PAYMENT_REQUESTS)) {
                    w.notes.add(ImportNote.Kind.PAYMENT_REQUEST_SKIPPED);
                }
                continue;
            }
            MappedRequest q = mapped.get();
            String checksum = Canonical.sha256(r.canonical());
            if (run.imported(BREF_PLAN_PAYMENT_REQUEST, r.id(), PAYMENT_REQUESTS)) {
                already++;
            } else {
                w.requests.add(new ImportedPaymentRequest(r.id(), tenantId, schema, account, q.provider(), q.method(),
                        q.planCode(), q.cycleCode(), q.planLabel(), q.cycleLabel(), q.months(), r.amount(),
                        q.reductionXaf(), r.creditApplied(), r.amountDue(), r.createdAt(), q.status(), q.appRef(),
                        q.providerRef() == null || providerRefs.add(q.providerRef()) ? q.providerRef() : null,
                        q.providerStatus(), q.failureReason(),
                        q.ledger() == null ? null : q.ledger().id(), q.periodStart(), q.periodEnd(), q.expiresAt(),
                        q.confirmedAt(), r.createdAt(), r.updatedAt()));
                w.records.add(new MigrationRecord(PAYMENT_REQUESTS, r.id(), BREF_PLAN_PAYMENT_REQUEST, r.id(),
                        checksum, q.note() == null ? null : q.note().label()));
                if (q.note() != null) {
                    w.notes.add(q.note());
                }
            }
            if (q.ledger() != null) {
                if (run.imported(BREF_PLAN_PAYMENT_REQUEST, r.id(), TENANT_PAYMENTS)) {
                    already++;
                } else {
                    w.ledger.add(payment(q.ledger(), tenantId, run.actorId()));
                    w.records.add(new MigrationRecord(TENANT_PAYMENTS, q.ledger().id(), BREF_PLAN_PAYMENT_REQUEST,
                            r.id(), checksum, null));
                }
            }
        }

        // ── App Store subscriptions, purchases and processed events
        AppleImport apple = BrefMonetisationPlanner.mapApple(m, decision, t, catalogue);
        Set<UUID> applePending = new LinkedHashSet<>();
        Map<UUID, UUID> appleTarget = new java.util.HashMap<>(); // planned subscription id → the id it has in Keevo
        for (MappedAppleSubscription s : apple.subscriptions()) {
            List<AppleTransaction> missing = s.transactions().stream()
                    .filter(a -> !run.imported(BREF_APPLE_TRANSACTION, a.id(), EXTERNAL_SUBSCRIPTIONS)).toList();
            already += s.transactions().size() - missing.size();
            // already imported by an earlier run (a later Bref transaction changes the latest id): keep its row
            UUID recorded = s.transactions().stream()
                    .map(a -> run.target(BREF_APPLE_TRANSACTION, a.id(), EXTERNAL_SUBSCRIPTIONS))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (recorded != null) {
                appleTarget.put(s.id(), recorded);
                for (AppleTransaction a : missing) {
                    w.records.add(new MigrationRecord(EXTERNAL_SUBSCRIPTIONS, recorded, BREF_APPLE_TRANSACTION,
                            a.id(), Canonical.sha256(a.canonical()), null));
                }
                continue;
            }
            w.externals.add(new ImportedExternalSubscription(s.id(), tenantId, schema, account,
                    s.originalTransactionId(), s.productId(), s.planCode(), s.cycleCode(), s.status(), s.autoRenew(),
                    s.environment(), s.expiresAt(), s.graceExpiresAt(), s.grantedExpiresAt(), s.lastEventMs(),
                    s.createdAt(), s.updatedAt()));
            for (AppleTransaction a : missing) {
                w.appleRecords.add(new PendingApple(s.id(), new MigrationRecord(EXTERNAL_SUBSCRIPTIONS, s.id(),
                        BREF_APPLE_TRANSACTION, a.id(), Canonical.sha256(a.canonical()), null)));
            }
            applePending.add(s.id());
        }
        Map<UUID, AppleTransaction> appleById = new java.util.HashMap<>();
        m.appleTransactions().forEach(a -> appleById.put(a.id(), a));
        for (Ledger l : apple.ledger()) {
            if (run.imported(BREF_APPLE_TRANSACTION, l.id(), TENANT_PAYMENTS)) {
                already++;
                continue;
            }
            // written only with its subscription (inserted now, or by an earlier run); an Apple purchase another
            // company already recorded is noted, never fails the import
            w.appleLedger.add(new PendingPayment(appleTarget.containsKey(l.appleSubscriptionId()) ? null
                    : l.appleSubscriptionId(), payment(l, tenantId, run.actorId()),
                    new MigrationRecord(TENANT_PAYMENTS, l.id(), BREF_APPLE_TRANSACTION, l.id(),
                            Canonical.sha256(appleById.get(l.id()).canonical()), null)));
        }
        apple.transactionNotes().forEach((id, kind) -> {
            if (!run.imported(BREF_APPLE_TRANSACTION, id, TENANT_PAYMENTS)
                    && !run.imported(BREF_APPLE_TRANSACTION, id, EXTERNAL_SUBSCRIPTIONS)) {
                w.notes.add(kind);
            }
        });
        for (RevenueCatEvent e : m.revenueCatEvents()) {
            if (run.imported(BREF_REVENUECAT_EVENT, e.sourceId(), REVENUECAT_EVENTS)) {
                already++;
            } else if (e.processedAt() == null) {
                w.notes.add(ImportNote.Kind.REVENUECAT_EVENT_UNPROCESSED);
            } else if (e.type() == null || e.type().isBlank() || e.type().length() > 64 || e.id().length() > 128) {
                w.notes.add(ImportNote.Kind.REVENUECAT_EVENT_UNREADABLE); // Keevo's columns: type 64, id 128
            } else {
                String original = BrefMonetisationPlanner.originalOf(e, owner);
                w.events.add(new ImportedRevenueCatEvent(e.id(), e.type(), account.toString(), original,
                        e.eventTimestampMs(), tenantId, e.processedAt()));
                w.records.add(new MigrationRecord(REVENUECAT_EVENTS, e.sourceId(), BREF_REVENUECAT_EVENT,
                        e.sourceId(), Canonical.sha256(e.canonical()), null));
            }
        }

        // ── account credit, SMS recharges paid, counters
        if (m.accountCredit() != null) {
            if (run.imported(BREF_ACCOUNT_CREDIT, m.accountCredit().id(), TENANT_CREDITS)) {
                already++;
            } else {
                w.credit = m.accountCredit().amount();
                w.records.add(new MigrationRecord(TENANT_CREDITS, tenantId, BREF_ACCOUNT_CREDIT,
                        m.accountCredit().id(), Canonical.sha256(m.accountCredit().canonical()), null));
            }
        }
        List<SmsRecharge> paid = BrefMonetisationPlanner.paidRecharges(m);
        Set<UUID> paidIds = new java.util.HashSet<>();
        for (SmsRecharge r : paid) {
            paidIds.add(r.id());
            if (run.imported(BREF_SMS_RECHARGE, r.id(), USAGE_CREDITS)) {
                already++;
                continue;
            }
            w.smsUnits += r.smsQuantity();
            w.records.add(new MigrationRecord(USAGE_CREDITS, tenantId, BREF_SMS_RECHARGE, r.id(),
                    Canonical.sha256(r.canonical()), null));
        }
        m.smsRecharges().stream().filter(r -> !paidIds.contains(r.id()))
                .forEach(r -> w.notes.add(ImportNote.Kind.SMS_RECHARGE_NOT_PAID));
        List<MappedCounter> current = BrefMonetisationPlanner.mapCounters(m, t);
        Set<UUID> currentIds = new java.util.HashSet<>();
        for (MappedCounter c : current) {
            currentIds.add(c.source().id());
            if (run.imported(BREF_USAGE_COUNTER, c.source().id(), USAGE_COUNTERS)) {
                already++;
                continue;
            }
            w.counters.add(new ImportedCounter(c.metric(), c.period(), c.periodKey(), c.source().used()));
            w.records.add(new MigrationRecord(USAGE_COUNTERS, tenantId, BREF_USAGE_COUNTER, c.source().id(),
                    Canonical.sha256(c.source().canonical()), null));
        }
        m.usageCounters().stream().filter(c -> !currentIds.contains(c.id()))
                .forEach(c -> w.notes.add(ImportNote.Kind.COUNTER_PAST_PERIOD));
        run.count(ImportRun.ALREADY_IMPORTED, already);

        if (w.isEmpty() && planDone) {
            w.notes.forEach(run::note);
            return;
        }
        Written written = uow.platform(() -> write(run, decision, planDone, w, applePending));
        run.remember(written.records());
        written.notes().forEach(run::note);
        run.count(PLANS_APPLIED, planDone ? 0 : 1);
        run.count(PAYMENT_REQUESTS_IMPORTED, w.requests.size());
        run.count(PAYMENTS_RECORDED, w.ledger.size() + written.applePayments());
        run.count(APP_STORE_SUBSCRIPTIONS, written.externals());
        run.count(APP_STORE_EVENTS, w.events.size());
        // counters are ints: clamped (the rows are committed already, a count never fails the batch)
        run.count(CREDIT_IMPORTED_XAF, (int) Math.min(Integer.MAX_VALUE, Math.max(0, w.credit) + w.scheduledCredit));
        run.count(SMS_UNITS_IMPORTED, (int) Math.min(Integer.MAX_VALUE, w.smsUnits));
        run.count(COUNTERS_IMPORTED, w.counters.size());
    }

    /** One transaction: the plan, the payments rows, the credit, the packs, the counters and the records. */
    private Written write(ImportRun run, PlanDecision decision, boolean planDone, Work w, Set<UUID> applePending) {
        UUID tenantId = run.tenantId();
        String schema = run.tenantSchema();
        if (!planDone) { // the registration plan (24.2) is replaced; trial module rows realigned in the same transaction
            plans.changePlan(new ChangePlanInput(tenantId, schema, PlanType.of(decision.planCode()),
                    decision.expiresAt(), PlanChangeCause.MIGRATION_BREF, run.actorId(), PLAN_REASON));
        }
        Set<UUID> requests = payments.importPaymentRequests(w.requests);
        requireAll("payment request", w.requests.stream().map(ImportedPaymentRequest::id).toList(), requests);
        Set<UUID> ledger = payments.importLedger(w.ledger);
        requireAll("payment", w.ledger.stream().map(ImportedPayment::id).toList(), ledger);
        Set<UUID> externals = payments.importExternalSubscriptions(w.externals);
        List<ImportNote.Kind> notes = new ArrayList<>(w.notes);
        List<MigrationRecord> rows = new ArrayList<>(w.records);
        for (PendingApple p : w.appleRecords) {
            if (externals.contains(p.subscriptionId())) {
                rows.add(p.record());
            }
        }
        // an original transaction already bound to another company is not moved (21.2 rule): noted, not recorded,
        // and its purchases are not counted for this company
        applePending.stream().filter(id -> !externals.contains(id))
                .forEach(id -> notes.add(ImportNote.Kind.APPLE_SUBSCRIPTION_HELD));
        List<PendingPayment> appleLedger = w.appleLedger.stream()
                .filter(p -> p.subscriptionId() == null || externals.contains(p.subscriptionId())).toList();
        Set<UUID> applePaid = payments.importLedger(appleLedger.stream().map(PendingPayment::payment).toList());
        for (PendingPayment p : appleLedger) {
            if (applePaid.contains(p.payment().id())) {
                rows.add(p.record());
            } else {
                notes.add(ImportNote.Kind.APPLE_PURCHASE_ALREADY_RECORDED);
            }
        }
        payments.importRevenueCatEvents(w.events); // an event id already there is already processed: recorded too
        if (w.credit > 0) {
            payments.creditAccount(tenantId, w.credit, CREDIT_REASON);
        }
        if (w.scheduledCredit > 0) {
            payments.creditAccount(tenantId, w.scheduledCredit, SCHEDULED_REASON);
        }
        if (w.smsUnits > 0) {
            usageCredits.add(tenantId, UsageMetric.SMS, Math.toIntExact(w.smsUnits), UsageCredits.Grant.migration());
        }
        counters.importCounters(tenantId, w.counters);
        records.insert(run.batchId(), run.ownerId(), rows);
        return new Written(rows, notes, externals.size(), applePaid.size());
    }

    private static void requireAll(String what, List<UUID> planned, Set<UUID> inserted) {
        if (!inserted.containsAll(planned)) {
            // a row of this owner already there without its record, or a reference held elsewhere: never half-written
            throw new IllegalStateException("Imported " + what + " not inserted");
        }
    }

    private static ImportedPayment payment(Ledger l, UUID tenantId, UUID actorId) {
        return new ImportedPayment(l.id(), tenantId, l.amountXaf(), l.creditXaf(), l.method(), l.paidAt(),
                l.periodStart(), l.periodEnd(), l.note(), actorId, l.planCode(), l.cycleCode(), l.paymentRequestId(),
                l.storeTransactionId());
    }

    /** What the run writes (built before the transaction). */
    private static final class Work {
        final List<MigrationRecord> records = new ArrayList<>();
        final List<PendingApple> appleRecords = new ArrayList<>();
        final List<ImportNote.Kind> notes = new ArrayList<>();
        final List<ImportedPaymentRequest> requests = new ArrayList<>();
        final List<ImportedPayment> ledger = new ArrayList<>();
        final List<PendingPayment> appleLedger = new ArrayList<>();
        final List<ImportedExternalSubscription> externals = new ArrayList<>();
        final List<ImportedRevenueCatEvent> events = new ArrayList<>();
        final List<ImportedCounter> counters = new ArrayList<>();
        long credit;
        long scheduledCredit;
        long smsUnits;

        boolean isEmpty() {
            return records.isEmpty() && appleRecords.isEmpty() && appleLedger.isEmpty();
        }
    }

    private record PendingApple(UUID subscriptionId, MigrationRecord record) {
    }

    /** @param subscriptionId the App Store subscription inserted by this run it needs, {@code null} = already there */
    private record PendingPayment(UUID subscriptionId, ImportedPayment payment, MigrationRecord record) {
    }

    private record Written(List<MigrationRecord> records, List<ImportNote.Kind> notes, int externals,
                           int applePayments) {
    }
}

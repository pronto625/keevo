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
import com.keevo.migration.domain.service.BrefMonetisationPlanner.AppleImport;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.Catalogue;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.Cycle;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.MappedCounter;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.MappedRequest;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.PlanDecision;
import com.keevo.migration.domain.service.BrefMonetisationPlanner.StoreProduct;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("24.5 — Bref monetisation rules (plan in force, requests, App Store, counters)")
class BrefMonetisationPlannerTest {

    /** 2026-11-15 10:00 WAT. */
    private static final Instant T = Instant.parse("2026-11-15T09:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();
    private static final Duration DAY = Duration.ofDays(1);

    private static final Catalogue CATALOGUE = new Catalogue() {
        @Override
        public String freePlan() {
            return "FREE";
        }

        @Override
        public Optional<String> planLabel(String planCode) {
            return Optional.ofNullable(Map.of("FREE", "Gratuit", "PRO", "Pro", "TEAM", "Team", "BUSINESS",
                    "Business").get(planCode));
        }

        @Override
        public Optional<Cycle> cycle(String cycleCode) {
            return Optional.ofNullable(Map.of("MONTHLY", new Cycle("MONTHLY", "Mensuel", 1), "QUARTERLY",
                    new Cycle("QUARTERLY", "Trimestriel", 3), "ANNUAL", new Cycle("ANNUAL", "Annuel", 12))
                    .get(cycleCode));
        }

        @Override
        public Optional<StoreProduct> storeProduct(String productId) {
            return Optional.ofNullable(Map.of("brefpoint.pro.month", new StoreProduct("PRO", "MONTHLY", 2500L),
                    "brefpoint.business.monthly", new StoreProduct("BUSINESS", "MONTHLY", null)).get(productId));
        }
    };

    private static Subscription sub(String plan, String status, Instant start, Instant end, String method,
                                    Instant grace, String original, long paid, long credit) {
        return new Subscription(UUID.randomUUID(), OWNER, plan, status, "monthly", start, end, paid, credit, method,
                original, grace, start == null ? T.minus(DAY.multipliedBy(40)) : start, T);
    }

    private static BrefMonetisation section(List<Subscription> subs) {
        return new BrefMonetisation(subs, List.of(), List.of(), List.of(), null, List.of(), List.of());
    }

    private static PlanDecision decide(Subscription... subs) {
        return BrefMonetisationPlanner.decidePlan(section(List.of(subs)), T, "FREE");
    }

    // ── the plan ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("no subscription → free plan, no expiry")
    void noSubscription() {
        PlanDecision d = decide();
        assertThat(d.planCode()).isEqualTo("FREE");
        assertThat(d.expiresAt()).isNull();
        assertThat(d.source()).isNull();
    }

    @Test
    @DisplayName("Mobile Money in force → its plan until its exact end")
    void mobileMoneyInForce() {
        Instant end = T.plus(DAY.multipliedBy(25));
        Subscription s = sub("pro", "active", T.minus(DAY.multipliedBy(5)), end, "orange_money", null, null, 3000, 500);
        PlanDecision d = decide(s);
        assertThat(d.planCode()).isEqualTo("PRO");
        assertThat(d.expiresAt()).isEqualTo(end);
        assertThat(d.source()).isEqualTo(s);
        assertThat(d.subscriptionNotes()).containsEntry(s.id(), "Abonnement en vigueur à la migration");
    }

    @Test
    @DisplayName("Apple in grace → plan kept until the grace end, noted")
    void appleInGrace() {
        Instant grace = T.plus(DAY.multipliedBy(10));
        PlanDecision d = decide(sub("business", "active", T.minus(DAY.multipliedBy(30)), T.minus(DAY.multipliedBy(2)),
                "apple", grace, "O-1", 0, 0));
        assertThat(d.planCode()).isEqualTo("BUSINESS");
        assertThat(d.expiresAt()).isEqualTo(grace);
        assertThat(d.inGrace()).isTrue();
        assertThat(d.notes()).contains(ImportNote.Kind.PLAN_IN_GRACE);
    }

    @Test
    @DisplayName("ended without grace, expired or cancelled, or unpaid → free plan (no grace, ADR-07)")
    void expired() {
        assertThat(decide(sub("team", "active", T.minus(DAY.multipliedBy(31)), T.minus(DAY), "momo", null, null, 5500,
                0)).planCode()).isEqualTo("FREE");
        assertThat(decide(sub("team", "expired", T.minus(DAY), T.plus(DAY), "momo", null, null, 5500, 0)).planCode())
                .isEqualTo("FREE");
        assertThat(decide(sub("team", "cancelled", T.minus(DAY), T.plus(DAY), "momo", null, null, 5500, 0))
                .planCode()).isEqualTo("FREE");
        assertThat(decide(sub("team", "active", T.minus(DAY), T.plus(DAY), null, null, null, 0, 0)).planCode())
                .isEqualTo("FREE");
    }

    @Test
    @DisplayName("admin grant without amount → its plan until its end; the latest start wins")
    void adminGrantLatestWins() {
        Subscription older = sub("pro", "active", T.minus(DAY.multipliedBy(10)), T.plus(DAY.multipliedBy(20)),
                "admin_grant", null, null, 0, 0);
        Subscription newer = sub("business", "active", T.minus(DAY.multipliedBy(2)), T.plus(DAY.multipliedBy(20)),
                "admin_grant", null, null, 0, 0);
        PlanDecision d = decide(older, newer);
        assertThat(d.planCode()).isEqualTo("BUSINESS");
        assertThat(d.subscriptionNotes()).containsEntry(older.id(), "Abonnement terminé ou remplacé (historique)");
    }

    @Test
    @DisplayName("no end in Bref → paid plan without expiry, noted")
    void withoutEnd() {
        PlanDecision d = decide(sub("pro", "active", T.minus(DAY), null, "admin_grant", null, null, 0, 0));
        assertThat(d.planCode()).isEqualTo("PRO");
        assertThat(d.expiresAt()).isNull();
        assertThat(d.notes()).contains(ImportNote.Kind.PLAN_WITHOUT_END);
    }

    @Test
    @DisplayName("due scheduled subscription (Bref's reconcile) is the plan when nothing is active")
    void dueScheduled() {
        Instant end = T.plus(DAY.multipliedBy(20));
        PlanDecision d = decide(sub("team", "scheduled", T.minus(DAY), end, "momo", null, null, 5500, 0));
        assertThat(d.planCode()).isEqualTo("TEAM");
        assertThat(d.expiresAt()).isEqualTo(end);
    }

    @Test
    @DisplayName("future scheduled period: same plan extends, other plan goes to the credit, Apple change ignored")
    void scheduled() {
        Instant end = T.plus(DAY.multipliedBy(10));
        Instant next = end.plus(DAY.multipliedBy(30));
        Subscription current = sub("pro", "active", T.minus(DAY.multipliedBy(20)), end, "momo", null, null, 3500, 0);
        Subscription renew = sub("pro", "scheduled", end, next, "momo", null, null, 3500, 0);
        Subscription other = sub("team", "scheduled", next, next.plus(DAY.multipliedBy(30)), "momo", null, null,
                5000, 500);
        Subscription apple = sub("business", "scheduled", end, null, "apple", null, null, 0, 0);
        PlanDecision d = decide(current, renew, other, apple);
        assertThat(d.planCode()).isEqualTo("PRO");
        assertThat(d.expiresAt()).isEqualTo(next);
        assertThat(d.creditXaf()).isEqualTo(5500);
        assertThat(d.notes()).contains(ImportNote.Kind.PLAN_SCHEDULED_EXTENDED, ImportNote.Kind.PLAN_SCHEDULED_TO_CREDIT,
                ImportNote.Kind.APPLE_PRODUCT_CHANGE_IGNORED);
        assertThat(d.subscriptionNotes()).containsEntry(other.id(),
                ImportNote.Kind.PLAN_SCHEDULED_TO_CREDIT.label());
        assertThat(d.creditBySubscription()).containsExactly(Map.entry(other.id(), 5500L));
    }

    @Test
    @DisplayName("review: a plan without end already covers a same-plan scheduled period (never also credited)")
    void endlessPlanCoversScheduled() {
        Subscription endless = sub("pro", "active", T.minus(DAY), null, "admin_grant", null, null, 0, 0);
        Subscription renew = sub("pro", "scheduled", T.plus(DAY.multipliedBy(30)), T.plus(DAY.multipliedBy(60)), "momo",
                null, null, 3500, 0);
        PlanDecision d = decide(endless, renew);
        assertThat(d.expiresAt()).isNull();
        assertThat(d.creditXaf()).isZero();
        assertThat(d.notes()).contains(ImportNote.Kind.PLAN_SCHEDULED_EXTENDED);
    }

    @Test
    @DisplayName("review: a free plan in force does not stop a paid future period from starting now")
    void freeInForceScheduledStarts() {
        Instant end = T.plus(DAY.multipliedBy(40));
        PlanDecision d = decide(sub("free", "active", T.minus(DAY.multipliedBy(30)), null, "admin_grant", null, null, 0,
                0), sub("team", "scheduled", T.plus(DAY.multipliedBy(10)), end, "momo", null, null, 5500, 0));
        assertThat(d.planCode()).isEqualTo("TEAM");
        assertThat(d.expiresAt()).isEqualTo(end);
        assertThat(d.creditXaf()).isZero();
    }

    @Test
    @DisplayName("a paid future period with nothing in force starts at the migration")
    void scheduledStartsEarly() {
        Instant end = T.plus(DAY.multipliedBy(40));
        PlanDecision d = decide(sub("team", "scheduled", T.plus(DAY.multipliedBy(10)), end, "momo", null, null, 5500,
                0));
        assertThat(d.planCode()).isEqualTo("TEAM");
        assertThat(d.expiresAt()).isEqualTo(end);
        assertThat(d.creditXaf()).isZero();
        assertThat(d.notes()).contains(ImportNote.Kind.PLAN_SCHEDULED_STARTED);
    }

    @Test
    @DisplayName("unknown plan in force, unknown plan of a confirmed request, negative credit → blocking")
    void blocking() {
        BrefMonetisation m = new BrefMonetisation(
                List.of(sub("gold", "active", T.minus(DAY), T.plus(DAY), "momo", null, null, 1, 0)),
                List.of(request("pro", "weekly", 3500, 0, 3500, "confirmed")), List.of(), List.of(),
                new BrefMonetisation.AccountCredit(UUID.randomUUID(), OWNER, -5, T), List.of(), List.of());
        List<BlockedCase> cases = BrefMonetisationPlanner.validate(m, T, CATALOGUE);
        assertThat(cases).hasSize(3).allMatch(c -> c.code().equals("MONETISATION_INVALID"));
        // review 24.5: a confirmed payment that cannot be imported (inconsistent amounts, other currency) blocks too
        BrefMonetisation inconsistent = new BrefMonetisation(List.of(),
                List.of(request("pro", "monthly", 3500, 0, 4000, "confirmed")), List.of(), List.of(), null, List.of(),
                List.of());
        assertThat(BrefMonetisationPlanner.validate(inconsistent, T, CATALOGUE)).singleElement()
                .satisfies(c -> assertThat(c.code()).isEqualTo("MONETISATION_INVALID"));
        assertThat(BrefMonetisationPlanner.validate(section(List.of(sub("pro", "active", T.minus(DAY), T.plus(DAY),
                "momo", null, null, 1, 0))), T, CATALOGUE)).isEmpty();
    }

    // ── requests ──────────────────────────────────────────────────────────

    private static PlanPaymentRequest request(String plan, String cycle, long amount, long credit, long due,
                                              String status) {
        return new PlanPaymentRequest(UUID.randomUUID(), OWNER, plan, cycle, amount, credit, due, "XAF", status,
                "orange_money", T.minus(DAY.multipliedBy(5)), T.plus(DAY.multipliedBy(25)),
                T.minus(DAY.multipliedBy(5)), T.minus(DAY.multipliedBy(5)).plusSeconds(60), "mycoolpay",
                "MCP-" + UUID.randomUUID(), "SUCCESS");
    }

    @Test
    @DisplayName("confirmed Mobile Money request with credit → CONFIRMED, ledger cash + credit part")
    void confirmedRequest() {
        PlanPaymentRequest r = request("pro", "monthly", 3500, 500, 3000, "confirmed");
        MappedRequest q = BrefMonetisationPlanner.mapRequest(r, CATALOGUE).orElseThrow();
        assertThat(q.status()).isEqualTo("CONFIRMED");
        assertThat(q.provider()).isEqualTo("MYCOOLPAY");
        assertThat(q.method()).isEqualTo("ORANGE_MONEY");
        assertThat(q.reductionXaf()).isZero();
        assertThat(q.confirmedAt()).isEqualTo(r.updatedAt());
        assertThat(q.periodStart()).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(q.periodEnd()).isEqualTo(LocalDate.parse("2026-12-09"));
        assertThat(q.ledger().amountXaf()).isEqualTo(3000);
        assertThat(q.ledger().creditXaf()).isEqualTo(500);
        assertThat(q.ledger().method()).isEqualTo("MOBILE_MONEY");
        assertThat(q.ledger().id()).isEqualTo(r.id());
        assertThat(q.appRef()).isEqualTo("BREF-" + r.id());
    }

    @Test
    @DisplayName("confirmed request paid 100 % with the credit → ledger amount 0, method ACCOUNT_CREDIT")
    void creditOnly() {
        MappedRequest q = BrefMonetisationPlanner.mapRequest(request("team", "quarterly", 15000, 15000, 0,
                "confirmed"), CATALOGUE).orElseThrow();
        assertThat(q.ledger().amountXaf()).isZero();
        assertThat(q.ledger().creditXaf()).isEqualTo(15000);
        assertThat(q.ledger().method()).isEqualTo("ACCOUNT_CREDIT");
    }

    @Test
    @DisplayName("request states: failed, cancelled, pending → EXPIRED (noted), expired; no ledger")
    void states() {
        assertThat(map("failed").status()).isEqualTo("FAILED");
        assertThat(map("cancelled").status()).isEqualTo("CANCELLED");
        MappedRequest pending = map("pending");
        assertThat(pending.status()).isEqualTo("EXPIRED");
        assertThat(pending.note()).isEqualTo(ImportNote.Kind.PAYMENT_PENDING_EXPIRED);
        assertThat(pending.failureReason()).isNotBlank();
        assertThat(map("expired").status()).isEqualTo("EXPIRED");
        assertThat(map("failed").ledger()).isNull();
        assertThat(map("pending").confirmedAt()).isNull();
    }

    private static MappedRequest map(String status) {
        return BrefMonetisationPlanner.mapRequest(request("pro", "monthly", 3500, 0, 3500, status), CATALOGUE)
                .orElseThrow();
    }

    @Test
    @DisplayName("inconsistent, zero or foreign-currency request → skipped")
    void skipped() {
        assertThat(BrefMonetisationPlanner.mapRequest(request("pro", "monthly", 3500, 0, 4000, "confirmed"),
                CATALOGUE)).isEmpty();
        assertThat(BrefMonetisationPlanner.mapRequest(request("free", "monthly", 0, 0, 0, "confirmed"), CATALOGUE))
                .isEmpty();
        PlanPaymentRequest eur = request("pro", "monthly", 3500, 0, 3500, "confirmed");
        eur = new PlanPaymentRequest(eur.id(), OWNER, "pro", "monthly", 3500, 0, 3500, "EUR", "confirmed", null,
                null, null, T, T, null, null, null);
        assertThat(BrefMonetisationPlanner.mapRequest(eur, CATALOGUE)).isEmpty();
        // a discount (due < amount − credit) is kept as a reduction
        assertThat(BrefMonetisationPlanner.mapRequest(request("pro", "monthly", 3500, 0, 3000, "confirmed"),
                CATALOGUE).orElseThrow().reductionXaf()).isEqualTo(500);
    }

    // ── App Store ─────────────────────────────────────────────────────────

    private static AppleTransaction tx(String original, String product, String env, Instant purchase, Instant expires,
                                       Instant revoked) {
        return new AppleTransaction(UUID.randomUUID(), OWNER.toString(), original, "T-" + UUID.randomUUID(), product,
                "com.bref", env, revoked == null ? "active" : "revoked", purchase, expires, null, revoked, null,
                purchase, purchase);
    }

    @Test
    @DisplayName("Apple in grace: subscription BILLING_ISSUE (grace and granted = grace end), ledger for priced production purchases")
    void appleImport() {
        Instant grace = T.plus(DAY.multipliedBy(10));
        Subscription s = sub("pro", "active", T.minus(DAY.multipliedBy(32)), T.minus(DAY.multipliedBy(2)), "apple",
                grace, "O-1", 0, 0);
        AppleTransaction first = tx("O-1", "brefpoint.pro.month", "Production", T.minus(DAY.multipliedBy(62)),
                T.minus(DAY.multipliedBy(32)), null);
        AppleTransaction second = tx("O-1", "brefpoint.pro.month", "Production", T.minus(DAY.multipliedBy(32)),
                T.minus(DAY.multipliedBy(2)), null);
        AppleTransaction sandbox = tx("O-2", "brefpoint.pro.month", "Sandbox", T.minus(DAY.multipliedBy(3)),
                T.plus(DAY.multipliedBy(27)), null);
        AppleTransaction unpriced = tx("O-3", "brefpoint.business.monthly", "Production", T.minus(DAY),
                T.plus(DAY.multipliedBy(29)), null);
        AppleTransaction unknown = tx("O-4", "brefpoint.gold", "Production", T.minus(DAY), T.plus(DAY), null);
        RevenueCatEvent e1 = new RevenueCatEvent("ev-1", "RENEWAL", "O-1", 1_000L, T.minus(DAY), T.minus(DAY));
        RevenueCatEvent e2 = new RevenueCatEvent("ev-2", "BILLING_ISSUE", "O-1", 2_000L, T.minus(DAY), T.minus(DAY));
        BrefMonetisation m = new BrefMonetisation(List.of(s), List.of(), List.of(first, second, sandbox, unpriced,
                unknown), List.of(e1, e2), null, List.of(), List.of());
        PlanDecision d = BrefMonetisationPlanner.decidePlan(m, T, "FREE");
        AppleImport a = BrefMonetisationPlanner.mapApple(m, d, T, CATALOGUE);

        assertThat(a.subscriptions()).hasSize(3); // O-4: product unknown
        var o1 = a.subscriptions().stream().filter(x -> x.originalTransactionId().equals("O-1")).findFirst()
                .orElseThrow();
        assertThat(o1.id()).isEqualTo(second.id());
        assertThat(o1.status()).isEqualTo("BILLING_ISSUE");
        assertThat(o1.graceExpiresAt()).isEqualTo(grace);
        assertThat(o1.grantedExpiresAt()).isEqualTo(grace);
        assertThat(o1.lastEventMs()).isEqualTo(2_000L);
        assertThat(o1.environment()).isEqualTo("PRODUCTION");
        assertThat(o1.autoRenew()).isTrue();
        var o2 = a.subscriptions().stream().filter(x -> x.originalTransactionId().equals("O-2")).findFirst()
                .orElseThrow();
        assertThat(o2.status()).isEqualTo("ACTIVE"); // live at Apple, not the plan in force (review 24.5)
        assertThat(o2.environment()).isEqualTo("SANDBOX");
        assertThat(a.ledger()).extracting(BrefMonetisationPlanner.Ledger::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(a.ledger()).allMatch(l -> l.amountXaf() == 2500 && l.method().equals("APP_STORE")
                && l.storeTransactionId().startsWith("T-"));
        assertThat(a.notes()).contains(ImportNote.Kind.APPLE_PRODUCT_UNKNOWN, ImportNote.Kind.APPLE_PRICE_UNKNOWN,
                ImportNote.Kind.APPLE_PURCHASE_NOT_COUNTED);
    }

    @Test
    @DisplayName("review: a live Apple subscription that does not give the plan stays ACTIVE; unreadable original "
            + "noted; a purchase without expiry covers the product's cycle")
    void appleReviewCases() {
        Subscription momo = sub("team", "active", T.minus(DAY), T.plus(DAY.multipliedBy(29)), "momo", null, null, 5500,
                0);
        AppleTransaction live = tx("O-7", "brefpoint.pro.month", "Production", T.minus(DAY.multipliedBy(3)), null,
                null);
        AppleTransaction blank = tx(" ", "brefpoint.pro.month", "Production", T.minus(DAY), T.plus(DAY), null);
        AppleTransaction liveExpiring = tx("O-8", "brefpoint.pro.month", "Production", T.minus(DAY),
                T.plus(DAY.multipliedBy(29)), null);
        BrefMonetisation m = new BrefMonetisation(List.of(momo), List.of(), List.of(live, blank, liveExpiring),
                List.of(), null, List.of(), List.of());
        AppleImport a = BrefMonetisationPlanner.mapApple(m, BrefMonetisationPlanner.decidePlan(m, T, "FREE"), T,
                CATALOGUE);
        assertThat(a.subscriptions()).filteredOn(x -> x.originalTransactionId().equals("O-8")).singleElement()
                .satisfies(x -> {
                    assertThat(x.status()).isEqualTo("ACTIVE");
                    assertThat(x.autoRenew()).isTrue();
                });
        assertThat(a.notes()).contains(ImportNote.Kind.APPLE_TRANSACTION_UNREADABLE);
        assertThat(a.transactionNotes()).containsEntry(blank.id(), ImportNote.Kind.APPLE_TRANSACTION_UNREADABLE);
        BrefMonetisationPlanner.Ledger noExpiry = a.ledger().stream().filter(l -> l.id().equals(live.id()))
                .findFirst().orElseThrow();
        assertThat(noExpiry.periodEnd()).isEqualTo(noExpiry.periodStart().plusMonths(1).minusDays(1));
        assertThat(noExpiry.appleSubscriptionId()).isEqualTo(live.id());
    }

    @Test
    @DisplayName("revoked Apple purchase → REVOKED, not counted")
    void appleRevoked() {
        AppleTransaction refunded = tx("O-9", "brefpoint.pro.month", "Production", T.minus(DAY.multipliedBy(5)),
                T.plus(DAY.multipliedBy(25)), T.minus(DAY));
        BrefMonetisation m = new BrefMonetisation(List.of(), List.of(), List.of(refunded), List.of(), null, List.of(),
                List.of());
        AppleImport a = BrefMonetisationPlanner.mapApple(m, BrefMonetisationPlanner.decidePlan(m, T, "FREE"), T,
                CATALOGUE);
        assertThat(a.subscriptions().get(0).status()).isEqualTo("REVOKED");
        assertThat(a.subscriptions().get(0).autoRenew()).isFalse();
        assertThat(a.ledger()).isEmpty();
    }

    @Test
    @DisplayName("events: processed only; subject = original transaction or app user")
    void events() {
        RevenueCatEvent processed = new RevenueCatEvent("a", "RENEWAL", "O-1", 5, T, T);
        RevenueCatEvent pending = new RevenueCatEvent("b", "RENEWAL", "O-1", 6, null, T);
        BrefMonetisation m = new BrefMonetisation(List.of(), List.of(), List.of(), List.of(processed, pending), null,
                List.of(), List.of());
        assertThat(BrefMonetisationPlanner.processedEvents(m)).containsExactly(processed);
        assertThat(BrefMonetisationPlanner.originalOf(processed, OWNER)).isEqualTo("O-1");
        assertThat(BrefMonetisationPlanner.originalOf(new RevenueCatEvent("c", "TRANSFER", OWNER.toString(), 1, T, T),
                OWNER)).isNull();
        assertThat(processed.sourceId()).isEqualTo(new RevenueCatEvent("a", "X", null, 0, null, null).sourceId());
    }

    // ── SMS and counters ──────────────────────────────────────────────────

    @Test
    @DisplayName("SMS recharges: only the paid ones")
    void sms() {
        SmsRecharge paid = new SmsRecharge(UUID.randomUUID(), OWNER, 100, 2000, "XAF", "completed", "momo", T, T, T);
        SmsRecharge pending = new SmsRecharge(UUID.randomUUID(), OWNER, 50, 1000, "XAF", "pending", "momo", null, T, T);
        BrefMonetisation m = new BrefMonetisation(List.of(), List.of(), List.of(), List.of(), null,
                List.of(paid, pending), List.of());
        assertThat(BrefMonetisationPlanner.paidRecharges(m)).containsExactly(paid);
    }

    @Test
    @DisplayName("counters: this WAT month, today, lifetime free SMS; past periods dropped")
    void counters() {
        List<UsageCounter> rows = List.of(counter("sms", "2026-11", 4), counter("invoices", "2026-11", 12),
                counter("daily_ai_credits", "2026-11-15", 3), counter("sms_free", "all", 2),
                counter("email", "2026-10", 7), counter("daily_ai_credits", "2026-11-14", 9),
                counter("unknown", "2026-11", 1));
        BrefMonetisation m = new BrefMonetisation(List.of(), List.of(), List.of(), List.of(), null, List.of(), rows);
        List<MappedCounter> mapped = BrefMonetisationPlanner.mapCounters(m, T);
        assertThat(mapped).extracting(c -> c.metric() + ":" + c.period() + ":" + c.periodKey() + "=" + c.source().used())
                .containsExactlyInAnyOrder("SMS:MONTH:2026-11=4", "DOCUMENTS:MONTH:2026-11=12",
                        "AI_CREDITS:DAY:2026-11-15=3", "SMS:LIFETIME:" + QuotaPeriod.LIFETIME_KEY + "=2");
        assertThat(mapped).noneMatch(c -> c.metric() == UsageMetric.EMAILS);
    }

    private static UsageCounter counter(String metric, String key, long used) {
        return new UsageCounter(UUID.randomUUID(), OWNER, metric, key, used, T);
    }
}

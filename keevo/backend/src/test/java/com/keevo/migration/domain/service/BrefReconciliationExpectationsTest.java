package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefExpense;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.model.BrefInvoiceItem;
import com.keevo.migration.domain.model.BrefInvoicePayment;
import com.keevo.migration.domain.model.BrefMonetisation;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.ReconciliationFigures;
import com.keevo.migration.domain.model.ReconciliationFigures.Amount;
import com.keevo.migration.domain.model.ReconciliationFigures.Bucket;
import com.keevo.migration.domain.model.ReconciliationFigures.Expected;
import com.keevo.migration.domain.model.ReconciliationFigures.ExpectedCount;
import com.keevo.migration.domain.model.ReconciliationFigures.RecordCount;
import com.keevo.migration.domain.model.ReconciliationFigures.RecordsSummary;
import com.keevo.migration.domain.model.ReconciliationFigures.SampleDocument;
import com.keevo.migration.domain.model.ReconciliationFigures.SampleField;
import com.keevo.migration.domain.model.SourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("24.8 — expected Keevo figures of a Bref owner")
class BrefReconciliationExpectationsTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID SHOP_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final RecordsSummary NO_RECORDS = new RecordsSummary(Map.of(), Set.of());

    private static BrefShop shop(UUID id, Instant created) {
        return new BrefShop(id, OWNER, "Boutique", "Adresse", "food", null, null, null, null, null, "CM", true,
                created, created, null, null);
    }

    private static BrefAccount account(UUID id, String role, boolean active, Instant deleted) {
        return new BrefAccount(id, role, null, false, "+237600000001", true, null, null, "Awa", "Test",
                SHOP_A.toString(), active, false, "email", null, T, T, deleted);
    }

    private static BrefInvoice doc(UUID id, String type, String status, String total, String initial, String date,
                                   String shop, Instant created, Instant deleted, String token,
                                   List<BrefInvoiceItem> items, List<BrefInvoicePayment> payments) {
        return new BrefInvoice(id, OWNER.toString(), shop, " Client ", null, null, null, null, null, null, null,
                new BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(total),
                status, "cash", new BigDecimal(initial), null, null, date, token, null, null, null, null, type, null,
                created, created, deleted, items, payments);
    }

    private static BrefInvoice sale(String status, String total, String initial, String date, Instant created,
                                    BrefInvoicePayment... payments) {
        UUID id = UUID.randomUUID();
        return doc(id, "sale", status, total, initial, date, SHOP_A.toString(), created, null, null, List.of(),
                List.of(payments).stream().map(p -> withInvoice(p, id)).toList());
    }

    private static BrefInvoicePayment pay(String amount, boolean paid) {
        return new BrefInvoicePayment(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount), "2026-03-10",
                paid, paid ? "2026-03-10" : null, null, T, T);
    }

    private static BrefInvoicePayment withInvoice(BrefInvoicePayment p, UUID invoice) {
        return new BrefInvoicePayment(p.id(), invoice, p.amount(), p.dueDate(), p.paid(), p.paidDate(), p.notes(),
                p.createdAt(), p.updatedAt());
    }

    private static BrefExpense expense(String amount, String date, Instant deleted) {
        return new BrefExpense(UUID.randomUUID(), OWNER.toString(), SHOP_A.toString(), OWNER.toString(),
                new BigDecimal(amount), "rent", "Loyer", date, "cash", T, T, deleted);
    }

    private static BrefSnapshot snapshot(List<BrefAccount> members, List<BrefInvoice> invoices,
                                         List<BrefExpense> expenses) {
        return new BrefSnapshot(account(OWNER, "owner", true, null), List.of(shop(SHOP_B, T.plusSeconds(5)),
                shop(SHOP_A, T)), members, List.of(), List.of(), invoices, expenses, SourceKind.DATABASE);
    }

    private static Map<String, ExpectedCount> counts(Expected e) {
        return e.counts().stream().collect(Collectors.toMap(ExpectedCount::key, Function.identity()));
    }

    @Test
    @DisplayName("counts: deleted and left-out members, merged products, zero payments and quote acomptes explained")
    void counts() {
        UUID recorded = UUID.randomUUID();
        List<BrefAccount> members = List.of(account(UUID.randomUUID(), "seller", true, null),
                account(UUID.randomUUID(), "seller", true, T), account(UUID.randomUUID(), "seller", false, null),
                account(recorded, "viewer", false, null));
        BrefInvoice quote = doc(UUID.randomUUID(), "quote", "pending", "100", "50", "2026-03-01",
                SHOP_A.toString(), T, null, null, List.of(), List.of());
        BrefInvoice zero = sale("pending", "100", "0.4", "2026-03-01", T, pay("0.2", true), pay("40", false));
        BrefInvoice deleted = doc(UUID.randomUUID(), "sale", "paid", "10", "10", "2026-03-01", SHOP_A.toString(), T,
                T, null, List.of(new BrefInvoiceItem(UUID.randomUUID(), UUID.randomUUID(), null, "A", null,
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN)), List.of());
        RecordsSummary records = new RecordsSummary(Map.of(
                RecordsSummary.pair("product", "products"), new RecordCount(5, 3, 1, T),
                RecordsSummary.pair("customer", "clients"), new RecordCount(2, 2, 0, T)), Set.of(recorded));

        Expected e = BrefReconciliationExpectations.expected(snapshot(members, List.of(quote, zero, deleted),
                List.of()), null, records, null, "FREE", UUID.randomUUID());
        Map<String, ExpectedCount> c = counts(e);

        assertThat(c.get(ReconciliationFigures.STORES).expected()).isEqualTo(2);
        assertThat(c.get(ReconciliationFigures.OWNER).expected()).isEqualTo(1);
        ExpectedCount team = c.get(ReconciliationFigures.TEAM);
        assertThat(team.bref()).isEqualTo(4);
        assertThat(team.explained()).isEqualTo(2); // one deleted, one deactivated and never recorded
        assertThat(team.explanation()).contains("supprimé", "non invité");
        assertThat(c.get(ReconciliationFigures.PRODUCTS).explained()).isEqualTo(2); // 5 rows → 3 products
        assertThat(c.get(ReconciliationFigures.CLIENTS).explained()).isZero();
        assertThat(c.get(ReconciliationFigures.documents("QUOTE", "DRAFT")).expected()).isEqualTo(1);
        assertThat(c.get(ReconciliationFigures.documents("INVOICE", "ISSUED")).expected()).isEqualTo(1);
        assertThat(c.get(ReconciliationFigures.DELETED_DOCUMENTS).expected()).isEqualTo(1);
        assertThat(c.get(ReconciliationFigures.documents("INVOICE", "PAID")).expected()).isZero();
        assertThat(c.get(ReconciliationFigures.LINES).expected()).isEqualTo(1);
        ExpectedCount payments = c.get(ReconciliationFigures.PAYMENTS);
        // quote acompte, sale acompte 0.4, 0.2, 40 (unpaid), deleted sale acompte 10
        assertThat(payments.bref()).isEqualTo(5);
        assertThat(payments.explained()).isEqualTo(3); // 0.4 and 0.2 round to 0, the quote acompte
        assertThat(payments.expected()).isEqualTo(2);
    }

    @Test
    @DisplayName("sales per shop and month: cancelled, deleted and quotes out; invalid date → creation day in Douala")
    void sales() {
        BrefInvoice march = sale("paid", "1500.50", "0", "2026-03-31", T);
        BrefInvoice march2 = sale("pending", "100", "0", " 2026-03-02 ", T);
        BrefInvoice noDate = sale("pending", "200", "0", "31/03/2026", Instant.parse("2026-03-31T23:30:00Z"));
        BrefInvoice cancelled = sale("cancelled", "999", "0", "2026-03-05", T);
        BrefInvoice quote = doc(UUID.randomUUID(), "quote", "pending", "999", "0", "2026-03-05", SHOP_A.toString(),
                T, null, null, List.of(), List.of());

        Map<Bucket, Amount> sales = BrefReconciliationExpectations.sales(List.of(march, march2, noDate, cancelled,
                quote));

        assertThat(sales.get(new Bucket(SHOP_A, "2026-03"))).isEqualTo(new Amount(new BigDecimal("1600.50"), 2));
        // 23:30 UTC = 00:30 the next day in Africa/Douala
        assertThat(sales.get(new Bucket(SHOP_A, "2026-04"))).isEqualTo(new Amount(new BigDecimal("200"), 1));
        assertThat(sales).hasSize(2);
    }

    @Test
    @DisplayName("collected: sale acomptes and paid instalments of every document; quote acompte out")
    void collected() {
        BrefInvoice s = sale("partial", "1000", "200", "2026-03-01", T, pay("300", true), pay("500", false));
        BrefInvoice quote = doc(UUID.randomUUID(), "quote", "pending", "100", "50", "2026-03-01",
                SHOP_A.toString(), T, null, null, List.of(), List.of());
        BrefInvoice cancelled = sale("cancelled", "1000", "100.50", "2026-03-01", T);

        assertThat(BrefReconciliationExpectations.collected(List.of(s, quote, cancelled)))
                .isEqualTo(new Amount(new BigDecimal("600.50"), 3));
    }

    @Test
    @DisplayName("outstanding: Bref dashboard formula — open sales only, never negative")
    void outstanding() {
        BrefInvoice open = sale("partial", "1000", "200", "2026-03-01", T, pay("300", true), pay("500", false));
        BrefInvoice overpaid = sale("pending", "100", "150", "2026-03-01", T);
        BrefInvoice paid = sale("paid", "1000", "100", "2026-03-01", T);
        BrefInvoice overdue = sale(" OVERDUE ", "50.5", "0", "2026-03-01", T);

        Amount a = BrefReconciliationExpectations.outstanding(List.of(open, overpaid, paid, overdue));

        assertThat(a.sum()).isEqualByComparingTo("550.5"); // 500 + 0 + 50.5
        assertThat(a.items()).isEqualTo(6); // 3 documents + acompte + paid row + overpaid acompte
    }

    @Test
    @DisplayName("expenses per month: live ones only, exact decimals")
    void expenses() {
        Map<String, BigDecimal> months = BrefReconciliationExpectations.expenses(List.of(
                expense("15000.50", "2026-03-05", null), expense("100", "2026-03-06", null),
                expense("999", "2026-03-07", T), expense("20", "2026-04-01", null)));

        assertThat(months.get("2026-03")).isEqualByComparingTo("15100.50");
        assertThat(months.get("2026-04")).isEqualByComparingTo("20");
        assertThat(months).hasSize(2);
    }

    @Test
    @DisplayName("sample: the 5 largest sales then documents drawn with the batch seed (same seed, same sample)")
    void sample() {
        List<BrefInvoice> docs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            docs.add(sale("paid", Integer.toString(1000 + i), "0", "2026-03-01", T.plusSeconds(i)));
        }
        UUID seed = UUID.randomUUID();
        Expected e1 = BrefReconciliationExpectations.expected(snapshot(List.of(), docs, List.of()), null, NO_RECORDS,
                null, "FREE", seed);
        Expected e2 = BrefReconciliationExpectations.expected(snapshot(List.of(), docs, List.of()), null, NO_RECORDS,
                null, "FREE", seed);

        assertThat(e1.sample()).hasSize(20);
        assertThat(e1.sample().subList(0, 5)).extracting(d -> d.fields().get(SampleField.TOTAL))
                .containsExactly("1029", "1028", "1027", "1026", "1025");
        assertThat(e1.sample()).extracting(SampleDocument::id).doesNotHaveDuplicates()
                .containsExactlyElementsOf(e2.sample().stream().map(SampleDocument::id).toList());
    }

    @Test
    @DisplayName("sample fields: 24.4 mapping — francs half-up, BRF numbering with suffix, default client, link")
    void sampleFields() {
        UUID first = UUID.fromString("abcdef12-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("abcdef12-0000-4000-8000-000000000002");
        UUID token = UUID.randomUUID();
        BrefInvoiceItem item = new BrefInvoiceItem(UUID.randomUUID(), second, null, "A", null, BigDecimal.ONE,
                new BigDecimal("999.5"), new BigDecimal("999.5"));
        BrefInvoice older = doc(first, "sale", "paid", "10", "10", "2026-03-01", SHOP_A.toString(), T, null, null,
                List.of(), List.of());
        BrefInvoice newer = new BrefInvoice(second, OWNER.toString(), SHOP_B.toString().toUpperCase(), "  ", null,
                null, null, null, null, null, null, new BigDecimal("999.5"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("1500.50"), "pending", "cash", new BigDecimal("500.4"), null, null,
                "bad", token.toString(), null, null, null, null, "sale", null, T.plusSeconds(10), T.plusSeconds(10),
                null, List.of(item), List.of(new BrefInvoicePayment(UUID.randomUUID(), second,
                new BigDecimal("0.3"), "2026-03-10", true, "2026-03-10", null, T, T)));

        Map<UUID, String> numbers = BrefReconciliationExpectations.numbers(List.of(newer, older));
        Map<SampleField, String> f = BrefReconciliationExpectations.fields(newer, numbers.get(second));

        assertThat(numbers.get(first)).isEqualTo("BRF-ABCDEF12");
        assertThat(f).containsEntry(SampleField.NUMBER, "BRF-ABCDEF12-2").containsEntry(SampleField.KIND, "INVOICE")
                .containsEntry(SampleField.STATUS, "ISSUED").containsEntry(SampleField.TOTAL, "1501")
                .containsEntry(SampleField.SUBTOTAL, "1000").containsEntry(SampleField.CLIENT_NAME, "Client")
                .containsEntry(SampleField.STORE, SHOP_B.toString()).containsEntry(SampleField.ISSUE_DATE,
                        "2026-03-01")
                .containsEntry(SampleField.LINE_COUNT, "1").containsEntry(SampleField.LINES_TOTAL, "1000")
                .containsEntry(SampleField.PAYMENT_COUNT, "1").containsEntry(SampleField.PAID, "500")
                .containsEntry(SampleField.PUBLIC_LINK, token.toString())
                .containsEntry(SampleField.DELETED, "false");
    }

    @Test
    @DisplayName("links: live documents with a readable token only, at most 5; shops labelled by creation order")
    void linksAndStores() {
        List<BrefInvoice> docs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            docs.add(doc(UUID.randomUUID(), "sale", "paid", "10", "10", "2026-03-01", SHOP_A.toString(), T, null,
                    UUID.randomUUID().toString(), List.of(), List.of()));
        }
        docs.add(doc(UUID.randomUUID(), "sale", "paid", "10", "10", "2026-03-01", SHOP_A.toString(), T, T,
                UUID.randomUUID().toString(), List.of(), List.of()));
        docs.add(doc(UUID.randomUUID(), "sale", "paid", "10", "10", "2026-03-01", SHOP_A.toString(), T, null,
                "not-a-token", List.of(), List.of()));

        Expected e = BrefReconciliationExpectations.expected(snapshot(List.of(), docs, List.of()), null, NO_RECORDS,
                null, "FREE", UUID.randomUUID());

        assertThat(e.links()).hasSize(5).allSatisfy(l -> assertThat(l.label()).startsWith("Facture BRF-"));
        assertThat(e.storeLabels()).containsEntry(SHOP_A, "Boutique 1").containsEntry(SHOP_B, "Boutique 2");
        assertThat(e.subscription()).isNull(); // no plan recorded: the reconciler reports it
    }

    @Test
    @DisplayName("subscription: plan in force at the import's instant, credit = Bref credit + scheduled periods")
    void subscription() {
        BrefMonetisation.Subscription active = new BrefMonetisation.Subscription(UUID.randomUUID(), OWNER, "pro",
                "active", "monthly", T, T.plusSeconds(30L * 86400), 5000, 0, "momo", null, null, T, T);
        BrefMonetisation m = new BrefMonetisation(List.of(active), List.of(), List.of(), List.of(),
                new BrefMonetisation.AccountCredit(UUID.randomUUID(), OWNER, 1200, T), List.of(), List.of());

        ReconciliationFigures.ExpectedSubscription s = BrefReconciliationExpectations.subscription(m,
                T.plusSeconds(60), "FREE");

        assertThat(s.planCode()).isEqualTo(BrefMonetisationPlanner.decidePlan(m, T.plusSeconds(60), "FREE")
                .planCode());
        assertThat(s.expiresAt()).isEqualTo(T.plusSeconds(30L * 86400));
        assertThat(s.creditXaf()).isEqualTo(1200);
        assertThat(BrefReconciliationExpectations.subscription(m, null, "FREE")).isNull();
    }
}

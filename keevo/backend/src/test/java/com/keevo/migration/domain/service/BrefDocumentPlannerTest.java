package com.keevo.migration.domain.service;

import com.keevo.invoicing.api.InvoiceImportPort.Document;
import com.keevo.invoicing.api.InvoiceImportPort.Line;
import com.keevo.invoicing.api.InvoiceImportPort.Payment;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.model.BrefInvoiceItem;
import com.keevo.migration.domain.model.BrefInvoicePayment;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.ImportNote.Kind;
import com.keevo.migration.domain.service.BrefDocumentPlanner.Lookup;
import com.keevo.migration.domain.service.BrefDocumentPlanner.Plan;
import com.keevo.migration.domain.service.BrefDocumentPlanner.PlannedDocument;
import com.keevo.migration.domain.service.BrefDocumentPlanner.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 24.4 — the document planner, one test per row of the spec's I/O matrix (synthetic data only). */
@DisplayName("24.4 — Bref document planner")
class BrefDocumentPlannerTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-03-01T08:00:00Z");
    private static final BrefShop SHOP_A = shop("Boutique A", T0, null);
    private static final BrefShop SHOP_B = shop("Boutique B", T0.plusSeconds(60), null);

    private static BrefShop shop(String name, Instant created, Instant deleted) {
        return new BrefShop(UUID.randomUUID(), OWNER, name, "Rue " + name, "electronics", null, "699000111", null,
                "M0123", "RC/1", "CM", true, created, created, deleted, null);
    }

    /** A builder of Bref invoices with sensible defaults (a paid sale of 1 000 F, one line). */
    private static final class Inv {
        UUID id = UUID.randomUUID();
        String shopId = SHOP_A.id().toString();
        String type = "sale";
        String status = "paid";
        String subtotal = "1000";
        String tax = "0";
        String discount = "0";
        String delivery = "0";
        String total = "1000";
        String initial = "1000";
        String date = "2026-03-01";
        String phone = "699000222";
        String name = "Client";
        String token = null;
        String notes = null;
        Instant created = T0;
        Instant validated = null;
        Instant deleted = null;
        Instant cancelled = null;
        List<BrefInvoiceItem> items = new ArrayList<>();
        List<BrefInvoicePayment> payments = new ArrayList<>();

        Inv item(String name, String reference, String quantity, String unit, String lineTotal) {
            items.add(new BrefInvoiceItem(UUID.randomUUID(), id, reference, name, null, new BigDecimal(quantity),
                    new BigDecimal(unit), new BigDecimal(lineTotal)));
            return this;
        }

        Inv payment(String amount, String due, boolean paid, String paidDate) {
            payments.add(new BrefInvoicePayment(UUID.randomUUID(), id, new BigDecimal(amount), due, paid, paidDate,
                    null, created, created.plusSeconds(3600)));
            return this;
        }

        BrefInvoice build() {
            if (items.isEmpty()) {
                item("Riz", "REF-1", "1", subtotal, subtotal);
            }
            return new BrefInvoice(id, OWNER.toString(), shopId, name, phone, null, null, null, null, null, null,
                    new BigDecimal(subtotal), new BigDecimal(tax), new BigDecimal(discount), new BigDecimal(delivery),
                    new BigDecimal(total), status, "cash", new BigDecimal(initial), null, notes, date, token, null,
                    cancelled, null, null, type, validated, created, created.plusSeconds(7200), deleted, items,
                    payments);
        }
    }

    private static Plan plan(Lookup lookup, BrefInvoice... invoices) {
        return BrefDocumentPlanner.plan(List.of(SHOP_A, SHOP_B), List.of(invoices), OWNER, lookup);
    }

    private static PlannedDocument one(BrefInvoice invoice) {
        return plan(new Lookup(Map.of(), Map.of()), invoice).documents().get(0);
    }

    @Test
    @DisplayName("happy path: quote first, sale numbered BRF-, acompte as one paid row, line linked to its product, client by shop and phone")
    void happyPath() {
        Inv sale = new Inv();
        sale.item("Riz parfumé  5 KG", "REF-AB", "2", "500", "1000");
        sale.created = T0.plusSeconds(10);
        Inv quote = new Inv();
        quote.type = "quote";
        quote.status = "pending";
        quote.initial = "0";
        quote.created = T0.plusSeconds(20);
        UUID product = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        Lookup lookup = new Lookup(
                Map.of(BrefDocumentPlanner.shopKey(SHOP_A.id().toString(), "nm:riz parfume 5 kg"), product),
                Map.of(BrefDocumentPlanner.shopKey(SHOP_A.id().toString(), "+237699000222"), client));
        Plan plan = plan(lookup, sale.build(), quote.build());
        assertThat(plan.settingsShop()).isEqualTo(SHOP_A);
        assertThat(plan.settings().legalName()).isEqualTo("Boutique A");
        assertThat(plan.settings().phone()).isEqualTo("+237699000111");

        PlannedDocument q = plan.documents().get(0);
        assertThat(q.document().quote()).isTrue();
        assertThat(q.document().status()).isEqualTo("DRAFT");
        assertThat(q.document().number()).isNull();
        assertThat(q.document().issueDate()).isNull();
        assertThat(q.document().seller()).isNull();
        assertThat(q.payments()).isEmpty();

        PlannedDocument s = plan.documents().get(1);
        Document d = s.document();
        assertThat(d.quote()).isFalse();
        assertThat(d.status()).isEqualTo("PAID");
        assertThat(d.number()).isEqualTo(BrefDocumentPlanner.legacyReference(sale.id)).startsWith("BRF-").hasSize(12);
        assertThat(d.legacyReference()).isEqualTo(d.number());
        assertThat(d.issueDate()).isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(d.issuedAt()).isEqualTo(sale.created);
        assertThat(d.storeId()).isEqualTo(SHOP_A.id());
        assertThat(d.clientId()).isEqualTo(client);
        assertThat(d.clientPhone()).isEqualTo("+237699000222");
        assertThat(d.seller().legalName()).isEqualTo("Boutique A");
        assertThat(d.totalTtc()).isEqualTo(1000);
        assertThat(d.totalHt()).isEqualTo(1000);
        assertThat(d.vatRegistered()).isFalse();
        assertThat(d.paymentMethod()).isEqualTo("Espèces");
        assertThat(d.createdBy()).isEqualTo(OWNER);
        assertThat(s.notes()).isEmpty();
        assertThat(s.note()).isNull();
        Line line = s.lines().get(0).row();
        assertThat(line.productId()).isEqualTo(product);
        assertThat(line.position()).isEqualTo(1);
        assertThat(line.quantity()).isEqualByComparingTo("2");
        Row<Payment> acompte = s.payments().get(0);
        assertThat(acompte.row().id()).isEqualTo(BrefDocumentPlanner.acompteId(sale.id));
        assertThat(acompte.row().amount()).isEqualTo(1000);
        assertThat(acompte.row().paidAt()).isEqualTo(sale.created);
        assertThat(acompte.sourceTable()).isEqualTo("invoice");
        assertThat(d.paidAt()).isEqualTo(sale.created);
    }

    @Test
    @DisplayName("partial sale with instalments: paid one dated its civil day at noon (Douala), unpaid one due, VAT rate from tax")
    void instalmentsAndVat() {
        Inv sale = new Inv();
        sale.status = "partial";
        sale.subtotal = "10000";
        sale.discount = "1000";
        sale.tax = "1732.50";
        sale.delivery = "500";
        sale.total = "11232.50";
        sale.initial = "2000";
        sale.items.clear();
        sale.item("Pagne", "REF-P", "2", "5000", "10000");
        sale.payment("4000", "2026-03-10", true, "2026-03-09");
        sale.payment("5232.50", "2026-04-10", false, null);
        PlannedDocument p = one(sale.build());
        Document d = p.document();
        assertThat(d.status()).isEqualTo("ISSUED");
        assertThat(d.vatRegistered()).isTrue();
        assertThat(d.vatRate()).isEqualByComparingTo("19.25");
        assertThat(d.subtotal()).isEqualTo(10000);
        assertThat(d.discount()).isEqualTo(1000);
        assertThat(d.totalHt()).isEqualTo(9000);
        assertThat(d.totalVat()).isEqualTo(1733); // 1732.50 rounded HALF_UP, noted
        assertThat(d.totalTtc()).isEqualTo(11233);
        assertThat(p.notes()).contains(Kind.DOCUMENT_AMOUNT_NOT_INTEGER).doesNotContain(Kind.DOCUMENT_TOTAL_MISMATCH);
        assertThat(p.note()).contains("Valeurs Bref").contains("TVA Bref 1732.5").contains("total Bref 11232.5");
        assertThat(p.payments()).hasSize(3);
        Payment paid = p.payments().get(1).row();
        assertThat(paid.paidAt()).isEqualTo(Instant.parse("2026-03-09T11:00:00Z"));
        assertThat(paid.dueDate()).isEqualTo(LocalDate.parse("2026-03-10"));
        Payment due = p.payments().get(2).row();
        assertThat(due.paidAt()).isNull();
        assertThat(due.amount()).isEqualTo(5233);
        assertThat(p.payments().get(2).notes()).contains(Kind.DOCUMENT_AMOUNT_NOT_INTEGER);
        Line line = p.lines().get(0).row();
        assertThat(line.vatRate()).isEqualByComparingTo("19.25");
        assertThat(line.totalHt()).isEqualTo(10000);
        assertThat(line.totalVat()).isEqualTo(1925);
        assertThat(line.totalTtc()).isEqualTo(11925);
        assertThat(line.productId()).isNull();
        assertThat(p.lines().get(0).notes()).containsExactly(Kind.LINE_WITHOUT_PRODUCT);
    }

    @Test
    @DisplayName("total anomaly: Bref total kept, noted with both values; sub-total vs lines too")
    void totalMismatch() {
        Inv sale = new Inv();
        sale.subtotal = "1000";
        sale.total = "1500";
        sale.initial = "1500";
        sale.items.clear();
        sale.item("Riz", "REF-1", "1", "800", "800");
        PlannedDocument p = one(sale.build());
        assertThat(p.document().totalTtc()).isEqualTo(1500);
        assertThat(p.notes()).contains(Kind.DOCUMENT_TOTAL_MISMATCH);
        assertThat(p.note()).contains("total Bref 1500, recalcul 1000").contains("sous-total Bref 1000, somme des "
                + "lignes 800");
    }

    @Test
    @DisplayName("a difference of 1 F or less is not an anomaly")
    void oneFrancTolerance() {
        Inv sale = new Inv();
        sale.total = "1001";
        sale.initial = "1001";
        assertThat(one(sale.build()).notes()).doesNotContain(Kind.DOCUMENT_TOTAL_MISMATCH);
    }

    @Test
    @DisplayName("cents: 1500.50 stored 1501, exact value in the record note")
    void cents() {
        Inv sale = new Inv();
        sale.subtotal = "1500.50";
        sale.total = "1500.50";
        sale.initial = "1500.50";
        sale.items.clear();
        sale.item("Riz", "REF-1", "1", "1500.50", "1500.50");
        PlannedDocument p = one(sale.build());
        assertThat(p.document().totalTtc()).isEqualTo(1501);
        assertThat(p.notes()).contains(Kind.DOCUMENT_AMOUNT_NOT_INTEGER);
        assertThat(p.note()).contains("total Bref 1500.5");
        assertThat(p.lines().get(0).note()).contains("total de ligne Bref 1500.5");
    }

    @Test
    @DisplayName("paid in Bref without full collection: PAID kept, noted; pending but collected: noted; overpaid: noted")
    void collectionAnomalies() {
        Inv paid = new Inv();
        paid.initial = "300";
        PlannedDocument p = one(paid.build());
        assertThat(p.document().status()).isEqualTo("PAID");
        assertThat(p.notes()).contains(Kind.DOCUMENT_PAID_NOT_COLLECTED);
        assertThat(p.note()).contains("encaissé 300 sur 1000");

        Inv pending = new Inv();
        pending.status = "pending";
        pending.initial = "0";
        pending.payment("1000", "2026-03-02", true, "2026-03-02");
        PlannedDocument q = one(pending.build());
        assertThat(q.document().status()).isEqualTo("ISSUED");
        assertThat(q.notes()).contains(Kind.DOCUMENT_STATE_DIFFERS);

        Inv over = new Inv();
        over.payment("500", "2026-03-02", true, "2026-03-02");
        PlannedDocument o = one(over.build());
        assertThat(o.notes()).contains(Kind.DOCUMENT_OVERPAID);
        assertThat(o.payments()).hasSize(2);
    }

    @Test
    @DisplayName("payment edges: 0 F skipped, paid without date → its updatedAt, unpaid with bad due date → issue date")
    void paymentEdges() {
        Inv sale = new Inv();
        sale.status = "partial";
        sale.initial = "0";
        sale.payment("0", "2026-03-05", false, null);
        sale.payment("200", "2026-03-05", true, null);
        sale.payment("300", "05/03/2026", false, null);
        BrefInvoice built = sale.build();
        PlannedDocument p = one(built);
        assertThat(p.notes()).contains(Kind.PAYMENT_ZERO_SKIPPED);
        assertThat(p.payments()).hasSize(2);
        Row<Payment> noDate = p.payments().get(0);
        assertThat(noDate.row().paidAt()).isEqualTo(built.payments().get(1).updatedAt());
        assertThat(noDate.notes()).contains(Kind.PAYMENT_DATE_INVALID);
        Row<Payment> badDue = p.payments().get(1);
        assertThat(badDue.row().dueDate()).isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(badDue.notes()).contains(Kind.PAYMENT_DATE_INVALID);
    }

    @Test
    @DisplayName("prefix clash: the older sale keeps BRF-XXXXXXXX, the newer gets -2; both legacy references unchanged")
    void prefixClash() {
        UUID older = UUID.fromString("abcdef12-0000-4000-8000-000000000001");
        UUID newer = UUID.fromString("abcdef12-0000-4000-8000-000000000002");
        Inv a = new Inv();
        a.id = older;
        Inv b = new Inv();
        b.id = newer;
        b.created = T0.plusSeconds(5);
        Plan plan = plan(new Lookup(Map.of(), Map.of()), b.build(), a.build());
        Document first = plan.documents().get(0).document();
        Document second = plan.documents().get(1).document();
        assertThat(first.number()).isEqualTo("BRF-ABCDEF12");
        assertThat(second.number()).isEqualTo("BRF-ABCDEF12-2");
        assertThat(second.legacyReference()).isEqualTo("BRF-ABCDEF12");
        assertThat(plan.documents().get(1).notes()).contains(Kind.DOCUMENT_NUMBER_SUFFIXED);
    }

    @Test
    @DisplayName("long texts cut (client name 200, line 500) and noted; blank client name → « Client »")
    void longTexts() {
        Inv sale = new Inv();
        sale.name = "N".repeat(250);
        sale.items.clear();
        sale.item("L".repeat(600), "REF-1", "1", "1000", "1000");
        PlannedDocument p = one(sale.build());
        assertThat(p.document().clientName()).hasSize(200);
        assertThat(p.notes()).contains(Kind.DOCUMENT_FIELD_SHORTENED);
        assertThat(p.lines().get(0).row().description()).hasSize(500);
        assertThat(p.lines().get(0).notes()).contains(Kind.DOCUMENT_FIELD_SHORTENED);

        Inv blank = new Inv();
        blank.name = "  ";
        assertThat(one(blank.build()).document().clientName()).isEqualTo("Client");
    }

    @Test
    @DisplayName("cancelled, deleted, invalid date, validated quote: mapped as Bref had them")
    void statusesAndDates() {
        Inv cancelled = new Inv();
        cancelled.status = "cancelled";
        cancelled.token = UUID.randomUUID().toString();
        PlannedDocument c = one(cancelled.build());
        assertThat(c.document().status()).isEqualTo("CANCELLED");
        assertThat(c.document().cancellationReason()).isEqualTo("Annulée dans Bref");
        assertThat(c.document().cancelledAt()).isNotNull();
        assertThat(c.document().legacyToken()).isEqualTo(UUID.fromString(cancelled.token));

        Inv deleted = new Inv();
        deleted.deleted = T0.plusSeconds(99);
        deleted.token = UUID.randomUUID().toString();
        PlannedDocument del = one(deleted.build());
        assertThat(del.document().deletedAt()).isEqualTo(deleted.deleted);
        assertThat(del.document().legacyToken()).isNull();
        assertThat(del.notes()).contains(Kind.DOCUMENT_DELETED);

        Inv badDate = new Inv();
        badDate.date = "31/02/2026";
        badDate.created = Instant.parse("2026-03-01T23:30:00Z"); // 00:30 on 2 March in Douala
        PlannedDocument b = one(badDate.build());
        assertThat(b.document().issueDate()).isEqualTo(LocalDate.parse("2026-03-02"));
        assertThat(b.notes()).contains(Kind.DOCUMENT_DATE_INVALID);

        Inv validated = new Inv();
        validated.validated = T0.plusSeconds(500);
        validated.notes = "Livraison demain";
        PlannedDocument v = one(validated.build());
        assertThat(v.document().issuedAt()).isEqualTo(validated.validated);
        assertThat(v.payments().get(0).row().paidAt()).isEqualTo(validated.validated);
        assertThat(v.document().legacyNotes()).isEqualTo("Livraison demain");

        Inv cancelledQuote = new Inv();
        cancelledQuote.type = "quote";
        cancelledQuote.status = "cancelled";
        cancelledQuote.initial = "0";
        assertThat(one(cancelledQuote.build()).document().status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a payment recorded on a quote is kept and noted")
    void paymentOnQuote() {
        Inv quote = new Inv();
        quote.type = "quote";
        quote.status = "pending";
        quote.initial = "0";
        quote.payment("100", "2026-03-05", false, null);
        PlannedDocument p = one(quote.build());
        assertThat(p.payments()).singleElement().satisfies(r -> assertThat(r.notes()).contains(Kind.PAYMENT_ON_QUOTE));
    }

    @Test
    @DisplayName("several lines numbered 1..n in Bref's order; missing names noted as missing")
    void linesAndMissingValues() {
        Inv sale = new Inv();
        sale.name = " ";
        sale.items.clear();
        sale.item("Savon", "REF-1", "2", "250", "500");
        sale.item("  ", "REF-2", "1", "300", "300");
        sale.item("Bougie", "REF-3", "1", "200", "200");
        PlannedDocument p = one(sale.build());
        assertThat(p.lines()).extracting(r -> r.row().position()).containsExactly(1, 2, 3);
        assertThat(p.lines()).extracting(r -> r.row().description()).containsExactly("Savon", "Article", "Bougie");
        assertThat(p.lines().get(1).notes()).contains(Kind.DOCUMENT_VALUE_MISSING);
        assertThat(p.document().clientName()).isEqualTo("Client");
        assertThat(p.notes()).contains(Kind.DOCUMENT_VALUE_MISSING).doesNotContain(Kind.DOCUMENT_FIELD_SHORTENED);
    }

    @Test
    @DisplayName("an acompte on a quote and an unreadable public token are noted, never silently dropped")
    void quoteAcompteAndBadToken() {
        Inv quote = new Inv();
        quote.type = "quote";
        quote.status = "pending";
        quote.initial = "500";
        PlannedDocument q = one(quote.build());
        assertThat(q.payments()).isEmpty();
        assertThat(q.notes()).contains(Kind.PAYMENT_ON_QUOTE);
        assertThat(q.note()).contains("acompte sur devis 500");

        Inv sale = new Inv();
        sale.token = "pas-un-uuid";
        PlannedDocument s = one(sale.build());
        assertThat(s.document().legacyToken()).isNull();
        assertThat(s.notes()).contains(Kind.DOCUMENT_LINK_DROPPED);
    }

    @Test
    @DisplayName("VAT without a taxable base is kept and noted")
    void taxWithoutBase() {
        Inv sale = new Inv();
        sale.subtotal = "100";
        sale.discount = "100";
        sale.tax = "50";
        sale.total = "50";
        sale.initial = "50";
        sale.items.clear();
        sale.item("Riz", "REF-1", "1", "100", "100");
        PlannedDocument p = one(sale.build());
        assertThat(p.document().totalVat()).isEqualTo(50);
        assertThat(p.document().vatRate()).isEqualByComparingTo("0");
        assertThat(p.notes()).contains(Kind.DOCUMENT_TOTAL_MISMATCH);
        assertThat(p.note()).contains("sans base taxable");
    }

    @Test
    @DisplayName("the note never exceeds 300 characters")
    void noteBounded() {
        Inv sale = new Inv();
        sale.subtotal = "1000.40";
        sale.total = "5000.30";
        sale.initial = "10.10";
        sale.name = "N".repeat(300);
        sale.date = "x";
        PlannedDocument p = one(sale.build());
        assertThat(p.note()).hasSizeLessThanOrEqualTo(BrefDocumentPlanner.MAX_NOTE).startsWith("Valeurs Bref");
    }

    @Test
    @DisplayName("deterministic: the same snapshot gives the same plan")
    void deterministic() {
        BrefInvoice a = new Inv().build();
        BrefInvoice b = new Inv().build();
        assertThat(plan(new Lookup(Map.of(), Map.of()), a, b).documents().stream().map(x -> x.document().number()))
                .containsExactlyElementsOf(plan(new Lookup(Map.of(), Map.of()), b, a).documents().stream()
                        .map(x -> x.document().number()).toList());
    }
}

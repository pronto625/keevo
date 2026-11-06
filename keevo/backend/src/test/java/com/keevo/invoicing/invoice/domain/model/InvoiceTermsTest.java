package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.VatRates;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Invoice terms — discount, delivery, own VAT rate, exemption, due date, credit notes (Story 20.5)")
class InvoiceTermsTest {

    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);
    /** A Cameroon seller, VAT registered, prices without VAT (the mode of tenants coming from Bref). */
    private static final InvoicingSettings HT_SELLER = new InvoicingSettings("Boutique Awa SARL", "Akwa", null, null,
            null, true, new BigDecimal("19.25"), false, null, null);
    private static final InvoicingSettings TTC_SELLER = new InvoicingSettings("Boutique Awa SARL", "Akwa", null, null,
            null, true, new BigDecimal("19.25"), true, null, null);
    private static final ClientInfo CLIENT = new ClientInfo(null, "Société Ndolo", "P01", "Bonapriso", "+237690000001",
            "RC/DLA/2021/A/77", "compta@ndolo.cm", "M. Ndolo", "ndolo.cm");
    private static final List<LineInput> LINES = List.of(
            new LineInput("Sac de riz", new BigDecimal("2"), 150_000, VatTreatment.STANDARD, UUID.randomUUID(),
                    "RIZ-25"),
            new LineInput("Savon", new BigDecimal("1"), 17_700, VatTreatment.STANDARD));

    private static Invoice draft(InvoiceTerms terms, InvoicingSettings settings) {
        return Invoice.draft(UUID.randomUUID(), CLIENT, LINES, terms, settings, null, T0);
    }

    @Test
    @DisplayName("V1 body (no terms): totals identical to the pre-20.5 draft, no mention, the seller's rate")
    void noTermsUnchanged() {
        Invoice before = Invoice.draft(UUID.randomUUID(), CLIENT, LINES, TTC_SELLER, null, T0);
        Invoice after = draft(InvoiceTerms.NONE, TTC_SELLER);
        assertThat(after.totals()).isEqualTo(before.totals())
                .isEqualTo(VatCalculator.totals(VatCalculator.lines(LINES, true, new BigDecimal("19.25"), true)));
        assertThat(after.vatMention()).isNull();
        assertThat(after.vatRate()).isEqualByComparingTo("19.25");
    }

    @Test
    @DisplayName("HT seller: discount and delivery give Bref's totals; client fields and line product/reference kept")
    void brefTotals() {
        Invoice d = draft(new InvoiceTerms(2_500, 2_000, null, false, DAY.plusDays(30)), HT_SELLER);
        assertThat(d.totals()).isEqualTo(new InvoiceTotals(315_200, 60_676, 377_876, 317_700, 2_500, 2_000));
        assertThat(d.client().rccm()).isEqualTo("RC/DLA/2021/A/77");
        assertThat(d.client().website()).isEqualTo("ndolo.cm");
        assertThat(d.lines().get(0).reference()).isEqualTo("RIZ-25");
        assertThat(d.lines().get(0).productId()).isEqualTo(LINES.get(0).productId());
        Invoice issued = d.issue("FAC-2026-000001", DAY, HT_SELLER, T0);
        assertThat(issued.totals()).isEqualTo(d.totals());
        assertThat(issued.terms()).isEqualTo(d.terms());
        assertThat(issued.lines().get(0).reference()).isEqualTo("RIZ-25");
    }

    @Test
    @DisplayName("exempt: VAT 0, rate 0, « Exonéré de TVA »; own rate 18 % on a CM seller is applied")
    void exemptAndOwnRate() {
        Invoice exempt = draft(new InvoiceTerms(0, 0, null, true, null), HT_SELLER);
        assertThat(exempt.totals().totalVat()).isZero();
        assertThat(exempt.vatRate()).isEqualByComparingTo("0");
        assertThat(exempt.vatMention()).isEqualTo("Exonéré de TVA");
        assertThat(exempt.lines()).allSatisfy(l -> assertThat(l.totalVat()).isZero());

        Invoice own = draft(new InvoiceTerms(0, 0, new BigDecimal("18"), false, null), HT_SELLER);
        assertThat(own.vatRate()).isEqualByComparingTo("18");
        assertThat(own.totals().totalVat()).isEqualTo(57_186); // 317 700 × 18 %
    }

    @Test
    @DisplayName("refusals (422): negative amounts, rate > 100 or 3 decimals, discount above the subtotal")
    void refusals() {
        assertThatThrownBy(() -> new InvoiceTerms(-1, 0, null, false, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new InvoiceTerms(0, -1, null, false, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new InvoiceTerms(0, 0, new BigDecimal("100.01"), false, null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new InvoiceTerms(0, 0, new BigDecimal("18.125"), false, null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> draft(new InvoiceTerms(317_701, 0, null, false, null), HT_SELLER))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("due date before the issue date: refused at issue (422); same day or later accepted")
    void dueDate() {
        Invoice d = draft(new InvoiceTerms(0, 0, null, false, DAY.minusDays(1)), HT_SELLER);
        assertThatThrownBy(() -> d.issue("FAC-2026-000001", DAY, HT_SELLER, T0))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getDetails().get("reason").toString()).contains("échéance"));
        assertThat(draft(new InvoiceTerms(0, 0, null, false, DAY), HT_SELLER)
                .issue("FAC-2026-000001", DAY, HT_SELLER, T0).terms().dueDate()).isEqualTo(DAY);
    }

    @Test
    @DisplayName("quote conversion keeps the terms and the totals")
    void conversion() {
        Invoice quote = Invoice.quote(UUID.randomUUID(), CLIENT, LINES,
                new InvoiceTerms(2_500, 2_000, null, false, null), HT_SELLER, null, T0);
        Invoice converted = quote.conversionDraft(UUID.randomUUID(), HT_SELLER, null, T0);
        assertThat(converted.totals()).isEqualTo(quote.totals());
        assertThat(converted.terms()).isEqualTo(quote.terms());
        assertThat(converted.lines().get(0).reference()).isEqualTo("RIZ-25");
    }

    @Test
    @DisplayName("credit note on a discounted invoice: partial → 409; full → exactly the invoice's totals")
    void creditNotes() {
        Invoice issued = draft(new InvoiceTerms(2_500, 2_000, null, false, null), HT_SELLER)
                .issue("FAC-2026-000001", DAY, HT_SELLER, T0);
        assertThatThrownBy(() -> CreditNote.creditLines(issued, List.of(),
                List.of(new CreditNoteSelection(1, BigDecimal.ONE))))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVOICE_INVALID_TRANSITION);
                    assertThat(e.getUserMessage()).isEqualTo(CreditNote.PARTIAL_ON_ADJUSTED);
                });
        assertThatThrownBy(() -> CreditNote.creditLines(issued, List.of(),
                List.of(new CreditNoteSelection(1, new BigDecimal("2")))))
                .isInstanceOf(DomainException.class);
        // every line, whole quantities: accepted like a full one
        List<CreditNoteLine> all = CreditNote.creditLines(issued, List.of(), List.of(
                new CreditNoteSelection(1, new BigDecimal("2")), new CreditNoteSelection(2, BigDecimal.ONE)));
        List<CreditNoteLine> lines = CreditNote.creditLines(issued, List.of(), null);
        assertThat(all).hasSameSizeAs(lines);
        CreditNote note = CreditNote.issue(UUID.randomUUID(), "AV-2026-000001", issued, "Retour", lines, null, T0,
                null);
        assertThat(note.totals()).isEqualTo(issued.totals()); // every field, subtotal / discount / delivery included
        assertThat(CreditNote.remainingTtc(issued, List.of())).isEqualTo(377_876);
        assertThat(CreditNote.remainingTtc(issued, lines)).isZero();
    }

    @Test
    @DisplayName("default VAT rate per country (Bref table); a country outside it → settings default 19.25")
    void countryRates() {
        assertThat(VatRates.forCountry("CM")).get().isEqualTo(new BigDecimal("19.25"));
        for (String c : List.of("SN", "CI", "BJ", "ML", "BF", "TG", "GN", "GA")) {
            assertThat(VatRates.forCountry(c)).as(c).get().satisfies(r -> assertThat(r).isEqualByComparingTo("18"));
        }
        assertThat(VatRates.forCountry("cd")).get().satisfies(r -> assertThat(r).isEqualByComparingTo("16"));
        assertThat(VatRates.forCountry("KE")).get().satisfies(r -> assertThat(r).isEqualByComparingTo("16"));
        assertThat(VatRates.forCountry("GH")).get().satisfies(r -> assertThat(r).isEqualByComparingTo("15"));
        assertThat(VatRates.forCountry("NG")).get().satisfies(r -> assertThat(r).isEqualByComparingTo("7.5"));
        assertThat(VatRates.forCountry("TD")).isEmpty();
        assertThat(VatRates.forCountry(null)).isEmpty();
        assertThat(InvoicingSettings.defaults(VatRates.forCountry("SN").orElse(null)).vatRate())
                .isEqualByComparingTo("18");
        assertThat(InvoicingSettings.defaults(VatRates.forCountry("TD").orElse(null)).vatRate())
                .isEqualByComparingTo("19.25");
    }

    private static Invoice htIssued(List<LineInput> lines) {
        return Invoice.draft(UUID.randomUUID(), CLIENT, lines, InvoiceTerms.NONE, HT_SELLER, null, T0)
                .issue("FAC-2026-000002", DAY, HT_SELLER, T0);
    }

    private static LineInput unit(long price) {
        return new LineInput("Article", BigDecimal.ONE, price, VatTreatment.STANDARD);
    }

    @Test
    @DisplayName("HT invoice, full credit note: exactly the invoice's totals (2 × 1 001 F → 2 387, 3 × 2 F → 7), "
            + "amount due 0")
    void htFullCreditNote() {
        Invoice two = htIssued(List.of(unit(1_001), unit(1_001)));
        assertThat(two.totals().totalTtc()).isEqualTo(2_387); // lines sum to 2 388
        CreditNote note = CreditNote.issue(UUID.randomUUID(), "AV-2026-000001", two, "Retour",
                CreditNote.creditLines(two, List.of(), null), null, T0, null);
        assertThat(note.totals()).isEqualTo(two.totals());
        assertThat(PaymentStateCalculator.amountDue(two.totals().totalTtc(), note.totals().totalTtc())).isZero();
        assertThat(CreditNote.fullyCredited(two, note.lines())).isTrue();
        assertThat(CreditNote.remainingTtc(two, note.lines())).isZero();

        Invoice three = htIssued(List.of(unit(2), unit(2), unit(2)));
        assertThat(three.totals().totalTtc()).isEqualTo(7);
        CreditNote all = CreditNote.issue(UUID.randomUUID(), "AV-2026-000002", three, "Retour",
                CreditNote.creditLines(three, List.of(), null), null, T0, null);
        assertThat(all.totals().totalTtc()).isEqualTo(7);
    }

    @Test
    @DisplayName("HT invoice, partial then final credit note: the final one credits exactly what remains")
    void htPartialThenFinal() {
        Invoice invoice = htIssued(List.of(unit(1_001), unit(1_001)));
        List<CreditNoteLine> first = CreditNote.creditLines(invoice, List.of(),
                List.of(new CreditNoteSelection(1, BigDecimal.ONE)));
        CreditNote partial = CreditNote.issue(UUID.randomUUID(), "AV-2026-000001", invoice, "Retour", List.of(),
                first, null, T0, null);
        assertThat(partial.totals().totalTtc()).isEqualTo(1_194); // the line: 1 001 + 193
        assertThat(CreditNote.remainingTtc(invoice, first)).isEqualTo(1_193);

        List<CreditNoteLine> second = CreditNote.creditLines(invoice, first, null);
        CreditNote last = CreditNote.issue(UUID.randomUUID(), "AV-2026-000002", invoice, "Retour", first, second,
                null, T0, null);
        assertThat(last.totals().totalTtc()).isEqualTo(1_193);
        assertThat(last.totals().totalHt()).isEqualTo(1_001);
        assertThat(last.totals().totalVat()).isEqualTo(192);
        assertThat(partial.totals().totalTtc() + last.totals().totalTtc()).isEqualTo(invoice.totals().totalTtc());
    }

    @Test
    @DisplayName("payment state: an invoice past its own due date and not fully paid is late")
    void overdueByDueDate() {
        assertThat(PaymentStateCalculator.state(100, List.of(), DAY, DAY.minusDays(1)))
                .isEqualTo(PaymentState.OVERDUE);
        assertThat(PaymentStateCalculator.state(100, List.of(), DAY, DAY)).isEqualTo(PaymentState.UNPAID);
        // a payment never makes it overdue itself (the job does, once)
        assertThat(PaymentStateCalculator.afterChange(100, List.of(), DAY, PaymentState.UNPAID, DAY.minusDays(1)))
                .isEqualTo(PaymentState.UNPAID);
        assertThat(PaymentStateCalculator.afterChange(100, List.of(), DAY, PaymentState.OVERDUE, DAY.minusDays(1)))
                .isEqualTo(PaymentState.OVERDUE);
    }
}

package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CreditNote — lines, amounts and refusals (Story 14.7)")
class CreditNoteTest {

    private static final Instant T0 = Instant.parse("2026-10-30T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-30T10:00:00Z");
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique Awa SARL", "Akwa, Douala",
            "+237690000000", "M012345678901A", "RC/DLA/2020/B/1234", true, new BigDecimal("19.25"), true,
            null, null);
    private static final ClientInfo CLIENT = new ClientInfo(null, "Société Ndolo", null, null, null);
    /** Line 1: 3 × 1 000 TTC at 19.25 % → HT 2 516 (one unit alone: 839 — odd rounding). Line 2: 5 000 exempt. */
    private static final List<LineInput> LINES = List.of(
            new LineInput("Savon", new BigDecimal("3"), 1_000, VatTreatment.STANDARD),
            new LineInput("Livraison", BigDecimal.ONE, 5_000, VatTreatment.EXEMPT));

    private static Invoice issued() {
        return Invoice.draft(UUID.randomUUID(), CLIENT, LINES, SELLER, null, T0)
                .issue("FAC-2026-000001", LocalDate.of(2026, 10, 30), SELLER, T1);
    }

    private static Invoice paid() {
        return issued().pay("Espèces", T1);
    }

    private static String code(Throwable t) {
        return ((DomainException) t).getDomainCode();
    }

    private static String reason(Throwable t) {
        return String.valueOf(((DomainException) t).getDetails().get("reason"));
    }

    private static List<CreditNoteSelection> one(int position, String quantity) {
        return List.of(new CreditNoteSelection(position, new BigDecimal(quantity)));
    }

    @Test
    @DisplayName("full credit note on a PAID invoice: every line, totals = invoice totals; numbering AV-yyyy-000001")
    void fullCreditNote() {
        Invoice invoice = paid();
        List<CreditNoteLine> lines = CreditNote.creditLines(invoice, List.of(), null);
        CreditNote note = CreditNote.issue(UUID.randomUUID(), CreditNote.number(2026, 1), invoice, "  Retour  ",
                lines, null, T1, null);

        assertThat(note.number()).isEqualTo("AV-2026-000001");
        assertThat(note.reason()).isEqualTo("Retour");
        assertThat(note.invoiceNumber()).isEqualTo("FAC-2026-000001");
        assertThat(note.totals()).isEqualTo(invoice.totals());
        assertThat(lines).extracting(CreditNoteLine::position).containsExactly(1, 2);
        assertThat(lines).extracting(CreditNoteLine::invoicePosition).containsExactly(1, 2);
        assertThat(lines.get(1).vatMention()).isEqualTo("Exonérée");
        assertThat(CreditNote.fullyCredited(invoice, lines)).isTrue();
    }

    @Test
    @DisplayName("partial: 1 of a 3-quantity line → amounts of 1 unit, computed like the invoice line")
    void partial() {
        List<CreditNoteLine> lines = CreditNote.creditLines(paid(), List.of(), one(1, "1"));

        assertThat(lines).hasSize(1);
        CreditNoteLine l = lines.get(0);
        assertThat(l.quantity()).isEqualByComparingTo("1");
        assertThat(l.unitPrice()).isEqualTo(1_000);
        assertThat(l.totalTtc()).isEqualTo(1_000);
        assertThat(l.totalHt()).isEqualTo(839);
        assertThat(l.totalVat()).isEqualTo(161);
        assertThat(l.vatRate()).isEqualByComparingTo("19.25");
    }

    @Test
    @DisplayName("remaining exact: 3 credit notes of 1 unit add up to the line exactly despite the VAT rounding")
    void remainingExact() {
        Invoice invoice = paid();
        InvoiceLine line = invoice.lines().get(0);
        List<CreditNoteLine> credited = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            credited.addAll(CreditNote.creditLines(invoice, credited, one(1, "1")));
        }
        assertThat(credited.stream().mapToLong(CreditNoteLine::totalHt).sum()).isEqualTo(line.totalHt());
        assertThat(credited.stream().mapToLong(CreditNoteLine::totalVat).sum()).isEqualTo(line.totalVat());
        assertThat(credited.stream().mapToLong(CreditNoteLine::totalTtc).sum()).isEqualTo(line.totalTtc());
        assertThat(credited.get(2).totalHt()).isEqualTo(838); // 2 516 − 2 × 839

        // nothing left on line 1 → refused; the full rest = line 2 only
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, credited, one(1, "1")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CREDIT_NOTE_INVALID"))
                .satisfies(e -> assertThat(reason(e)).isEqualTo("la ligne 1 est déjà entièrement créditée"));
        List<CreditNoteLine> rest = CreditNote.creditLines(invoice, credited, null);
        assertThat(rest).extracting(CreditNoteLine::invoicePosition).containsExactly(2);
        assertThat(rest.get(0).position()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalid selections → 422 CREDIT_NOTE_INVALID with a French reason")
    void invalidSelections() {
        Invoice invoice = paid();
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, List.of(), List.of()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CREDIT_NOTE_INVALID"))
                .satisfies(e -> assertThat(reason(e)).isEqualTo("choisissez au moins une ligne à créditer"));
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, List.of(), one(1, "4")))
                .satisfies(e -> assertThat(reason(e))
                        .isEqualTo("la quantité de la ligne 1 dépasse la quantité restant à créditer (3)"));
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, List.of(), one(9, "1")))
                .satisfies(e -> assertThat(reason(e)).isEqualTo("la ligne 9 n'existe pas sur la facture"));
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, List.of(), one(1, "0")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CREDIT_NOTE_INVALID"));
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, List.of(), one(1, "0.0001")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CREDIT_NOTE_INVALID"));
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, List.of(),
                List.of(new CreditNoteSelection(1, null))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CREDIT_NOTE_INVALID"));
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, List.of(), List.of(
                new CreditNoteSelection(1, BigDecimal.ONE), new CreditNoteSelection(1, BigDecimal.ONE))))
                .satisfies(e -> assertThat(reason(e)).isEqualTo("la ligne 1 est indiquée plusieurs fois"));
    }

    @Test
    @DisplayName("fractional quantity: 1,5 of 3 credited with the invoice rounding")
    void fractional() {
        CreditNoteLine l = CreditNote.creditLines(paid(), List.of(), one(1, "1.5")).get(0);
        assertThat(l.totalTtc()).isEqualTo(1_500);
        assertThat(l.totalHt()).isEqualTo(1_258);
        assertThat(l.totalVat()).isEqualTo(242);
    }

    @Test
    @DisplayName("draft, cancelled or fully credited invoice → 409 INVOICE_INVALID_TRANSITION; blank reason → 422")
    void refusedInvoices() {
        Invoice draft = Invoice.draft(UUID.randomUUID(), CLIENT, LINES, SELLER, null, T0);
        assertThatThrownBy(() -> CreditNote.creditLines(draft, List.of(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"));
        Invoice cancelled = issued().cancel("erreur", T1);
        assertThatThrownBy(() -> CreditNote.creditLines(cancelled, List.of(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"))
                .satisfies(e -> assertThat(((DomainException) e).getDetails()).containsEntry("status", "annulée"));
        Invoice invoice = issued();
        List<CreditNoteLine> all = CreditNote.creditLines(invoice, List.of(), null);
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, all, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"));
        assertThatThrownBy(() -> CreditNote.requireReason("   "))
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> CreditNote.requireReason("x".repeat(301)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("a credit note of 0 F (free line) → 422 CREDIT_NOTE_INVALID before any number; the rest is "
            + "fully credited by quantities")
    void zeroAmountRefused() {
        Invoice invoice = Invoice.draft(UUID.randomUUID(), CLIENT, List.of(
                        new LineInput("Savon", BigDecimal.ONE, 1_000, VatTreatment.STANDARD),
                        new LineInput("Cadeau", BigDecimal.ONE, 0, VatTreatment.STANDARD)), SELLER, null, T0)
                .issue("FAC-2026-000002", LocalDate.of(2026, 10, 30), SELLER, T1);

        assertThatThrownBy(() -> CreditNote.creditLines(invoice, List.of(), one(2, "1")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CREDIT_NOTE_INVALID"))
                .satisfies(e -> assertThat(reason(e)).isEqualTo("le montant de l'avoir serait nul"));
        List<CreditNoteLine> first = CreditNote.creditLines(invoice, List.of(), one(1, "1"));
        assertThat(CreditNote.remainingTtc(invoice, first)).isZero();
        assertThat(CreditNote.fullyCredited(invoice, first)).isFalse(); // the free line is still to credit
        assertThatThrownBy(() -> CreditNote.creditLines(invoice, first, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CREDIT_NOTE_INVALID"));
    }

    @Test
    @DisplayName("an ISSUED invoice with a credit note can no longer be cancelled")
    void cancelGuard() {
        assertThatThrownBy(() -> issued().cancel("erreur", T1, true))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVOICE_INVALID_TRANSITION"));
        assertThat(issued().cancel("erreur", T1, false).status()).isEqualTo(InvoiceStatus.CANCELLED);
    }
}

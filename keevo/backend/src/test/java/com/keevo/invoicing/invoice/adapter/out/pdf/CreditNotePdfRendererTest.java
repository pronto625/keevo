package com.keevo.invoicing.invoice.adapter.out.pdf;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.CreditNoteLine;
import com.keevo.invoicing.invoice.domain.model.CreditNoteSelection;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.shared.infrastructure.pdf.PdfDocumentFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CreditNotePdfRenderer (Story 14.7)")
class CreditNotePdfRendererTest {

    private static final Instant T0 = Instant.parse("2026-10-30T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-30T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-03T08:00:00Z");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-");
    private static final InvoicingSettings SELLER = new InvoicingSettings("Tech Centre SARL", "Rue Joss, Akwa, Douala",
            "+237690000000", "M012345678901A", "RC/DLA/2020/B/1234", true, new BigDecimal("19.25"), true,
            "MTN MoMo 690000000", "Merci de votre confiance");
    private static final ClientInfo CLIENT = new ClientInfo(UUID.randomUUID(), "Société Ndolo", "P098765432109Z",
            "Bonapriso, Douala", "+237677000111");
    /** 2 × 11 925 TTC + 5 000 exempt = HT 25 000 / VAT 3 850 / TTC 28 850. */
    private static final List<LineInput> LINES = List.of(
            new LineInput("Sac de riz 25 kg", new BigDecimal("2"), 11_925, VatTreatment.STANDARD),
            new LineInput("Livraison", BigDecimal.ONE, 5_000, VatTreatment.EXEMPT));

    private final CreditNotePdfRenderer renderer = new CreditNotePdfRenderer(new PdfDocumentFactory());

    private static Invoice paid() {
        return Invoice.draft(UUID.randomUUID(), CLIENT, LINES, SELLER, null, T0)
                .issue("FAC-2026-000005", LocalDate.of(2026, 10, 30), SELLER, T1).pay("Espèces", T1);
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replace('\u00a0', ' ');
        }
    }

    @Test
    @DisplayName("full: « AVOIR N° AV-… », date, « Avoir sur la facture FAC-… du … », reason, negative totals, "
            + "seller snapshot of the invoice, no UUID")
    void fullCreditNote() throws IOException {
        Invoice invoice = paid();
        List<CreditNoteLine> lines = CreditNote.creditLines(invoice, List.of(), null);
        CreditNote note = CreditNote.issue(UUID.randomUUID(), "AV-2026-000001", invoice, "Retour de marchandise",
                lines, null, T2, null);

        String text = text(renderer.render(note, invoice, null));

        assertThat(text).contains("AVOIR N° AV-2026-000001", "Date : 03/11/2026",
                "Avoir sur la facture FAC-2026-000005 du 30/10/2026", "Motif : Retour de marchandise",
                "Tech Centre SARL", "NIU : M012345678901A", "RCCM : RC/DLA/2020/B/1234", "Société Ndolo",
                "Sac de riz 25 kg", "Livraison", "Exonérée", "19,25 %",
                "Total HT", "−25 000 XAF", "Total TVA", "−3 850 XAF", "Total TTC", "−28 850 XAF",
                "−23 850", "−5 000");
        assertThat(text).doesNotContain("PAYÉE", "FACTURE N°");
        assertThat(UUID_PATTERN.matcher(text).find()).as("no UUID in the PDF").isFalse();
    }

    @Test
    @DisplayName("partial: only the credited line, 1 unit")
    void partialCreditNote() throws IOException {
        Invoice invoice = paid();
        List<CreditNoteLine> lines = CreditNote.creditLines(invoice, List.of(),
                List.of(new CreditNoteSelection(1, BigDecimal.ONE)));
        CreditNote note = CreditNote.issue(UUID.randomUUID(), "AV-2026-000002", invoice, "Article abîmé", lines,
                null, T2, null);

        String text = text(renderer.render(note, invoice, null));

        assertThat(text).contains("AVOIR N° AV-2026-000002", "Sac de riz 25 kg", "−11 925 XAF")
                .doesNotContain("Livraison");
    }
}

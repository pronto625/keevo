package com.keevo.invoicing.invoice.adapter.out.pdf;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.infrastructure.pdf.PdfDocumentFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InvoicePdfRenderer (Story 14.2)")
class InvoicePdfRendererTest {

    private static final Instant T0 = Instant.parse("2026-10-30T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-30T10:00:00Z");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-");
    private static final InvoicingSettings SELLER = new InvoicingSettings("Tech Centre SARL", "Rue Joss, Akwa, Douala",
            "+237690000000", "M012345678901A", "RC/DLA/2020/B/1234", true, new BigDecimal("19.25"), true,
            "MTN MoMo 690000000", "Merci de votre confiance");
    private static final ClientInfo CLIENT = new ClientInfo(UUID.randomUUID(), "Société Ndolo", "P098765432109Z",
            "Bonapriso, Douala", "+237677000111");
    /** 2 × 11 925 TTC + 5 000 exempt = HT 25 000 / VAT 3 850 / TTC 28 850 (14.1 live check). */
    private static final List<LineInput> LINES = List.of(
            new LineInput("Sac de riz 25 kg", new BigDecimal("2"), 11_925, VatTreatment.STANDARD),
            new LineInput("Livraison <b>express</b>", BigDecimal.ONE, 5_000, VatTreatment.EXEMPT));

    private final InvoicePdfRenderer renderer = new InvoicePdfRenderer(new PdfDocumentFactory());

    private static Invoice draft() {
        return Invoice.draft(UUID.randomUUID(), CLIENT, LINES, SELLER, UUID.randomUUID(), T0);
    }

    private static Invoice issued() {
        return draft().issue("FAC-2026-000001", LocalDate.of(2026, 10, 30), SELLER, T1);
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDRectangle box = doc.getPage(0).getMediaBox();
            assertThat(box.getWidth()).isCloseTo(PDRectangle.A4.getWidth(), org.assertj.core.data.Offset.offset(1f));
            return new PDFTextStripper().getText(doc).replace(' ', ' ');
        }
    }

    private static boolean hasImage(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(0);
            for (COSName name : page.getResources().getXObjectNames()) {
                if (page.getResources().isImageXObject(name)) return true;
            }
            return false;
        }
    }

    private byte[] render(Invoice invoice, Logo logo) {
        return renderer.render(invoice, invoice.seller() != null ? invoice.seller() : SellerSnapshot.of(SELLER), logo);
    }

    @Test
    @DisplayName("issued: number, date, seller identity, client, lines, totals « 28 850 XAF », escaped text, no UUID")
    void issuedInvoice() throws IOException {
        Invoice invoice = issued();
        String text = text(render(invoice, null));
        assertThat(text).contains("FACTURE N° FAC-2026-000001", "Date : 30/10/2026",
                "Tech Centre SARL", "Rue Joss, Akwa, Douala", "NIU : M012345678901A", "RCCM : RC/DLA/2020/B/1234",
                "FACTURÉ À", "Société Ndolo", "NIU : P098765432109Z", "Bonapriso, Douala",
                "Sac de riz 25 kg", "Livraison <b>express</b>", "Exonérée", "19,25 %",
                "Total HT", "25 000 XAF", "Total TVA", "3 850 XAF", "Total TTC", "28 850 XAF",
                "Montants en francs CFA (XAF)", "MTN MoMo 690000000", "Merci de votre confiance");
        assertThat(text).doesNotContain("BROUILLON", "ANNULÉE", "PAYÉE", "TVA non applicable");
        assertThat(UUID_PATTERN.matcher(text).find()).as("no UUID in the PDF").isFalse();
        assertThat(text).doesNotContain(invoice.id().toString(), CLIENT.clientId().toString());
    }

    @Test
    @DisplayName("stamps: draft « BROUILLON — sans valeur », cancelled « ANNULÉE » + reason, paid « PAYÉE le … »")
    void stamps() throws IOException {
        String draft = text(render(draft(), null));
        assertThat(draft).contains("FACTURE — BROUILLON", "BROUILLON — sans valeur", "Non émise");
        String cancelled = text(render(issued().cancel("Erreur de client", T1), null));
        assertThat(cancelled).contains("ANNULÉE", "Motif : Erreur de client", "FAC-2026-000001");
        String paid = text(render(issued().markedPaid(Instant.parse("2026-11-02T10:00:00Z"), "Mobile Money", Instant.parse("2026-11-02T10:00:00Z")), null));
        assertThat(paid).contains("PAYÉE le 02/11/2026", "Mode de paiement : Mobile Money");
    }

    @Test
    @DisplayName("Story 20.3 quote: « DEVIS DV-XXXXXXXX », « Devis — en attente de validation », no FAC- number, no "
            + "UUID; converted / cancelled quotes say so")
    void quote() throws IOException {
        UUID id = UUID.fromString("ab12cd34-5678-4abc-8def-0123456789ab");
        Invoice quote = Invoice.quote(id, CLIENT, LINES, SELLER, UUID.randomUUID(), T0);
        String text = text(render(quote, null));
        assertThat(text).contains("DEVIS DV-AB12CD34", "Devis — en attente de validation", "Date : 30/10/2026",
                "Société Ndolo", "Total TTC", "28 850 XAF");
        assertThat(text).doesNotContain("FACTURE", "FAC-", "BROUILLON", "Non émise");
        assertThat(UUID_PATTERN.matcher(text).find()).as("no UUID in the PDF").isFalse();
        assertThat(text(render(quote.converted(UUID.randomUUID(), T1), null))).contains("DEVIS CONVERTI EN FACTURE");
        assertThat(text(render(quote.cancel("Le client a renoncé", T1), null)))
                .contains("DEVIS ANNULÉ", "Motif : Le client a renoncé");
    }

    @Test
    @DisplayName("cancelled without a stored reason: no « Motif : null » line")
    void cancelledWithoutReason() throws IOException {
        Invoice c = issued().cancel("x", T1);
        Invoice noReason = new Invoice(c.id(), c.number(), c.status(), c.issueDate(), c.client(), c.seller(),
                c.vatRegistered(), c.vatRate(), c.pricesIncludeVat(), c.vatMention(), c.lines(), c.totals(),
                c.issuedAt(), c.paidAt(), c.paymentMethod(), c.cancelledAt(), "  ", c.createdBy(), c.createdAt(),
                c.updatedAt());
        assertThat(text(render(noReason, null))).contains("ANNULÉE").doesNotContain("Motif", "null");
    }

    @Test
    @DisplayName("seller not VAT-registered: « TVA non applicable », every line at 0 %")
    void notVatRegistered() throws IOException {
        InvoicingSettings notRegistered = new InvoicingSettings("Boutique Awa", null, null, null, null, false,
                null, true, null, null);
        Invoice invoice = Invoice.draft(UUID.randomUUID(), CLIENT, LINES, notRegistered, null, T0)
                .issue("FAC-2026-000002", LocalDate.of(2026, 10, 30), notRegistered, T1);
        String text = text(renderer.render(invoice, invoice.seller(), null));
        assertThat(text).contains("TVA non applicable", "Boutique Awa", "0 %", "28 850 XAF")
                .doesNotContain("NIU : M0", "RCCM");
    }

    @Test
    @DisplayName("logo: drawn when given, absent otherwise")
    void logo() throws IOException {
        BufferedImage image = new BufferedImage(200, 80, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        assertThat(hasImage(render(issued(), new Logo(png.toByteArray(), "image/png")))).isTrue();
        assertThat(hasImage(render(issued(), null))).isFalse();
    }

    @Test
    @DisplayName("Story 20.5 — a plain TTC invoice prints as before 20.5: no Sous-total, « Total TVA » kept, line VAT/TTC")
    void plainInvoiceUnchanged() throws IOException {
        String text = text(render(issued(), null));
        assertThat(text).contains("Total HT", "25 000 XAF", "Total TVA", "3 850 XAF", "Total TTC", "28 850 XAF",
                "Montant TTC", "23 850");
        assertThat(text).doesNotContain("Sous-total", "Remise", "Échéance");
        InvoicingSettings notRegistered = new InvoicingSettings("Boutique Awa", null, null, null, null, false,
                null, true, null, null);
        Invoice zeroVat = Invoice.draft(UUID.randomUUID(), CLIENT, LINES, notRegistered, null, T0)
                .issue("FAC-2026-000002", LocalDate.of(2026, 10, 30), notRegistered, T1);
        assertThat(text(renderer.render(zeroVat, zeroVat.seller(), null))).contains("Total TVA", "0 XAF");
    }

    @Test
    @DisplayName("Story 20.5 — HT invoice with discount and delivery: Sous-total, Remise, Total TVA, Livraison, "
            + "Échéance, references; lines show HT and rate only")
    void adjustedHtInvoice() throws IOException {
        InvoicingSettings ht = new InvoicingSettings("Tech Centre SARL", "Rue Joss", null, null, null, true,
                new BigDecimal("19.25"), false, null, null);
        ClientInfo client = new ClientInfo(null, "Société Ndolo", null, null, null, "RC/DLA/2021/A/77",
                "compta@ndolo.cm", "M. Ndolo", "ndolo.cm");
        List<LineInput> lines = List.of(
                new LineInput("Sac de riz", new BigDecimal("2"), 150_000, VatTreatment.STANDARD, null, "RIZ-25"),
                new LineInput("Savon", BigDecimal.ONE, 17_700, VatTreatment.STANDARD));
        Invoice invoice = Invoice.draft(UUID.randomUUID(), client, lines,
                        new com.keevo.invoicing.invoice.domain.model.InvoiceTerms(2_500, 2_000, null, false,
                                LocalDate.of(2026, 11, 30)), ht, null, T0)
                .issue("FAC-2026-000003", LocalDate.of(2026, 10, 30), ht, T1);
        String text = text(renderer.render(invoice, invoice.seller(), null));
        assertThat(text).contains("Sous-total HT", "317 700 XAF", "Remise", "-2 500 XAF", "Total HT", "315 200 XAF",
                "Total TVA", "60 676 XAF", "Livraison", "2 000 XAF", "Total TTC", "377 876 XAF",
                "Échéance : 30/11/2026", "RCCM : RC/DLA/2021/A/77", "Contact : M. Ndolo", "Email : compta@ndolo.cm",
                "Site web : ndolo.cm", "Réf. : RIZ-25", "Montant HT");
        assertThat(text).doesNotContain("Montant TTC");
    }
}

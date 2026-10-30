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
        String paid = text(render(issued().pay("Mobile Money", Instant.parse("2026-11-02T10:00:00Z")), null));
        assertThat(paid).contains("PAYÉE le 02/11/2026", "Mode de paiement : Mobile Money");
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
}

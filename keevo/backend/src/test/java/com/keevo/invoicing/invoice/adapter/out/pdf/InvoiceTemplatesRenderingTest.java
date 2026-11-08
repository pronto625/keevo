package com.keevo.invoicing.invoice.adapter.out.pdf;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicePayment;
import com.keevo.invoicing.invoice.domain.model.InvoiceTemplate;
import com.keevo.invoicing.invoice.domain.model.InvoiceTerms;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.model.TicketWidth;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceDocumentRenderer.RenderRequest;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.infrastructure.pdf.PdfDocumentFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.7 — the 8 Bref layouts (and the Keevo standard one) rendered on three typical documents each: an issued
 * invoice with a deposit, a received payment and an instalment, signed by both parties; a Bref-like invoice with prices
 * without VAT, discount, delivery, due date and its Bref reference; a quote waiting for validation. Each PDF carries
 * the number, the client, the totals, the payment block (amount paid / still due) and no technical identifier; the
 * tickets are one page of their roll width (58 / 80 mm) or an A4 sheet.
 *
 * <p>Visual check: with {@code KEEVO_PDF_PREVIEWS=<dir>} in the environment every PDF is also written there (render
 * them with {@code pdftoppm -png}).
 */
@DisplayName("Invoice templates — 8 Bref layouts, tickets 58/80/A4, payments, signatures (Story 20.7)")
class InvoiceTemplatesRenderingTest {

    private static final Instant T0 = Instant.parse("2026-11-02T09:30:00Z");
    private static final Instant T1 = Instant.parse("2026-11-02T10:15:00Z");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-");
    private static final InvoicingSettings TTC_SELLER = new InvoicingSettings("Tech Centre SARL",
            "Rue Joss, Akwa, Douala", "+237690000000", "M012345678901A", "RC/DLA/2020/B/1234", true,
            new BigDecimal("19.25"), true, "MTN MoMo 690000000", "Merci de votre confiance");
    private static final InvoicingSettings HT_SELLER = new InvoicingSettings("Boutique Awa", "Marché Mokolo, Yaoundé",
            "+237699112233", null, null, true, new BigDecimal("19.25"), false, null, null);
    private static final PdfDocumentFactory FACTORY = new PdfDocumentFactory();
    private static final InvoicePdfRenderer RENDERER = new InvoicePdfRenderer(FACTORY);

    /** One typical document and what its PDF must show. */
    record Sample(String name, RenderRequest request, List<String> expected) {
    }

    // ── samples ──────────────────────────────────────────────────────────────────────────────────────────

    static Sample partiallyPaid() throws IOException {
        ClientInfo client = new ClientInfo(UUID.randomUUID(), "Société Ndolo", "P098765432109Z", "Bonapriso, Douala",
                "+237677000111", null, "contact@ndolo.cm", null, null);
        List<LineInput> lines = List.of(
                new LineInput("Sac de riz 25 kg", new BigDecimal("2"), 11_925, VatTreatment.STANDARD, null, "RIZ-25"),
                new LineInput("Huile 5 L", new BigDecimal("3"), 7_500, VatTreatment.STANDARD),
                new LineInput("Livraison", BigDecimal.ONE, 5_000, VatTreatment.EXEMPT));
        Invoice issued = Invoice.draft(UUID.randomUUID(), client, lines, TTC_SELLER, UUID.randomUUID(), T0)
                .issue("FAC-2026-000042", LocalDate.of(2026, 11, 2), TTC_SELLER, T1);
        long total = issued.totals().totalTtc(); // 51 350
        List<InvoicePayment> payments = List.of(
                InvoicePayment.received(UUID.randomUUID(), issued.id(), 20_000, T1, "Espèces", null),
                InvoicePayment.received(UUID.randomUUID(), issued.id(), 10_000,
                        Instant.parse("2026-11-05T11:00:00Z"), "MTN MoMo", null),
                InvoicePayment.scheduled(UUID.randomUUID(), issued.id(), total - 30_000, LocalDate.of(2026, 12, 15),
                        null, null));
        var collection = new PaymentStateCalculator.Collection(PaymentState.PARTIAL, 30_000, total, payments);
        RenderRequest r = new RenderRequest(issued, SellerSnapshot.of(TTC_SELLER), logo(), null, null, collection,
                signature(Color.BLUE), signature(Color.BLACK), null);
        return new Sample("partiellement-payee", r, List.of("FAC-2026-000042", "Société Ndolo", "Sac de riz 25 kg",
                "51 350 XAF", "Montant payé", "30 000 XAF", "Reste à payer", "21 350 XAF", "Partiellement payée"));
    }

    static Sample brefImported() {
        ClientInfo client = new ClientInfo(null, "Ets Fotso & Fils", null, "Bafoussam", "+237655443322",
                "RC/BFM/2021/A/77", null, "M. Fotso", null);
        List<LineInput> lines = List.of(
                new LineInput("Ciment 50 kg", new BigDecimal("40"), 5_200, VatTreatment.STANDARD),
                new LineInput("Fer à béton 10 mm", new BigDecimal("12.5"), 4_600, VatTreatment.STANDARD),
                new LineInput("Sable (m3)", new BigDecimal("2"), 25_000, VatTreatment.STANDARD));
        InvoiceTerms terms = new InvoiceTerms(2_500, 2_000, null, false, LocalDate.of(2026, 12, 2));
        Invoice issued = Invoice.draft(UUID.randomUUID(), client, lines, terms, HT_SELLER, UUID.randomUUID(), T0)
                .issue("FAC-2026-000043", LocalDate.of(2026, 11, 2), HT_SELLER, T1);
        long total = issued.totals().totalTtc();
        var collection = new PaymentStateCalculator.Collection(PaymentState.UNPAID, 0, total, List.of());
        RenderRequest r = new RenderRequest(issued, SellerSnapshot.of(HT_SELLER), null, null, null, collection,
                null, null, "BRF-1A2B3C4D");
        return new Sample("bref-importee", r, List.of("FAC-2026-000043", "BRF-1A2B3C4D", "Ets Fotso & Fils",
                "Ciment 50 kg", "Remise", "Livraison", "Reste à payer", "02 décembre 2026"));
    }

    static Sample quote() {
        ClientInfo client = new ClientInfo(null, "Mme Ekane", null, null, "+237699000222");
        List<LineInput> lines = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            lines.add(new LineInput("Prestation " + i, BigDecimal.ONE, 15_000L * i, VatTreatment.STANDARD));
        }
        Invoice q = Invoice.quote(UUID.randomUUID(), client, lines, TTC_SELLER, UUID.randomUUID(), T0);
        RenderRequest r = new RenderRequest(q, SellerSnapshot.of(TTC_SELLER), null, null, null, null, null, null,
                null);
        return new Sample("devis", r, List.of(q.quoteReference(), "Mme Ekane", "Prestation 4", "150 000 XAF",
                "En attente de validation"));
    }

    static List<Sample> samples() throws IOException {
        return List.of(partiallyPaid(), brefImported(), quote());
    }

    static Stream<Arguments> layouts() {
        List<Arguments> out = new ArrayList<>();
        for (InvoiceTemplate t : InvoiceTemplate.values()) {
            if (t.narrow()) {
                for (TicketWidth w : TicketWidth.values()) out.add(Arguments.of(t, w));
            } else {
                out.add(Arguments.of(t, null));
            }
        }
        return out.stream();
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("layouts")
    @DisplayName("each layout × paper renders the 3 typical documents: number, client, totals, payments, no id")
    void layout(InvoiceTemplate template, TicketWidth width) throws IOException {
        for (Sample s : samples()) {
            RenderRequest base = s.request();
            RenderRequest r = new RenderRequest(base.invoice(), base.seller(), base.logo(), template, width,
                    base.collection(), base.clientSignature(), base.sellerSignature(), base.legacyReference());
            byte[] pdf = RENDERER.render(r);
            preview(template.code() + (width == null ? "" : "-" + width.code()) + "-" + s.name(), pdf);
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                String text = new PDFTextStripper().getText(doc).replace('\u00A0', ' ');
                // text-transform (upper case), letter-spacing and narrow columns change the extracted text: compared
                // case-insensitively, whitespace ignored
                List<String> expected = s.expected().stream()
                        // Bref's compact ticket prints no due date
                        .filter(e -> template != InvoiceTemplate.TICKET_COMPACT || !e.contains("décembre"))
                        .map(InvoiceTemplatesRenderingTest::squash).toList();
                assertThat(squash(text)).as(template + " " + width + " " + s.name()).contains(expected);
                assertThat(text).doesNotContainPattern(UUID_PATTERN);
                PDRectangle box = doc.getPage(0).getMediaBox();
                if (!template.narrow() || width == TicketWidth.A4) {
                    assertThat(box.getWidth()).isCloseTo(PDRectangle.A4.getWidth(),
                            org.assertj.core.data.Offset.offset(1f));
                } else {
                    float mm = box.getWidth() * 25.4f / 72f;
                    assertThat(mm).isCloseTo(width.contentWidthMm(), org.assertj.core.data.Offset.offset(1f));
                    assertThat(doc.getNumberOfPages()).as("a roll ticket is one page fitted to its content")
                            .isEqualTo(1);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(InvoiceTemplate.class)
    @DisplayName("signatures are drawn as images when present (except the compact ticket, which has none in Bref)")
    void signatures(InvoiceTemplate template) throws IOException {
        Sample s = partiallyPaid();
        RenderRequest base = s.request();
        RenderRequest r = new RenderRequest(base.invoice(), base.seller(), null, template, TicketWidth.MM_80,
                base.collection(), base.clientSignature(), base.sellerSignature(), null);
        int images = images(RENDERER.render(r));
        if (template == InvoiceTemplate.TICKET_COMPACT) {
            assertThat(images).isZero();
        } else {
            assertThat(images).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a very long ticket (400 lines) is paginated on roll pages of the maximum height, never one huge page")
    void longTicket() throws IOException {
        List<LineInput> lines = new ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            lines.add(new LineInput("Article numéro " + i, BigDecimal.ONE, 1_000, VatTreatment.STANDARD));
        }
        Invoice issued = Invoice.draft(UUID.randomUUID(), new ClientInfo(null, "Grossiste", null, null, null), lines,
                TTC_SELLER, UUID.randomUUID(), T0).issue("FAC-2026-000099", LocalDate.of(2026, 11, 2), TTC_SELLER, T1);
        byte[] pdf = RENDERER.render(new RenderRequest(issued, SellerSnapshot.of(TTC_SELLER), null,
                InvoiceTemplate.TICKET_CLASSIC, TicketWidth.MM_80, null, null, null, null));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            float maxMm = InvoicePdfRenderer.MAX_TICKET_HEIGHT_MM;
            for (var page : doc.getPages()) {
                assertThat(page.getMediaBox().getHeight() * 25.4f / 72f)
                        .isCloseTo(maxMm, org.assertj.core.data.Offset.offset(1f));
                assertThat(page.getMediaBox().getWidth() * 25.4f / 72f)
                        .isCloseTo(80f, org.assertj.core.data.Offset.offset(1f));
            }
            assertThat(squash(new PDFTextStripper().getText(doc))).contains(squash("Article numéro 400"),
                    squash("400 000 XAF"));
        }
    }

    @Test
    @DisplayName("Keevo standard layout: payment block, signatures and Bref reference added; plain invoice unchanged")
    void standardLayout() throws IOException {
        Sample paid = partiallyPaid();
        byte[] pdf = RENDERER.render(paid.request());
        preview("standard-" + paid.name(), pdf);
        String text = text(pdf);
        assertThat(text).containsIgnoringCase("Signature client").contains("FACTURE N° FAC-2026-000042", "Montant payé", "Reste à payer", "21 350 XAF",
                "Échéance 15/12/2026", "Versement du 02/11/2026");
        Sample imported = brefImported();
        byte[] brf = RENDERER.render(imported.request());
        preview("standard-" + imported.name(), brf);
        assertThat(text(brf)).contains("FACTURE N° FAC-2026-000043", "BRF-1A2B3C4D").doesNotContain("Montant payé");
        String plain = text(RENDERER.render(paid.request().invoice(), paid.request().seller(), null));
        assertThat(plain).doesNotContain("Montant payé").doesNotContainIgnoringCase("Signature client");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replace('\u00A0', ' ');
        }
    }

    private static String squash(String text) {
        return text.replaceAll("[\\s\\u00A0]+", "").toLowerCase(java.util.Locale.FRENCH);
    }

    private static int images(byte[] pdf) throws IOException {
        int n = 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (var page : doc.getPages()) {
                for (var name : page.getResources().getXObjectNames()) {
                    if (page.getResources().isImageXObject(name)) n++;
                }
            }
        }
        return n;
    }

    private static void preview(String name, byte[] pdf) throws IOException {
        String dir = System.getenv("KEEVO_PDF_PREVIEWS");
        if (dir != null && !dir.isBlank()) {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            Files.write(out.resolve(name + ".pdf"), pdf);
        }
    }

    private static Logo logo() throws IOException {
        BufferedImage img = new BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(15, 118, 110));
        g.fillRect(0, 0, 160, 160);
        g.setColor(Color.WHITE);
        g.fillOval(30, 30, 100, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new Logo(out.toByteArray(), "image/png");
    }

    private static String signature(Color color) throws IOException {
        BufferedImage img = new BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.setStroke(new BasicStroke(4));
        g.drawPolyline(new int[]{10, 60, 90, 140, 180, 230, 290}, new int[]{70, 20, 80, 30, 75, 25, 60}, 7);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }
}

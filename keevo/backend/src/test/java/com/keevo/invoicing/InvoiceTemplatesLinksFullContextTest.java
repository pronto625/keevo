package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.invoicing.invoice.application.usecase.InvoicePublicLinkUseCase;
import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.7 — layouts, signatures and public links on the real HTTP chain (production security, real JWTs, entitlement
 * aspect with lazy provisioning, PostgreSQL): the layout chosen per document and per tenant drives the PDF; signatures
 * are validated, stored and printed; public links follow the tenant's lifetime, rotate, and old Bref links of imported
 * invoices keep opening with their {@code BRF-} reference; a quote is sent by WhatsApp; the PDF shows the payments.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Invoice layouts, signatures, public links — full context (Story 20.7)")
class InvoiceTemplatesLinksFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired SendInvoiceUseCase sendInvoiceUseCase;
    @Autowired InvoicePublicLinkUseCase links;

    static final class FakeWhatsApp implements WhatsAppSender {
        final List<String[]> sent = new ArrayList<>();

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void sendReport(String phoneNumber, String reportText) {
            sent.add(new String[]{phoneNumber, reportText});
        }
    }

    private final UUID ownerId = UUID.randomUUID();
    private final FakeWhatsApp fake = new FakeWhatsApp();
    private final List<UUID> legacyTokens = new ArrayList<>();
    private WhatsAppSender original;
    private String schema;
    private UUID tenantId;
    private String ownerToken;

    @BeforeEach
    void setUp() throws Exception {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        ownerToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema,
                "OWNER");
        original = (WhatsAppSender) ReflectionTestUtils.getField(target(), "whatsApp");
        settings();
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(target(), "whatsApp", original);
        for (UUID token : legacyTokens) {
            jdbc.update("DELETE FROM public.invoice_legacy_links WHERE token = ?", token);
        }
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private Object target() {
        return AopTestUtils.getUltimateTargetObject(sendInvoiceUseCase);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult owner(HttpMethod method, String path, Object body) throws Exception {
        return call(ownerToken, method, path, body);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode data(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(expectedStatus);
        return objectMapper.readTree(body(r)).path("data");
    }

    private JsonNode error(MvcResult r, int status, String domainCode) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        assertThat(body(r)).contains("\"domainCode\":\"" + domainCode + "\"");
        return objectMapper.readTree(body(r));
    }

    private static String pdfText(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        try (PDDocument doc = Loader.loadPDF(r.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc).replace(' ', ' ');
        }
    }

    private static float pageWidthMm(MvcResult r) throws Exception {
        try (PDDocument doc = Loader.loadPDF(r.getResponse().getContentAsByteArray())) {
            return doc.getPage(0).getMediaBox().getWidth() * 25.4f / 72f;
        }
    }

    private void settings() throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("legalName", "Boutique Awa SARL");
        s.put("address", "Rue Joss, Akwa, Douala");
        s.put("niu", "M012345678901A");
        s.put("rccm", "RC/DLA/2020/B/1234");
        s.put("vatRegistered", true);
        s.put("vatRate", 19.25);
        s.put("pricesIncludeVat", true);
        data(owner(HttpMethod.PUT, "/api/v1/invoicing/settings", s), 200);
    }

    private JsonNode create(String kind) throws Exception {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "Société Ndolo");
        client.put("phone", "+237677000111");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 2);
        line.put("unitPrice", 11_925);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> doc = new LinkedHashMap<>();
        if (kind != null) doc.put("kind", kind);
        doc.put("client", client);
        doc.put("lines", List.of(line));
        return data(owner(HttpMethod.POST, "/api/v1/invoices", doc), 201);
    }

    private String issued() throws Exception {
        String id = create(null).path("id").asText();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        return id;
    }

    private static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.setStroke(new BasicStroke(4));
        g.drawPolyline(new int[]{10, 80, 150, 220, 290}, new int[]{70, 20, 80, 25, 60}, 5);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private MvcResult upload(String id, String role, byte[] content, String type) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.multipart(HttpMethod.PUT,
                        "/api/v1/invoices/" + id + "/signatures/" + role)
                .file(new MockMultipartFile("file", "signature.png", type, content))
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
    }

    private static String path(String url) {
        int i = url.indexOf("/api/v1/");
        assertThat(i).as(url).isPositive();
        return url.substring(i);
    }

    // ── layouts ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("layout: per document (any status, response fields), per tenant default, preview override; "
            + "tickets on an 80 / 58 mm page; unknown values → 400 French; V1 PDF unchanged without choice")
    void layouts() throws Exception {
        String id = issued();
        JsonNode before = data(owner(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
        assertThat(before.path("invoiceTemplate").isNull()).isTrue();
        assertThat(before.path("clientSigned").asBoolean()).isFalse();
        assertThat(pageWidthMm(owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf", null))).isCloseTo(210f,
                org.assertj.core.data.Offset.offset(1f));

        JsonNode laid = data(owner(HttpMethod.PUT, "/api/v1/invoices/" + id + "/layout",
                Map.of("invoiceTemplate", "ticket-classic", "ticketWidth", "58")), 200);
        assertThat(laid.path("invoiceTemplate").asText()).isEqualTo("TICKET_CLASSIC");
        assertThat(laid.path("ticketWidth").asText()).isEqualTo("58");
        MvcResult ticket = owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf", null);
        assertThat(pageWidthMm(ticket)).isCloseTo(58f, org.assertj.core.data.Offset.offset(1f));
        assertThat(pdfText(ticket).replaceAll("\\s+", "")).contains("FAC-");

        MvcResult preview = owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf?template=CLASSIC", null);
        assertThat(pageWidthMm(preview)).isCloseTo(210f, org.assertj.core.data.Offset.offset(1f));
        assertThat(pdfText(preview)).containsIgnoringCase("Facturé à");
        assertThat(data(owner(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200).path("invoiceTemplate")
                .asText()).as("a preview saves nothing").isEqualTo("TICKET_CLASSIC");

        // tenant default: a document without its own layout follows it
        String other = issued();
        JsonNode prefs = data(owner(HttpMethod.PUT, "/api/v1/invoicing/settings/documents",
                Map.of("invoiceTemplate", "TICKET_MODERN", "ticketWidth", "80")), 200);
        assertThat(prefs.path("invoiceTemplate").asText()).isEqualTo("TICKET_MODERN");
        assertThat(prefs.path("effectivePublicLinkDays").asInt()).isEqualTo(30);
        assertThat(pageWidthMm(owner(HttpMethod.GET, "/api/v1/invoices/" + other + "/pdf", null)))
                .isCloseTo(80f, org.assertj.core.data.Offset.offset(1f));

        JsonNode bad = error(owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf?template=fancy", null), 400,
                "VALIDATION_ERROR");
        assertThat(bad.path("error").asText()).startsWith("Modèle inconnu");
        error(owner(HttpMethod.PUT, "/api/v1/invoices/" + id + "/layout", Map.of("ticketWidth", "100")), 400,
                "VALIDATION_ERROR");
        error(owner(HttpMethod.PUT, "/api/v1/invoicing/settings/documents", Map.of("publicLinkDays", 0)), 422,
                "VALIDATION_ERROR");
    }

    // ── signatures ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("signatures: PNG stored and printed; type / size checked (422 French); an issued document's "
            + "signature is never replaced nor removed (409); a draft's can be removed")
    void signatures() throws Exception {
        String draft = create(null).path("id").asText();
        JsonNode signed = data(upload(draft, "client", png(), "image/png"), 200);
        assertThat(signed.path("clientSigned").asBoolean()).isTrue();
        assertThat(signed.path("sellerSigned").asBoolean()).isFalse();
        assertThat(body(upload(draft, "client", png(), "image/png"))).as("a draft's signature can be replaced")
                .contains("\"clientSigned\":true");
        MvcResult image = owner(HttpMethod.GET, "/api/v1/invoices/" + draft + "/signatures/client", null);
        assertThat(image.getResponse().getStatus()).isEqualTo(200);
        assertThat(image.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(jdbc.queryForObject("SELECT signature_client_path FROM \"" + schema + "\".invoices WHERE id = ?::uuid",
                String.class, draft)).startsWith(draft + "-client-").endsWith(".png");
        assertThat(body(owner(HttpMethod.GET, "/api/v1/invoices/" + draft, null))).doesNotContain("-client-");

        JsonNode invalid = error(upload(draft, "seller", "not an image at all".getBytes(), "image/png"), 422,
                "INVOICE_SIGNATURE_INVALID");
        assertThat(invalid.path("error").asText()).isEqualTo("Format de signature non pris en charge (PNG ou JPEG)");
        error(upload(draft, "seller", png(), "application/pdf"), 422, "INVOICE_SIGNATURE_INVALID");
        error(upload(draft, "seller", new byte[300 * 1024], "image/png"), 422, "INVOICE_SIGNATURE_TOO_LARGE");
        error(upload(draft, "witness", png(), "image/png"), 400, "VALIDATION_ERROR");

        data(owner(HttpMethod.POST, "/api/v1/invoices/" + draft + "/issue", null), 200);
        JsonNode both = data(upload(draft, "seller", png(), "image/png"), 200);
        assertThat(both.path("sellerSigned").asBoolean()).isTrue();
        JsonNode replace = error(upload(draft, "seller", png(), "image/png"), 409, "INVOICE_INVALID_TRANSITION");
        assertThat(replace.path("error").asText()).contains("déjà signé");
        error(owner(HttpMethod.DELETE, "/api/v1/invoices/" + draft + "/signatures/client", null), 409,
                "INVOICE_INVALID_TRANSITION");
        MvcResult pdf = owner(HttpMethod.GET, "/api/v1/invoices/" + draft + "/pdf", null);
        assertThat(pdfText(pdf)).containsIgnoringCase("Signature client");

        String other = create(null).path("id").asText();
        data(upload(other, "client", png(), "image/png"), 200);
        assertThat(data(owner(HttpMethod.DELETE, "/api/v1/invoices/" + other + "/signatures/client", null), 200)
                .path("clientSigned").asBoolean()).isFalse();
        error(owner(HttpMethod.GET, "/api/v1/invoices/" + other + "/signatures/client", null), 404,
                "INVOICE_SIGNATURE_NOT_FOUND");
    }

    // ── public links ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("public link: lifetime of the tenant (30 d, then 90 d), opens without login, rotation kills the "
            + "previous links (404) and the new one opens; a draft invoice has none (409)")
    void publicLinks() throws Exception {
        String id = issued();
        JsonNode first = data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link", null), 200);
        assertThat(first.path("validityDays").asInt()).isEqualTo(30);
        assertThat(Duration.between(Instant.now(), Instant.parse(first.path("expiresAt").asText())).toDays())
                .isBetween(29L, 30L);
        String firstPath = path(first.path("url").asText());
        assertThat(pdfText(call(null, HttpMethod.GET, firstPath, null))).contains("Société Ndolo");

        data(owner(HttpMethod.PUT, "/api/v1/invoicing/settings/documents", Map.of("publicLinkDays", 90)), 200);
        JsonNode longer = data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link", null), 200);
        assertThat(longer.path("validityDays").asInt()).isEqualTo(90);
        assertThat(Duration.between(Instant.now(), Instant.parse(longer.path("expiresAt").asText())).toDays())
                .isBetween(89L, 90L);

        JsonNode rotated = data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link/rotate", null), 200);
        error(call(null, HttpMethod.GET, firstPath, null), 404, "INVOICE_LINK_INVALID");
        error(call(null, HttpMethod.GET, path(longer.path("url").asText()), null), 404, "INVOICE_LINK_INVALID");
        assertThat(pdfText(call(null, HttpMethod.GET, path(rotated.path("url").asText()), null)))
                .contains("Société Ndolo");

        String draft = create(null).path("id").asText();
        error(owner(HttpMethod.POST, "/api/v1/invoices/" + draft + "/public-link", null), 409,
                "INVOICE_INVALID_TRANSITION");
    }

    @Test
    @DisplayName("quote: public link and WhatsApp sending allowed (« voici votre devis DV-… »), the link opens the "
            + "DEVIS PDF; a cancelled quote is not sendable (409)")
    void quoteSending() throws Exception {
        ReflectionTestUtils.setField(target(), "whatsApp", fake);
        JsonNode quote = create("QUOTE");
        String id = quote.path("id").asText();
        String reference = quote.path("quoteReference").asText();
        JsonNode sent = data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", null), 200);
        assertThat(sent.path("sendCount").asInt()).isEqualTo(1);
        assertThat(fake.sent).hasSize(1);
        String message = fake.sent.get(0)[1];
        assertThat(message).startsWith("Bonjour Société Ndolo, voici votre devis " + reference
                + " de Boutique Awa SARL d'un montant de 23 850 XAF : ");
        assertThat(pdfText(call(null, HttpMethod.GET, message.substring(message.indexOf("/api/v1/")), null)))
                .contains("DEVIS " + reference);
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link", null), 200);

        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/cancel", Map.of("reason", "Abandon")), 200);
        error(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", null), 409, "INVOICE_INVALID_TRANSITION");
    }

    @Test
    @DisplayName("legacy Bref link: the imported invoice opens without login with its BRF- reference next to the "
            + "number; unknown / malformed → 404, past its Bref expiry → 410, rotation retires it")
    void legacyBrefLink() throws Exception {
        String id = issued();
        UUID token = UUID.randomUUID();
        legacyTokens.add(token);
        TenantContext.setCurrentTenant(schema);
        try {
            links.attachLegacy(UUID.fromString(id), token, "BRF-1A2B3C4D", Instant.now().plus(Duration.ofDays(60)),
                    schema);
        } finally {
            TenantContext.clear();
        }
        assertThat(data(owner(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200).path("legacyReference").asText())
                .isEqualTo("BRF-1A2B3C4D");
        String text = pdfText(call(null, HttpMethod.GET, "/api/v1/invoices/public/" + token, null));
        assertThat(text).contains("BRF-1A2B3C4D", "FAC-").doesNotContain(token.toString());

        error(call(null, HttpMethod.GET, "/api/v1/invoices/public/" + UUID.randomUUID(), null), 404,
                "INVOICE_LINK_INVALID");
        error(call(null, HttpMethod.GET, "/api/v1/invoices/public/not-a-token", null), 404, "INVOICE_LINK_INVALID");

        jdbc.update("UPDATE public.invoice_legacy_links SET expires_at = now() - interval '1 day' WHERE token = ?",
                token);
        error(call(null, HttpMethod.GET, "/api/v1/invoices/public/" + token, null), 410, "INVOICE_LINK_EXPIRED");
        jdbc.update("UPDATE public.invoice_legacy_links SET expires_at = NULL WHERE token = ?", token);

        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link/rotate", null), 200);
        error(call(null, HttpMethod.GET, "/api/v1/invoices/public/" + token, null), 404, "INVOICE_LINK_INVALID");
    }

    @Test
    @DisplayName("payments on the PDF and the public link: amount paid, still due, payments and instalments")
    void paymentsOnDocuments() throws Exception {
        String id = issued(); // 23 850 XAF
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Douala")).toString();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/payments", Map.of("amount", 10_000, "paidAt", today)),
                201);
        Map<String, Object> instalment = new LinkedHashMap<>();
        instalment.put("amount", 13_850);
        instalment.put("dueDate", java.time.LocalDate.now(java.time.ZoneId.of("Africa/Douala")).plusDays(30).toString());
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/payments", instalment), 201);
        String standard = pdfText(owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf", null));
        assertThat(standard).contains("Montant payé", "10 000 XAF", "Reste à payer", "13 850 XAF", "Échéance");

        JsonNode link = data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link", null), 200);
        data(owner(HttpMethod.PUT, "/api/v1/invoices/" + id + "/layout", Map.of("invoiceTemplate", "CLASSIC")), 200);
        String classic = pdfText(call(null, HttpMethod.GET, path(link.path("url").asText()), null));
        assertThat(classic.toLowerCase(java.util.Locale.FRENCH)).contains("montant payé", "reste à payer",
                "13 850 xaf");
    }
}

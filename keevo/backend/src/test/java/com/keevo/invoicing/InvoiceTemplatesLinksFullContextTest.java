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
    @Autowired com.keevo.invoicing.invoice.application.service.InvoiceLinkSigner signer;
    @Autowired com.keevo.invoicing.invoice.adapter.out.storage.InvoiceSignatureProperties signatureProperties;

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
    private String employeeToken;
    private String viewerToken;
    private UUID otherTenantId;

    @BeforeEach
    void setUp() throws Exception {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        ownerToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema,
                "OWNER");
        employeeToken = member("EMPLOYEE");
        viewerToken = member("VIEWER");
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
        if (otherTenantId != null) {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", otherTenantId);
        }
        jdbc.update("UPDATE public.tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private Object target() {
        return AopTestUtils.getUltimateTargetObject(sendInvoiceUseCase);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private String member(String role) {
        UUID userId = UUID.randomUUID();
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", userId, storeId);
        return com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, userId, schema, role,
                "ACTIVE", storeId, false);
    }

    private java.nio.file.Path signatureFile(String id, String role) {
        String name = jdbc.queryForObject("SELECT signature_" + role + "_path FROM \"" + schema
                + "\".invoices WHERE id = ?::uuid", String.class, id);
        return name == null ? null
                : java.nio.file.Path.of(signatureProperties.getStorageDir()).resolve(schema).resolve(name);
    }

    private List<Map<String, Object>> audit(String action) {
        return jdbc.queryForList("SELECT entity_type, entity_label FROM \"" + schema + "\".audit_log WHERE action = ?",
                action);
    }

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

    /** Story 20.14 — an invoice created and issued by the employee (it acts only on its own documents). */
    private String issuedByEmployee() throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 1);
        line.put("unitPrice", 11_925);
        line.put("vatTreatment", "STANDARD");
        String id = data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices",
                Map.of("client", Map.of("name", "Société Ndolo"), "lines", List.of(line))), 201).path("id").asText();
        data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
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
        return upload(ownerToken, id, role, content, type);
    }

    private MvcResult upload(String token, String id, String role, byte[] content, String type) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.multipart(HttpMethod.PUT,
                        "/api/v1/invoices/" + id + "/signatures/" + role)
                .file(new MockMultipartFile("file", "signature.png", type, content))
                .header("Authorization", "Bearer " + token)).andReturn();
    }

    /** A 1600 × 1000 slightly noisy photo at maximum JPEG quality (over 200 KB, under 2 MB). */
    private static byte[] bigJpeg() throws Exception {
        BufferedImage img = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < 1000; y++) {
            for (int x = 0; x < 1600; x++) {
                int v = 150 + x / 20 + random.nextInt(6);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(1.0f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        }
        writer.dispose();
        return out.toByteArray();
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

        // partial updates: an absent field keeps its value, null clears it
        JsonNode kept = data(owner(HttpMethod.PUT, "/api/v1/invoices/" + id + "/layout",
                Map.of("ticketWidth", "A4")), 200);
        assertThat(kept.path("invoiceTemplate").asText()).isEqualTo("TICKET_CLASSIC");
        assertThat(kept.path("ticketWidth").asText()).isEqualTo("A4");
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("invoiceTemplate", null);
        JsonNode cleared = data(owner(HttpMethod.PUT, "/api/v1/invoices/" + id + "/layout", clear), 200);
        assertThat(cleared.path("invoiceTemplate").isNull()).isTrue();
        assertThat(cleared.path("ticketWidth").asText()).isEqualTo("A4");
        JsonNode prefsKept = data(owner(HttpMethod.PUT, "/api/v1/invoicing/settings/documents",
                Map.of("publicLinkDays", 45)), 200);
        assertThat(prefsKept.path("invoiceTemplate").asText()).isEqualTo("TICKET_MODERN");
        assertThat(prefsKept.path("publicLinkDays").asInt()).isEqualTo(45);
        assertThat(audit("INVOICE_LAYOUT_CHANGED")).isNotEmpty().allSatisfy(a ->
                assertThat(String.valueOf(a.get("entity_label"))).startsWith("FAC-"));
        assertThat(audit("INVOICE_DOCUMENT_SETTINGS_UPDATED")).hasSize(2);

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
        error(upload(draft, "seller", new byte[3 * 1024 * 1024], "image/png"), 422, "INVOICE_SIGNATURE_TOO_LARGE");
        // a raw photo over 200 KB (≤ 2 MB) is accepted: re-encoded and downscaled before the 200 KB check
        byte[] photo = bigJpeg();
        assertThat(photo.length).isGreaterThan(200 * 1024).isLessThan(2 * 1024 * 1024);
        assertThat(data(upload(draft, "seller", photo, "image/jpeg"), 200).path("sellerSigned").asBoolean()).isTrue();
        data(owner(HttpMethod.DELETE, "/api/v1/invoices/" + draft + "/signatures/seller", null), 200);
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

        assertThat(audit("INVOICE_LINK_ROTATED")).singleElement()
                .satisfies(a -> assertThat(String.valueOf(a.get("entity_label"))).startsWith("FAC-"));

        String draft = create(null).path("id").asText();
        error(owner(HttpMethod.POST, "/api/v1/invoices/" + draft + "/public-link", null), 409,
                "INVOICE_INVALID_TRANSITION");
        JsonNode rotateDraft = error(owner(HttpMethod.POST, "/api/v1/invoices/" + draft + "/public-link/rotate", null),
                409, "INVOICE_INVALID_TRANSITION");
        assertThat(rotateDraft.path("error").asText()).isEqualTo(objectMapper.readTree(body(owner(HttpMethod.POST,
                "/api/v1/invoices/" + draft + "/send", Map.of("phone", "+237677000111")))).path("error").asText());
        String cancelled = issued();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + cancelled + "/cancel", Map.of("reason", "Erreur")), 200);
        error(owner(HttpMethod.POST, "/api/v1/invoices/" + cancelled + "/public-link/rotate", null), 409,
                "INVOICE_INVALID_TRANSITION");
    }

    @Test
    @DisplayName("a link of the 14.2 shape (3-part token, version 0) opens end to end, and stops after the first "
            + "rotation")
    void legacyShapeToken() throws Exception {
        String id = issued();
        String token = signer.sign(schema, UUID.fromString(id), Instant.now().plus(Duration.ofDays(30)));
        assertThat(new String(java.util.Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.UTF_8).split("\\|")).hasSize(3);
        assertThat(pdfText(call(null, HttpMethod.GET, "/api/v1/invoices/shared?token=" + token, null)))
                .contains("Société Ndolo");
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link/rotate", null), 200);
        error(call(null, HttpMethod.GET, "/api/v1/invoices/shared?token=" + token, null), 404, "INVOICE_LINK_INVALID");
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
        assertThat(audit("QUOTE_SENT")).singleElement().satisfies(a -> {
            assertThat(a.get("entity_type")).isEqualTo("Quote");
            assertThat(a.get("entity_label")).isEqualTo(reference);
        });
        assertThat(audit("INVOICE_SENT")).isEmpty();

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

        // the invoice no longer holds the token (registry row left behind): 404
        jdbc.update("UPDATE \"" + schema + "\".invoices SET legacy_public_token = NULL WHERE id = ?::uuid", id);
        error(call(null, HttpMethod.GET, "/api/v1/invoices/public/" + token, null), 404, "INVOICE_LINK_INVALID");
        jdbc.update("UPDATE \"" + schema + "\".invoices SET legacy_public_token = ? WHERE id = ?::uuid", token, id);

        jdbc.update("UPDATE public.tenants SET status = 'SUSPENDED' WHERE id = ?", tenantId);
        error(call(null, HttpMethod.GET, "/api/v1/invoices/public/" + token, null), 404, "INVOICE_LINK_INVALID");
        jdbc.update("UPDATE public.tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);

        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link/rotate", null), 200);
        error(call(null, HttpMethod.GET, "/api/v1/invoices/public/" + token, null), 404, "INVOICE_LINK_INVALID");
    }

    @Test
    @DisplayName("legacy Bref link limits: 30 openings of a real token per window then 429; a token moved to "
            + "another invoice is refused (import)")
    void legacyLimits() throws Exception {
        String id = issued();
        UUID token = UUID.randomUUID();
        legacyTokens.add(token);
        TenantContext.setCurrentTenant(schema);
        try {
            links.attachLegacy(UUID.fromString(id), token, "BRF-0A0B0C0D", null, schema);
            String other = id; // same target again: accepted (expiry refreshed)
            links.attachLegacy(UUID.fromString(other), token, "BRF-0A0B0C0D", null, schema);
        } finally {
            TenantContext.clear();
        }
        String second = issued();
        TenantContext.setCurrentTenant(schema);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> links.attachLegacy(UUID.fromString(second),
                    token, "BRF-0A0B0C0D", null, schema)).isInstanceOf(IllegalStateException.class);
        } finally {
            TenantContext.clear();
        }
        for (int i = 0; i < com.keevo.invoicing.invoice.application.service.InvoiceLinkRateLimiter.MAX_PER_WINDOW;
             i++) {
            assertThat(call(null, HttpMethod.GET, "/api/v1/invoices/public/" + token, null).getResponse().getStatus())
                    .isEqualTo(200);
        }
        error(call(null, HttpMethod.GET, "/api/v1/invoices/public/" + token, null), 429, "INVOICE_LINK_RATE_LIMITED");
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

    @Test
    @DisplayName("signing refused (409) on a cancelled invoice, a converted quote and a cancelled quote; audited "
            + "« Document signé » / « Signature retirée » with the document reference")
    void signingRefusedAndAudited() throws Exception {
        String cancelled = issued();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + cancelled + "/cancel", Map.of("reason", "Erreur")), 200);
        error(upload(cancelled, "client", png(), "image/png"), 409, "INVOICE_INVALID_TRANSITION");

        String converted = create("QUOTE").path("id").asText();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + converted + "/convert", null), 201);
        error(upload(converted, "client", png(), "image/png"), 409, "INVOICE_INVALID_TRANSITION");

        String cancelledQuote = create("QUOTE").path("id").asText();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + cancelledQuote + "/cancel", Map.of("reason", "Abandon")),
                200);
        JsonNode refused = error(upload(cancelledQuote, "seller", png(), "image/png"), 409,
                "INVOICE_INVALID_TRANSITION");
        assertThat(refused.path("error").asText()).contains("ne peut plus être signé");

        JsonNode quote = create("QUOTE");
        String q = quote.path("id").asText();
        data(upload(q, "client", png(), "image/png"), 200);
        data(owner(HttpMethod.DELETE, "/api/v1/invoices/" + q + "/signatures/client", null), 200);
        assertThat(audit("INVOICE_SIGNED")).singleElement().satisfies(a -> {
            assertThat(a.get("entity_type")).isEqualTo("Quote");
            assertThat(a.get("entity_label")).isEqualTo(quote.path("quoteReference").asText());
        });
        assertThat(audit("INVOICE_SIGNATURE_REMOVED")).hasSize(1);
    }

    @Test
    @DisplayName("a sent quote's client signature is frozen (replace / remove → 409); the seller's stays editable")
    void sentQuoteFrozen() throws Exception {
        String q = create("QUOTE").path("id").asText();
        data(upload(q, "client", png(), "image/png"), 200);
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + q + "/public-link", null), 200);
        JsonNode replace = error(upload(q, "client", png(), "image/png"), 409, "INVOICE_INVALID_TRANSITION");
        assertThat(replace.path("error").asText()).contains("envoyé au client");
        error(owner(HttpMethod.DELETE, "/api/v1/invoices/" + q + "/signatures/client", null), 409,
                "INVOICE_INVALID_TRANSITION");
        data(upload(q, "seller", png(), "image/png"), 200);
        data(upload(q, "seller", png(), "image/png"), 200);
    }

    @Test
    @DisplayName("issued document whose signature file is missing: it can be signed again; a deleted draft's "
            + "signature files are removed after the commit")
    void missingFileAndDraftDeletion() throws Exception {
        String id = create(null).path("id").asText();
        data(upload(id, "client", png(), "image/png"), 200);
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        java.nio.file.Path file = signatureFile(id, "client");
        assertThat(file).exists();
        java.nio.file.Files.delete(file);
        data(upload(id, "client", png(), "image/png"), 200);
        assertThat(signatureFile(id, "client")).exists();
        error(upload(id, "client", png(), "image/png"), 409, "INVOICE_INVALID_TRANSITION");

        String draft = create(null).path("id").asText();
        data(upload(draft, "client", png(), "image/png"), 200);
        data(upload(draft, "seller", png(), "image/png"), 200);
        java.nio.file.Path client = signatureFile(draft, "client");
        java.nio.file.Path seller = signatureFile(draft, "seller");
        assertThat(client).exists();
        assertThat(seller).exists();
        assertThat(owner(HttpMethod.DELETE, "/api/v1/invoices/" + draft, null).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(client).doesNotExist();
        assertThat(seller).doesNotExist();
    }

    @Test
    @DisplayName("a signed quote with its layout converts into an invoice with the same layout and signatures")
    void signedQuoteConverts() throws Exception {
        String q = create("QUOTE").path("id").asText();
        data(owner(HttpMethod.PUT, "/api/v1/invoices/" + q + "/layout",
                Map.of("invoiceTemplate", "TICKET_MODERN", "ticketWidth", "58")), 200);
        data(upload(q, "client", png(), "image/png"), 200);
        JsonNode invoice = data(owner(HttpMethod.POST, "/api/v1/invoices/" + q + "/convert", null), 201);
        assertThat(invoice.path("clientSigned").asBoolean()).isTrue();
        assertThat(invoice.path("sellerSigned").asBoolean()).isFalse();
        assertThat(invoice.path("invoiceTemplate").asText()).isEqualTo("TICKET_MODERN");
        assertThat(invoice.path("ticketWidth").asText()).isEqualTo("58");
        String id = invoice.path("id").asText();
        assertThat(pageWidthMm(owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf", null)))
                .isCloseTo(58f, org.assertj.core.data.Offset.offset(1f));
        assertThat(owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/signatures/client", null).getResponse()
                .getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("roles: EMPLOYEE signs and rotates (create / send rights) but cannot change the document settings; "
            + "VIEWER reads signatures and settings only")
    void roles() throws Exception {
        String id = issuedByEmployee(); // Story 20.14 — an employee signs / rotates only its own documents
        assertThat(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + issued() + "/public-link/rotate", null)
                .getResponse().getStatus()).isEqualTo(404);
        data(upload(employeeToken, id, "client", png(), "image/png"), 200);
        data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link/rotate", null), 200);
        assertThat(call(employeeToken, HttpMethod.PUT, "/api/v1/invoicing/settings/documents",
                Map.of("publicLinkDays", 60)).getResponse().getStatus()).isEqualTo(403);
        data(call(employeeToken, HttpMethod.GET, "/api/v1/invoicing/settings/documents", null), 200);

        assertThat(upload(viewerToken, id, "seller", png(), "image/png").getResponse().getStatus()).isEqualTo(403);
        assertThat(call(viewerToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link/rotate", null)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(call(viewerToken, HttpMethod.DELETE, "/api/v1/invoices/" + id + "/signatures/client", null)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(call(viewerToken, HttpMethod.PUT, "/api/v1/invoicing/settings/documents",
                Map.of("publicLinkDays", 60)).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(viewerToken, HttpMethod.GET, "/api/v1/invoices/" + id + "/signatures/client", null)
                .getResponse().getStatus()).isEqualTo(200);
        data(call(viewerToken, HttpMethod.GET, "/api/v1/invoicing/settings/documents", null), 200);
    }

    @Test
    @DisplayName("tenant isolation: another tenant cannot read the signature nor rotate the link (404)")
    void crossTenant() throws Exception {
        String id = issued();
        data(upload(id, "client", png(), "image/png"), 200);
        String otherSchema = tx.execute(st -> tenantFactory.create()).getSchemaName();
        otherTenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class,
                otherSchema);
        String otherOwner = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider,
                UUID.randomUUID(), otherSchema, "OWNER");
        assertThat(call(otherOwner, HttpMethod.GET, "/api/v1/invoices/" + id + "/signatures/client", null)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(call(otherOwner, HttpMethod.POST, "/api/v1/invoices/" + id + "/public-link/rotate", null)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT public_link_version FROM \"" + schema + "\".invoices WHERE id = ?::uuid",
                Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("list and sync pull carry invoiceTemplate / ticketWidth / clientSigned / legacyReference; the pull "
            + "from the cursor taken before the changes returns the row")
    void listAndPull() throws Exception {
        String id = issued();
        MvcResult first = call(ownerToken, HttpMethod.GET, "/api/v1/sync/pull", null);
        String since = data(first, 200).path("serverTimestamp").asText();
        assertThat(since).isNotBlank();
        Thread.sleep(5);

        data(owner(HttpMethod.PUT, "/api/v1/invoices/" + id + "/layout",
                Map.of("invoiceTemplate", "CLASSIC", "ticketWidth", "80")), 200);
        data(upload(id, "client", png(), "image/png"), 200);
        UUID token = UUID.randomUUID();
        legacyTokens.add(token);
        TenantContext.setCurrentTenant(schema);
        try {
            links.attachLegacy(UUID.fromString(id), token, "BRF-ABCDEF12", null, schema);
        } finally {
            TenantContext.clear();
        }

        JsonNode listed = null;
        for (JsonNode n : data(owner(HttpMethod.GET, "/api/v1/invoices", null), 200)) {
            if (n.path("id").asText().equals(id)) listed = n;
        }
        assertThat(listed).isNotNull();
        assertThat(listed.path("invoiceTemplate").asText()).isEqualTo("CLASSIC");
        assertThat(listed.path("ticketWidth").asText()).isEqualTo("80");
        assertThat(listed.path("clientSigned").asBoolean()).isTrue();
        assertThat(listed.path("legacyReference").asText()).isEqualTo("BRF-ABCDEF12");

        MvcResult delta = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/sync/pull").param("since", since)
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        JsonNode pulled = null;
        for (JsonNode n : data(delta, 200).path("entities").path("invoices")) {
            if (n.path("id").asText().equals(id)) pulled = n;
        }
        assertThat(pulled).as("changed since the cursor").isNotNull();
        assertThat(pulled.path("invoiceTemplate").asText()).isEqualTo("CLASSIC");
        assertThat(pulled.path("clientSigned").asBoolean()).isTrue();
        assertThat(pulled.path("legacyReference").asText()).isEqualTo("BRF-ABCDEF12");
    }
}

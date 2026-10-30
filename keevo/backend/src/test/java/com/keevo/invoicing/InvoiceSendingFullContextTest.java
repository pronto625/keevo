package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.invoicing.invoice.application.service.InvoiceLinkSigner;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.2 — invoice PDF and WhatsApp sending on the real HTTP chain (production security, real JWTs, method
 * security, entitlement aspect with lazy provisioning, PostgreSQL): every row of the spec's I/O matrix. The WhatsApp
 * provider of the context is the NoOp one (503); a capturing fake is swapped into the use case for the sending rows.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Invoice PDF + WhatsApp sending — full context (Story 14.2)")
class InvoiceSendingFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired SendInvoiceUseCase sendInvoiceUseCase;
    @Autowired InvoiceLinkSigner signer;

    /** Captures what would be sent; {@code fail} simulates a provider error. */
    static final class FakeWhatsApp implements WhatsAppSender {
        final List<String[]> sent = new ArrayList<>();
        boolean fail;

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void sendReport(String phoneNumber, String reportText) {
            if (fail) {
                throw new IllegalStateException("provider down");
            }
            sent.add(new String[]{phoneNumber, reportText});
        }
    }

    private final UUID ownerId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final FakeWhatsApp fake = new FakeWhatsApp();
    private WhatsAppSender original;
    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private String employeeToken;
    private String year;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        jdbc.update("UPDATE public.tenants SET name = 'Boutique Awa' WHERE id = ?", tenantId);
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", employeeId, storeId);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
        employeeToken = jwtTokenProvider.generateAccessToken(employeeId, schema, "EMPLOYEE", "ACTIVE", storeId,
                false);
        year = String.valueOf(LocalDate.now(ZoneId.of("Africa/Douala")).getYear());
        original = (WhatsAppSender) ReflectionTestUtils.getField(target(), "whatsApp");
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(target(), "whatsApp", original);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private Object target() {
        return AopTestUtils.getUltimateTargetObject(sendInvoiceUseCase);
    }

    private void useFakeWhatsApp() {
        ReflectionTestUtils.setField(target(), "whatsApp", fake);
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

    private static void assertError(MvcResult r, int status, String domainCode) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        assertThat(body(r)).contains("\"domainCode\":\"" + domainCode + "\"");
    }

    private static String pdfText(MvcResult r) throws Exception {
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        try (PDDocument doc = Loader.loadPDF(r.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc).replace(' ', ' ');
        }
    }

    private static Map<String, Object> line(String description, Object qty, long price, String treatment) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("description", description);
        l.put("quantity", qty);
        l.put("unitPrice", price);
        l.put("vatTreatment", treatment);
        return l;
    }

    /** A draft for « Société Ndolo » (phone optional): 2 × 11 925 TTC + 5 000 exempt = 28 850 TTC. */
    private String createDraft(String clientPhone) throws Exception {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "Société Ndolo");
        client.put("phone", clientPhone);
        Map<String, Object> draft = Map.of("client", client, "lines", List.of(
                line("Sac de riz 25 kg", 2, 11_925, "STANDARD"), line("Livraison", 1, 5_000, "EXEMPT")));
        return data(owner(HttpMethod.POST, "/api/v1/invoices", draft), 201).path("id").asText();
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

    private String issued(String clientPhone) throws Exception {
        String id = createDraft(clientPhone);
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        return id;
    }

    private Map<String, Object> sending(String id) {
        return jdbc.queryForMap("SELECT last_sent_at, last_sent_to, send_count FROM \"" + schema
                + "\".invoices WHERE id = ?::uuid", id);
    }

    private long sentAudits() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".audit_log WHERE action = 'INVOICE_SENT'",
                Long.class);
        return n == null ? 0 : n;
    }

    private static String linkPath(String message) {
        int i = message.indexOf("/api/v1/invoices/shared?token=");
        assertThat(i).as(message).isPositive();
        return message.substring(i);
    }

    // ── matrix ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PDF: issued (number, client, totals « 28 850 XAF », inline FAC-….pdf), draft stamp, employee can "
            + "view, unknown → 404")
    void pdf() throws Exception {
        settings();
        String draft = createDraft(null);
        MvcResult d = call(employeeToken, HttpMethod.GET, "/api/v1/invoices/" + draft + "/pdf", null);
        assertThat(d.getResponse().getStatus()).isEqualTo(200);
        assertThat(d.getResponse().getHeader("Content-Disposition")).contains("inline").contains("brouillon.pdf");
        assertThat(pdfText(d)).contains("BROUILLON — sans valeur");

        data(owner(HttpMethod.POST, "/api/v1/invoices/" + draft + "/issue", null), 200);
        MvcResult r = owner(HttpMethod.GET, "/api/v1/invoices/" + draft + "/pdf", null);
        String number = "FAC-" + year + "-000001";
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("filename=\"" + number + ".pdf\"");
        String text = pdfText(r);
        assertThat(text).contains("FACTURE N° " + number, "Boutique Awa SARL", "Société Ndolo", "28 850 XAF",
                "NIU : M012345678901A").doesNotContain("BROUILLON", draft);
        assertThat(text).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-");

        data(owner(HttpMethod.POST, "/api/v1/invoices/" + draft + "/pay", Map.of("paymentMethod", "Espèces")), 200);
        assertThat(pdfText(owner(HttpMethod.GET, "/api/v1/invoices/" + draft + "/pdf", null))).contains("PAYÉE le");

        assertError(owner(HttpMethod.GET, "/api/v1/invoices/" + UUID.randomUUID() + "/pdf", null), 404,
                "INVOICE_NOT_FOUND");
    }

    private boolean pdfHasImage(String id) throws Exception {
        MvcResult r = owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf", null);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        try (PDDocument doc = Loader.loadPDF(r.getResponse().getContentAsByteArray())) {
            var resources = doc.getPage(0).getResources();
            for (var name : resources.getXObjectNames()) {
                if (resources.isImageXObject(name)) return true;
            }
            return false;
        }
    }

    @Test
    @DisplayName("PDF logo: the current company logo on every invoice, including one issued before the upload")
    void pdfLogo() throws Exception {
        settings();
        String before = issued(null);
        assertThat(pdfHasImage(before)).isFalse();

        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(200, 80,
                java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", png);
        MvcResult upload = mockMvc.perform(MockMvcRequestBuilders.multipart(HttpMethod.PUT,
                        "/api/v1/account/company/logo")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "logo.png", "image/png",
                        png.toByteArray()))
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(upload.getResponse().getStatus()).as(body(upload)).isEqualTo(200);
        try {
            String after = issued(null);
            assertThat(jdbc.queryForObject("SELECT seller_has_logo FROM \"" + schema
                    + "\".invoices WHERE id = ?::uuid", Boolean.class, after)).isTrue();
            assertThat(pdfHasImage(after)).isTrue();
            assertThat(pdfHasImage(before)).as("issued before the upload").isTrue();
        } finally {
            owner(HttpMethod.DELETE, "/api/v1/account/company/logo", null);
        }
    }

    @Test
    @DisplayName("WhatsApp not configured (NoOp) → 503 WHATSAPP_UNAVAILABLE, nothing recorded")
    void whatsAppUnavailable() throws Exception {
        settings();
        String id = issued("+237677000111");
        MvcResult r = owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", null);
        assertError(r, 503, "WHATSAPP_UNAVAILABLE");
        assertThat(body(r)).contains("L'envoi WhatsApp n'est pas disponible pour le moment");
        assertThat(sending(id).get("send_count")).isEqualTo(0);
        assertThat(sentAudits()).isZero();
    }

    @Test
    @DisplayName("send: French message with an absolute link (forwarded headers honoured) to the client's phone; "
            + "recorded, audited with a masked number; the link opens the PDF without login; phone override "
            + "normalized")
    void sendAndOpenLink() throws Exception {
        settings();
        useFakeWhatsApp();
        String id = issued("+237677000111");
        String number = "FAC-" + year + "-000001";

        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/invoices/" + id + "/send")
                .header("Authorization", "Bearer " + ownerToken)
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "keevo.ad2s.cm")).andReturn();
        JsonNode sent = data(r, 200);
        assertThat(sent.path("sendCount").asInt()).isEqualTo(1);
        assertThat(sent.path("lastSentTo").asText()).isEqualTo("+237 6•• ••• 111");
        assertThat(sent.path("lastSentAt").isNull()).isFalse();

        assertThat(fake.sent).hasSize(1);
        assertThat(fake.sent.get(0)[0]).isEqualTo("+237677000111");
        String message = fake.sent.get(0)[1];
        assertThat(message).startsWith("Bonjour Société Ndolo, voici votre facture " + number
                + " de Boutique Awa SARL d'un montant de 28 850 XAF : https://keevo.ad2s.cm/api/v1/invoices/shared?token=");
        assertThat(message).doesNotContain(id);

        Map<String, Object> row = sending(id);
        assertThat(row.get("send_count")).isEqualTo(1);
        assertThat(row.get("last_sent_to")).isEqualTo("+237677000111");
        List<Map<String, Object>> audit = jdbc.queryForList("SELECT entity_label, value_after FROM \"" + schema
                + "\".audit_log WHERE action = 'INVOICE_SENT'");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("entity_label")).isEqualTo(number);
        assertThat(String.valueOf(audit.get(0).get("value_after"))).contains("+237 6•• ••• 111")
                .doesNotContain("677000111");

        // the client opens the link: no JWT
        MvcResult pdf = call(null, HttpMethod.GET, linkPath(message), null);
        assertThat(pdf.getResponse().getStatus()).as(body(pdf)).isEqualTo(200);
        assertThat(pdfText(pdf)).contains("FACTURE N° " + number, "28 850 XAF");

        // override phone (employee may send): local number normalized
        data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/send", Map.of("phone", "677 00 01 12")),
                200);
        assertThat(fake.sent.get(1)[0]).isEqualTo("+237677000112");
        assertThat(sending(id).get("send_count")).isEqualTo(2);
        assertThat(sentAudits()).isEqualTo(2);

        assertError(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", Map.of("phone", "abc")), 422,
                "INVOICE_PHONE_INVALID");
        // a paid invoice can be re-sent
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/pay", null), 200);
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", null), 200);
        assertThat(sending(id).get("send_count")).isEqualTo(3);
    }

    @Test
    @DisplayName("send refused: draft / cancelled → 409, no phone → 422 French, provider failure → 502 nothing recorded")
    void sendRefused() throws Exception {
        settings();
        useFakeWhatsApp();
        String draft = createDraft("+237677000111");
        assertError(owner(HttpMethod.POST, "/api/v1/invoices/" + draft + "/send", null), 409,
                "INVOICE_INVALID_TRANSITION");

        String cancelled = issued("+237677000111");
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + cancelled + "/cancel", Map.of("reason", "Erreur")), 200);
        assertError(owner(HttpMethod.POST, "/api/v1/invoices/" + cancelled + "/send", null), 409,
                "INVOICE_INVALID_TRANSITION");

        String noPhone = issued(null);
        MvcResult r = owner(HttpMethod.POST, "/api/v1/invoices/" + noPhone + "/send", null);
        assertError(r, 422, "INVOICE_PHONE_REQUIRED");
        assertThat(body(r)).contains("Indiquez le numéro WhatsApp du client");

        fake.fail = true;
        MvcResult failed = owner(HttpMethod.POST, "/api/v1/invoices/" + noPhone + "/send",
                Map.of("phone", "+237677000111"));
        assertError(failed, 502, "WHATSAPP_DELIVERY_FAILED");
        assertThat(sending(noPhone).get("send_count")).isEqualTo(0);
        assertThat(sentAudits()).isZero();
        assertThat(fake.sent).isEmpty();
    }

    @Test
    @DisplayName("rate limit: the 31st sending of the tenant in an hour → 429")
    void rateLimit() throws Exception {
        settings();
        useFakeWhatsApp();
        String id = issued("+237677000111");
        for (int i = 0; i < 30; i++) {
            data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", null), 200);
        }
        assertError(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", null), 429, "RATE_LIMIT_EXCEEDED");
        assertThat(sending(id).get("send_count")).isEqualTo(30);
    }

    @Test
    @DisplayName("public link: expired → 410, tampered / unknown invoice / suspended tenant → 404 French, no detail")
    void publicLinkRefused() throws Exception {
        settings();
        String id = issued("+237677000111");
        String valid = signer.sign(schema, UUID.fromString(id), Instant.now().plusSeconds(3600));
        assertThat(call(null, HttpMethod.GET, "/api/v1/invoices/shared?token=" + valid, null).getResponse()
                .getStatus()).isEqualTo(200);

        String expired = signer.sign(schema, UUID.fromString(id), Instant.now().minusSeconds(60));
        MvcResult r = call(null, HttpMethod.GET, "/api/v1/invoices/shared?token=" + expired, null);
        assertError(r, 410, "INVOICE_LINK_EXPIRED");
        assertThat(body(r)).contains("Ce lien de facture a expiré");

        MvcResult tampered = call(null, HttpMethod.GET, "/api/v1/invoices/shared?token=" + valid + "x", null);
        assertError(tampered, 404, "INVOICE_LINK_INVALID");
        assertThat(body(tampered)).contains("Ce lien de facture n'est pas valide").doesNotContain(schema);

        String unknown = signer.sign(schema, UUID.randomUUID(), Instant.now().plusSeconds(3600));
        assertError(call(null, HttpMethod.GET, "/api/v1/invoices/shared?token=" + unknown, null), 404,
                "INVOICE_LINK_INVALID");

        jdbc.update("UPDATE public.tenants SET status = 'SUSPENDED' WHERE id = ?", tenantId);
        try {
            assertError(call(null, HttpMethod.GET, "/api/v1/invoices/shared?token=" + valid, null), 404,
                    "INVOICE_LINK_INVALID");
        } finally {
            jdbc.update("UPDATE public.tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);
        }
    }

    @Test
    @DisplayName("module disabled → /pdf and /send 403 MODULE_NOT_ENTITLED")
    void moduleDisabled() throws Exception {
        settings();
        String id = issued("+237677000111");
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, 'invoicing', "
                + "'DISABLED', 'FREE') ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status",
                tenantId);
        assertError(owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf", null), 403, "MODULE_NOT_ENTITLED");
        assertError(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", null), 403, "MODULE_NOT_ENTITLED");
    }

    @Test
    @DisplayName("upgrade V1 → V5: a tenant provisioned at V1 is migrated on its first call, its invoices kept")
    void upgradeFromV1() throws Exception {
        String q = "\"" + schema + "\".";
        // back to the 14.1 state: V2 (14.2), V3 (14.4), V4 and V5 (14.7) tables, columns and history rows gone, one
        // issued invoice written under V1
        jdbc.execute("DROP TABLE " + q + "credit_note_items, " + q + "credit_notes, " + q + "credit_note_counters, "
                + q + "sale_invoice_states");
        jdbc.execute("ALTER TABLE " + q + "invoices DROP COLUMN last_sent_at, DROP COLUMN last_sent_to, "
                + "DROP COLUMN send_count, DROP COLUMN seller_name_from_display, DROP COLUMN seller_has_logo, "
                + "DROP COLUMN source_sale_id, DROP COLUMN superseded_at");
        jdbc.update("DELETE FROM " + q + "flyway_history_invoicing WHERE version IN ('2', '3', '4', '5')");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "invoices (id, number, status, issue_date, client_name, seller_legal_name, "
                + "vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, total_ttc, issued_at) VALUES "
                + "(?, 'FAC-2026-000042', 'ISSUED', DATE '2026-10-30', 'Client V1', 'Boutique V1', false, 19.25, "
                + "true, 5000, 0, 5000, now())", id);

        JsonNode invoice = data(owner(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
        assertThat(invoice.path("number").asText()).isEqualTo("FAC-2026-000042");
        assertThat(invoice.path("seller").path("legalName").asText()).isEqualTo("Boutique V1");
        assertThat(invoice.path("sendCount").asInt()).isZero();
        assertThat(invoice.path("saleId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + q + "flyway_history_invoicing WHERE version IN "
                + "('2', '3', '4', '5') AND success", Integer.class)).isEqualTo(4);
        assertThat(invoice.path("creditNotes")).isEmpty();
        assertThat(invoice.path("creditedTtc").asLong()).isZero();
        assertThat(pdfText(owner(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf", null)))
                .contains("FAC-2026-000042", "Boutique V1", "5 000 XAF");
    }
}

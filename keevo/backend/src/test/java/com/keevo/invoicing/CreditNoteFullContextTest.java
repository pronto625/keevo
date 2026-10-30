package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.7 — manual credit notes (« avoirs ») through the REST API with real JWTs: full and partial credit notes on
 * an issued / paid invoice, own gapless series, invoice view (credited amount, list of credit notes), refusals,
 * permissions, gating, PDF and audit.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Credit notes — full context (Story 14.7)")
class CreditNoteFullContextTest extends FullContextIntegrationTest {

    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private String employeeToken;
    private String year;

    @BeforeEach
    void setUp() throws Exception {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", employeeId, storeId);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
        employeeToken = jwtTokenProvider.generateAccessToken(employeeId, schema, "EMPLOYEE", "ACTIVE", storeId,
                false);
        year = String.valueOf(LocalDate.now(ZoneId.of("Africa/Douala")).getYear());
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

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
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

    private static Map<String, Object> line(String description, Object qty, long price, String treatment) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("description", description);
        l.put("quantity", qty);
        l.put("unitPrice", price);
        l.put("vatTreatment", treatment);
        return l;
    }

    /** A draft: 3 × 1 000 TTC at 19.25 % (HT 2 516, VAT 484) + 5 000 exempt — TTC 8 000. */
    private String draft() throws Exception {
        return data(owner(HttpMethod.POST, "/api/v1/invoices", Map.of("client", Map.of("name", "Société Ndolo"),
                "lines", List.of(line("Savon", 3, 1_000, "STANDARD"), line("Livraison", 1, 5_000, "EXEMPT")))), 201)
                .path("id").asText();
    }

    private String issued() throws Exception {
        String id = draft();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        return id;
    }

    private String paid() throws Exception {
        String id = issued();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/pay", Map.of("paymentMethod", "Espèces")), 200);
        return id;
    }

    private MvcResult credit(String invoiceId, String reason, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        if (lines != null) {
            body.put("lines", lines);
        }
        return owner(HttpMethod.POST, "/api/v1/invoices/" + invoiceId + "/credit-notes", body);
    }

    private static Map<String, Object> sel(int position, Object quantity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("position", position);
        m.put("quantity", quantity);
        return m;
    }

    private JsonNode invoice(String id) throws Exception {
        return data(owner(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
    }

    // ── matrix ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("full credit note on a PAID invoice: AV-yyyy-000001, totals = invoice totals, invoice unchanged and "
            + "fully credited, audit « Avoir émis », a second one → 409")
    void fullCreditNoteOnPaidInvoice() throws Exception {
        String id = paid();
        JsonNode before = invoice(id);
        List<Map<String, Object>> itemsBefore = jdbc.queryForList("SELECT id, position, total_ttc FROM \"" + schema
                + "\".invoice_items WHERE invoice_id = ?::uuid ORDER BY position", id);

        JsonNode note = data(credit(id, "Retour de la marchandise", null), 201);

        assertThat(note.path("number").asText()).isEqualTo("AV-" + year + "-000001");
        assertThat(note.path("invoiceNumber").asText()).isEqualTo("FAC-" + year + "-000001");
        assertThat(note.path("invoiceId").asText()).isEqualTo(id);
        assertThat(note.path("reason").asText()).isEqualTo("Retour de la marchandise");
        assertThat(note.path("totalHt").asLong()).isEqualTo(before.path("totalHt").asLong());
        assertThat(note.path("totalVat").asLong()).isEqualTo(before.path("totalVat").asLong());
        assertThat(note.path("totalTtc").asLong()).isEqualTo(8_000);
        assertThat(note.path("lines")).hasSize(2);
        assertThat(note.path("lines").get(1).path("vatMention").asText()).isEqualTo("Exonérée");

        JsonNode after = invoice(id);
        assertThat(after.path("number").asText()).isEqualTo(before.path("number").asText());
        assertThat(after.path("status").asText()).isEqualTo("PAID");
        assertThat(after.path("lines")).isEqualTo(before.path("lines"));
        assertThat(after.path("totalTtc").asLong()).isEqualTo(8_000);
        assertThat(after.path("creditedTtc").asLong()).isEqualTo(8_000);
        assertThat(after.path("fullyCredited").asBoolean()).isTrue();
        assertThat(after.path("creditNotes")).hasSize(1);
        assertThat(after.path("creditNotes").get(0).path("number").asText()).isEqualTo("AV-" + year + "-000001");
        assertThat(after.path("creditNotes").get(0).path("reason").asText()).isEqualTo("Retour de la marchandise");
        assertThat(jdbc.queryForList("SELECT id, position, total_ttc FROM \"" + schema
                + "\".invoice_items WHERE invoice_id = ?::uuid ORDER BY position", id)).isEqualTo(itemsBefore);
        // the list carries the same view
        JsonNode listed = data(owner(HttpMethod.GET, "/api/v1/invoices", null), 200);
        assertThat(listed.get(0).path("creditedTtc").asLong()).isEqualTo(8_000);

        // GET the credit note
        JsonNode got = data(owner(HttpMethod.GET, "/api/v1/credit-notes/" + note.path("id").asText(), null), 200);
        assertThat(got.path("number").asText()).isEqualTo("AV-" + year + "-000001");
        assertThat(got.path("lines").get(0).path("invoicePosition").asInt()).isEqualTo(1);

        // already fully credited → 409
        assertError(credit(id, "Encore", null), 409, "INVOICE_INVALID_TRANSITION");

        // audit: label = credit note number, detail « sur la facture FAC-… », no UUID
        Map<String, Object> audit = jdbc.queryForMap("SELECT entity_label, value_after, metadata::text AS meta FROM \""
                + schema + "\".audit_log WHERE action = 'CREDIT_NOTE_ISSUED' AND entity_type = 'CreditNote'");
        assertThat(audit.get("entity_label")).isEqualTo("AV-" + year + "-000001");
        assertThat((String) audit.get("meta")).contains("sur la facture FAC-" + year + "-000001");
        assertThat((String) audit.get("value_after")).contains("AV-" + year + "-000001", "FAC-" + year + "-000001");
        assertThat(UUID_PATTERN.matcher(audit.get("entity_label") + " " + audit.get("value_after")).find()).isFalse();
    }

    @Test
    @DisplayName("partial credit notes: 1 unit, creditedTtc updated; 3 × 1 unit add up to the line exactly; "
            + "over-quantity / empty / unknown line → 422 CREDIT_NOTE_INVALID")
    void partialCreditNotes() throws Exception {
        String id = paid();

        JsonNode first = data(credit(id, "Un savon abîmé", List.of(sel(1, 1))), 201);
        assertThat(first.path("totalTtc").asLong()).isEqualTo(1_000);
        assertThat(first.path("totalHt").asLong()).isEqualTo(839);
        assertThat(first.path("lines")).hasSize(1);
        JsonNode inv = invoice(id);
        assertThat(inv.path("creditedTtc").asLong()).isEqualTo(1_000);
        assertThat(inv.path("fullyCredited").asBoolean()).isFalse();

        assertError(credit(id, "Trop", List.of(sel(1, 3))), 422, "CREDIT_NOTE_INVALID");
        assertError(credit(id, "Vide", List.of()), 422, "CREDIT_NOTE_INVALID");
        assertError(credit(id, "Inconnue", List.of(sel(7, 1))), 422, "CREDIT_NOTE_INVALID");
        assertError(credit(id, "Zéro", List.of(sel(1, 0))), 422, "VALIDATION_ERROR");
        assertError(credit(id, "Décimales", List.of(sel(1, new java.math.BigDecimal("0.0001")))), 422,
                "VALIDATION_ERROR");
        assertError(credit(id, "Sans quantité", List.of(sel(1, null))), 422, "VALIDATION_ERROR");
        assertError(credit(id, "  ", null), 422, "VALIDATION_ERROR");

        data(credit(id, "Deuxième savon", List.of(sel(1, 1))), 201);
        data(credit(id, "Troisième savon", List.of(sel(1, 1))), 201);
        Long ht = jdbc.queryForObject("SELECT sum(l.total_ht) FROM \"" + schema + "\".credit_note_items l JOIN \""
                + schema + "\".credit_notes c ON c.id = l.credit_note_id WHERE c.invoice_id = ?::uuid "
                + "AND l.invoice_position = 1", Long.class, id);
        Long ttc = jdbc.queryForObject("SELECT sum(l.total_ttc) FROM \"" + schema + "\".credit_note_items l JOIN \""
                + schema + "\".credit_notes c ON c.id = l.credit_note_id WHERE c.invoice_id = ?::uuid "
                + "AND l.invoice_position = 1", Long.class, id);
        assertThat(ht).isEqualTo(2_516);
        assertThat(ttc).isEqualTo(3_000);
        assertError(credit(id, "Plus rien", List.of(sel(1, 1))), 422, "CREDIT_NOTE_INVALID");

        // the rest (the exempt line) → fully credited; numbers AV-…-000001..000004, no gap
        data(credit(id, "Livraison annulée", null), 201);
        assertThat(invoice(id).path("fullyCredited").asBoolean()).isTrue();
        assertThat(jdbc.queryForList("SELECT number FROM \"" + schema + "\".credit_notes ORDER BY number",
                String.class)).containsExactly("AV-" + year + "-000001", "AV-" + year + "-000002",
                "AV-" + year + "-000003", "AV-" + year + "-000004");
    }

    @Test
    @DisplayName("draft / cancelled → 409; ISSUED with a credit note can no longer be cancelled; own counter")
    void refusalsAndCancelGuard() throws Exception {
        assertError(credit(draft(), "Brouillon", null), 409, "INVOICE_INVALID_TRANSITION");
        String cancelled = issued();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + cancelled + "/cancel", Map.of("reason", "Erreur")), 200);
        assertError(credit(cancelled, "Annulée", null), 409, "INVOICE_INVALID_TRANSITION");

        String id = issued(); // FAC-…-000002
        data(credit(id, "Remise accordée", List.of(sel(2, 1))), 201);
        MvcResult cancel = owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/cancel", Map.of("reason", "Erreur"));
        assertError(cancel, 409, "INVOICE_INVALID_TRANSITION");
        assertThat(body(cancel)).contains("ayant déjà un avoir");
        assertThat(invoice(id).path("status").asText()).isEqualTo("ISSUED");

        // per-year counters, independent: FAC at 2, AV at 1
        assertThat(jdbc.queryForObject("SELECT last_value FROM \"" + schema + "\".credit_note_counters WHERE year = ?",
                Integer.class, Integer.parseInt(year))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_number FROM \"" + schema + "\".invoice_counters WHERE year = ?",
                Integer.class, Integer.parseInt(year))).isEqualTo(2);
        assertError(owner(HttpMethod.GET, "/api/v1/credit-notes/" + UUID.randomUUID(), null), 404,
                "CREDIT_NOTE_NOT_FOUND");
        assertError(owner(HttpMethod.POST, "/api/v1/invoices/" + UUID.randomUUID() + "/credit-notes",
                Map.of("reason", "x")), 404, "INVOICE_NOT_FOUND");
    }

    @Test
    @DisplayName("EMPLOYEE: POST → 403 FORBIDDEN, GET 200; read-only grace: POST → 403 MODULE_READ_ONLY")
    void permissionsAndGating() throws Exception {
        String id = paid();
        assertError(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/credit-notes",
                Map.of("reason", "Retour")), 403, "FORBIDDEN");
        String noteId = data(credit(id, "Retour", List.of(sel(1, 1))), 201).path("id").asText();
        data(call(employeeToken, HttpMethod.GET, "/api/v1/credit-notes/" + noteId, null), 200);

        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'PREMIUM_TRIAL', "
                + "expires_at = now() - interval '1 day'");
        data(owner(HttpMethod.GET, "/api/v1/credit-notes/" + noteId, null), 200);
        assertError(credit(id, "Retour", List.of(sel(1, 1))), 403, "MODULE_READ_ONLY");
    }

    @Test
    @DisplayName("PDF: « AVOIR », number, invoice reference, reason, negative totals, no UUID")
    void pdf() throws Exception {
        String id = paid();
        String noteId = data(credit(id, "Retour de la marchandise", null), 201).path("id").asText();

        MvcResult r = owner(HttpMethod.GET, "/api/v1/credit-notes/" + noteId + "/pdf", null);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("AV-" + year + "-000001.pdf");
        String text;
        try (PDDocument doc = Loader.loadPDF(r.getResponse().getContentAsByteArray())) {
            text = new PDFTextStripper().getText(doc).replace('\u00a0', ' ');
        }
        assertThat(text).contains("AVOIR N° AV-" + year + "-000001", "Avoir sur la facture FAC-" + year + "-000001",
                "Motif : Retour de la marchandise", "Boutique Awa SARL", "Société Ndolo", "−8 000 XAF");
        assertThat(UUID_PATTERN.matcher(text).find()).isFalse();
    }
}

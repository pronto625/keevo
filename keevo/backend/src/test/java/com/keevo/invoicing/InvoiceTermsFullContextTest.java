package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.invoicing.invoice.adapter.in.scheduling.InvoiceOverdueJob;
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
 * Story 20.5 — discount, delivery fee, per-document VAT rate or exemption, due date, copied client data and line
 * references end to end in the real application context (REST, sync push/pull, PDF, credit notes, overdue job,
 * default rate per seller country): every row of the spec's I/O matrix.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Invoice terms — discount, delivery, VAT per document, due date (Story 20.5)")
class InvoiceTermsFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired InvoiceOverdueJob overdueJob;

    private final UUID ownerId = UUID.randomUUID();
    private String schema;
    private String ownerToken;
    private final LocalDate today = LocalDate.now(ZoneId.of("Africa/Douala"));
    private Instant clientClock = Instant.now().minusSeconds(600);
    private final String deviceId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        ownerToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema,
                "OWNER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MvcResult call(HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + ownerToken);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode data(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(expectedStatus);
        return objectMapper.readTree(body(r)).path("data");
    }

    private JsonNode error(MvcResult r, int expectedStatus, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(expectedStatus);
        JsonNode json = objectMapper.readTree(body(r));
        assertThat(json.path("domainCode").asText()).as(body(r)).isEqualTo(code);
        return json;
    }

    private void settings(boolean vatRegistered, boolean pricesIncludeVat) throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("legalName", "Boutique Awa SARL");
        s.put("address", "Rue Joss, Akwa, Douala");
        s.put("vatRegistered", vatRegistered);
        s.put("vatRate", 19.25);
        s.put("pricesIncludeVat", pricesIncludeVat);
        data(call(HttpMethod.PUT, "/api/v1/invoicing/settings", s), 200);
    }

    private static Map<String, Object> line(String description, Object quantity, long unitPrice) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", description);
        line.put("quantity", quantity);
        line.put("unitPrice", unitPrice);
        return line;
    }

    /** Bref case 1: subtotal 317 700 (6 lines, prices without VAT). */
    private static List<Map<String, Object>> brefLines() {
        Map<String, Object> rice = line("Sac de riz", 2, 150_000);
        rice.put("reference", "RIZ-25");
        rice.put("productId", UUID.randomUUID().toString());
        return List.of(line("Savon", 1, 5_000), line("Huile", 2, 3_500), line("Sel", 1, 600), rice,
                line("Sucre", 1, 100), line("Savon", 1, 5_000));
    }

    private static Map<String, Object> client() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "Société Ndolo");
        c.put("rccm", "RC/DLA/2021/A/77");
        c.put("email", "compta@ndolo.cm");
        c.put("contactName", "M. Ndolo");
        c.put("website", "ndolo.cm");
        return c;
    }

    private Map<String, Object> brefBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", client());
        body.put("lines", brefLines());
        body.put("discountAmount", 2_500);
        body.put("deliveryFee", 2_000);
        body.put("dueDate", today.plusDays(30).toString());
        return body;
    }

    private Map<String, Object> op(String type, String entityId, Map<String, Object> payload) {
        clientClock = clientClock.plusSeconds(1);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", entityId);
        op.put("payload", payload);
        op.put("clientTimestamp", clientClock.toString());
        return op;
    }

    private JsonNode push(Map<String, Object> op) throws Exception {
        JsonNode data = data(call(HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", deviceId, "operations", List.of(op))), 200);
        return data.path("results").get(0);
    }

    private List<JsonNode> pullInvoices() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        do {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull").header("Authorization", "Bearer " + ownerToken);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode d = data(mockMvc.perform(request).andReturn(), 200);
            d.path("entities").path("invoices").forEach(out::add);
            cursor = d.path("hasMore").asBoolean() ? d.path("nextCursor").asText() : null;
        } while (cursor != null);
        return out;
    }

    private static String pdfText(MvcResult r) throws Exception {
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        try (PDDocument doc = Loader.loadPDF(r.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc).replace(' ', ' ');
        }
    }

    // ── matrix ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HT seller: draft with discount, delivery, due date, client data and references → Bref's totals; "
            + "issued; PDF shows Remise, Livraison, Échéance")
    void brefTotalsIssueAndPdf() throws Exception {
        settings(true, false);
        JsonNode d = data(call(HttpMethod.POST, "/api/v1/invoices", brefBody()), 201);
        assertThat(d.path("subtotal").asLong()).isEqualTo(317_700);
        assertThat(d.path("discountAmount").asLong()).isEqualTo(2_500);
        assertThat(d.path("deliveryFee").asLong()).isEqualTo(2_000);
        assertThat(d.path("totalHt").asLong()).isEqualTo(315_200);
        assertThat(d.path("totalVat").asLong()).isEqualTo(60_676);
        assertThat(d.path("totalTtc").asLong()).isEqualTo(377_876);
        assertThat(d.path("vatRate").decimalValue()).isEqualByComparingTo("19.25");
        assertThat(d.path("vatRateOverride").isNull()).isTrue();
        assertThat(d.path("vatExempt").asBoolean()).isFalse();
        assertThat(d.path("dueDate").asText()).isEqualTo(today.plusDays(30).toString());
        assertThat(d.path("client").path("rccm").asText()).isEqualTo("RC/DLA/2021/A/77");
        assertThat(d.path("client").path("email").asText()).isEqualTo("compta@ndolo.cm");
        assertThat(d.path("client").path("contactName").asText()).isEqualTo("M. Ndolo");
        assertThat(d.path("client").path("website").asText()).isEqualTo("ndolo.cm");
        assertThat(d.path("lines").get(3).path("reference").asText()).isEqualTo("RIZ-25");
        assertThat(d.path("lines").get(3).path("productId").asText()).isNotBlank();

        String id = d.path("id").asText();
        JsonNode issued = data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        assertThat(issued.path("totalTtc").asLong()).isEqualTo(377_876);
        assertThat(issued.path("amountDue").asLong()).isEqualTo(377_876);
        assertThat(issued.path("dueDate").asText()).isEqualTo(today.plusDays(30).toString());

        String pdf = pdfText(call(HttpMethod.GET, "/api/v1/invoices/" + id + "/pdf", null));
        assertThat(pdf).contains("Sous-total HT", "317 700 XAF", "Remise", "-2 500 XAF", "Livraison", "2 000 XAF",
                "Total TVA", "60 676 XAF", "Total TTC", "377 876 XAF", "Échéance : ", "RCCM : RC/DLA/2021/A/77",
                "Contact : M. Ndolo", "Email : compta@ndolo.cm", "Site web : ndolo.cm", "Réf. : RIZ-25");
    }

    @Test
    @DisplayName("TTC seller, V1 body: totals as today; TTC discount reduces the VAT proportionally")
    void keevoDefaultUnchangedAndTtcDiscount() throws Exception {
        settings(true, true);
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("client", Map.of("name", "Client"));
        v1.put("lines", List.of(line("Article", 1, 119_250)));
        JsonNode plain = data(call(HttpMethod.POST, "/api/v1/invoices", v1), 201);
        assertThat(plain.path("totalHt").asLong()).isEqualTo(100_000);
        assertThat(plain.path("totalVat").asLong()).isEqualTo(19_250);
        assertThat(plain.path("totalTtc").asLong()).isEqualTo(119_250);
        assertThat(plain.path("subtotal").asLong()).isEqualTo(119_250);
        assertThat(plain.path("discountAmount").asLong()).isZero();
        assertThat(plain.path("dueDate").isNull()).isTrue();

        v1.put("discountAmount", 19_250);
        JsonNode discounted = data(call(HttpMethod.POST, "/api/v1/invoices", v1), 201);
        assertThat(discounted.path("totalTtc").asLong()).isEqualTo(100_000);
        assertThat(discounted.path("totalVat").asLong()).isEqualTo(16_143); // HALF_UP(19 250 × 100 000 / 119 250)
        assertThat(discounted.path("totalHt").asLong()).isEqualTo(83_857);
    }

    @Test
    @DisplayName("exempt → VAT 0, « Exonéré de TVA » (PDF too); own rate 18 % on a CM seller; rate 101 / discount "
            + "above the subtotal / bad email → refused, nothing written")
    void exemptOwnRateAndRefusals() throws Exception {
        settings(true, false);
        Map<String, Object> exempt = brefBody();
        exempt.put("vatExempt", true);
        JsonNode e = data(call(HttpMethod.POST, "/api/v1/invoices", exempt), 201);
        assertThat(e.path("totalVat").asLong()).isZero();
        assertThat(e.path("vatExempt").asBoolean()).isTrue();
        assertThat(e.path("vatRate").decimalValue()).isEqualByComparingTo("0");
        assertThat(e.path("vatMention").asText()).isEqualTo("Exonéré de TVA");
        assertThat(e.path("totalTtc").asLong()).isEqualTo(317_200);
        assertThat(pdfText(call(HttpMethod.GET, "/api/v1/invoices/" + e.path("id").asText() + "/pdf", null)))
                .contains("Exonéré de TVA", "Sous-total HT", "Total TVA");

        Map<String, Object> own = brefBody();
        own.put("vatRate", 18);
        JsonNode o = data(call(HttpMethod.POST, "/api/v1/invoices", own), 201);
        assertThat(o.path("vatRate").decimalValue()).isEqualByComparingTo("18");
        assertThat(o.path("vatRateOverride").decimalValue()).isEqualByComparingTo("18");
        assertThat(o.path("totalVat").asLong()).isEqualTo(56_736); // 315 200 × 18 %

        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class);
        Map<String, Object> rate = brefBody();
        rate.put("vatRate", 101);
        error(call(HttpMethod.POST, "/api/v1/invoices", rate), 422, "VALIDATION_ERROR");
        Map<String, Object> discount = brefBody();
        discount.put("discountAmount", 317_701);
        JsonNode tooBig = error(call(HttpMethod.POST, "/api/v1/invoices", discount), 422, "VALIDATION_ERROR");
        assertThat(tooBig.toString()).contains("La remise ne peut pas dépasser");
        Map<String, Object> mail = brefBody();
        Map<String, Object> badClient = client();
        badClient.put("email", "pas-un-email");
        mail.put("client", badClient);
        assertThat(call(HttpMethod.POST, "/api/v1/invoices", mail).getResponse().getStatus()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("credit notes on a discounted invoice: partial → 409 French; full → exactly the invoice's TTC")
    void creditNotes() throws Exception {
        settings(true, false);
        String id = data(call(HttpMethod.POST, "/api/v1/invoices", brefBody()), 201).path("id").asText();
        data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        JsonNode partial = error(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/credit-notes",
                Map.of("reason", "Retour", "lines", List.of(Map.of("position", 1, "quantity", 1)))), 409,
                "INVOICE_INVALID_TRANSITION");
        assertThat(partial.path("error").asText()).isEqualTo(
                "Avoir partiel impossible sur une facture avec remise ou livraison : établissez un avoir total");
        JsonNode note = data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/credit-notes",
                Map.of("reason", "Retour")), 201);
        assertThat(note.path("totalTtc").asLong()).isEqualTo(377_876);
        JsonNode after = data(call(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
        assertThat(after.path("creditedTtc").asLong()).isEqualTo(377_876);
        assertThat(after.path("amountDue").asLong()).isZero();
    }

    @Test
    @DisplayName("sync: CREATE_INVOICE with every new field applied, pull carries them; UPDATE_INVOICE replaces them")
    void syncCreateUpdatePull() throws Exception {
        settings(true, false);
        String id = UUID.randomUUID().toString();
        Map<String, Object> payload = brefBody();
        payload.put("id", id);
        payload.put("vatRate", 18);
        assertThat(push(op("CREATE_INVOICE", id, payload)).path("status").asText()).isEqualTo("APPLIED");
        JsonNode pulled = pullInvoices().stream().filter(n -> n.path("id").asText().equals(id)).findFirst()
                .orElseThrow();
        assertThat(pulled.path("discountAmount").asLong()).isEqualTo(2_500);
        assertThat(pulled.path("deliveryFee").asLong()).isEqualTo(2_000);
        assertThat(pulled.path("vatRateOverride").decimalValue()).isEqualByComparingTo("18");
        assertThat(pulled.path("dueDate").asText()).isEqualTo(today.plusDays(30).toString());
        assertThat(pulled.path("client").path("contactName").asText()).isEqualTo("M. Ndolo");
        assertThat(pulled.path("lines").get(3).path("reference").asText()).isEqualTo("RIZ-25");

        Map<String, Object> update = brefBody();
        update.put("invoiceId", id);
        update.put("discountAmount", 0);
        update.remove("deliveryFee");
        update.put("vatExempt", true);
        assertThat(push(op("UPDATE_INVOICE", id, update)).path("status").asText()).isEqualTo("APPLIED");
        JsonNode got = data(call(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
        assertThat(got.path("totalTtc").asLong()).isEqualTo(317_700);
        assertThat(got.path("vatExempt").asBoolean()).isTrue();

        Map<String, Object> bad = brefBody();
        bad.put("invoiceId", id);
        bad.put("discountAmount", 400_000);
        JsonNode rejected = push(op("UPDATE_INVOICE", id, bad));
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("reason").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("due date passed, unpaid → the job marks OVERDUE; a due date before the issue date is refused at issue")
    void dueDateOverdueAndIssueRule() throws Exception {
        settings(true, false);
        Map<String, Object> body = brefBody();
        body.put("dueDate", today.toString());
        String id = data(call(HttpMethod.POST, "/api/v1/invoices", body), 201).path("id").asText();
        data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        // a day passes: the due date is now yesterday
        jdbc.update("UPDATE \"" + schema + "\".invoices SET due_date = ?::date WHERE id = ?::uuid",
                today.minusDays(1).toString(), id);
        overdueJob.run();
        JsonNode late = data(call(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
        assertThat(late.path("paymentState").asText()).isEqualTo("OVERDUE");

        // a past due date is refused when the draft is saved (single call: status, French reason, nothing written)
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class);
        Map<String, Object> past = brefBody();
        past.put("dueDate", today.minusDays(1).toString());
        JsonNode pastRefused = error(call(HttpMethod.POST, "/api/v1/invoices", past), 422, "VALIDATION_ERROR");
        assertThat(pastRefused.toString()).contains("La date d'échéance ne peut pas être passée");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class))
                .isEqualTo(before);

        // a draft saved with a valid due date that has passed by the time it is issued: refused at issue, no number
        Map<String, Object> later = brefBody();
        later.put("dueDate", today.toString());
        String early = data(call(HttpMethod.POST, "/api/v1/invoices", later), 201).path("id").asText();
        jdbc.update("UPDATE \"" + schema + "\".invoices SET due_date = ?::date WHERE id = ?::uuid",
                today.minusDays(1).toString(), early);
        JsonNode refused = error(call(HttpMethod.POST, "/api/v1/invoices/" + early + "/issue", null), 422,
                "VALIDATION_ERROR");
        assertThat(refused.toString()).contains("La date d'échéance ne peut pas précéder la date d'émission");
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices/" + early, null), 200).path("number").isNull())
                .isTrue();
    }

    @Test
    @DisplayName("no settings yet: the default rate is the oldest active store's country rate (SN → 18 %)")
    void defaultRatePerCountry() throws Exception {
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoicing/settings", null), 200).path("vatRate").decimalValue())
                .isEqualByComparingTo("19.25");
        jdbc.update("UPDATE \"" + schema + "\".stores SET country = 'SN'");
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoicing/settings", null), 200).path("vatRate").decimalValue())
                .isEqualByComparingTo("18");
        jdbc.update("UPDATE \"" + schema + "\".stores SET country = 'TD'"); // outside Bref's table
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoicing/settings", null), 200).path("vatRate").decimalValue())
                .isEqualByComparingTo("19.25");
    }
}

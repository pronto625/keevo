package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.invoicing.invoice.adapter.in.scheduling.InvoiceOverdueJob;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story 20.4 — invoice collections end to end in the real application context (security, module gate, use cases,
 * JDBC adapters on the V10 tenant schema, sync push/pull, overdue job, owner notification, audit): every row of the
 * spec's I/O matrix over REST and sync.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Invoice collections — deposit, instalments, overdue, settlement (Story 20.4)")
class InvoicePaymentsFullContextTest extends FullContextIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

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
    void setUp() throws Exception {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        // a real owner account: the overdue notification is stored in the owners' inbox
        String phone = "+2376" + String.format("%08d",
                java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'OWNER')", ownerId,
                phone);
        ownerToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema,
                "OWNER");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("legalName", "Boutique Awa SARL");
        settings.put("address", "Rue Joss, Akwa, Douala");
        settings.put("vatRegistered", false);
        settings.put("vatRate", 19.25);
        settings.put("pricesIncludeVat", true);
        data(call(HttpMethod.PUT, "/api/v1/invoicing/settings", settings), 200);
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

    private static Map<String, Object> line(String description, long unitPrice) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", description);
        line.put("quantity", 1);
        line.put("unitPrice", unitPrice);
        line.put("vatTreatment", "STANDARD");
        return line;
    }

    /** A draft of 100 000 FCFA (80 000 + 20 000). */
    private String draft() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", "Société Ndolo"));
        body.put("lines", List.of(line("Prestation", 80_000), line("Déplacement", 20_000)));
        return data(call(HttpMethod.POST, "/api/v1/invoices", body), 201).path("id").asText();
    }

    private String issued() throws Exception {
        String id = draft();
        data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        return id;
    }

    private static Map<String, Object> received(String paymentId, long amount, LocalDate day) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (paymentId != null) {
            body.put("paymentId", paymentId);
        }
        body.put("amount", amount);
        body.put("paidAt", day.toString());
        body.put("method", "Mobile Money");
        return body;
    }

    private static Map<String, Object> instalment(String paymentId, long amount, LocalDate dueDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", paymentId);
        body.put("amount", amount);
        body.put("dueDate", dueDate.toString());
        return body;
    }

    private MvcResult pay(String invoiceId, Object body) throws Exception {
        return call(HttpMethod.POST, "/api/v1/invoices/" + invoiceId + "/payments", body);
    }

    private JsonNode get(String invoiceId) throws Exception {
        return data(call(HttpMethod.GET, "/api/v1/invoices/" + invoiceId, null), 200);
    }

    private int rows(String invoiceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoice_payments WHERE invoice_id = "
                + "?::uuid", Integer.class, invoiceId);
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

    @SafeVarargs
    private Map<String, JsonNode> push(Map<String, Object>... ops) throws Exception {
        JsonNode data = data(call(HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", deviceId, "operations", List.of(ops))), 200);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode result : data.path("results")) {
            byId.put(result.path("operationId").asText(), result);
        }
        return byId;
    }

    private static JsonNode result(Map<String, JsonNode> results, Map<String, Object> op) {
        return results.get((String) op.get("operationId"));
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

    // ── matrix ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deposit 30 000 → PARTIAL; balance → PAID with status PAID, paidAt / paymentMethod of the last payment; "
            + "audit « Paiement reçu »")
    void depositThenBalance() throws Exception {
        String id = issued();
        JsonNode partial = data(pay(id, received(null, 30_000, today)), 201);
        assertThat(partial.path("status").asText()).isEqualTo("ISSUED");
        assertThat(partial.path("paymentState").asText()).isEqualTo("PARTIAL");
        assertThat(partial.path("paymentStateLabel").asText()).isEqualTo("Partiellement payée");
        assertThat(partial.path("amountPaid").asLong()).isEqualTo(30_000);
        assertThat(partial.path("amountDue").asLong()).isEqualTo(100_000);
        assertThat(partial.path("payments")).singleElement().satisfies(p -> {
            assertThat(p.path("amount").asLong()).isEqualTo(30_000);
            assertThat(p.path("legacy").asBoolean()).isFalse();
            assertThat(p.path("method").asText()).isEqualTo("Mobile Money");
        });
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices?paymentState=PARTIAL", null), 200))
                .extracting(n -> n.path("id").asText()).containsExactly(id);

        Map<String, Object> balance = received(null, 70_000, today);
        balance.put("method", "Espèces");
        JsonNode paid = data(pay(id, balance), 201);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        assertThat(paid.path("paymentState").asText()).isEqualTo("PAID");
        assertThat(paid.path("paymentMethod").asText()).isEqualTo("Espèces");
        assertThat(paid.path("paidAt").isNull()).isFalse();
        assertThat(paid.path("amountPaid").asLong()).isEqualTo(100_000);

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT action FROM \"" + schema
                + "\".audit_log WHERE entity_type = 'Invoice' AND action IN ('INVOICE_PAYMENT_RECEIVED', 'INVOICE_PAID')",
                String.class)).containsExactlyInAnyOrder("INVOICE_PAYMENT_RECEIVED", "INVOICE_PAYMENT_RECEIVED",
                "INVOICE_PAID"));
        assertThat(jdbc.queryForList("SELECT metadata::text FROM \"" + schema + "\".audit_log WHERE action = "
                + "'INVOICE_PAYMENT_RECEIVED'", String.class)).anySatisfy(m -> assertThat(m).contains("30\u202F000 FCFA reçus le"));
    }

    @Test
    @DisplayName("deposit = total → PAID at once; overpayment / schedule exceeding the due → 409 OVERPAYMENT French, "
            + "nothing written")
    void depositEqualsTotalAndOverpayment() throws Exception {
        String full = issued();
        assertThat(data(pay(full, received(null, 100_000, today)), 201).path("status").asText()).isEqualTo("PAID");

        String id = issued();
        data(pay(id, received(null, 80_000, today)), 201);
        JsonNode err = error(pay(id, received(null, 30_000, today)), 409, "OVERPAYMENT");
        assertThat(err.path("error").asText())
                .isEqualTo("Le montant dépasse ce qui reste dû sur la facture (reste dû : 20\u202F000 FCFA)");
        error(pay(id, instalment(UUID.randomUUID().toString(), 30_000, today.plusDays(10))), 409, "OVERPAYMENT");
        assertThat(rows(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("schedule on a draft: rows stored, no state; after issue UNPAID; payment date in the future / before "
            + "issue → 400; amount 0 → 422; quote → 409")
    void scheduleAndValidation() throws Exception {
        String id = draft();
        JsonNode d = data(pay(id, instalment(UUID.randomUUID().toString(), 50_000, today.plusDays(30))), 201);
        assertThat(d.path("paymentState").isNull()).isTrue();
        data(pay(id, instalment(UUID.randomUUID().toString(), 50_000, today.plusDays(60))), 201);
        error(pay(id, received(null, 10_000, today)), 409, "INVOICE_INVALID_TRANSITION");
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT entity_label || ' | ' || "
                + "metadata::text FROM \"" + schema + "\".audit_log WHERE action = 'INVOICE_INSTALMENT_SCHEDULED'",
                String.class)).hasSize(2).allSatisfy(m -> assertThat(m).startsWith("brouillon de Société Ndolo | ")
                .contains("50\u202F000 FCFA à régler le")));
        data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        JsonNode issued = get(id);
        assertThat(issued.path("paymentState").asText()).isEqualTo("UNPAID");
        assertThat(issued.path("payments")).hasSize(2);

        String other = issued();
        error(pay(other, received(null, 1_000, today.plusDays(1))), 400, "INVOICE_PAYMENT_DATE_INVALID");
        error(pay(other, received(null, 1_000, today.minusDays(3))), 400, "INVOICE_PAYMENT_DATE_INVALID");
        error(pay(other, received(null, 0, today)), 422, "VALIDATION_ERROR");
        Map<String, Object> both = received(null, 1_000, today);
        both.put("dueDate", today.plusDays(3).toString());
        error(pay(other, both), 422, "VALIDATION_ERROR");
        assertThat(call(HttpMethod.GET, "/api/v1/invoices?paymentState=EN_RETARD", null).getResponse().getStatus())
                .isEqualTo(400);

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("kind", "QUOTE");
        quote.put("client", Map.of("name", "Devis client"));
        quote.put("lines", List.of(line("Riz", 5_000)));
        String quoteId = data(call(HttpMethod.POST, "/api/v1/invoices", quote), 201).path("id").asText();
        JsonNode q = error(pay(quoteId, instalment(UUID.randomUUID().toString(), 1_000, today)), 409,
                "INVOICE_INVALID_TRANSITION");
        assertThat(q.path("error").asText()).isEqualTo("Un devis doit être converti en facture");
    }

    @Test
    @DisplayName("overdue: instalment due yesterday → job marks OVERDUE once, one owner notification; settle it → "
            + "PARTIAL, settle the rest → PAID")
    void overdueJobAndSettle() throws Exception {
        String id = issued();
        String late = UUID.randomUUID().toString();
        String next = UUID.randomUUID().toString();
        data(pay(id, received(null, 20_000, today)), 201);
        // never scheduled in the past (422); one due tomorrow, then a day passes (its due date moved to yesterday)
        error(pay(id, instalment(UUID.randomUUID().toString(), 40_000, today.minusDays(1))), 422, "VALIDATION_ERROR");
        data(pay(id, instalment(late, 40_000, today.plusDays(1))), 201);
        data(pay(id, instalment(next, 40_000, today.plusDays(20))), 201);
        jdbc.update("UPDATE \"" + schema + "\".invoice_payments SET due_date = ?::date WHERE id = ?::uuid",
                today.minusDays(1).toString(), late);
        JsonNode before = get(id);
        String number = before.path("number").asText();
        // becoming overdue is the job's transition (with its notification), never a payment's
        assertThat(before.path("paymentState").asText()).isEqualTo("PARTIAL");

        overdueJob.run();
        overdueJob.run(); // a later run finds nothing new

        JsonNode overdue = get(id);
        assertThat(overdue.path("paymentState").asText()).isEqualTo("OVERDUE");
        assertThat(overdue.path("paymentStateLabel").asText()).isEqualTo("En retard");
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices?paymentState=OVERDUE", null), 200))
                .extracting(n -> n.path("id").asText()).containsExactly(id);
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT body FROM \"" + schema
                + "\".notifications WHERE type = 'INVOICE_OVERDUE'", String.class))
                .containsExactly("1 facture(s) en retard de paiement : " + number));

        JsonNode settled = data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/payments/" + late + "/settle",
                Map.of("method", "Espèces")), 200);
        assertThat(settled.path("paymentState").asText()).isEqualTo("PARTIAL");
        // settled again → no-op
        assertThat(data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/payments/" + late + "/settle", null), 200)
                .path("amountPaid").asLong()).isEqualTo(60_000);
        error(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/payments/" + UUID.randomUUID() + "/settle", null),
                404, "INVOICE_NOT_FOUND");
        JsonNode paid = data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/payments/" + next + "/settle", null),
                200);
        assertThat(paid.path("paymentState").asText()).isEqualTo("PAID");
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT action FROM \"" + schema
                + "\".audit_log WHERE action LIKE 'INVOICE_INSTALMENT_%'", String.class))
                .contains("INVOICE_INSTALMENT_SCHEDULED", "INVOICE_INSTALMENT_SETTLED"));
        assertThat(jdbc.queryForList("SELECT metadata::text FROM \"" + schema + "\".audit_log WHERE action = "
                + "'INVOICE_INSTALMENT_SETTLED'", String.class))
                .anySatisfy(m -> assertThat(m).contains("40\u202F000 FCFA réglés le"));
    }

    @Test
    @DisplayName("credit note reduces the due: 100 000 credited 20 000, 80 000 received → PAID")
    void creditNote() throws Exception {
        String id = issued();
        data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/credit-notes", Map.of("reason", "Déplacement annulé",
                "lines", List.of(Map.of("position", 2, "quantity", 1)))), 201);
        assertThat(get(id).path("amountDue").asLong()).isEqualTo(80_000);
        error(pay(id, received(null, 90_000, today)), 409, "OVERPAYMENT");
        JsonNode paid = data(pay(id, received(null, 80_000, today)), 201);
        assertThat(paid.path("paymentState").asText()).isEqualTo("PAID");
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("credit note after a partial payment: fully covered → PAID (status unchanged), listed under "
            + "?paymentState=PAID; exceeding what was received → 409 French; instalments trimmed latest first")
    void creditNoteAfterPayments() throws Exception {
        String covered = issued();
        data(pay(covered, received(null, 80_000, today)), 201);
        data(call(HttpMethod.POST, "/api/v1/invoices/" + covered + "/credit-notes", Map.of("reason", "Remise",
                "lines", List.of(Map.of("position", 2, "quantity", 1)))), 201);
        JsonNode after = get(covered);
        assertThat(after.path("paymentState").asText()).isEqualTo("PAID");
        assertThat(after.path("status").asText()).isEqualTo("ISSUED");
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices?paymentState=PAID", null), 200))
                .extracting(n -> n.path("id").asText()).contains(covered);

        String refused = issued();
        data(pay(refused, received(null, 90_000, today)), 201);
        JsonNode err = error(call(HttpMethod.POST, "/api/v1/invoices/" + refused + "/credit-notes",
                Map.of("reason", "Remise", "lines", List.of(Map.of("position", 2, "quantity", 1)))), 409,
                "INVOICE_INVALID_TRANSITION");
        assertThat(err.path("error").asText()).isEqualTo("Le montant déjà encaissé dépasse le reste dû après cet avoir.");
        assertThat(get(refused).path("creditNotes")).isEmpty();

        String trimmed = issued();
        String early = UUID.randomUUID().toString();
        String late = UUID.randomUUID().toString();
        data(pay(trimmed, received(null, 20_000, today)), 201);
        data(pay(trimmed, instalment(early, 40_000, today.plusDays(10))), 201);
        data(pay(trimmed, instalment(late, 40_000, today.plusDays(40))), 201);
        data(call(HttpMethod.POST, "/api/v1/invoices/" + trimmed + "/credit-notes", Map.of("reason", "Remise",
                "lines", List.of(Map.of("position", 2, "quantity", 1)))), 201); // due 100 000 → 80 000
        JsonNode t = get(trimmed);
        assertThat(t.path("amountDue").asLong()).isEqualTo(80_000);
        assertThat(t.path("payments")).extracting(p -> p.path("amount").asLong())
                .containsExactly(20_000L, 40_000L, 20_000L);
    }

    @Test
    @DisplayName("fully credited invoice with a past-due instalment: instalment removed, PAID, never marked by the job")
    void fullyCreditedNotMarked() throws Exception {
        String id = issued();
        String instalmentId = UUID.randomUUID().toString();
        data(pay(id, instalment(instalmentId, 100_000, today.plusDays(1))), 201);
        jdbc.update("UPDATE \"" + schema + "\".invoice_payments SET due_date = ?::date WHERE id = ?::uuid",
                today.minusDays(2).toString(), instalmentId);
        data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/credit-notes", Map.of("reason", "Annulation")), 201);

        overdueJob.run();

        JsonNode after = get(id);
        assertThat(after.path("amountDue").asLong()).isZero();
        assertThat(after.path("paymentState").asText()).isEqualTo("PAID");
        assertThat(after.path("payments")).isEmpty();
        assertThat(jdbc.queryForList("SELECT body FROM \"" + schema + "\".notifications WHERE type = "
                + "'INVOICE_OVERDUE'", String.class)).isEmpty();
    }

    @Test
    @DisplayName("legacy: a PAID invoice without row reads as one legacy payment; an ISSUED one as UNPAID; filters")
    void legacy() throws Exception {
        String q = "\"" + schema + "\".";
        UUID paid = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "invoices (id, number, status, issue_date, client_name, seller_legal_name, "
                + "vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, total_ttc, issued_at, paid_at, "
                + "payment_method) VALUES (?, 'FAC-2020-000001', 'PAID', DATE '2020-01-02', 'Client V1', 'Boutique', "
                + "false, 19.25, true, 5000, 0, 5000, now(), now(), 'Espèces')", paid);
        UUID open = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "invoices (id, number, status, issue_date, client_name, seller_legal_name, "
                + "vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, total_ttc, issued_at) VALUES "
                + "(?, 'FAC-2020-000002', 'ISSUED', DATE '2020-01-02', 'Client V1', 'Boutique', false, 19.25, true, "
                + "5000, 0, 5000, now())", open);

        JsonNode p = get(paid.toString());
        assertThat(p.path("paymentState").asText()).isEqualTo("PAID");
        assertThat(p.path("payments")).singleElement().satisfies(row -> {
            assertThat(row.path("legacy").asBoolean()).isTrue();
            assertThat(row.path("id").isNull()).isTrue();
            assertThat(row.path("amount").asLong()).isEqualTo(5_000);
            assertThat(row.path("method").asText()).isEqualTo("Espèces");
        });
        assertThat(get(open.toString()).path("paymentState").asText()).isEqualTo("UNPAID");
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices?paymentState=PAID", null), 200))
                .extracting(n -> n.path("id").asText()).contains(paid.toString()).doesNotContain(open.toString());
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices?paymentState=UNPAID", null), 200))
                .extracting(n -> n.path("id").asText()).contains(open.toString()).doesNotContain(paid.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + q + "invoice_payments", Integer.class)).isZero();
    }

    @Test
    @DisplayName("V1 pay: ISSUED with 30 000 received + an instalment → instalment settled, balance added, PAID with "
            + "paidAt; cancel with a payment → 409 French; cancel with instalments only → allowed")
    void v1PayAndCancel() throws Exception {
        String id = issued();
        data(pay(id, received(null, 30_000, today)), 201);
        data(pay(id, instalment(UUID.randomUUID().toString(), 40_000, today.plusDays(10))), 201);
        JsonNode err = error(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/cancel", Map.of("reason", "Erreur")),
                409, "INVOICE_INVALID_TRANSITION");
        assertThat(err.path("error").asText())
                .isEqualTo("Des paiements sont enregistrés sur cette facture : établissez un avoir");

        JsonNode paid = data(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/pay",
                Map.of("paymentMethod", "Espèces")), 200);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        assertThat(paid.path("paidAt").isNull()).isFalse();
        assertThat(paid.path("paymentMethod").asText()).isEqualTo("Espèces");
        assertThat(paid.path("paymentState").asText()).isEqualTo("PAID");
        assertThat(paid.path("amountPaid").asLong()).isEqualTo(100_000);
        assertThat(paid.path("payments")).hasSize(3).allSatisfy(p -> assertThat(p.path("paidAt").isNull()).isFalse());

        String scheduledOnly = issued();
        data(pay(scheduledOnly, instalment(UUID.randomUUID().toString(), 40_000, today.plusDays(10))), 201);
        JsonNode cancelled = data(call(HttpMethod.POST, "/api/v1/invoices/" + scheduledOnly + "/cancel",
                Map.of("reason", "Erreur de client")), 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("paymentState").isNull()).isTrue();
    }

    @Test
    @DisplayName("replay REST: same paymentId + content → 200, one row; another content → 422")
    void restReplay() throws Exception {
        String id = issued();
        String paymentId = UUID.randomUUID().toString();
        data(pay(id, received(paymentId, 30_000, today)), 201);
        assertThat(data(pay(id, received(paymentId, 30_000, today)), 200).path("amountPaid").asLong())
                .isEqualTo(30_000);
        error(pay(id, received(paymentId, 31_000, today)), 422, "VALIDATION_ERROR");
        assertThat(rows(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("sync: ADD_INVOICE_PAYMENT replayed under a new operation → APPLIED, one row; other content → "
            + "REJECTED VALIDATION_ERROR; overpayment → REJECTED OVERPAYMENT; SETTLE applied; the pull carries the state")
    void sync() throws Exception {
        String id = issued();
        String deposit = UUID.randomUUID().toString();
        String instalmentId = UUID.randomUUID().toString();
        Map<String, Object> add = new LinkedHashMap<>(received(deposit, 30_000, today));
        add.put("invoiceId", id);
        Map<String, Object> schedule = new LinkedHashMap<>(instalment(instalmentId, 50_000, today.plusDays(15)));
        schedule.put("invoiceId", id);
        var addOp = op("ADD_INVOICE_PAYMENT", id, add);
        var scheduleOp = op("ADD_INVOICE_PAYMENT", id, schedule);
        Map<String, JsonNode> first = push(addOp, scheduleOp);
        assertThat(result(first, addOp).path("status").asText()).as(first.toString()).isEqualTo("APPLIED");
        assertThat(result(first, addOp).path("serverEntityId").asText()).isEqualTo(id);
        assertThat(result(first, scheduleOp).path("status").asText()).isEqualTo("APPLIED");

        var replay = op("ADD_INVOICE_PAYMENT", id, add);
        Map<String, Object> changed = new LinkedHashMap<>(add);
        changed.put("amount", 31_000);
        var conflicting = op("ADD_INVOICE_PAYMENT", id, changed);
        Map<String, Object> tooMuch = new LinkedHashMap<>(received(UUID.randomUUID().toString(), 30_000, today));
        tooMuch.put("invoiceId", id);
        var over = op("ADD_INVOICE_PAYMENT", id, tooMuch);
        Map<String, JsonNode> second = push(replay, conflicting, over);
        assertThat(result(second, replay).path("status").asText()).isEqualTo("APPLIED");
        assertThat(result(second, conflicting).path("status").asText()).isEqualTo("REJECTED");
        assertThat(result(second, conflicting).path("reason").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(result(second, over).path("reason").asText()).isEqualTo("OVERPAYMENT");
        assertThat(rows(id)).isEqualTo(2);

        Map<String, Object> settle = new LinkedHashMap<>();
        settle.put("invoiceId", id);
        settle.put("paymentId", instalmentId);
        settle.put("method", "Espèces");
        var settleOp = op("SETTLE_INVOICE_PAYMENT", id, settle);
        assertThat(result(push(settleOp), settleOp).path("status").asText()).isEqualTo("APPLIED");

        JsonNode pulled = pullInvoices().stream().filter(n -> n.path("id").asText().equals(id)).findFirst()
                .orElseThrow();
        assertThat(pulled.path("paymentState").asText()).isEqualTo("PARTIAL");
        assertThat(pulled.path("amountPaid").asLong()).isEqualTo(80_000);
        assertThat(pulled.path("payments")).hasSize(2);
    }

    @Test
    @DisplayName("concurrent payments on one invoice never exceed the due (row lock)")
    void concurrentPayments() throws Exception {
        String id = issued();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return pay(id, received(null, 60_000, today));
                }));
            }
            start.countDown();
            for (Future<MvcResult> call : calls) {
                statuses.add(call.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM \"" + schema + "\".invoice_payments WHERE invoice_id "
                + "= ?::uuid", Long.class, id)).isEqualTo(60_000);
    }
}

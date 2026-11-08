package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.application.port.PlanCatalogStore;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
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
import java.time.YearMonth;
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
 * Story 20.3 — quotes end to end in the real application context (security, module gate, use cases, JDBC adapter on
 * the V8 tenant schema, quota, sync push/pull, PDF, audit): every row of the spec's I/O matrix over REST and sync.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Quotes — create, edit with check, cancel, convert (Story 20.3)")
class QuoteFullContextTest extends FullContextIntegrationTest {

    private static final String PLAN = "TEST_QUOTE_FC";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanModuleRowSync rowSync;
    @Autowired PlanCatalogStore catalogStore;
    @Autowired ModuleRegistry registry;

    private final UUID ownerId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private final String month = YearMonth.now(ZoneId.of("Africa/Douala")).toString();
    private final int year = LocalDate.now(ZoneId.of("Africa/Douala")).getYear();
    private Instant clientClock = Instant.now().minusSeconds(600);
    private final String deviceId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
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
        jdbc.update("DELETE FROM public.usage_counters WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM public.plan_quotas WHERE plan_type = ?", PLAN);
        jdbc.update("DELETE FROM public.profile_modules WHERE plan_type = ?", PLAN);
        jdbc.update("DELETE FROM public.plan_profiles WHERE plan_type = ?", PLAN);
        jdbc.update("DELETE FROM public.plans WHERE code = ?", PLAN);
        catalogStore.invalidate();
        registry.evict(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A paid test plan whose documents quota is {@code documentsPerMonth}. */
    private void testPlan(int documentsPerMonth) {
        jdbc.update("INSERT INTO public.plans (code, label, sort_order, visible, kind) VALUES (?, 'Offre test', 98, "
                + "false, 'PAID') ON CONFLICT (code) DO NOTHING", PLAN);
        jdbc.update("INSERT INTO public.plan_profiles (plan_type) VALUES (?) ON CONFLICT (plan_type) DO NOTHING",
                PLAN);
        jdbc.update("INSERT INTO public.plan_quotas (plan_type, metric, period, quota_limit) VALUES (?, "
                + "'DOCUMENTS', 'MONTH', ?)", PLAN, documentsPerMonth);
        catalogStore.invalidate();
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = ?, status = 'ACTIVE', "
                + "expires_at = now() + interval '30 days'", PLAN);
        jdbc.update("UPDATE public.tenants SET plan_type = ? WHERE id = ?", PLAN, tenantId);
        rowSync.sync(tenantId, schema);
        registry.evict(schema);
    }

    private long documents() {
        List<Long> rows = jdbc.queryForList("SELECT used FROM public.usage_counters WHERE tenant_id = ? "
                + "AND metric = 'DOCUMENTS' AND period_key = ?", Long.class, tenantId, month);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private long counter() {
        List<Long> rows = jdbc.queryForList("SELECT last_number FROM \"" + schema + "\".invoice_counters "
                + "WHERE year = ?", Long.class, year);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

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

    private static Map<String, Object> content(String clientName, long unitPrice) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 1);
        line.put("unitPrice", unitPrice);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", clientName));
        body.put("lines", List.of(line));
        return body;
    }

    private JsonNode createQuote() throws Exception {
        Map<String, Object> body = content("Société Ndolo", 11_925);
        body.put("kind", "QUOTE");
        return data(call(HttpMethod.POST, "/api/v1/invoices", body), 201);
    }

    private String createInvoice() throws Exception {
        return data(call(HttpMethod.POST, "/api/v1/invoices", content("Awa Traoré", 5_000)), 201).path("id").asText();
    }

    private MvcResult convert(String quoteId, Object body) throws Exception {
        return call(HttpMethod.POST, "/api/v1/invoices/" + quoteId + "/convert", body);
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT kind, status, number, issue_date, converted_invoice_id, source_quote_id "
                + "FROM \"" + schema + "\".invoices WHERE id = ?::uuid", id);
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
        List<JsonNode> rows = new ArrayList<>();
        String cursor = null;
        do {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull")
                    .header("Authorization", "Bearer " + ownerToken);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode d = data(mockMvc.perform(request).andReturn(), 200);
            d.path("entities").path("invoices").forEach(rows::add);
            cursor = d.path("hasMore").asBoolean() ? d.path("nextCursor").asText() : null;
        } while (cursor != null);
        return rows;
    }

    private static List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path("id").asText()));
        return out;
    }

    // ── matrix ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create quote: 201 DRAFT QUOTE, no number, DV- reference; counter and quota untouched; V1 create = "
            + "invoice; lists default / QUOTE / ALL / bad kind")
    void createAndLists() throws Exception {
        JsonNode quote = createQuote();
        String quoteId = quote.path("id").asText();
        assertThat(quote.path("kind").asText()).isEqualTo("QUOTE");
        assertThat(quote.path("status").asText()).isEqualTo("DRAFT");
        assertThat(quote.path("number").isNull()).isTrue();
        assertThat(quote.path("issueDate").isNull()).isTrue();
        assertThat(quote.path("quoteReference").asText())
                .isEqualTo("DV-" + quoteId.substring(0, 8).toUpperCase());
        assertThat(quote.path("convertedInvoiceId").isNull()).isTrue();
        assertThat(documents()).isZero();
        assertThat(counter()).isZero();

        String invoiceId = createInvoice();
        JsonNode invoice = data(call(HttpMethod.GET, "/api/v1/invoices/" + invoiceId, null), 200);
        assertThat(invoice.path("kind").asText()).isEqualTo("INVOICE");
        assertThat(invoice.path("quoteReference").isNull()).isTrue();

        assertThat(ids(data(call(HttpMethod.GET, "/api/v1/invoices", null), 200))).containsExactly(invoiceId);
        assertThat(ids(data(call(HttpMethod.GET, "/api/v1/invoices?kind=QUOTE", null), 200)))
                .containsExactly(quoteId);
        assertThat(ids(data(call(HttpMethod.GET, "/api/v1/invoices?kind=all", null), 200)))
                .containsExactlyInAnyOrder(quoteId, invoiceId);
        assertThat(call(HttpMethod.GET, "/api/v1/invoices?kind=BON", null).getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> bad = content("X", 1);
        bad.put("kind", "AVOIR");
        // a body constraint: 422 VALIDATION_ERROR, as every invoice body field
        error(call(HttpMethod.POST, "/api/v1/invoices", bad), 422, "VALIDATION_ERROR");

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT entity_label FROM \"" + schema
                + "\".audit_log WHERE action = 'QUOTE_CREATED' AND entity_type = 'Quote'", String.class))
                .containsExactly(quote.path("quoteReference").asText()));
    }

    @Test
    @DisplayName("edit: stale expectedUpdatedAt → 409 OPTIMISTIC_LOCK, nothing written; matching → applied; none → "
            + "applied; an issued invoice stays non-editable")
    void optimisticEdit() throws Exception {
        JsonNode quote = createQuote();
        String id = quote.path("id").asText();
        Instant seen = Instant.parse(quote.path("updatedAt").asText());

        Map<String, Object> stale = content("Client modifié", 20_000);
        stale.put("expectedUpdatedAt", seen.minusSeconds(5).toString());
        JsonNode err = error(call(HttpMethod.PUT, "/api/v1/invoices/" + id, stale), 409, "OPTIMISTIC_LOCK");
        assertThat(err.path("error").asText())
                .isEqualTo("Ce document a été modifié entre-temps : rechargez-le avant de l'enregistrer.");
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200).path("client").path("name")
                .asText()).isEqualTo("Société Ndolo");

        Map<String, Object> fresh = content("Client modifié", 20_000);
        fresh.put("expectedUpdatedAt", seen.toString());
        JsonNode edited = data(call(HttpMethod.PUT, "/api/v1/invoices/" + id, fresh), 200);
        assertThat(edited.path("client").path("name").asText()).isEqualTo("Client modifié");
        assertThat(edited.path("kind").asText()).isEqualTo("QUOTE");
        // the updatedAt returned is the stored one: usable as the next check
        Map<String, Object> next = content("Client encore modifié", 20_000);
        next.put("expectedUpdatedAt", edited.path("updatedAt").asText());
        data(call(HttpMethod.PUT, "/api/v1/invoices/" + id, next), 200);
        data(call(HttpMethod.PUT, "/api/v1/invoices/" + id, content("Sans contrôle", 1_000)), 200);

        String invoiceId = createInvoice();
        data(call(HttpMethod.POST, "/api/v1/invoices/" + invoiceId + "/issue", null), 200);
        error(call(HttpMethod.PUT, "/api/v1/invoices/" + invoiceId, content("X", 1)), 409, "INVOICE_NOT_EDITABLE");
    }

    @Test
    @DisplayName("issue / pay a quote → 409 INVOICE_INVALID_TRANSITION « Un devis doit être converti en "
            + "facture »; credit note refused too; Story 20.7: send goes through (no provider here → 503)")
    void invoiceActionsRefused() throws Exception {
        String id = createQuote().path("id").asText();
        error(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/send", Map.of("phone", "+237690000001")), 503,
                "WHATSAPP_UNAVAILABLE");
        for (String action : List.of("issue", "pay")) {
            JsonNode err = error(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/" + action, null), 409,
                    "INVOICE_INVALID_TRANSITION");
            assertThat(err.path("error").asText()).as(action).isEqualTo("Un devis doit être converti en facture");
        }
        assertThat(call(HttpMethod.POST, "/api/v1/invoices/" + id + "/credit-notes",
                Map.of("reason", "erreur")).getResponse().getStatus()).isEqualTo(409);
        assertThat(row(id).get("status")).isEqualTo("DRAFT");
        assertThat(counter()).isZero();
    }

    @Test
    @DisplayName("convert: 201 ISSUED invoice with the next gapless number, today's date, quote CONVERTED + links, "
            + "quota +1; replay → 200 same invoice, nothing counted; then an ordinary issue takes the next number")
    void convertAndReplay() throws Exception {
        String first = createInvoice();
        data(call(HttpMethod.POST, "/api/v1/invoices/" + first + "/issue", null), 200);
        assertThat(documents()).isEqualTo(1);
        JsonNode quote = createQuote();
        String quoteId = quote.path("id").asText();
        String chosenId = UUID.randomUUID().toString();

        JsonNode invoice = data(convert(quoteId, Map.of("invoiceId", chosenId)), 201);
        assertThat(invoice.path("id").asText()).isEqualTo(chosenId);
        assertThat(invoice.path("kind").asText()).isEqualTo("INVOICE");
        assertThat(invoice.path("status").asText()).isEqualTo("ISSUED");
        assertThat(invoice.path("number").asText()).isEqualTo("FAC-%d-000002".formatted(year));
        assertThat(invoice.path("issueDate").asText()).isEqualTo(LocalDate.now(ZoneId.of("Africa/Douala")).toString());
        assertThat(invoice.path("sourceQuoteId").asText()).isEqualTo(quoteId);
        assertThat(invoice.path("totalTtc").asLong()).isEqualTo(11_925);
        assertThat(documents()).isEqualTo(2);
        Map<String, Object> q = row(quoteId);
        assertThat(q.get("status")).isEqualTo("CONVERTED");
        assertThat(q.get("number")).isNull();
        assertThat(q.get("converted_invoice_id")).hasToString(chosenId);
        JsonNode quoteAfter = data(call(HttpMethod.GET, "/api/v1/invoices/" + quoteId, null), 200);
        assertThat(quoteAfter.path("statusLabel").asText()).isEqualTo("Converti");
        assertThat(quoteAfter.path("convertedInvoiceId").asText()).isEqualTo(chosenId);
        assertThat(quoteAfter.path("convertedAt").isNull()).isFalse();

        JsonNode replay = data(convert(quoteId, null), 200);
        assertThat(replay.path("id").asText()).isEqualTo(chosenId);
        assertThat(replay.path("number").asText()).isEqualTo("FAC-%d-000002".formatted(year));
        assertThat(documents()).isEqualTo(2);
        assertThat(counter()).isEqualTo(2);

        String third = createInvoice();
        assertThat(data(call(HttpMethod.POST, "/api/v1/invoices/" + third + "/issue", null), 200).path("number")
                .asText()).isEqualTo("FAC-%d-000003".formatted(year));

        // a converted quote is neither editable nor cancellable
        error(call(HttpMethod.PUT, "/api/v1/invoices/" + quoteId, content("X", 1)), 409, "INVOICE_NOT_EDITABLE");
        error(call(HttpMethod.POST, "/api/v1/invoices/" + quoteId + "/cancel", Map.of("reason", "trop tard")), 409,
                "INVOICE_INVALID_TRANSITION");
        // an invoice is never converted
        error(convert(third, null), 409, "INVOICE_INVALID_TRANSITION");

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT metadata::text FROM \"" + schema
                + "\".audit_log WHERE action = 'QUOTE_CONVERTED'", String.class)).singleElement().asString()
                .contains("Devis converti en facture FAC-%d-000002".formatted(year)));
    }

    @Test
    @DisplayName("convert over quota → 403 QUOTA_EXCEEDED; the quote stays a DRAFT, no number taken, no invoice row")
    void convertOverQuota() throws Exception {
        testPlan(0);
        String quoteId = createQuote().path("id").asText();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class);

        error(convert(quoteId, null), 403, "QUOTA_EXCEEDED");

        assertThat(row(quoteId).get("status")).isEqualTo("DRAFT");
        assertThat(counter()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("cancel quote: reason required (422 like an invoice); CANCELLED without number; converting it → 409")
    void cancelQuote() throws Exception {
        String quoteId = createQuote().path("id").asText();
        // same rule as an invoice's cancellation: a missing reason is a body constraint → 422 VALIDATION_ERROR
        error(call(HttpMethod.POST, "/api/v1/invoices/" + quoteId + "/cancel", Map.of()), 422, "VALIDATION_ERROR");
        JsonNode cancelled = data(call(HttpMethod.POST, "/api/v1/invoices/" + quoteId + "/cancel",
                Map.of("reason", "Le client a renoncé")), 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("statusLabel").asText()).isEqualTo("Annulé");
        assertThat(cancelled.path("number").isNull()).isTrue();
        assertThat(cancelled.path("cancellationReason").asText()).isEqualTo("Le client a renoncé");
        error(convert(quoteId, null), 409, "INVOICE_INVALID_TRANSITION");
        assertThat(counter()).isZero();
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT action FROM \"" + schema
                + "\".audit_log WHERE entity_type = 'Quote'", String.class)).contains("QUOTE_CANCELLED"));
    }

    @Test
    @DisplayName("PDF of a quote: application/pdf, file devis-DV-XXXXXXXX.pdf")
    void pdf() throws Exception {
        JsonNode quote = createQuote();
        MvcResult r = call(HttpMethod.GET, "/api/v1/invoices/" + quote.path("id").asText() + "/pdf", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(r.getResponse().getHeader("Content-Disposition"))
                .contains("devis-" + quote.path("quoteReference").asText() + ".pdf");
    }

    @Test
    @DisplayName("concurrent double conversion: one invoice, one number, both callers get it")
    void concurrentConversion() throws Exception {
        String quoteId = createQuote().path("id").asText();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return convert(quoteId, null);
                }));
            }
            start.countDown();
            List<String> invoiceIds = new ArrayList<>();
            for (Future<MvcResult> call : calls) {
                MvcResult r = call.get(30, TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus()).as(body(r)).isIn(200, 201);
                invoiceIds.add(objectMapper.readTree(body(r)).path("data").path("id").asText());
            }
            assertThat(invoiceIds.get(0)).isEqualTo(invoiceIds.get(1));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices WHERE source_quote_id = "
                + "?::uuid", Integer.class, quoteId)).isEqualTo(1);
        assertThat(counter()).isEqualTo(1);
        assertThat(documents()).isEqualTo(1);
    }

    @Test
    @DisplayName("sync: CREATE_INVOICE kind QUOTE, stale UPDATE_INVOICE rejected, CONVERT_QUOTE applied; replay with a "
            + "new operation → same invoice, same op → DUPLICATE; the pull carries kind and links")
    void sync() throws Exception {
        String quoteId = UUID.randomUUID().toString();
        String invoiceId = UUID.randomUUID().toString();
        Map<String, Object> createPayload = content("Société Ndolo", 11_925);
        createPayload.put("id", quoteId);
        createPayload.put("kind", "QUOTE");
        var create = op("CREATE_INVOICE", quoteId, createPayload);
        Map<String, Object> stalePayload = content("Client modifié", 1_000);
        stalePayload.put("invoiceId", quoteId);
        stalePayload.put("expectedUpdatedAt", "2020-01-01T00:00:00Z");
        var stale = op("UPDATE_INVOICE", quoteId, stalePayload);
        var conv = op("CONVERT_QUOTE", quoteId, Map.of("quoteId", quoteId, "invoiceId", invoiceId));

        Map<String, JsonNode> results = push(create, stale, conv);
        assertThat(result(results, create).path("status").asText()).as(results.toString()).isEqualTo("APPLIED");
        assertThat(result(results, stale).path("status").asText()).isEqualTo("REJECTED");
        assertThat(result(results, stale).path("reason").asText()).isEqualTo("OPTIMISTIC_LOCK");
        assertThat(result(results, conv).path("status").asText()).as(results.toString()).isEqualTo("APPLIED");
        assertThat(result(results, conv).path("serverEntityId").asText()).isEqualTo(invoiceId);
        assertThat(row(invoiceId).get("number")).isEqualTo("FAC-%d-000001".formatted(year));
        assertThat(documents()).isEqualTo(1);

        var again = op("CONVERT_QUOTE", quoteId, Map.of("quoteId", quoteId, "invoiceId", UUID.randomUUID().toString()));
        Map<String, JsonNode> replay = push(conv, again);
        assertThat(result(replay, conv).path("status").asText()).isEqualTo("DUPLICATE");
        assertThat(result(replay, again).path("status").asText()).isEqualTo("APPLIED");
        assertThat(result(replay, again).path("serverEntityId").asText()).isEqualTo(invoiceId);
        assertThat(documents()).isEqualTo(1);
        assertThat(counter()).isEqualTo(1);

        List<JsonNode> pulled = pullInvoices();
        JsonNode pulledQuote = pulled.stream().filter(n -> n.path("id").asText().equals(quoteId)).findFirst()
                .orElseThrow();
        assertThat(pulledQuote.path("kind").asText()).isEqualTo("QUOTE");
        assertThat(pulledQuote.path("status").asText()).isEqualTo("CONVERTED");
        assertThat(pulledQuote.path("convertedInvoiceId").asText()).isEqualTo(invoiceId);
        JsonNode pulledInvoice = pulled.stream().filter(n -> n.path("id").asText().equals(invoiceId)).findFirst()
                .orElseThrow();
        assertThat(pulledInvoice.path("kind").asText()).isEqualTo("INVOICE");
        assertThat(pulledInvoice.path("sourceQuoteId").asText()).isEqualTo(quoteId);
    }

    @Test
    @DisplayName("list: from/to on a quote = its creation day (Africa/Douala); search matches DV-XXXXXXXX; invoices "
            + "keep the issue-date rule")
    void listDatesAndReference() throws Exception {
        JsonNode quote = createQuote();
        String quoteId = quote.path("id").asText();
        String today = LocalDate.now(ZoneId.of("Africa/Douala")).toString();
        String yesterday = LocalDate.now(ZoneId.of("Africa/Douala")).minusDays(1).toString();
        String draftInvoice = createInvoice();

        assertThat(ids(data(call(HttpMethod.GET, "/api/v1/invoices?kind=ALL&from=" + today + "&to=" + today, null),
                200))).containsExactly(quoteId); // the draft invoice has no issue date
        assertThat(ids(data(call(HttpMethod.GET, "/api/v1/invoices?kind=QUOTE&to=" + yesterday, null), 200)))
                .isEmpty();
        String reference = quote.path("quoteReference").asText();
        assertThat(ids(data(call(HttpMethod.GET, "/api/v1/invoices?kind=QUOTE&search=" + reference, null), 200)))
                .containsExactly(quoteId);
        assertThat(ids(data(call(HttpMethod.GET, "/api/v1/invoices?kind=ALL&search="
                + reference.substring(0, 6).toLowerCase(), null), 200))).containsExactly(quoteId);
        assertThat(ids(data(call(HttpMethod.GET, "/api/v1/invoices?kind=ALL&search=DV-", null), 200)))
                .containsExactly(quoteId).doesNotContain(draftInvoice);
    }

    @Test
    @DisplayName("kind mismatch: re-sent creation of an existing invoice id as a quote → 409; PUT kind QUOTE on an "
            + "invoice → 409; PUT without kind on a quote keeps it a quote")
    void kindMismatch() throws Exception {
        String invoiceId = createInvoice();
        Map<String, Object> asQuote = content("Awa Traoré", 5_000);
        asQuote.put("id", invoiceId);
        asQuote.put("kind", "QUOTE");
        error(call(HttpMethod.POST, "/api/v1/invoices", asQuote), 409, "INVOICE_INVALID_TRANSITION");
        Map<String, Object> putQuote = content("Awa Traoré", 5_000);
        putQuote.put("kind", "QUOTE");
        error(call(HttpMethod.PUT, "/api/v1/invoices/" + invoiceId, putQuote), 409, "INVOICE_INVALID_TRANSITION");

        String quoteId = createQuote().path("id").asText();
        assertThat(data(call(HttpMethod.PUT, "/api/v1/invoices/" + quoteId, content("X", 1)), 200).path("kind")
                .asText()).isEqualTo("QUOTE");
        Map<String, Object> blankKind = content("X", 1);
        blankKind.put("kind", " ");
        error(call(HttpMethod.PUT, "/api/v1/invoices/" + quoteId, blankKind), 422, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("convert with a stale expectedUpdatedAt → 409 OPTIMISTIC_LOCK (French), nothing converted")
    void staleConversion() throws Exception {
        JsonNode quote = createQuote();
        String quoteId = quote.path("id").asText();
        JsonNode err = error(convert(quoteId, Map.of("expectedUpdatedAt", "2020-01-01T00:00:00Z")), 409,
                "OPTIMISTIC_LOCK");
        assertThat(err.path("error").asText())
                .isEqualTo("Ce document a été modifié entre-temps : rechargez-le avant de l'enregistrer.");
        assertThat(row(quoteId).get("status")).isEqualTo("DRAFT");
        assertThat(counter()).isZero();
        assertThat(data(convert(quoteId, Map.of("expectedUpdatedAt", quote.path("updatedAt").asText())), 201)
                .path("status").asText()).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("VAT settings changed since the quote → 409 with the French reason; quote untouched, no number, no "
            + "quota; saved again → convertible")
    void vatChangedSinceQuote() throws Exception {
        JsonNode quote = createQuote();
        String quoteId = quote.path("id").asText();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("legalName", "Boutique Awa SARL");
        settings.put("vatRegistered", true);
        settings.put("vatRate", 19.25);
        settings.put("pricesIncludeVat", false);
        data(call(HttpMethod.PUT, "/api/v1/invoicing/settings", settings), 200);

        JsonNode err = error(convert(quoteId, null), 409, "INVOICE_INVALID_TRANSITION");
        assertThat(err.path("error").asText()).startsWith("Les réglages de TVA ont changé depuis ce devis");
        assertThat(row(quoteId).get("status")).isEqualTo("DRAFT");
        assertThat(counter()).isZero();
        assertThat(documents()).isZero();

        data(call(HttpMethod.PUT, "/api/v1/invoices/" + quoteId, content("Société Ndolo", 11_925)), 200);
        data(convert(quoteId, null), 201);
    }

    @Test
    @DisplayName("quotes stay out of invoice outputs: cancelling one publishes no INVOICE_CANCELLED audit row, the "
            + "invoice lists (default, status=CANCELLED) and the dashboard summary are unchanged")
    void quotesOutOfInvoiceOutputs() throws Exception {
        String summaryBefore = body(call(HttpMethod.GET, "/api/v1/dashboard/summary", null))
                .replaceAll("\"timestamp\":\"[^\"]*\"", "");
        String quoteId = createQuote().path("id").asText();
        data(call(HttpMethod.POST, "/api/v1/invoices/" + quoteId + "/cancel", Map.of("reason", "Renoncé")), 200);

        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices", null), 200)).isEmpty();
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoices?status=CANCELLED", null), 200)).isEmpty();
        assertThat(body(call(HttpMethod.GET, "/api/v1/dashboard/summary", null))
                .replaceAll("\"timestamp\":\"[^\"]*\"", "")).isEqualTo(summaryBefore);
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT action FROM \"" + schema
                + "\".audit_log WHERE action LIKE 'QUOTE_%' OR action LIKE 'INVOICE_%'", String.class))
                .contains("QUOTE_CANCELLED").doesNotContain("INVOICE_CANCELLED", "INVOICE_CREATED"));
    }

    @Test
    @DisplayName("sync ISSUE_INVOICE and PAY_INVOICE on a quote → REJECTED INVOICE_INVALID_TRANSITION")
    void syncInvoiceOpsOnQuote() throws Exception {
        String quoteId = createQuote().path("id").asText();
        var issue = op("ISSUE_INVOICE", quoteId, Map.of("invoiceId", quoteId));
        var pay = op("PAY_INVOICE", quoteId, Map.of("invoiceId", quoteId));
        Map<String, JsonNode> results = push(issue, pay);
        for (var o : List.of(issue, pay)) {
            assertThat(result(results, o).path("status").asText()).isEqualTo("REJECTED");
            assertThat(result(results, o).path("reason").asText()).isEqualTo("INVOICE_INVALID_TRANSITION");
        }
        assertThat(row(quoteId).get("status")).isEqualTo("DRAFT");
        assertThat(counter()).isZero();
    }
}

package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
 * Story 15.5b — offline invoices through the real sync chain ({@code POST /api/v1/sync/push} with the production
 * security filter, JWTs, module gate and operation log; {@code GET /api/v1/sync/pull}): every row of the spec's I/O
 * matrix that the server owns — create + issue + pay in one batch numbered by the server, the same batch re-sent (one
 * invoice, one number), a re-sent creation under a new operation id, a payment date refused at sync, permissions,
 * module disabled (held + pull skips invoices), and changes made on the server (credit note) reaching the pull.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Invoicing — offline invoices through sync push / pull (Story 15.5b)")
class InvoiceSyncFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository invoiceRepository;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private String employeeToken;
    private String year;
    private Instant clock = Instant.now().minusSeconds(600);
    /** One device per actor (a device id belongs to one user). */
    private final Map<String, String> devices = new java.util.HashMap<>();

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
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("legalName", "Boutique Awa SARL");
        settings.put("address", "Rue Joss, Akwa, Douala");
        settings.put("vatRegistered", false);
        settings.put("vatRate", 19.25);
        settings.put("pricesIncludeVat", true);
        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/invoicing/settings")
                .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(settings))).andReturn();
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** A queued operation; client timestamps increase in creation order (the device's queue order). */
    private Map<String, Object> op(String type, String entityId, Map<String, Object> payload) {
        clock = clock.plusSeconds(1);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", entityId);
        op.put("payload", payload);
        op.put("clientTimestamp", clock.toString());
        return op;
    }

    private Map<String, Object> create(String invoiceId) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 2);
        line.put("unitPrice", 11_925);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", "Société Ndolo");
        client.put("phone", "+237690000001");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", invoiceId);
        p.put("client", client);
        p.put("lines", List.of(line));
        return op("CREATE_INVOICE", invoiceId, p);
    }

    private Map<String, Object> simple(String type, String invoiceId, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>(extra);
        p.put("invoiceId", invoiceId);
        return op(type, invoiceId, p);
    }

    /** Pushes the operations; the results by operation id ({@code status}, {@code reason}). */
    @SafeVarargs
    private Map<String, JsonNode> push(String token, Map<String, Object>... ops) throws Exception {
        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/sync/push")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("deviceId", devices.computeIfAbsent(token, t -> UUID.randomUUID().toString()),
                        "operations", List.of(ops))))).andReturn();
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode result : objectMapper.readTree(body(r)).path("data").path("results")) {
            byId.put(result.path("operationId").asText(), result);
        }
        return byId;
    }

    private static String status(Map<String, JsonNode> results, Map<String, Object> op) {
        return results.get((String) op.get("operationId")).path("status").asText();
    }

    private static String reason(Map<String, JsonNode> results, Map<String, Object> op) {
        return results.get((String) op.get("operationId")).path("reason").asText();
    }

    /** The {@code invoices} rows of a full pull (every page). */
    private List<JsonNode> pullInvoices(String token) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        String cursor = null;
        do {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull").header("Authorization", "Bearer " + token);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            MvcResult r = mockMvc.perform(request).andReturn();
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
            JsonNode data = objectMapper.readTree(body(r)).path("data");
            data.path("entities").path("invoices").forEach(rows::add);
            cursor = data.path("hasMore").asBoolean() ? data.path("nextCursor").asText() : null;
        } while (cursor != null);
        return rows;
    }

    private JsonNode pulled(String token, String invoiceId) throws Exception {
        return pullInvoices(token).stream().filter(n -> n.path("id").asText().equals(invoiceId)).findFirst()
                .orElse(null);
    }

    private int invoiceCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class);
    }

    private void moduleRow(String moduleId, String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status",
                tenantId, moduleId, status, "ACTIVE".equals(status) ? "PREMIUM" : "FREE");
    }

    // ── I/O matrix ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create + issue + pay queued offline: applied in queue order, numbered FAC-<year>-000001, pulled PAID")
    void createIssuePay() throws Exception {
        String id = UUID.randomUUID().toString();
        var create = create(id);
        var issue = simple("ISSUE_INVOICE", id, Map.of());
        var pay = simple("PAY_INVOICE", id, Map.of("paymentMethod", "Espèces"));

        // sent in reverse: the push applies them in the device's queue (clientTimestamp) order
        Map<String, JsonNode> results = push(ownerToken, pay, issue, create);

        assertThat(status(results, create)).isEqualTo("APPLIED");
        assertThat(status(results, issue)).isEqualTo("APPLIED");
        assertThat(status(results, pay)).isEqualTo("APPLIED");
        assertThat(results.get((String) create.get("operationId")).path("serverEntityId").asText()).isEqualTo(id);

        JsonNode row = pulled(ownerToken, id);
        assertThat(row).isNotNull();
        assertThat(row.path("number").asText()).isEqualTo("FAC-" + year + "-000001");
        assertThat(row.path("status").asText()).isEqualTo("PAID");
        assertThat(row.path("statusLabel").asText()).isEqualTo("Payée");
        assertThat(row.path("paymentMethod").asText()).isEqualTo("Espèces");
        assertThat(row.path("totalTtc").asLong()).isEqualTo(23_850);
        assertThat(row.path("client").path("name").asText()).isEqualTo("Société Ndolo");
        assertThat(row.path("lines")).hasSize(1);
        assertThat(row.path("updatedAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the same batch pushed twice → DUPLICATE, one invoice, one number; the creation re-sent under a new "
            + "operation id → APPLIED, still one invoice")
    void duplicateSend() throws Exception {
        String id = UUID.randomUUID().toString();
        var create = create(id);
        var issue = simple("ISSUE_INVOICE", id, Map.of());
        push(ownerToken, create, issue);

        Map<String, JsonNode> again = push(ownerToken, create, issue);
        assertThat(status(again, create)).isEqualTo("DUPLICATE");
        assertThat(status(again, issue)).isEqualTo("DUPLICATE");

        @SuppressWarnings("unchecked")
        Map<String, Object> resent = new LinkedHashMap<>(create);
        resent.put("operationId", UUID.randomUUID().toString());
        Map<String, JsonNode> r = push(ownerToken, resent);
        assertThat(status(r, resent)).isEqualTo("APPLIED");

        assertThat(invoiceCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT number FROM \"" + schema + "\".invoices WHERE id = ?::uuid",
                String.class, id)).isEqualTo("FAC-" + year + "-000001");
        assertThat(jdbc.queryForObject("SELECT last_number FROM \"" + schema + "\".invoice_counters WHERE year = ?",
                Long.class, Integer.parseInt(year))).isEqualTo(1L);
    }

    @Test
    @DisplayName("edit offline (UPDATE_INVOICE) before the issue: the issued invoice carries the edited content")
    void updateThenIssue() throws Exception {
        String id = UUID.randomUUID().toString();
        var create = create(id);
        Map<String, Object> line = Map.of("description", "Huile 5 L", "quantity", 3, "unitPrice", 6_500);
        var update = simple("UPDATE_INVOICE", id, Map.of("client", Map.of("name", "Ets Mbarga"),
                "lines", List.of(line)));
        var issue = simple("ISSUE_INVOICE", id, Map.of());

        Map<String, JsonNode> r = push(ownerToken, create, update, issue);
        assertThat(status(r, update)).isEqualTo("APPLIED");

        JsonNode row = pulled(ownerToken, id);
        assertThat(row.path("status").asText()).isEqualTo("ISSUED");
        assertThat(row.path("client").path("name").asText()).isEqualTo("Ets Mbarga");
        assertThat(row.path("totalTtc").asLong()).isEqualTo(19_500);
    }

    @Test
    @DisplayName("payment date before the issue date at sync → REJECTED INVOICE_PAYMENT_DATE_INVALID, stays ISSUED")
    void payDateInvalid() throws Exception {
        String id = UUID.randomUUID().toString();
        var create = create(id);
        var issue = simple("ISSUE_INVOICE", id, Map.of());
        String yesterday = LocalDate.now(ZoneId.of("Africa/Douala")).minusDays(1).toString();
        var pay = simple("PAY_INVOICE", id, Map.of("paymentMethod", "Espèces", "paidAt", yesterday));

        Map<String, JsonNode> r = push(ownerToken, create, issue, pay);

        assertThat(status(r, pay)).isEqualTo("REJECTED");
        assertThat(reason(r, pay)).isEqualTo("INVOICE_PAYMENT_DATE_INVALID");
        assertThat(pulled(ownerToken, id).path("status").asText()).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("paying an invoice already paid → REJECTED INVOICE_INVALID_TRANSITION (the server rules apply at sync)")
    void alreadyPaid() throws Exception {
        String id = UUID.randomUUID().toString();
        push(ownerToken, create(id), simple("ISSUE_INVOICE", id, Map.of()),
                simple("PAY_INVOICE", id, Map.of("paymentMethod", "Espèces")));
        var payAgain = simple("PAY_INVOICE", id, Map.of("paymentMethod", "Virement"));
        Map<String, JsonNode> r = push(ownerToken, payAgain);
        assertThat(reason(r, payAgain)).isEqualTo("INVOICE_INVALID_TRANSITION");
    }

    @Test
    @DisplayName("employee: create / issue applied, cancel → REJECTED FORBIDDEN; employee pull receives invoices")
    void employee() throws Exception {
        String id = UUID.randomUUID().toString();
        var create = create(id);
        var issue = simple("ISSUE_INVOICE", id, Map.of());
        var cancel = simple("CANCEL_INVOICE", id, Map.of("reason", "Erreur de client"));

        Map<String, JsonNode> r = push(employeeToken, create, issue, cancel);

        assertThat(status(r, create)).isEqualTo("APPLIED");
        assertThat(status(r, issue)).isEqualTo("APPLIED");
        assertThat(reason(r, cancel)).isEqualTo("FORBIDDEN");
        assertThat(pulled(employeeToken, id).path("status").asText()).isEqualTo("ISSUED");

        var ownerCancel = simple("CANCEL_INVOICE", id, Map.of("reason", "Erreur de client"));
        assertThat(status(push(ownerToken, ownerCancel), ownerCancel)).isEqualTo("APPLIED");
        assertThat(pulled(ownerToken, id).path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("invoicing DISABLED: operations REJECTED MODULE_INACTIVE (held on the device), pull skips invoices; "
            + "re-enabled → the same operations apply")
    void moduleDisabled() throws Exception {
        String before = UUID.randomUUID().toString();
        push(ownerToken, create(before));
        moduleRow("invoicing", "DISABLED");

        String id = UUID.randomUUID().toString();
        var create = create(id);
        Map<String, JsonNode> r = push(ownerToken, create);
        assertThat(reason(r, create)).isEqualTo("MODULE_INACTIVE");
        assertThat(pullInvoices(ownerToken)).isEmpty();

        moduleRow("invoicing", "ACTIVE");
        Map<String, JsonNode> again = push(ownerToken, create);
        assertThat(status(again, create)).isEqualTo("APPLIED");
        assertThat(pullInvoices(ownerToken)).extracting(n -> n.path("id").asText()).contains(before, id);
    }

    @Test
    @DisplayName("REST creation with the device id is idempotent too (online fast path, then the queued copy)")
    void restCreateWithId() throws Exception {
        String id = UUID.randomUUID().toString();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) create(id).get("payload");
        for (int i = 0; i < 2; i++) {
            MvcResult r = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/invoices")
                    .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload))).andReturn();
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
            assertThat(objectMapper.readTree(body(r)).path("data").path("id").asText()).isEqualTo(id);
        }
        var queued = create(id);
        assertThat(status(push(ownerToken, queued), queued)).isEqualTo("APPLIED");
        assertThat(invoiceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a credit note made on the server moves the invoice in the delta: pulled again with it")
    void creditNoteReachesTheDelta() throws Exception {
        String id = UUID.randomUUID().toString();
        push(ownerToken, create(id), simple("ISSUE_INVOICE", id, Map.of()));
        String since = Instant.now().minusSeconds(1).toString();
        jdbc.update("UPDATE \"" + schema + "\".invoices SET updated_at = now() - interval '1 hour' WHERE id = ?::uuid",
                id);

        MvcResult cn = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/invoices/" + id + "/credit-notes")
                .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "Retour client")))).andReturn();
        assertThat(cn.getResponse().getStatus()).as(body(cn)).isEqualTo(201);

        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/sync/pull")
                .header("Authorization", "Bearer " + ownerToken).param("since", since)).andReturn();
        JsonNode rows = objectMapper.readTree(body(r)).path("data").path("entities").path("invoices");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("creditNotes")).hasSize(1);
        assertThat(rows.get(0).path("fullyCredited").asBoolean()).isTrue();
    }

    /** The invoices delta since [since] (one page). */
    private JsonNode deltaSince(String since) throws Exception {
        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/sync/pull")
                .header("Authorization", "Bearer " + ownerToken).param("since", since)).andReturn();
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        return objectMapper.readTree(body(r)).path("data").path("entities").path("invoices");
    }

    /** An issued invoice whose updated_at is moved one hour back; the cursor taken just before. */
    private String issuedAndAged(String id) throws Exception {
        push(ownerToken, create(id), simple("ISSUE_INVOICE", id, Map.of()));
        String since = Instant.now().minusSeconds(1).toString();
        jdbc.update("UPDATE \"" + schema + "\".invoices SET updated_at = now() - interval '1 hour' WHERE id = ?::uuid",
                id);
        return since;
    }

    private <T> T inTenant(java.util.function.Supplier<T> action) {
        TenantContext.setCurrentTenant(schema);
        try {
            return tx.execute(s -> action.get());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("a WhatsApp sending recorded on the server moves the invoice in the delta (sendCount, lastSentAt)")
    void sendingReachesTheDelta() throws Exception {
        String id = UUID.randomUUID().toString();
        String since = issuedAndAged(id);
        inTenant(() -> {
            var invoice = invoiceRepository.findByIdForUpdate(UUID.fromString(id)).orElseThrow();
            invoiceRepository.markSent(invoice.sent("+237690000001", Instant.now()));
            return null;
        });

        JsonNode rows = deltaSince(since);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("sendCount").asInt()).isEqualTo(1);
        assertThat(rows.get(0).path("lastSentAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("an invoice replaced by a corrected sale moves in the delta (supersededAt)")
    void supersedeReachesTheDelta() throws Exception {
        String id = UUID.randomUUID().toString();
        String since = issuedAndAged(id);
        inTenant(() -> {
            invoiceRepository.markSuperseded(UUID.fromString(id), Instant.now());
            return null;
        });

        JsonNode rows = deltaSince(since);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("supersededAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a deleted draft: 404 / absent from the list, a tombstone {id, deleted} in the delta, never "
            + "resurrected by a re-sent creation")
    void deletedDraftTombstone() throws Exception {
        String id = UUID.randomUUID().toString();
        var create = create(id);
        push(ownerToken, create);
        String since = Instant.now().minusSeconds(1).toString();

        MvcResult del = mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/invoices/" + id)
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(del.getResponse().getStatus()).as(body(del)).isEqualTo(204);
        MvcResult get = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/invoices/" + id)
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(get.getResponse().getStatus()).isEqualTo(404);
        MvcResult list = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/invoices")
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(objectMapper.readTree(body(list)).path("data")).isEmpty();

        JsonNode rows = deltaSince(since);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("id").asText()).isEqualTo(id);
        assertThat(rows.get(0).path("deleted").asBoolean()).isTrue();
        assertThat(rows.get(0).has("status")).isFalse();

        @SuppressWarnings("unchecked")
        Map<String, Object> resent = new LinkedHashMap<>(create);
        resent.put("operationId", UUID.randomUUID().toString());
        assertThat(status(push(ownerToken, resent), resent)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM \"" + schema
                + "\".invoices WHERE id = ?::uuid", Boolean.class, id)).isTrue();
        var issue = simple("ISSUE_INVOICE", id, Map.of());
        assertThat(reason(push(ownerToken, issue), issue)).isEqualTo("INVOICE_NOT_FOUND");
    }

    @Test
    @DisplayName("the same creation sent concurrently (online call + queued operation): one invoice, no error")
    void concurrentCreation() throws Exception {
        String id = UUID.randomUUID().toString();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) create(id).get("payload");
        String json = objectMapper.writeValueAsString(payload);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() -> mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json)).andReturn().getResponse().getStatus()));
            }
            for (var call : calls) {
                assertThat(call.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(invoiceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("tenant provisioned at an older invoicing version (before V6/V7): the pull migrates it to the "
            + "latest version and returns its invoices")
    void pullUpgradesAnOlderTenant() throws Exception {
        // a tenant whose invoicing tables stopped at V5 and that has not made a gated call since the deploy
        String old = tx.execute(st -> tenantFactory.create()).getSchemaName();
        UUID oldTenant = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, old);
        String q = "\"" + old + "\".";
        try {
            jdbc.execute("DROP INDEX IF EXISTS " + q + "idx_invoices_sync_keyset");
            jdbc.execute("ALTER TABLE " + q + "invoices DROP COLUMN deleted_at");
            jdbc.update("DELETE FROM " + q + "flyway_history_invoicing WHERE version IN ('6', '7')");
            UUID issued = UUID.randomUUID();
            jdbc.update("INSERT INTO " + q + "invoices (id, number, status, issue_date, client_name, "
                    + "seller_legal_name, vat_registered, vat_rate, prices_include_vat, total_ht, total_vat, total_ttc, "
                    + "issued_at) VALUES (?, 'FAC-2026-000042', 'ISSUED', DATE '2026-10-30', 'Client V5', "
                    + "'Boutique V5', false, 19.25, true, 5000, 0, 5000, now())", issued);
            UUID draft = UUID.randomUUID();
            jdbc.update("INSERT INTO " + q + "invoices (id, client_name, vat_registered, vat_rate, prices_include_vat) "
                    + "VALUES (?, 'Brouillon V5', false, 19.25, true)", draft);
            String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), old, "OWNER");

            List<JsonNode> rows = pullInvoices(token);

            assertThat(rows).extracting(n -> n.path("id").asText())
                    .containsExactlyInAnyOrder(issued.toString(), draft.toString());
            assertThat(rows).filteredOn(n -> n.path("id").asText().equals(issued.toString()))
                    .singleElement().satisfies(n -> assertThat(n.path("number").asText()).isEqualTo("FAC-2026-000042"));
            assertThat(jdbc.queryForObject("SELECT MAX(version::int) FROM " + q + "flyway_history_invoicing "
                    + "WHERE success", Integer.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? "
                    + "AND table_name = 'invoices' AND column_name = 'deleted_at'", Integer.class, old)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", oldTenant);
        }
    }
}

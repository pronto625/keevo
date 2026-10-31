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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.1 — the « Facturation » module on the real HTTP chain (production security filter, real JWTs, method
 * security, entitlement aspect with lazy provisioning, JDBC adapters on PostgreSQL): every row of the spec's I/O
 * matrix. The tenant is created by the production {@link TenantFactory} (registration trial), so the invoicing tables
 * come from the registration provisioning; pos / crm / catalog are DISABLED by rows to prove the module stands alone.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Invoicing module — full context (Story 14.1)")
class InvoicingFullContextTest extends FullContextIntegrationTest {

    private static final List<String> TABLES = List.of("credit_note_counters", "credit_note_items",
            "credit_notes", "invoice_counters", "invoice_items", "invoices", "invoicing_settings",
            "sale_invoice_states");

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
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", employeeId, storeId);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
        employeeToken = jwtTokenProvider.generateAccessToken(employeeId, schema, "EMPLOYEE", "ACTIVE", storeId,
                false);
        year = String.valueOf(LocalDate.now(ZoneId.of("Africa/Douala")).getYear());
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

    /** GET /api/v1/invoices as the owner, {@code k=v&k=v} sent as raw request parameters (no URL encoding). */
    private MvcResult list(String query) throws Exception {
        var request = MockMvcRequestBuilders.get("/api/v1/invoices").header("Authorization", "Bearer " + ownerToken);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            request.param(pair.substring(0, eq), pair.substring(eq + 1));
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

    private static void assertError(MvcResult r, int status, String domainCode) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        assertThat(body(r)).contains("\"domainCode\":\"" + domainCode + "\"");
    }

    private void moduleRow(String moduleId, String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status",
                tenantId, moduleId, status, "ACTIVE".equals(status) ? "PREMIUM" : "FREE");
    }

    /** pos, crm and catalog DISABLED: the tenant only has core + invoicing (trial). */
    private void standalone() {
        for (String id : List.of("pos", "crm", "catalog")) {
            moduleRow(id, "DISABLED");
        }
    }

    private List<String> tables() {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name = ANY (?) ORDER BY 1", String.class, schema, TABLES.toArray(new String[0]));
    }

    private void dropInvoicingTables() {
        jdbc.execute("DROP TABLE IF EXISTS \"" + schema + "\".credit_note_items, \"" + schema + "\".credit_notes, \""
                + schema + "\".credit_note_counters, \"" + schema + "\".sale_invoice_states, \"" + schema + "\".invoice_items, \"" + schema + "\".invoices, \""
                + schema + "\".invoice_counters, \"" + schema + "\".invoicing_settings, \"" + schema
                + "\".flyway_history_invoicing CASCADE");
    }

    private void plan(String planType, String expiresInterval) {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = '" + planType + "', expires_at = "
                + (expiresInterval == null ? "NULL" : "now() + interval '" + expiresInterval + "'"));
    }

    private static Map<String, Object> settings(boolean vatRegistered, String niu) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("legalName", "Boutique Awa SARL");
        s.put("address", "Rue Joss, Akwa, Douala");
        s.put("phone", "+237690000000");
        s.put("niu", niu);
        s.put("rccm", null);
        s.put("vatRegistered", vatRegistered);
        s.put("vatRate", 19.25);
        s.put("pricesIncludeVat", true);
        s.put("paymentDetails", "MTN MoMo 690000000");
        s.put("footer", "Merci de votre confiance");
        return s;
    }

    private static Map<String, Object> line(String description, Object qty, long price, String treatment) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("description", description);
        l.put("quantity", qty);
        l.put("unitPrice", price);
        l.put("vatTreatment", treatment);
        return l;
    }

    private static Map<String, Object> draft(String client, List<Map<String, Object>> lines) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", client);
        c.put("niu", "P098765432109Z");
        return Map.of("client", c, "lines", lines);
    }

    private String createDraft(String token) throws Exception {
        JsonNode d = data(call(token, HttpMethod.POST, "/api/v1/invoices",
                draft("Société Ndolo", List.of(line("Sac de riz 25 kg", 1, 11_925, "STANDARD")))), 201);
        return d.path("id").asText();
    }

    private List<Map<String, Object>> itemIds(String invoiceId) {
        return jdbc.queryForList("SELECT id, position, total_ttc FROM \"" + schema
                + "\".invoice_items WHERE invoice_id = ?::uuid ORDER BY position", invoiceId);
    }

    private Map<String, Object> creation(String invoiceId) {
        return jdbc.queryForMap("SELECT created_by, created_at, number, issued_at FROM \"" + schema
                + "\".invoices WHERE id = ?::uuid", invoiceId);
    }

    private void ownerSettings(boolean vatRegistered, String niu) throws Exception {
        data(owner(HttpMethod.PUT, "/api/v1/invoicing/settings", settings(vatRegistered, niu)), 200);
    }

    // ── matrix ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("new trial tenant: invoicing tables provisioned at registration, no tenant_modules row for invoicing")
    void newTrialTenant() {
        assertThat(tables()).containsExactlyElementsOf(TABLES);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND module_id = 'invoicing'", Integer.class, tenantId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name = ANY (?)", Integer.class, (Object) TABLES.toArray(new String[0]))).isZero();
    }

    @Test
    @DisplayName("standalone (pos/crm/catalog DISABLED): create, edit, list, issue, pay, cancel, delete — with CGI mentions")
    void standaloneLifecycle() throws Exception {
        standalone();
        ownerSettings(true, null);

        // create: 2 free lines, computed totals
        JsonNode created = data(owner(HttpMethod.POST, "/api/v1/invoices", draft("Société Ndolo", List.of(
                line("Sac de riz 25 kg", 1, 11_925, "STANDARD"), line("Manuel scolaire", 2, 2_500, "EXEMPT")))), 201);
        String id = created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("number").isNull()).isTrue();
        assertThat(created.path("totalHt").asLong()).isEqualTo(15_000);
        assertThat(created.path("totalVat").asLong()).isEqualTo(1_925);
        assertThat(created.path("totalTtc").asLong()).isEqualTo(16_925);
        assertThat(created.path("lines").get(1).path("vatMention").asText()).isEqualTo("Exonérée");

        // update the draft
        JsonNode updated = data(owner(HttpMethod.PUT, "/api/v1/invoices/" + id, draft("Société Ndolo", List.of(
                line("Sac de riz 25 kg", 1, 11_925, "STANDARD"), line("Vaccins", 1, 3_000, "STATE_BORNE")))), 200);
        assertThat(updated.path("totalTtc").asLong()).isEqualTo(14_925);
        assertThat(updated.path("lines").get(1).path("vatMention").asText()).isEqualTo("Prise en charge État");

        // issue: number, snapshot, mentions, warnings (NIU / RCCM missing)
        MvcResult issueResult = owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null);
        JsonNode issued = data(issueResult, 200);
        assertThat(issued.path("status").asText()).isEqualTo("ISSUED");
        assertThat(issued.path("statusLabel").asText()).isEqualTo("Émise");
        assertThat(issued.path("number").asText()).isEqualTo("FAC-" + year + "-000001");
        assertThat(issued.path("issueDate").asText()).isNotBlank();
        assertThat(issued.path("seller").path("legalName").asText()).isEqualTo("Boutique Awa SARL");
        assertThat(issued.path("seller").path("address").asText()).isEqualTo("Rue Joss, Akwa, Douala");
        assertThat(issued.path("client").path("name").asText()).isEqualTo("Société Ndolo");
        assertThat(issued.path("client").path("niu").asText()).isEqualTo("P098765432109Z");
        assertThat(issued.path("lines").get(0).path("totalHt").asLong()).isEqualTo(10_000);
        assertThat(issued.path("lines").get(0).path("vatRate").decimalValue()).isEqualByComparingTo("19.25");
        assertThat(issued.path("lines").get(0).path("totalVat").asLong()).isEqualTo(1_925);
        assertThat(issued.path("complianceWarnings").toString()).contains("Le NIU de votre commerce n'est pas renseigné")
                .contains("RCCM");

        // frozen state after the issue: line ids and creation fields never rewritten by pay / cancel
        List<Map<String, Object>> issuedLines = itemIds(id);
        Map<String, Object> creationFields = creation(id);

        // later settings edits never change the issued invoice
        Map<String, Object> renamed = settings(true, "M012345678901A");
        renamed.put("legalName", "Nouveau nom");
        data(owner(HttpMethod.PUT, "/api/v1/invoicing/settings", renamed), 200);
        assertThat(data(owner(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200).path("seller").path("legalName")
                .asText()).isEqualTo("Boutique Awa SARL");

        // issued: immutable, never deleted
        assertError(owner(HttpMethod.PUT, "/api/v1/invoices/" + id, draft("X", List.of())), 409, "INVOICE_NOT_EDITABLE");
        assertError(owner(HttpMethod.DELETE, "/api/v1/invoices/" + id, null), 409, "INVOICE_NOT_EDITABLE");

        // next number
        String second = createDraft(ownerToken);
        assertThat(data(owner(HttpMethod.POST, "/api/v1/invoices/" + second + "/issue", null), 200).path("number")
                .asText()).isEqualTo("FAC-" + year + "-000002");

        // pay, then cancel refused
        JsonNode paid = data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/pay",
                Map.of("paymentMethod", "Espèces")), 200);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        assertThat(paid.path("number").asText()).isEqualTo("FAC-" + year + "-000001");
        assertThat(itemIds(id)).as("pay keeps the lines").isEqualTo(issuedLines);
        assertThat(creation(id)).as("pay keeps the creation fields").isEqualTo(creationFields);
        assertError(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/cancel", Map.of("reason", "Erreur")), 409,
                "INVOICE_INVALID_TRANSITION");

        // cancel the second: blank reason 422, then cancelled with its number kept
        List<Map<String, Object>> secondLines = itemIds(second);
        Map<String, Object> secondCreated = creation(second);
        assertError(owner(HttpMethod.POST, "/api/v1/invoices/" + second + "/cancel", Map.of("reason", " ")), 422,
                "VALIDATION_ERROR");
        JsonNode cancelled = data(owner(HttpMethod.POST, "/api/v1/invoices/" + second + "/cancel",
                Map.of("reason", "Client erroné")), 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("number").asText()).isEqualTo("FAC-" + year + "-000002");
        assertThat(cancelled.path("cancellationReason").asText()).isEqualTo("Client erroné");
        assertThat(itemIds(second)).as("cancel keeps the lines").isEqualTo(secondLines);
        assertThat(creation(second)).as("cancel keeps the creation fields").isEqualTo(secondCreated);

        // a draft is deletable
        String third = createDraft(ownerToken);
        assertThat(owner(HttpMethod.DELETE, "/api/v1/invoices/" + third, null).getResponse().getStatus()).isEqualTo(204);
        assertError(owner(HttpMethod.GET, "/api/v1/invoices/" + third, null), 404, "INVOICE_NOT_FOUND");

        // list (paginated, newest first) and filter
        MvcResult list = owner(HttpMethod.GET, "/api/v1/invoices?page=0&size=10", null);
        JsonNode listed = data(list, 200);
        assertThat(listed).hasSize(2);
        assertThat(objectMapper.readTree(body(list)).path("meta").path("totalElements").asLong()).isEqualTo(2);
        assertThat(data(owner(HttpMethod.GET, "/api/v1/invoices?status=PAID", null), 200)).hasSize(1);
        assertThat(data(owner(HttpMethod.GET, "/api/v1/invoices?status=paid", null), 200)).hasSize(1);
        assertThat(owner(HttpMethod.GET, "/api/v1/invoices?status=bogus", null).getResponse().getStatus())
                .isEqualTo(400);

        // audit: French action codes with the invoice number as label, never a UUID
        List<Map<String, Object>> audit = jdbc.queryForList("SELECT action, entity_label FROM \"" + schema
                + "\".audit_log WHERE entity_type = 'Invoice' ORDER BY occurred_at");
        assertThat(audit).extracting(r -> r.get("action")).contains("INVOICE_CREATED", "INVOICE_ISSUED",
                "INVOICE_PAID", "INVOICE_CANCELLED");
        assertThat(audit).filteredOn(r -> "INVOICE_ISSUED".equals(r.get("action")))
                .extracting(r -> r.get("entity_label")).contains("FAC-" + year + "-000001");
        assertThat(audit).filteredOn(r -> "INVOICE_CREATED".equals(r.get("action")))
                .extracting(r -> r.get("entity_label")).contains("brouillon de Société Ndolo");
        List<Map<String, Object>> settingsAudit = jdbc.queryForList("SELECT entity_label FROM \"" + schema
                + "\".audit_log WHERE action = 'INVOICING_SETTINGS_UPDATED' AND entity_type = 'InvoicingSettings' "
                + "ORDER BY occurred_at");
        assertThat(settingsAudit).extracting(r -> r.get("entity_label"))
                .containsExactly("Boutique Awa SARL", "Nouveau nom");
        assertThat(audit).extracting(r -> String.valueOf(r.get("entity_label")))
                .noneMatch(l -> l.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-.*"));
        // no pos / crm / catalog table touched by invoicing: their modules are DISABLED and still are
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".sales", Integer.class)).isZero();
    }

    @Test
    @DisplayName("settings: VAT-registered with a 0 % rate → 422 with a French message")
    void vatRegisteredNeedsRate() throws Exception {
        Map<String, Object> zero = settings(true, null);
        zero.put("vatRate", 0);
        MvcResult r = owner(HttpMethod.PUT, "/api/v1/invoicing/settings", zero);
        assertError(r, 422, "VALIDATION_ERROR");
        assertThat(body(r)).contains("Un commerce assujetti à la TVA doit avoir un taux de TVA supérieur à 0");
        Map<String, Object> notRegistered = settings(false, null);
        notRegistered.put("vatRate", 0);
        data(owner(HttpMethod.PUT, "/api/v1/invoicing/settings", notRegistered), 200);
    }

    @Test
    @DisplayName("not VAT-registered: every line 0 %, « TVA non applicable »")
    void notVatRegistered() throws Exception {
        ownerSettings(false, "M012345678901A");
        String id = createDraft(ownerToken);
        JsonNode issued = data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        assertThat(issued.path("vatMention").asText()).isEqualTo("TVA non applicable");
        assertThat(issued.path("totalVat").asLong()).isZero();
        assertThat(issued.path("totalHt").asLong()).isEqualTo(11_925);
    }

    @Test
    @DisplayName("not issuable: no line → 422 INVOICE_NOT_ISSUABLE (French reason), no number used; no legal name → "
            + "the company display name is printed with a warning (14.2)")
    void notIssuable() throws Exception {
        String noLegalName = createDraft(ownerToken);
        JsonNode draftDto = data(owner(HttpMethod.GET, "/api/v1/invoices/" + noLegalName, null), 200);
        assertThat(draftDto.path("complianceWarnings").toString()).contains("La raison sociale n'est pas renseignée");

        String empty = data(owner(HttpMethod.POST, "/api/v1/invoices", draft("Awa", List.of())), 201)
                .path("id").asText();
        MvcResult r = owner(HttpMethod.POST, "/api/v1/invoices/" + empty + "/issue", null);
        assertError(r, 422, "INVOICE_NOT_ISSUABLE");
        assertThat(body(r)).contains("La facture ne peut pas être émise").contains("ajoutez au moins une ligne");

        // Story 14.2: issuing is no longer blocked by a missing legal name — the shop name is printed instead
        JsonNode issued = data(owner(HttpMethod.POST, "/api/v1/invoices/" + noLegalName + "/issue", null), 200);
        assertThat(issued.path("number").asText()).isEqualTo("FAC-" + year + "-000001");
        String shopName = jdbc.queryForObject("SELECT name FROM public.tenants WHERE id = ?", String.class, tenantId);
        String expectedName = shopName == null || shopName.startsWith("KV-") ? "Mon commerce" : shopName;
        assertThat(issued.path("seller").path("legalName").asText()).isEqualTo(expectedName);
        assertThat(issued.path("complianceWarnings").toString())
                .contains("La raison sociale n'est pas renseignée : le nom affiché de votre entreprise est utilisé.");
        // read back: the flag is persisted with the snapshot
        JsonNode reread = data(owner(HttpMethod.GET, "/api/v1/invoices/" + noLegalName, null), 200);
        assertThat(reread.path("complianceWarnings").toString())
                .contains("La raison sociale n'est pas renseignée : le nom affiché de votre entreprise est utilisé.");
        assertThat(reread.path("seller").path("legalName").asText()).isEqualTo(expectedName);
    }

    @Test
    @DisplayName("concurrent issue: 20 drafts issued in parallel → numbers 1..20, no gap, no duplicate")
    void concurrentIssue() throws Exception {
        ownerSettings(true, null);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(createDraft(ownerToken));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<String>> numbers = new ArrayList<>();
        try {
            for (String id : ids) {
                Callable<String> issue = () -> {
                    try {
                        MvcResult r = owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null);
                        return data(r, 200).path("number").asText();
                    } finally {
                        TenantContext.clear();
                        SecurityContextHolder.clearContext();
                    }
                };
                numbers.add(pool.submit(issue));
            }
            Set<String> got = new HashSet<>();
            for (Future<String> f : numbers) {
                got.add(f.get());
            }
            Set<String> expected = new HashSet<>();
            for (int i = 1; i <= 20; i++) {
                expected.add(String.format("FAC-%s-%06d", year, i));
            }
            assertThat(got).isEqualTo(expected);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT last_number FROM \"" + schema + "\".invoice_counters WHERE year = ?",
                Integer.class, Integer.parseInt(year))).isEqualTo(20);
    }

    @Test
    @DisplayName("15.5 — list: search (number / client name, case-insensitive, LIKE wildcards literal), issue date range, French 400s")
    void listSearchAndDateRange() throws Exception {
        ownerSettings(true, null);
        String ndolo = createDraft(ownerToken); // client « Société Ndolo »
        JsonNode first = data(owner(HttpMethod.POST, "/api/v1/invoices/" + ndolo + "/issue", null), 200);
        String awa = data(owner(HttpMethod.POST, "/api/v1/invoices", draft("Boutique 100% Awa",
                List.of(line("Pagne", 1, 5_000, "STANDARD")))), 201).path("id").asText();
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + awa + "/issue", null), 200);
        String draftOnly = data(owner(HttpMethod.POST, "/api/v1/invoices", draft("Ndolo Junior",
                List.of(line("Riz", 1, 1_000, "STANDARD")))), 201).path("id").asText();
        // back-date the first invoice's issue date by 10 days (range filter)
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Douala"));
        jdbc.update("UPDATE \"" + schema + "\".invoices SET issue_date = ? WHERE id = ?::uuid",
                java.sql.Date.valueOf(today.minusDays(10)), ndolo);

        java.util.function.Function<String, List<String>> ids = query -> {
            try {
                List<String> out = new ArrayList<>();
                data(list(query), 200).forEach(n -> out.add(n.path("id").asText()));
                return out;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        // client name, case-insensitive, trimmed — the draft matches too (no date bound)
        assertThat(ids.apply("search= nDoLo ")).containsExactly(draftOnly, ndolo);
        // number part
        assertThat(ids.apply("search=" + first.path("number").asText().toLowerCase())).containsExactly(ndolo);
        // « % » is literal, not a wildcard
        assertThat(ids.apply("search=100%")).containsExactly(awa);
        assertThat(ids.apply("search=%%%")).isEmpty();
        // « _ » and a backslash are literal too: no number nor client name contains them
        assertThat(ids.apply("search=_")).isEmpty();
        assertThat(ids.apply("search=\\")).isEmpty();
        MvcResult nul = list("search=nd\u0000olo");
        assertError(nul, 400, "VALIDATION_ERROR");
        assertThat(body(nul)).contains("La recherche contient un caractère non autorisé");
        // issue date range, inclusive; drafts excluded
        assertThat(ids.apply("from=" + today + "&to=" + today)).containsExactly(awa);
        assertThat(ids.apply("from=" + today.minusDays(10) + "&to=" + today.minusDays(10))).containsExactly(ndolo);
        assertThat(ids.apply("to=" + today.minusDays(1))).containsExactly(ndolo);
        assertThat(ids.apply("search=ndolo&from=" + today.minusDays(30))).containsExactly(ndolo);
        assertThat(ids.apply("search=ndolo&status=DRAFT")).containsExactly(draftOnly);
        // pagination meta follows the filter
        MvcResult page = list("search=ndolo&size=1");
        assertThat(objectMapper.readTree(body(page)).path("meta").path("totalElements").asLong()).isEqualTo(2);

        MvcResult reversed = list("from=" + today + "&to=" + today.minusDays(1));
        assertError(reversed, 400, "VALIDATION_ERROR");
        assertThat(body(reversed)).contains("La date de début doit précéder la date de fin");
        MvcResult tooLong = list("search=" + "a".repeat(101));
        assertError(tooLong, 400, "VALIDATION_ERROR");
        assertThat(body(tooLong)).contains("La recherche ne doit pas dépasser 100 caractères");
        assertThat(ids.apply("search=" + "a".repeat(100))).isEmpty();
        MvcResult badDate = list("from=31-10-2026");
        assertThat(badDate.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(badDate)).contains("Paramètre invalide");
    }

    @Test
    @DisplayName("15.5 — pay with a payment date: yesterday → PAID that day; future / before issue → 400 French")
    void payBackDated() throws Exception {
        ownerSettings(true, null);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Douala"));
        String id = createDraft(ownerToken);
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        jdbc.update("UPDATE \"" + schema + "\".invoices SET issue_date = ? WHERE id = ?::uuid",
                java.sql.Date.valueOf(today.minusDays(3)), id);

        MvcResult future = owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/pay",
                Map.of("paidAt", today.plusDays(1).toString()));
        assertError(future, 400, "INVOICE_PAYMENT_DATE_INVALID");
        assertThat(body(future)).contains("Date de paiement invalide : elle ne peut pas être dans le futur");
        MvcResult beforeIssue = owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/pay",
                Map.of("paidAt", today.minusDays(4).toString()));
        assertError(beforeIssue, 400, "INVOICE_PAYMENT_DATE_INVALID");
        assertThat(body(beforeIssue)).contains("précéder la date d'émission");

        JsonNode paid = data(owner(HttpMethod.POST, "/api/v1/invoices/" + id + "/pay",
                Map.of("paymentMethod", "Espèces", "paidAt", today.minusDays(1).toString())), 200);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        assertThat(paid.path("paymentMethod").asText()).isEqualTo("Espèces");
        assertThat(java.time.Instant.parse(paid.path("paidAt").asText()).atZone(ZoneId.of("Africa/Douala"))
                .toLocalDate()).isEqualTo(today.minusDays(1));
        // audit: the chosen payment day in French, no technical data
        String meta = jdbc.queryForObject("SELECT metadata::text FROM \"" + schema + "\".audit_log WHERE action = "
                + "'INVOICE_PAID' AND entity_id = ?::uuid", String.class, id);
        assertThat(meta).contains("payée le " + today.minusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        assertThat(meta).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
        // without paidAt: paid now (today)
        String second = createDraft(ownerToken);
        data(owner(HttpMethod.POST, "/api/v1/invoices/" + second + "/issue", null), 200);
        JsonNode now = data(owner(HttpMethod.POST, "/api/v1/invoices/" + second + "/pay", null), 200);
        assertThat(java.time.Instant.parse(now.path("paidAt").asText()).atZone(ZoneId.of("Africa/Douala"))
                .toLocalDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("EMPLOYEE: create / issue / pay 200; cancel or PUT settings → 403 FORBIDDEN")
    void employee() throws Exception {
        ownerSettings(true, null);
        String id = createDraft(employeeToken);
        data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        data(call(employeeToken, HttpMethod.GET, "/api/v1/invoicing/settings", null), 200);
        assertError(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/cancel",
                Map.of("reason", "Erreur")), 403, "FORBIDDEN");
        assertError(call(employeeToken, HttpMethod.PUT, "/api/v1/invoicing/settings", settings(true, null)), 403,
                "FORBIDDEN");
        data(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/pay", null), 200);
    }

    @Test
    @DisplayName("SUPER_ADMIN: every invoicing endpoint → 403")
    void superAdmin() throws Exception {
        String admin = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "public", "SUPER_ADMIN");
        for (MvcResult r : List.of(call(admin, HttpMethod.GET, "/api/v1/invoices", null),
                call(admin, HttpMethod.POST, "/api/v1/invoices", draft("Awa", List.of())),
                call(admin, HttpMethod.GET, "/api/v1/invoicing/settings", null))) {
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("Free tenant: 403 MODULE_NOT_ENTITLED and no table created")
    void freeTenant() throws Exception {
        dropInvoicingTables();
        plan("FREE", null);
        assertError(owner(HttpMethod.GET, "/api/v1/invoices", null), 403, "MODULE_NOT_ENTITLED");
        assertError(owner(HttpMethod.POST, "/api/v1/invoices", draft("Awa", List.of())), 403, "MODULE_NOT_ENTITLED");
        assertThat(tables()).isEmpty();
    }

    @Test
    @DisplayName("read-only grace (trial ended 1 day ago): GET 200, POST 403 MODULE_READ_ONLY")
    void readOnlyGrace() throws Exception {
        String id = createDraft(ownerToken);
        plan("PREMIUM_TRIAL", "-1 day");
        data(owner(HttpMethod.GET, "/api/v1/invoices/" + id, null), 200);
        assertError(owner(HttpMethod.POST, "/api/v1/invoices", draft("Awa", List.of())), 403, "MODULE_READ_ONLY");
    }

    @Test
    @DisplayName("permission first: EMPLOYEE cancel with the module DISABLED → FORBIDDEN; deactivation keeps the data")
    void permissionBeforeEntitlementAndDeactivation() throws Exception {
        String id = createDraft(ownerToken);
        moduleRow("invoicing", "DISABLED");
        assertError(call(employeeToken, HttpMethod.POST, "/api/v1/invoices/" + id + "/cancel",
                Map.of("reason", "Erreur")), 403, "FORBIDDEN");
        assertError(owner(HttpMethod.GET, "/api/v1/invoices", null), 403, "MODULE_NOT_ENTITLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".invoices", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("unknown id → 404 INVOICE_NOT_FOUND")
    void unknownId() throws Exception {
        assertError(owner(HttpMethod.GET, "/api/v1/invoices/" + UUID.randomUUID(), null), 404, "INVOICE_NOT_FOUND");
        assertThat(body(owner(HttpMethod.GET, "/api/v1/invoices/" + UUID.randomUUID(), null)))
                .contains("Facture introuvable");
    }

    @Test
    @DisplayName("lazy provisioning: a PREMIUM tenant never provisioned gets its tables on its first call, no row")
    void lazyProvisioning() throws Exception {
        dropInvoicingTables();
        plan("PREMIUM", null);
        assertThat(tables()).isEmpty();
        createDraft(ownerToken);
        assertThat(tables()).containsExactlyElementsOf(TABLES);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND module_id = 'invoicing'", Integer.class, tenantId)).isZero();
    }
}

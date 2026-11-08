package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.module.ModuleRegistry;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.14 — visibility of documents per role, user decision of 2026-11-08 (as Bref): two employees of store A,
 * a manager of A, a manager of B, a viewer of A and the owner, across list (and the owner-only store filter), get,
 * pdf, issue, payment, send, public link, rotation, sync push and pull. Out of scope → 404 {@code INVOICE_NOT_FOUND}.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Visibility of documents per role (Story 20.14, décision utilisateur 2026-11-08)")
class InvoiceVisibilityFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry registry;

    private String schema;
    private UUID tenantId;
    private UUID storeA;
    private UUID storeB;
    private String owner;
    private String employee1;
    private String employee2;
    private String managerA;
    private String managerB;
    private String viewerA;
    private String x1; // employee 1, store A
    private String x2; // employee 2, store A
    private String x3; // manager B, store B
    private Instant clientClock = Instant.now().minusSeconds(600);

    @BeforeEach
    void setUp() throws Exception {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1",
                UUID.class);
        storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, now() + interval '1 minute', now(), true, 'Boutique Bonapriso', 'STORE')", storeB);
        owner = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), schema, "OWNER");
        employee1 = member("EMPLOYEE", storeA);
        employee2 = member("EMPLOYEE", storeA);
        managerA = member("MANAGER", storeA);
        managerB = member("MANAGER", storeB);
        viewerA = member("VIEWER", storeA);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("legalName", "Boutique Awa SARL");
        settings.put("address", "Rue Joss, Akwa, Douala");
        settings.put("vatRegistered", false);
        settings.put("vatRate", 19.25);
        settings.put("pricesIncludeVat", true);
        data(call(owner, HttpMethod.PUT, "/api/v1/invoicing/settings", settings), 200);
        x1 = create(employee1, "Client E1");
        x2 = create(employee2, "Client E2");
        x3 = create(managerB, "Client MB");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        registry.evict(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String member(String role, UUID store) {
        UUID user = UUID.randomUUID();
        if (store != null) {
            jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                    + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", user, store);
        }
        return TestAccess.token(jdbc, jwtTokenProvider, user, schema, role, "ACTIVE", store, false);
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
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

    private void notFound(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(404);
        assertThat(objectMapper.readTree(body(r)).path("domainCode").asText()).isEqualTo("INVOICE_NOT_FOUND");
    }

    private String create(String token, String client) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 1);
        line.put("unitPrice", 10_000);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", client));
        body.put("lines", List.of(line));
        return data(call(token, HttpMethod.POST, "/api/v1/invoices", body), 201).path("id").asText();
    }

    private Set<String> list(String token, String query) throws Exception {
        JsonNode items = data(call(token, HttpMethod.GET, "/api/v1/invoices" + query, null), 200);
        Set<String> ids = new HashSet<>();
        items.forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    private Set<String> pulled(String token) throws Exception {
        Set<String> ids = new HashSet<>();
        String cursor = null;
        do {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull").header("Authorization", "Bearer " + token);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode d = data(mockMvc.perform(request).andReturn(), 200);
            d.path("entities").path("invoices").forEach(n -> ids.add(n.path("id").asText()));
            cursor = d.path("hasMore").asBoolean() ? d.path("nextCursor").asText() : null;
        } while (cursor != null);
        return ids;
    }

    private JsonNode push(String token, String type, String entityId, Map<String, Object> payload) throws Exception {
        clientClock = clientClock.plusSeconds(1);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", entityId);
        op.put("payload", payload);
        op.put("clientTimestamp", clientClock.toString());
        return data(call(token, HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", UUID.randomUUID().toString(), "operations", List.of(op))), 200)
                .path("results").get(0);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list and pull: employee → own documents, manager / viewer → their store, owner → all; the store "
            + "filter is the owner's only")
    void listsAndPull() throws Exception {
        assertThat(list(employee1, "")).containsExactly(x1);
        assertThat(list(employee2, "")).containsExactly(x2);
        assertThat(list(managerA, "")).containsExactlyInAnyOrder(x1, x2);
        assertThat(list(viewerA, "")).containsExactlyInAnyOrder(x1, x2);
        assertThat(list(managerB, "")).containsExactly(x3);
        assertThat(list(owner, "")).containsExactlyInAnyOrder(x1, x2, x3);
        assertThat(list(employee1, "?kind=ALL&paymentState=")).containsExactly(x1);

        assertThat(list(owner, "?storeId=" + storeB)).containsExactly(x3);
        MvcResult filtered = call(managerA, HttpMethod.GET, "/api/v1/invoices?storeId=" + storeA, null);
        assertThat(filtered.getResponse().getStatus()).as(body(filtered)).isEqualTo(403);
        assertThat(objectMapper.readTree(body(filtered)).path("error").asText())
                .isEqualTo("Seul le propriétaire peut filtrer les documents par boutique.");

        assertThat(pulled(employee1)).containsExactly(x1);
        assertThat(pulled(managerA)).containsExactlyInAnyOrder(x1, x2);
        assertThat(pulled(viewerA)).containsExactlyInAnyOrder(x1, x2);
        assertThat(pulled(managerB)).containsExactly(x3);
        assertThat(pulled(owner)).containsExactlyInAnyOrder(x1, x2, x3);
    }

    @Test
    @DisplayName("one document out of scope → 404 INVOICE_NOT_FOUND on get, pdf, issue, payment, send, public link "
            + "and rotation; in scope → allowed")
    void actionsOutOfScope() throws Exception {
        notFound(call(employee2, HttpMethod.GET, "/api/v1/invoices/" + x1, null));
        notFound(call(managerB, HttpMethod.GET, "/api/v1/invoices/" + x1, null));
        data(call(viewerA, HttpMethod.GET, "/api/v1/invoices/" + x1, null), 200);
        data(call(managerA, HttpMethod.GET, "/api/v1/invoices/" + x1, null), 200);

        notFound(call(employee2, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/issue", null));
        notFound(call(managerB, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/issue", null));
        data(call(employee1, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/issue", null), 200);

        notFound(call(employee2, HttpMethod.GET, "/api/v1/invoices/" + x1 + "/pdf", null));
        assertThat(call(employee1, HttpMethod.GET, "/api/v1/invoices/" + x1 + "/pdf", null).getResponse()
                .getStatus()).isEqualTo(200);

        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("paymentId", UUID.randomUUID().toString());
        payment.put("amount", 1_000);
        payment.put("paidAt", java.time.LocalDate.now(java.time.ZoneId.of("Africa/Douala")).toString());
        notFound(call(employee2, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/payments", payment));
        notFound(call(managerB, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/payments", payment));
        assertThat(call(managerA, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/payments", payment).getResponse()
                .getStatus()).isEqualTo(201);

        notFound(call(employee2, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/send",
                Map.of("phone", "+237690000001")));

        notFound(call(employee2, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/public-link", null));
        data(call(employee1, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/public-link", null), 200);
        notFound(call(employee2, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/public-link/rotate", null));
        notFound(call(managerB, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/public-link/rotate", null));
        data(call(employee1, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/public-link/rotate", null), 200);
    }

    @Test
    @DisplayName("sync: an operation on a document out of scope → REJECTED INVOICE_NOT_FOUND, nothing changed; a "
            + "creation reusing its id → REJECTED, never returned as a replay")
    void syncOutOfScope() throws Exception {
        JsonNode issue = push(employee2, "ISSUE_INVOICE", x1, Map.of("invoiceId", x1));
        assertThat(issue.path("status").asText()).as(issue.toString()).isEqualTo("REJECTED");
        assertThat(issue.path("reason").asText()).isEqualTo("INVOICE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".invoices WHERE id = ?::uuid",
                String.class, x1)).isEqualTo("DRAFT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", x1);
        payload.put("client", Map.of("name", "Intrus"));
        payload.put("lines", new ArrayList<>());
        JsonNode create = push(employee2, "CREATE_INVOICE", x1, payload);
        assertThat(create.path("status").asText()).as(create.toString()).isEqualTo("REJECTED");
        assertThat(create.path("reason").asText()).isEqualTo("INVOICE_NOT_FOUND");
    }

    @Test
    @DisplayName("an employee of store A who created nothing lists and pulls no document (the store's documents are "
            + "its colleagues'); the member-without-store refusal is covered by DocumentAccessTest (the security "
            + "filter takes the store from the employee row)")
    void employeeWithoutDocuments() throws Exception {
        String newcomer = member("EMPLOYEE", storeA);
        assertThat(list(newcomer, "?kind=ALL")).isEmpty();
        assertThat(pulled(newcomer)).isEmpty();
    }
}

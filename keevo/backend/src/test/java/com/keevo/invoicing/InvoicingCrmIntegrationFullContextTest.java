package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.3 — optional CRM integration of invoicing on the real HTTP chain: the client picker
 * ({@code GET /api/v1/invoices/clients}) and the CRM link / prefill of a draft's client, with {@code crm} on and off
 * for the tenant (every invoice row of the spec's I/O matrix).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Invoicing — optional CRM integration, full context (Story 14.3)")
class InvoicingCrmIntegrationFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry registry;

    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private UUID mireille;
    private UUID amir;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        ownerToken = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), schema, "OWNER");
        mireille = client("Mireille Ngo", "+237690000011", false);
        amir = client("Amir Bello", "+237690000012", false);
        client("Mirabelle (archivée)", "+237690000013", true);
        client("Paul Etoa", "+237690000014", false);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        registry.evict(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private UUID client(String name, String phone, boolean archived) {
        return jdbc.queryForObject("INSERT INTO \"" + schema + "\".clients (name, phone, archived) VALUES (?, ?, ?) "
                + "RETURNING id", UUID.class, name, phone, archived);
    }

    private void crm(String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, 'crm', ?, "
                + "'FREE') ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status", tenantId, status);
        registry.evict(schema);
    }

    private MvcResult owner(HttpMethod method, String path, Object body) throws Exception {
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

    private static Map<String, Object> draft(UUID clientId, String name, String phone) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", clientId == null ? null : clientId.toString());
        c.put("name", name);
        c.put("phone", phone);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 1);
        line.put("unitPrice", 11_925);
        line.put("vatTreatment", "STANDARD");
        return Map.of("client", c, "lines", List.of(line));
    }

    private int invoiceCount() {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".invoices", Integer.class);
    }

    // ── suggestions ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("crm on: /invoices/clients?search=mir → non-archived matches sorted by name, ≤ limit")
    void suggestions_crmOn() throws Exception {
        JsonNode d = data(owner(HttpMethod.GET, "/api/v1/invoices/clients?search=mir", null), 200);
        assertThat(d.path("crmAvailable").asBoolean()).isTrue();
        assertThat(d.path("clients").findValuesAsText("name")).containsExactly("Amir Bello", "Mireille Ngo");
        assertThat(d.path("clients").get(0).path("id").asText()).isEqualTo(amir.toString());
        assertThat(d.path("clients").get(0).path("phone").asText()).isEqualTo("+237690000012");

        JsonNode all = data(owner(HttpMethod.GET, "/api/v1/invoices/clients?limit=2", null), 200);
        assertThat(all.path("clients").findValuesAsText("name")).containsExactly("Amir Bello", "Mireille Ngo");
    }

    @Test
    @DisplayName("crm DISABLED: /invoices/clients → 200 {crmAvailable:false, clients:[]}")
    void suggestions_crmOff() throws Exception {
        crm("DISABLED");
        JsonNode d = data(owner(HttpMethod.GET, "/api/v1/invoices/clients?search=mir", null), 200);
        assertThat(d.path("crmAvailable").asBoolean()).isFalse();
        assertThat(d.path("clients").isArray()).isTrue();
        assertThat(d.path("clients")).isEmpty();
    }

    // ── draft client resolution ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CRM client, blank name → name and phone copied from the CRM, link kept (create and update)")
    void draftFromCrmClient_prefilled() throws Exception {
        JsonNode d = data(owner(HttpMethod.POST, "/api/v1/invoices", draft(mireille, "", null)), 201);
        assertThat(d.path("client").path("id").asText()).isEqualTo(mireille.toString());
        assertThat(d.path("client").path("name").asText()).isEqualTo("Mireille Ngo");
        assertThat(d.path("client").path("phone").asText()).isEqualTo("+237690000011");
        String id = d.path("id").asText();
        assertThat(jdbc.queryForObject("SELECT client_id FROM \"" + schema + "\".invoices WHERE id = ?::uuid",
                UUID.class, id)).isEqualTo(mireille);

        JsonNode u = data(owner(HttpMethod.PUT, "/api/v1/invoices/" + id, draft(amir, " ", null)), 200);
        assertThat(u.path("client").path("id").asText()).isEqualTo(amir.toString());
        assertThat(u.path("client").path("name").asText()).isEqualTo("Amir Bello");
    }

    @Test
    @DisplayName("CRM client with a typed name / phone → typed values kept, link kept")
    void draftFromCrmClient_typedValuesWin() throws Exception {
        JsonNode d = data(owner(HttpMethod.POST, "/api/v1/invoices", draft(mireille, "SARL X", "+237677000000")),
                201);
        assertThat(d.path("client").path("id").asText()).isEqualTo(mireille.toString());
        assertThat(d.path("client").path("name").asText()).isEqualTo("SARL X");
        assertThat(d.path("client").path("phone").asText()).isEqualTo("+237677000000");
    }

    @Test
    @DisplayName("unknown client id with crm on → 404 CLIENT_NOT_FOUND « Client introuvable », nothing written")
    void unknownCrmClient_404() throws Exception {
        MvcResult r = owner(HttpMethod.POST, "/api/v1/invoices", draft(UUID.randomUUID(), "Paul", null));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(404);
        assertThat(body(r)).contains("\"domainCode\":\"CLIENT_NOT_FOUND\"").contains("Client introuvable");
        assertThat(invoiceCount()).isZero();
    }

    @Test
    @DisplayName("client id with crm DISABLED → id dropped, typed name kept, no error")
    void crmOff_idDropped() throws Exception {
        crm("DISABLED");
        JsonNode d = data(owner(HttpMethod.POST, "/api/v1/invoices", draft(UUID.randomUUID(), "Paul", null)), 201);
        assertThat(d.path("client").path("id").isNull()).isTrue();
        assertThat(d.path("client").path("name").asText()).isEqualTo("Paul");

        JsonNode free = data(owner(HttpMethod.POST, "/api/v1/invoices", draft(null, "Awa", null)), 201);
        assertThat(free.path("client").path("name").asText()).isEqualTo("Awa");
    }

    @Test
    @DisplayName("draft linked to a CRM client, crm then DISABLED: update keeps the stored link, a new id is dropped")
    void crmOff_updateKeepsStoredLink() throws Exception {
        String id = data(owner(HttpMethod.POST, "/api/v1/invoices", draft(mireille, "", null)), 201)
                .path("id").asText();
        crm("DISABLED");
        JsonNode kept = data(owner(HttpMethod.PUT, "/api/v1/invoices/" + id, draft(mireille, "Mireille N.", null)),
                200);
        assertThat(kept.path("client").path("id").asText()).isEqualTo(mireille.toString());
        assertThat(kept.path("client").path("name").asText()).isEqualTo("Mireille N.");
        assertThat(jdbc.queryForObject("SELECT client_id FROM \"" + schema + "\".invoices WHERE id = ?::uuid",
                UUID.class, id)).isEqualTo(mireille);

        JsonNode other = data(owner(HttpMethod.PUT, "/api/v1/invoices/" + id, draft(amir, "Amir", null)), 200);
        assertThat(other.path("client").path("id").isNull()).isTrue();
    }

    @Test
    @DisplayName("no name and no id → 422 VALIDATION_ERROR « Le nom du client est obligatoire »")
    void noName_validationError() throws Exception {
        MvcResult r = owner(HttpMethod.POST, "/api/v1/invoices", draft(null, "", null));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(422);
        assertThat(body(r)).contains("\"domainCode\":\"VALIDATION_ERROR\"")
                .contains("Le nom du client est obligatoire");
        assertThat(invoiceCount()).isZero();

        crm("DISABLED");
        MvcResult off = owner(HttpMethod.POST, "/api/v1/invoices", draft(mireille, null, null));
        assertThat(off.getResponse().getStatus()).as(body(off)).isEqualTo(422);
        assertThat(body(off)).contains("Le nom du client est obligatoire");
    }
}

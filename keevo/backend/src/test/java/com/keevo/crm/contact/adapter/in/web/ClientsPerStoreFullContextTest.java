package com.keevo.crm.contact.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.2 — clients per store (ADR-08) on the real chain (production security filter, real use cases, store
 * module API, PostgreSQL): every row of the I/O matrix over REST, the scoped sync pull (sharing off / on, the stores
 * delta carrying {@code shareClients}), the duplicate warning over sync, consent and opt-out, a V1 body.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("20.2 — clients par établissement, doublons, consentement (full context)")
class ClientsPerStoreFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JwtTokenProvider jwt;
    @Autowired com.keevo.crm.api.ClientQueryPort clientQuery;
    @Autowired com.keevo.crm.contact.domain.port.out.ClientRepository clientRepository;

    private String schema;
    private UUID storeA;
    private UUID storeB;
    private UUID storeC;
    private String owner;
    private String employeeA;
    private String employeeC;
    private String viewerA;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1",
                UUID.class);
        storeB = store("Boutique B", 60);
        storeC = store("Boutique C", 120);
        owner = TestAccess.token(jdbc, jwt, UUID.randomUUID(), schema, "OWNER");
        employeeA = memberToken(storeA, "EMPLOYEE");
        employeeC = memberToken(storeC, "EMPLOYEE");
        viewerA = memberToken(storeA, "VIEWER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private UUID store(String name, int secondsAfter) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now().plusSeconds(secondsAfter));
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type, is_active, created_at, updated_at) "
                + "VALUES (?, ?, 'STORE', true, ?, ?)", id, name, at, at);
        return id;
    }

    private String memberToken(UUID storeId, String role) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, 'Awa', 'Ndiaye', false)",
                UUID.randomUUID(), userId, storeId);
        return TestAccess.token(jdbc, jwt, userId, schema, role);
    }

    private UUID client(String name, String phone, UUID storeId, boolean archived) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".clients (id, name, phone, phone_normalized, store_id, archived, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                id, name, phone, phone, storeId, archived);
        return id;
    }

    private void share(UUID storeId, boolean share) {
        jdbc.update("UPDATE \"" + schema + "\".stores SET share_clients = ?, updated_at = now() WHERE id = ?",
                share, storeId);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult call(String token, HttpMethod method, String url, Object payload) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, url)
                .header("Authorization", "Bearer " + token);
        if (payload != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        return objectMapper.readTree(body(r));
    }

    private List<String> listNames(String token, String query) throws Exception {
        JsonNode data = json(call(token, HttpMethod.GET, "/api/v1/clients" + query, null), 200).path("data");
        List<String> names = new ArrayList<>();
        data.forEach(c -> names.add(c.path("name").asText()));
        return names;
    }

    private Map<String, Object> createBody(String name, String phone, UUID storeId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("phone", phone);
        if (storeId != null) b.put("storeId", storeId.toString());
        return b;
    }

    private JsonNode pull(String token) throws Exception {
        JsonNode d = json(call(token, HttpMethod.GET, "/api/v1/sync/pull", null), 200).path("data");
        return d.path("entities");
    }

    private List<String> pulledClientNames(String token) throws Exception {
        List<String> names = new ArrayList<>();
        pull(token).path("clients").forEach(c -> names.add(c.path("name").asText()));
        return names;
    }

    private JsonNode push(String token, String type, Map<String, Object> payload) throws Exception {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", String.valueOf(payload.getOrDefault("id", payload.get("clientId"))));
        op.put("payload", payload);
        op.put("clientTimestamp", Instant.now().minusSeconds(5).toString());
        return json(call(token, HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", UUID.randomUUID().toString(), "operations", List.of(op))), 200)
                .path("data").path("results").get(0);
    }

    // ── creation and store assignment ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("employee creates in its store; forging another store → 403; owner → oldest store / given / unknown 400")
    void storeAssignment() throws Exception {
        JsonNode created = json(call(employeeA, HttpMethod.POST, "/api/v1/clients",
                createBody("Awa", "699000001", null)), 201).path("data");
        assertThat(created.path("storeId").asText()).isEqualTo(storeA.toString());
        assertThat(created.path("phone").asText()).isEqualTo("+237699000001");

        MvcResult forged = call(employeeA, HttpMethod.POST, "/api/v1/clients", createBody("X", "699000002", storeB));
        assertThat(forged.getResponse().getStatus()).as(body(forged)).isEqualTo(403);
        assertThat(json(forged, 403).path("domainCode").asText()).isEqualTo("FORBIDDEN");

        JsonNode byOwner = json(call(owner, HttpMethod.POST, "/api/v1/clients",
                createBody("Paul", "699000003", null)), 201).path("data");
        assertThat(byOwner.path("storeId").asText()).isEqualTo(storeA.toString());
        JsonNode inB = json(call(owner, HttpMethod.POST, "/api/v1/clients",
                createBody("Bello", "699000004", storeB)), 201).path("data");
        assertThat(inB.path("storeId").asText()).isEqualTo(storeB.toString());

        MvcResult unknown = call(owner, HttpMethod.POST, "/api/v1/clients",
                createBody("Z", "699000005", UUID.randomUUID()));
        JsonNode err = json(unknown, 400);
        assertThat(err.path("domainCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".clients WHERE name = 'Z'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("V1 body (name, phone, email, notes) → 201, store assigned, response is a superset")
    void v1Body() throws Exception {
        JsonNode d = json(call(employeeA, HttpMethod.POST, "/api/v1/clients", Map.of("name", "Awa",
                "phone", "+237699000010", "email", "a@b.cm", "notes", "VIP")), 201).path("data");
        for (String key : List.of("id", "name", "phone", "email", "notes", "archived", "createdAt", "updatedAt",
                "clientType", "niu", "rccm", "address", "contactName", "website", "birthDate", "storeId",
                "phoneNormalized", "marketingConsentAt", "marketingOptOutAt", "marketingConsentBy",
                "marketingEligible")) {
            assertThat(d.has(key)).as(key).isTrue();
        }
        assertThat(d.path("marketingEligible").asBoolean()).isFalse();
        assertThat(d.path("storeId").asText()).isEqualTo(storeA.toString());
    }

    @Test
    @DisplayName("Bref fields on create and PATCH (absent keeps, blank clears), bad values → 422 in French")
    void brefFields() throws Exception {
        Map<String, Object> b = createBody("SARL Awa", "699000020", null);
        b.putAll(Map.of("clientType", "company", "niu", "M0123", "rccm", "RC/DLA/2020/B/1", "address", "Akwa",
                "contactName", "Awa", "website", "https://awa.cm", "birthDate", "1990-05-04"));
        JsonNode d = json(call(owner, HttpMethod.POST, "/api/v1/clients", b), 201).path("data");
        assertThat(d.path("clientType").asText()).isEqualTo("COMPANY");
        assertThat(d.path("birthDate").asText()).isEqualTo("1990-05-04");
        String id = d.path("id").asText();

        JsonNode p = json(call(owner, HttpMethod.PATCH, "/api/v1/clients/" + id,
                Map.of("niu", "", "address", "Bonapriso")), 200).path("data");
        assertThat(p.path("niu").isNull()).isTrue();
        assertThat(p.path("address").asText()).isEqualTo("Bonapriso");
        assertThat(p.path("rccm").asText()).isEqualTo("RC/DLA/2020/B/1");

        JsonNode bad = json(call(owner, HttpMethod.PATCH, "/api/v1/clients/" + id,
                Map.of("birthDate", "2999-01-01")), 422);
        assertThat(bad.path("error").asText()).contains("futur");
    }

    // ── visibility ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("lists: employee A sees A + legacy; with A,B sharing also B (never C); employee C sees C + legacy; owner all / filtered")
    void visibility() throws Exception {
        client("Client A", "+237699100001", storeA, false);
        client("Client B", "+237699100002", storeB, false);
        client("Client C", "+237699100003", storeC, false);
        client("Legacy", "+237699100004", null, false);

        assertThat(listNames(employeeA, "")).containsExactlyInAnyOrder("Client A", "Legacy");

        share(storeA, true);
        share(storeB, true);
        assertThat(listNames(employeeA, "")).containsExactlyInAnyOrder("Client A", "Client B", "Legacy");
        assertThat(listNames(employeeA, "?search=Client")).containsExactlyInAnyOrder("Client A", "Client B");
        assertThat(listNames(employeeC, "")).containsExactlyInAnyOrder("Client C", "Legacy");
        assertThat(listNames(owner, "")).containsExactlyInAnyOrder("Client A", "Client B", "Client C", "Legacy");
        assertThat(listNames(owner, "?storeId=" + storeC)).containsExactlyInAnyOrder("Client C", "Legacy");
        assertThat(listNames(owner, "?storeId=" + storeA))
                .containsExactlyInAnyOrder("Client A", "Client B", "Legacy");
        // a member's ?storeId is ignored
        assertThat(listNames(employeeC, "?storeId=" + storeA)).containsExactlyInAnyOrder("Client C", "Legacy");
    }

    @Test
    @DisplayName("a member reading / patching / archiving / consenting a client of another store → 404 CLIENT_NOT_FOUND")
    void outOfSightIsNotFound() throws Exception {
        UUID ofC = client("Client C", "+237699200001", storeC, false);
        String url = "/api/v1/clients/" + ofC;

        assertThat(json(call(employeeA, HttpMethod.GET, url, null), 404).path("domainCode").asText())
                .isEqualTo("CLIENT_NOT_FOUND");
        assertThat(json(call(employeeA, HttpMethod.PATCH, url, Map.of("name", "X")), 404)
                .path("domainCode").asText()).isEqualTo("CLIENT_NOT_FOUND");
        assertThat(call(employeeA, HttpMethod.DELETE, url, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(employeeA, HttpMethod.PUT, url + "/marketing-consent", Map.of("consent", true))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForMap("SELECT name, archived, marketing_consent_at FROM \"" + schema
                + "\".clients WHERE id = ?", ofC)).containsEntry("name", "Client C").containsEntry("archived", false)
                .containsEntry("marketing_consent_at", null);
        // the owner sees it; only the owner moves it
        json(call(owner, HttpMethod.GET, url, null), 200);
        UUID ofA = client("Client A", "+237699200002", storeA, false);
        assertThat(call(employeeA, HttpMethod.PATCH, "/api/v1/clients/" + ofA,
                Map.of("storeId", storeB.toString())).getResponse().getStatus()).isEqualTo(403);
        assertThat(json(call(owner, HttpMethod.PATCH, "/api/v1/clients/" + ofA,
                Map.of("storeId", storeB.toString())), 200).path("data").path("storeId").asText())
                .isEqualTo(storeB.toString());
    }

    // ── duplicates ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("same phone in the same store → 201 + DUPLICATE_PHONE; other store / legacy / archived → no warning; pre-check")
    void duplicates() throws Exception {
        UUID existing = client("Awa", "+237699001122", storeA, false);
        client("Awa B", "+237699001133", storeB, false);
        client("Awa legacy", "+237699001144", null, false);
        client("Awa archived", "+237699001155", storeA, true);

        JsonNode dup = json(call(employeeA, HttpMethod.POST, "/api/v1/clients",
                createBody("Awa 2", "6 99 00 11 22", null)), 201);
        JsonNode warning = dup.path("meta").path("warnings").get(0);
        assertThat(warning.path("code").asText()).isEqualTo("DUPLICATE_PHONE");
        assertThat(warning.path("existingClient").path("id").asText()).isEqualTo(existing.toString());
        assertThat(warning.path("existingClient").path("storeId").asText()).isEqualTo(storeA.toString());
        assertThat(warning.path("existingClient").path("phone").asText()).isEqualTo("+237699001122");

        for (String phone : List.of("699001133", "699001144", "699001155")) {
            JsonNode ok = json(call(employeeA, HttpMethod.POST, "/api/v1/clients", createBody("N", phone, null)), 201);
            assertThat(ok.has("meta")).as(phone).isFalse();
        }

        JsonNode pre = json(call(employeeA, HttpMethod.GET, "/api/v1/clients/duplicates?phone=699001122&storeId="
                + storeB, null), 200).path("data");
        assertThat(pre).hasSize(2); // forced to store A: the existing client + the one just created
        JsonNode none = json(call(owner, HttpMethod.GET, "/api/v1/clients/duplicates?phone=699001122&storeId="
                + storeB, null), 200).path("data");
        assertThat(none).isEmpty();

        JsonNode sync = push(employeeA, "CREATE_CLIENT", new LinkedHashMap<>(Map.of(
                "id", UUID.randomUUID().toString(), "name", "Awa 3", "phone", "+237699001122")));
        assertThat(sync.path("status").asText()).as(sync.toString()).isEqualTo("APPLIED");
        assertThat(sync.path("reason").asText()).isEqualTo("DUPLICATE_PHONE");
        assertThat(sync.path("conflictData").path("existingClientId").asText()).isEqualTo(existing.toString());
    }

    // ── consent ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("consent given then withdrawn (audited); the client's STOP wins; a VIEWER never writes")
    void consent() throws Exception {
        UUID id = client("Awa", "+237699300001", storeA, false);
        String url = "/api/v1/clients/" + id + "/marketing-consent";

        JsonNode given = json(call(employeeA, HttpMethod.PUT, url, Map.of("consent", true)), 200).path("data");
        assertThat(given.path("marketingEligible").asBoolean()).isTrue();
        assertThat(given.path("marketingConsentAt").isNull()).isFalse();
        JsonNode withdrawn = json(call(employeeA, HttpMethod.PUT, url, Map.of("consent", false)), 200).path("data");
        assertThat(withdrawn.path("marketingEligible").asBoolean()).isFalse();
        assertThat(withdrawn.path("marketingConsentAt").isNull()).isTrue();
        assertThat(withdrawn.path("marketingConsentBy").isNull()).isTrue();
        assertThat(jdbc.queryForList("SELECT action FROM \"" + schema + "\".audit_log WHERE entity_id = ? "
                + "ORDER BY occurred_at", String.class, id))
                .containsExactly("CLIENT_MARKETING_CONSENT_GIVEN", "CLIENT_MARKETING_CONSENT_WITHDRAWN");

        jdbc.update("UPDATE \"" + schema + "\".clients SET marketing_opt_out_at = now() WHERE id = ?", id);
        JsonNode afterStop = json(call(owner, HttpMethod.PUT, url, Map.of("consent", true)), 200).path("data");
        assertThat(afterStop.path("marketingConsentAt").isNull()).isFalse();
        assertThat(afterStop.path("marketingOptOutAt").isNull()).isFalse();
        assertThat(afterStop.path("marketingEligible").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT marketing_opt_out_at IS NOT NULL FROM \"" + schema
                + "\".clients WHERE id = ?", Boolean.class, id)).isTrue();

        assertThat(call(viewerA, HttpMethod.PUT, url, Map.of("consent", false)).getResponse().getStatus())
                .isEqualTo(403);
        JsonNode viaSync = push(employeeA, "SET_CLIENT_MARKETING_CONSENT",
                new LinkedHashMap<>(Map.of("clientId", id.toString(), "consent", false)));
        assertThat(viaSync.path("status").asText()).as(viaSync.toString()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT marketing_consent_at IS NULL FROM \"" + schema
                + "\".clients WHERE id = ?", Boolean.class, id)).isTrue();
    }

    // ── sync pull ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("restricted pull: exactly the visible clients (sharing off / on); stores carry shareClients; owner gets all")
    void scopedPull() throws Exception {
        client("Client A", "+237699400001", storeA, false);
        client("Client B", "+237699400002", storeB, false);
        client("Client C", "+237699400003", storeC, false);
        client("Legacy", "+237699400004", null, false);

        assertThat(pulledClientNames(employeeA)).containsExactlyInAnyOrder("Client A", "Legacy");

        JsonNode patched = json(call(owner, HttpMethod.PATCH, "/api/v1/stores/" + storeA,
                Map.of("name", "Boutique A", "shareClients", true)), 200).path("data");
        assertThat(patched.path("shareClients").asBoolean()).isTrue();
        share(storeB, true);

        assertThat(pulledClientNames(employeeA)).containsExactlyInAnyOrder("Client A", "Client B", "Legacy");
        assertThat(pulledClientNames(employeeC)).containsExactlyInAnyOrder("Client C", "Legacy");
        assertThat(pulledClientNames(owner)).containsExactlyInAnyOrder("Client A", "Client B", "Client C", "Legacy");

        JsonNode stores = pull(employeeA).path("stores");
        Map<String, Boolean> sharing = new LinkedHashMap<>();
        stores.forEach(s -> sharing.put(s.path("id").asText(), s.path("shareClients").asBoolean()));
        assertThat(sharing).containsEntry(storeA.toString(), true).containsEntry(storeB.toString(), true)
                .containsEntry(storeC.toString(), false);
        JsonNode pulledA = null;
        for (JsonNode c : pull(employeeA).path("clients")) {
            if (c.path("name").asText().equals("Client A")) pulledA = c;
        }
        assertThat(pulledA).isNotNull();
        assertThat(pulledA.path("storeId").asText()).isEqualTo(storeA.toString());
        assertThat(pulledA.has("marketingEligible")).isTrue();
    }

    // ── review follow-ups ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ClientQueryPort.marketingEligible: only consented, not opted out, not archived (one array query)")
    void marketingEligibleParity() {
        UUID consented = client("C1", "+237699500001", storeA, false);
        UUID optedOut = client("C2", "+237699500002", storeA, false);
        UUID archived = client("C3", "+237699500003", storeA, true);
        UUID none = client("C4", "+237699500004", storeA, false);
        jdbc.update("UPDATE \"" + schema + "\".clients SET marketing_consent_at = now() WHERE id IN (?, ?, ?)",
                consented, optedOut, archived);
        jdbc.update("UPDATE \"" + schema + "\".clients SET marketing_opt_out_at = now() WHERE id = ?", optedOut);

        TenantContext.setCurrentTenant(schema);
        assertThat(clientQuery.marketingEligible(schema, List.of(consented, optedOut, archived, none,
                UUID.randomUUID()))).containsExactly(consented);
        assertThat(clientQuery.marketingEligible(schema, List.of())).isEmpty();
    }

    @Test
    @DisplayName("a generic save holding a stale consent never reverts a withdrawal (consent columns not updatable)")
    void noLostUpdateOfAWithdrawal() throws Exception {
        UUID id = client("Awa", "+237699600001", storeA, false);
        String url = "/api/v1/clients/" + id + "/marketing-consent";
        json(call(owner, HttpMethod.PUT, url, Map.of("consent", true)), 200);
        TenantContext.setCurrentTenant(schema);
        var stale = clientRepository.findById(id).orElseThrow(); // read with the consent given
        TenantContext.clear();

        json(call(employeeA, HttpMethod.PUT, url, Map.of("consent", false)), 200);
        TenantContext.setCurrentTenant(schema);
        clientRepository.save(new com.keevo.crm.contact.domain.entity.Client(stale.id(), "Awa renommée",
                stale.phone(), stale.email(), stale.notes(), false, stale.createdAt(), Instant.now(),
                stale.profile(), stale.storeId(), stale.consent()));
        TenantContext.clear();

        Map<String, Object> row = jdbc.queryForMap("SELECT name, marketing_consent_at, marketing_consent_by FROM \""
                + schema + "\".clients WHERE id = ?", id);
        assertThat(row).containsEntry("name", "Awa renommée").containsEntry("marketing_consent_at", null)
                .containsEntry("marketing_consent_by", null);
        // a PATCH after a withdrawal keeps it withdrawn as well
        json(call(owner, HttpMethod.PATCH, "/api/v1/clients/" + id, Map.of("notes", "VIP")), 200);
        assertThat(jdbc.queryForObject("SELECT marketing_consent_at IS NULL FROM \"" + schema
                + "\".clients WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("owner list filtered on an unknown or inactive store → 400 VALIDATION_ERROR")
    void listUnknownStoreIs400() throws Exception {
        assertThat(json(call(owner, HttpMethod.GET, "/api/v1/clients?storeId=" + UUID.randomUUID(), null), 400)
                .path("domainCode").asText()).isEqualTo("VALIDATION_ERROR");
        jdbc.update("UPDATE \"" + schema + "\".stores SET is_active = false WHERE id = ?", storeC);
        json(call(owner, HttpMethod.GET, "/api/v1/clients?storeId=" + storeC, null), 400);
    }

    @Test
    @DisplayName("a member of a deactivated store cannot create a client (403)")
    void memberOfInactiveStoreCannotCreate() throws Exception {
        jdbc.update("UPDATE \"" + schema + "\".stores SET is_active = false WHERE id = ?", storeC);
        assertThat(call(employeeC, HttpMethod.POST, "/api/v1/clients", createBody("X", "699700001", null))
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("the owner moving a client to another store is audited with the store names, no ids")
    void moveIsAudited() throws Exception {
        UUID id = client("Awa", "+237699800001", storeA, false);
        json(call(owner, HttpMethod.PATCH, "/api/v1/clients/" + id, Map.of("storeId", storeB.toString())), 200);

        Map<String, Object> audit = jdbc.queryForMap("SELECT value_before, value_after, entity_label FROM \""
                + schema + "\".audit_log WHERE entity_id = ? AND action = 'CLIENT_STORE_CHANGED'", id);
        String storeAName = jdbc.queryForObject("SELECT name FROM \"" + schema + "\".stores WHERE id = ?",
                String.class, storeA);
        assertThat((String) audit.get("value_before")).contains(storeAName).doesNotContain(storeA.toString());
        assertThat((String) audit.get("value_after")).contains("Boutique B").doesNotContain(storeB.toString());
        assertThat(audit.get("entity_label")).isEqualTo("Awa");
    }

    private JsonNode devicePull(String token, String deviceId, String since) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get("/api/v1/sync/pull")
                .header("Authorization", "Bearer " + token).header("X-Device-Id", deviceId);
        if (since != null) request.param("since", since);
        return json(mockMvc.perform(request).andReturn(), 200).path("data");
    }

    @Test
    @DisplayName("sharing turned on → the device's next incremental pull re-seeds with the other store's clients; unchanged → incremental")
    void sharingChangeReseedsTheDevice() throws Exception {
        client("Client A", "+237699900001", storeA, false);
        client("Client B", "+237699900002", storeB, false);
        // old rows: an incremental pull (30 s margin) would never send them again
        jdbc.update("UPDATE \"" + schema + "\".clients SET updated_at = now() - interval '1 day'");
        String device = UUID.randomUUID().toString();

        JsonNode first = devicePull(employeeA, device, null);
        String since = first.path("serverTimestamp").asText();
        JsonNode unchanged = devicePull(employeeA, device, since);
        assertThat(unchanged.path("reseed").asBoolean()).isFalse();
        assertThat(unchanged.path("entities").path("clients")).isEmpty();

        share(storeA, true);
        share(storeB, true);
        JsonNode reseeded = devicePull(employeeA, device, unchanged.path("serverTimestamp").asText());
        assertThat(reseeded.path("reseed").asBoolean()).isTrue();
        List<String> names = new ArrayList<>();
        reseeded.path("entities").path("clients").forEach(c -> names.add(c.path("name").asText()));
        assertThat(names).contains("Client A", "Client B");
        assertThat(jdbc.queryForObject("SELECT last_scope_client_stores FROM public.user_sync_state "
                + "WHERE device_id = ?", String.class, device)).isNotBlank();

        JsonNode after = devicePull(employeeA, device, reseeded.path("serverTimestamp").asText());
        assertThat(after.path("reseed").asBoolean()).isFalse();
    }
}

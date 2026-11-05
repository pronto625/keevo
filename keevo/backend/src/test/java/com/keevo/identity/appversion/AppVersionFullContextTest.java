package com.keevo.identity.appversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Story 19.10 — on the real HTTP chain and PostgreSQL (V51): the public version check (Keevo and Bref RN shapes, raw
 * body, no JWT), the super-admin edit (201 / 200 / 204 / 403 / 404 / 422, audit columns) and the legacy-client guard
 * (V1 login without X-App-Version hides MANAGER / VIEWER, select-tenant and refresh refused APP_UPDATE_REQUIRED, the
 * header restores everything). Every test removes the rows it wrote.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("19.10 — app versions and legacy-client guard (full context)")
class AppVersionFullContextTest extends FullContextIntegrationTest {

    private static final String PASSWORD = "MonMotDePasse2026";
    private static final String TEST_IDENTITY = "test-app-1910";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JwtTokenProvider jwt;
    @Autowired PasswordEncoder passwordEncoder;

    private final List<Tenant> tenants = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();
    private final UUID adminId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private Tenant tenantA;
    private Tenant tenantB;
    private String adminToken;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        tenantA = tx.execute(s -> tenantFactory.create());
        tenantB = tx.execute(s -> tenantFactory.create());
        tenants.add(tenantA);
        tenants.add(tenantB);
        adminToken = TestAccess.token(jdbc, jwt, adminId, "public", "SUPER_ADMIN");
        ownerToken = TestAccess.token(jdbc, jwt, ownerId, tenantA.getSchemaName(), "OWNER");
        users.add(adminId);
        users.add(ownerId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM public.app_versions WHERE app_identity IN ('bref', 'keevo', ?)", TEST_IDENTITY);
        for (UUID id : users) {
            jdbc.update("DELETE FROM public.refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", id);
            jdbc.update("DELETE FROM public.users WHERE id = ?", id);
        }
        for (Tenant t : tenants) {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + t.getSchemaName() + "\" CASCADE"); // test database only
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE tenant_id = ?", t.getId());
            jdbc.update("DELETE FROM public.refresh_tokens WHERE tenant_id = ?", t.getSchemaName());
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", t.getId());
            jdbc.update("DELETE FROM public.tenant_module_activation WHERE tenant_id = ?", t.getId());
            jdbc.update("DELETE FROM public.tenants WHERE id = ?", t.getId());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MvcResult send(MockHttpServletRequestBuilder request, String token, Object body, String appVersion)
            throws Exception {
        request.contentType(MediaType.APPLICATION_JSON);
        if (body != null) request.content(objectMapper.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (appVersion != null) request.header("X-App-Version", appVersion);
        return mockMvc.perform(request).andReturn();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** GET /app-version/check with the decoded {@code a=b&c=d} parameters (no URI-template re-encoding). */
    private MvcResult checkRaw(String query) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/app-version/check");
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            request.param(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode check(String query) throws Exception {
        MvcResult r = checkRaw(query);
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private static Map<String, Object> versions(String min, String latest, String url, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("minVersion", min);
        body.put("latestVersion", latest);
        body.put("storeUrl", url);
        body.put("message", message);
        return body;
    }

    /** A phone account with password, member of each tenant with the given role. */
    private String person(Map<Tenant, String> roles) {
        String digits = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        String phone = "+237699" + digits.substring(digits.length() - 6);
        UUID id = UUID.randomUUID();
        users.add(id);
        jdbc.update("INSERT INTO public.users (id, phone_number, password_hash, role, is_active, failed_attempts, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'OWNER', true, 0, now(), now())",
                id, phone, passwordEncoder.encode(PASSWORD));
        roles.forEach((tenant, role) -> TestAccess.grant(jdbc, id, tenant.getSchemaName(), role));
        return phone;
    }

    private MvcResult login(String phone, String appVersion) throws Exception {
        return send(post("/api/v1/auth/login"), null, Map.of("phoneNumber", phone, "password", PASSWORD),
                appVersion);
    }

    private static List<String> codes(JsonNode login) {
        List<String> out = new ArrayList<>();
        login.get("memberships").forEach(m -> out.add(m.get("tenantCode").asText()));
        return out;
    }

    // ── public check ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("check: forced / optional / up to date / no row, raw body in Keevo and Bref RN shapes, no JWT")
    void checkMatrix() throws Exception {
        MvcResult saved = send(put("/api/v1/admin/app-versions/bref/android"), adminToken,
                versions("2.1.0", "2.2.0", "https://play.google.com/store/apps/details?id=test", "Mise à jour"),
                null);
        assertThat(status(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(201);

        JsonNode forced = check("platform=android&version=2.0.0"); // Bref RN: no identity
        assertThat(forced.has("data")).isFalse();
        assertThat(forced.get("updateRequired").asBoolean()).isTrue();
        assertThat(forced.get("isForced").asBoolean()).isTrue();
        assertThat(forced.get("updateAvailable").asBoolean()).isTrue();
        assertThat(forced.get("hasUpdate").asBoolean()).isTrue();
        assertThat(forced.get("latestVersion").asText()).isEqualTo("2.2.0");
        assertThat(forced.get("storeUrl").asText()).isEqualTo("https://play.google.com/store/apps/details?id=test");
        assertThat(forced.get("message").asText()).isEqualTo("Mise à jour");
        assertThat(forced.get("changelog").asText()).isEqualTo("Mise à jour");

        JsonNode optional = check("identity=bref&platform=ANDROID&version=2.1.5");
        assertThat(optional.get("updateRequired").asBoolean()).isFalse();
        assertThat(optional.get("updateAvailable").asBoolean()).isTrue();

        JsonNode upToDate = check("platform=android&version=2.2.0+80");
        assertThat(upToDate.get("updateRequired").asBoolean()).isFalse();
        assertThat(upToDate.get("updateAvailable").asBoolean()).isFalse();
        assertThat(upToDate.get("isForced").asBoolean()).isFalse();
        assertThat(upToDate.get("hasUpdate").asBoolean()).isFalse();

        JsonNode none = check("identity=keevo&platform=ios&version=1.0");
        assertThat(none.get("updateRequired").asBoolean()).isFalse();
        assertThat(none.get("updateAvailable").asBoolean()).isFalse();
        assertThat(none.get("latestVersion").asText()).isEqualTo("1.0");
        assertThat(none.get("storeUrl").isNull()).isTrue();
        assertThat(none.get("message").isNull()).isTrue();
        assertThat(none.get("changelog").isNull()).isTrue();

        for (String bad : new String[]{"platform=windows&version=1.0", "platform=android&version=abc",
                "platform=android"}) {
            MvcResult r = checkRaw(bad);
            assertThat(status(r)).as(bad).isEqualTo(422);
            assertThat(json(r).get("domainCode").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(r).get("error").asText()).isNotBlank().doesNotContain("Exception");
        }
    }

    // ── super-admin edit ─────────────────────────────────────────────────────

    @Test
    @DisplayName("admin: create 201, replace 200, list, audit columns, validation 422, delete 204 then 404, OWNER 403")
    void adminCrud() throws Exception {
        String path = "/api/v1/admin/app-versions/" + TEST_IDENTITY + "/ios";
        assertThat(status(send(put(path), adminToken, versions("1.0", null, null, null), null))).isEqualTo(201);
        MvcResult replaced = send(put(path), adminToken,
                versions("1.1.0", "1.2.0", "https://apps.apple.com/app/id1", "Nouvelle version"), null);
        assertThat(status(replaced)).isEqualTo(200);
        assertThat(json(replaced).at("/data/minVersion").asText()).isEqualTo("1.1.0");
        assertThat(jdbc.queryForObject("SELECT updated_by FROM public.app_versions WHERE app_identity = ? "
                + "AND platform = 'ios'", UUID.class, TEST_IDENTITY)).isEqualTo(adminId);

        MvcResult list = send(get("/api/v1/admin/app-versions"), adminToken, null, null);
        assertThat(status(list)).isEqualTo(200);
        List<String> identities = new ArrayList<>();
        json(list).get("data").forEach(n -> identities.add(n.get("identity").asText() + "/"
                + n.get("platform").asText()));
        assertThat(identities).contains(TEST_IDENTITY + "/ios");

        MvcResult invalid = send(put(path), adminToken, versions("2.1.0", "2.0.0", null, null), null);
        assertThat(status(invalid)).isEqualTo(422);
        assertThat(json(invalid).get("domainCode").asText()).isEqualTo("APP_VERSION_INVALID");
        assertThat(json(invalid).get("error").asText()).isEqualTo("La version minimale dépasse la dernière version");

        Map<String, Object> unknownKey = versions(null, null, null, null);
        unknownKey.put("bundleId", "x");
        assertThat(status(send(put(path), adminToken, unknownKey, null))).isEqualTo(422);
        assertThat(status(send(put(path), adminToken, Map.of("minVersion", "1.0"), null))).isEqualTo(422);
        Map<String, Object> numeric = versions(null, null, null, null);
        numeric.put("minVersion", 2);
        MvcResult notText = send(put(path), adminToken, numeric, null);
        assertThat(status(notText)).isEqualTo(422);
        assertThat(json(notText).get("domainCode").asText()).isEqualTo("APP_VERSION_INVALID");

        assertThat(status(send(get("/api/v1/admin/app-versions"), ownerToken, null, null))).isEqualTo(403);
        assertThat(status(send(put(path), ownerToken, versions(null, null, null, null), null))).isEqualTo(403);

        assertThat(status(send(delete(path), adminToken, null, null))).isEqualTo(204);
        MvcResult gone = send(delete(path), adminToken, null, null);
        assertThat(status(gone)).isEqualTo(404);
        assertThat(json(gone).get("domainCode").asText()).isEqualTo("APP_VERSION_NOT_FOUND");
    }

    // ── legacy-client guard ──────────────────────────────────────────────────

    @Test
    @DisplayName("V1 viewer: login lists nothing, select-tenant 403; new app lists and opens the membership")
    void legacyViewerLogin() throws Exception {
        String phone = person(Map.of(tenantB, "VIEWER"));

        MvcResult v1 = login(phone, null);
        assertThat(status(v1)).as(v1.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(v1).get("memberships")).isEmpty();
        MvcResult v1Select = send(post("/api/v1/auth/select-tenant"), null, Map.of("loginToken",
                json(v1).get("loginToken").asText(), "tenantCode", tenantB.getCode()), null);
        assertThat(status(v1Select)).isEqualTo(403);
        assertThat(json(v1Select).get("domainCode").asText()).isEqualTo("APP_UPDATE_REQUIRED");
        assertThat(json(v1Select).get("error").asText())
                .isEqualTo("Ce rôle nécessite la dernière version de l'application : mettez-la à jour");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.refresh_tokens WHERE tenant_id = ?",
                Integer.class, tenantB.getSchemaName())).isZero();

        MvcResult app = login(phone, "2.0.0");
        assertThat(codes(json(app))).containsExactly(tenantB.getCode());
        MvcResult appSelect = send(post("/api/v1/auth/select-tenant"), null, Map.of("loginToken",
                json(app).get("loginToken").asText(), "tenantCode", tenantB.getCode()), "2.0.0");
        assertThat(status(appSelect)).as(appSelect.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(appSelect).get("memberRole").asText()).isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("V1 mixed: OWNER of A + VIEWER of B → only A listed, A opens and renews as before")
    void legacyMixedLogin() throws Exception {
        String phone = person(Map.of(tenantA, "OWNER", tenantB, "VIEWER"));

        MvcResult v1 = login(phone, null);
        assertThat(codes(json(v1))).containsExactly(tenantA.getCode());
        MvcResult select = send(post("/api/v1/auth/select-tenant"), null, Map.of("loginToken",
                json(v1).get("loginToken").asText(), "tenantCode", tenantA.getCode()), null);
        assertThat(status(select)).as(select.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult renewed = send(post("/api/v1/auth/refresh"), null,
                Map.of("refreshToken", json(select).get("refreshToken").asText()), null);
        assertThat(status(renewed)).as(renewed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(renewed).get("role").asText()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("V1 refresh of a VIEWER (or MANAGER) session → 403 APP_UPDATE_REQUIRED; with the header → 200")
    void legacyRefresh() throws Exception {
        String phone = person(Map.of(tenantA, "MANAGER", tenantB, "VIEWER"));
        String loginToken = json(login(phone, "2.0.0")).get("loginToken").asText();

        for (Tenant t : new Tenant[]{tenantA, tenantB}) {
            MvcResult session = send(post("/api/v1/auth/select-tenant"), null,
                    Map.of("loginToken", loginToken, "tenantCode", t.getCode()), "2.0.0");
            assertThat(status(session)).as(session.getResponse().getContentAsString()).isEqualTo(200);
            String refresh = json(session).get("refreshToken").asText();

            MvcResult v1 = send(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", refresh), null);
            assertThat(status(v1)).isEqualTo(403);
            assertThat(json(v1).get("domainCode").asText()).isEqualTo("APP_UPDATE_REQUIRED");

            MvcResult app = send(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", refresh), "2.0.0");
            assertThat(status(app)).as(app.getResponse().getContentAsString()).isEqualTo(200);
        }
    }
}

package com.keevo.identity.auth.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.SessionTokenIssuer;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 16.11 — long and safe sessions on the real HTTP chain (production filter, access-state cache
 * with LISTEN/NOTIFY eviction, PostgreSQL): « Appareils connectés » (mine and owner → employee),
 * closing a session from another device, renewal keeping the device session, replay revoking the
 * device chain (audited), idle refresh refused, refresh rate limit, profile identity.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("16.11 — device sessions, renewal and profile (full context)")
class DeviceSessionsFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired SessionTokenIssuer issuer;
    @Autowired JwtTokenProvider jwt;

    private Tenant tenant;
    private String schema;
    private UUID storeId;
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenant = tx.execute(s -> tenantFactory.create());
        schema = tenant.getSchemaName();
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        TestAccess.grant(jdbc, ownerId, schema, "OWNER");
    }

    private AuthTokens signIn(UUID userId, String role, String label) {
        return tx.execute(s -> issuer.issue(userId, tenant, role, null,
                DeviceInfo.of("dev-" + UUID.randomUUID(), label)).tokens());
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return call(null, HttpMethod.POST, "/api/v1/auth/refresh", Map.of("refreshToken", refreshToken));
    }

    private UUID sid(String accessToken) {
        return jwt.extractSessionId(jwt.parseToken(accessToken));
    }

    private int audits(String action) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".audit_log WHERE action = ?",
                Integer.class, action);
        return n == null ? 0 : n;
    }

    /** The other instances learn about a change through NOTIFY; this one evicts at once. Polls ≤ 5 s anyway. */
    private static void eventually(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.get()) {
            assertThat(System.nanoTime()).as("not applied within 5 s").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private int status(String token, String path) {
        try {
            return call(token, HttpMethod.GET, path, null).getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("« Appareils connectés »: my sessions with label, last use and current flag — never a token or hash")
    void listMySessions() throws Exception {
        AuthTokens redmi = signIn(ownerId, "OWNER", "Redmi Note 12 · Android 14");
        signIn(ownerId, "OWNER", null);

        MvcResult r = call(redmi.accessToken(), HttpMethod.GET, "/api/v1/auth/sessions", null);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = json(r).get("data");
        assertThat(data).hasSize(2);
        JsonNode current = data.get(0).get("current").asBoolean() ? data.get(0) : data.get(1);
        JsonNode other = current == data.get(0) ? data.get(1) : data.get(0);
        assertThat(current.get("deviceLabel").asText()).isEqualTo("Redmi Note 12 · Android 14");
        assertThat(current.get("sessionId").asText()).isEqualTo(sid(redmi.accessToken()).toString());
        assertThat(current.hasNonNull("lastUsedAt")).isTrue();
        assertThat(other.get("deviceLabel").asText()).isEqualTo("Appareil inconnu");
        assertThat(other.get("current").asBoolean()).isFalse();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(redmi.refreshToken()).doesNotContain(SessionTokenIssuer.hash(redmi.refreshToken()))
                .doesNotContain("tokenHash").doesNotContain("deviceId");
    }

    @Test
    @DisplayName("session closed from another session (same owner) → that device's next request 401 SESSION_REVOKED, audited")
    void closeFromAnotherSession() throws Exception {
        AuthTokens redmi = signIn(ownerId, "OWNER", "Redmi Note 12 · Android 14");
        AuthTokens laptop = signIn(ownerId, "OWNER", "Chrome · Linux");
        assertThat(status(redmi.accessToken(), "/api/v1/auth/sessions")).isEqualTo(200);

        MvcResult closed = call(laptop.accessToken(), HttpMethod.DELETE,
                "/api/v1/auth/sessions/" + sid(redmi.accessToken()), null);
        assertThat(closed.getResponse().getStatus()).isEqualTo(200);

        eventually(() -> status(redmi.accessToken(), "/api/v1/auth/sessions") == 401);
        MvcResult refused = call(redmi.accessToken(), HttpMethod.GET, "/api/v1/auth/sessions", null);
        assertThat(json(refused).toString()).contains("SESSION_REVOKED");
        assertThat(refresh(redmi.refreshToken()).getResponse().getStatus()).isEqualTo(401);
        assertThat(status(laptop.accessToken(), "/api/v1/auth/sessions")).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT entity_label FROM \"" + schema + "\".audit_log "
                + "WHERE action = 'DEVICE_SESSION_CLOSED' AND entity_id = ?", String.class, ownerId))
                .isEqualTo("Redmi Note 12 · Android 14");

        // Closing it again: French 404, no technical data.
        MvcResult again = call(laptop.accessToken(), HttpMethod.DELETE,
                "/api/v1/auth/sessions/" + sid(redmi.accessToken()), null);
        assertThat(again.getResponse().getStatus()).isEqualTo(404);
        assertThat(again.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Cet appareil n'est plus connecté");
    }

    @Test
    @DisplayName("a session without device label (V1 app, registration, migrated row) can be closed")
    void closeSessionWithoutLabel() throws Exception {
        AuthTokens unlabelled = signIn(ownerId, "OWNER", null);
        AuthTokens laptop = signIn(ownerId, "OWNER", "Chrome");

        MvcResult closed = call(laptop.accessToken(), HttpMethod.DELETE,
                "/api/v1/auth/sessions/" + sid(unlabelled.accessToken()), null);

        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        eventually(() -> status(unlabelled.accessToken(), "/api/v1/auth/sessions") == 401);
    }

    @Test
    @DisplayName("« Déconnecter les autres » from a token without session (pre-16.11) is refused: it would close the caller too")
    void closeOthersRefusedWithoutCurrentSession() throws Exception {
        AuthTokens phone = signIn(ownerId, "OWNER", "Redmi");
        String sessionLess = jwt.generateAccessToken(ownerId, schema, "OWNER");

        MvcResult r = call(sessionLess, HttpMethod.DELETE, "/api/v1/auth/sessions", null);

        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Cet appareil n'est plus connecté");
        assertThat(status(phone.accessToken(), "/api/v1/auth/sessions")).isEqualTo(200);
    }

    @Test
    @DisplayName("« Déconnecter les autres »: every other session closed, the current one kept")
    void closeAllOthers() throws Exception {
        AuthTokens redmi = signIn(ownerId, "OWNER", "Redmi");
        AuthTokens laptop = signIn(ownerId, "OWNER", "Chrome");

        MvcResult r = call(redmi.accessToken(), HttpMethod.DELETE, "/api/v1/auth/sessions", null);

        assertThat(json(r).get("data").get("closed").asInt()).isEqualTo(1);
        eventually(() -> status(laptop.accessToken(), "/api/v1/auth/sessions") == 401);
        assertThat(status(redmi.accessToken(), "/api/v1/auth/sessions")).isEqualTo(200);
        assertThat(audits("DEVICE_SESSIONS_CLOSED")).isEqualTo(1);
    }

    @Test
    @DisplayName("owner closes a lost employee phone → that phone is refused; an employee cannot manage sessions of others")
    void ownerClosesEmployeeSession() throws Exception {
        UUID employeeUser = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, 'Awa', 'Ngo', false)", employeeId, employeeUser, storeId);
        TestAccess.grant(jdbc, employeeUser, schema, "EMPLOYEE");
        AuthTokens phone = signIn(employeeUser, "EMPLOYEE", "Tecno Spark · Android 13");
        AuthTokens owner = signIn(ownerId, "OWNER", "Redmi");

        assertThat(call(phone.accessToken(), HttpMethod.GET, "/api/v1/employees/" + employeeId + "/sessions", null)
                .getResponse().getStatus()).isEqualTo(403);

        MvcResult list = call(owner.accessToken(), HttpMethod.GET, "/api/v1/employees/" + employeeId + "/sessions", null);
        JsonNode sessions = json(list).get("data");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).get("deviceLabel").asText()).isEqualTo("Tecno Spark · Android 13");

        MvcResult closed = call(owner.accessToken(), HttpMethod.DELETE, "/api/v1/employees/" + employeeId
                + "/sessions/" + sessions.get(0).get("sessionId").asText(), null);
        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        eventually(() -> status(phone.accessToken(), "/api/v1/auth/profile") == 401);
        assertThat(audits("DEVICE_SESSION_CLOSED")).isEqualTo(1);
    }

    @Test
    @DisplayName("silent renewal keeps the device session; replaying a rotated token revokes that device chain only (audited)")
    void renewalAndReplay() throws Exception {
        AuthTokens redmi = signIn(ownerId, "OWNER", "Redmi");
        AuthTokens laptop = signIn(ownerId, "OWNER", "Chrome");
        UUID redmiSid = sid(redmi.accessToken());

        JsonNode second = json(refresh(redmi.refreshToken()));
        assertThat(sid(second.get("accessToken").asText())).isEqualTo(redmiSid);
        assertThat(second.get("expiresIn").asLong()).isEqualTo(30 * 60);
        JsonNode third = json(refresh(second.get("refreshToken").asText()));
        String latestAccess = third.get("accessToken").asText();
        assertThat(status(latestAccess, "/api/v1/auth/sessions")).isEqualTo(200);

        // The first token was exchanged and its successor used: two parties hold the session.
        MvcResult replay = refresh(redmi.refreshToken());
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);

        eventually(() -> status(latestAccess, "/api/v1/auth/sessions") == 401);
        assertThat(refresh(third.get("refreshToken").asText()).getResponse().getStatus()).isEqualTo(401);
        assertThat(status(laptop.accessToken(), "/api/v1/auth/sessions")).isEqualTo(200);
        assertThat(audits("DEVICE_SESSION_REPLAY_REVOKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("refresh unused for more than 365 days → refused with a French message (sign in again)")
    void idleSessionExpires() throws Exception {
        AuthTokens redmi = signIn(ownerId, "OWNER", "Redmi");
        jdbc.update("UPDATE public.refresh_tokens SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
                SessionTokenIssuer.hash(redmi.refreshToken()));

        MvcResult r = refresh(redmi.refreshToken());

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("REFRESH_TOKEN_INVALID").contains("veuillez vous reconnecter");
    }

    @Test
    @DisplayName("refresh flood on one token → 429 « Trop de requêtes »")
    void refreshRateLimited() throws Exception {
        String garbage = "flood-" + UUID.randomUUID();
        int last = 0;
        for (int i = 0; i < 11; i++) {
            last = refresh(garbage).getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
        assertThat(refresh(garbage).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Trop de requêtes");
    }

    @Test
    @DisplayName("profile: role, store (id + name), first name, password flag, account status — the app's identity source")
    void profile() throws Exception {
        UUID employeeUser = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Awa', 'Ngo', false)", employeeUser, storeId);
        TestAccess.grant(jdbc, employeeUser, schema, "EMPLOYEE");
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role) VALUES (?, now(), now(), true, 0, 'x', ?, 'EMPLOYEE')", employeeUser,
                "+2376" + String.format("%08d", Math.abs(employeeUser.getMostSignificantBits() % 100_000_000)));
        AuthTokens phone = signIn(employeeUser, "EMPLOYEE", "Tecno");

        MvcResult pr = call(phone.accessToken(), HttpMethod.GET, "/api/v1/auth/profile", null);
        JsonNode p = json(pr);
        assertThat(pr.getResponse().getStatus()).as(p.toString()).isEqualTo(200);

        assertThat(p.get("role").asText()).isEqualTo("EMPLOYEE");
        assertThat(p.get("storeId").asText()).isEqualTo(storeId.toString());
        assertThat(p.hasNonNull("storeName")).isTrue();
        assertThat(p.get("firstName").asText()).isEqualTo("Awa");
        assertThat(p.get("passwordChangeRequired").asBoolean()).isFalse();
        assertThat(p.get("tenantStatus").asText()).isEqualTo("ACTIVE");
        assertThat(jwt.parseToken(phone.accessToken())).doesNotContainKey("firstName");
    }
}

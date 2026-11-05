package com.keevo.identity.auth.session;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 19.4 — on the real HTTP chain and PostgreSQL: an owner creates a member with a username and no phone, the
 * member signs in with that username (any case) and the temporary password, selects the company, changes the password
 * and signs in again; a taken username answers 409; an imported Bref row ({@code $2b$12$} hash, no phone) signs in
 * with its hash unchanged; the V1 phone login is unchanged.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("19.4 — username login for members (full context)")
class UsernameLoginFullContextTest extends FullContextIntegrationTest {

    /** bcrypt("secret6", cost 12, $2b$) as Node's bcrypt writes it (Bref). */
    private static final String BREF_HASH = "$2b$12$uJP5XlDUmIGkKXbs6pUv5uyfefvSMoVTKAXjIvXJy2R4.i11Crz0C";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JwtTokenProvider jwt;

    private final List<UUID> users = new ArrayList<>();
    private Tenant tenant;
    private String schema;
    private UUID storeId;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        tenant = tx.execute(s -> tenantFactory.create());
        schema = tenant.getSchemaName();
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        ownerToken = TestAccess.token(jdbc, jwt, UUID.randomUUID(), schema, "OWNER");
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : users) {
            jdbc.update("DELETE FROM public.refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", id);
            jdbc.update("DELETE FROM \"" + schema + "\".employees WHERE user_id = ?", id);
            jdbc.update("DELETE FROM public.users WHERE id = ?", id);
        }
    }

    /** {@code n} decimal digits derived from a random UUID. */
    private static String uniqueDigits(int n) {
        String digits = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        return digits.substring(digits.length() - n);
    }

    private static String uniqueUsername(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private MvcResult postJson(String path, String token, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult createMember(String username) throws Exception {
        MvcResult r = postJson("/api/v1/employees", ownerToken, Map.of("firstName", "Awa", "lastName", "Kamga",
                "username", username, "storeId", storeId.toString()));
        if (r.getResponse().getStatus() == 201) {
            users.add(UUID.fromString(json(r).at("/data/employee/userId").asText()));
        }
        return r;
    }

    private MvcResult login(Map<String, String> body) throws Exception {
        return postJson("/api/v1/auth/login", null, body);
    }

    @Test
    @DisplayName("owner creates a username-only member → member logs in by username → select-tenant → change-password")
    void createLoginSelectChangePassword() throws Exception {
        String username = uniqueUsername("Awa.K");

        MvcResult created = createMember(username);
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode employee = json(created).at("/data/employee");
        assertThat(employee.get("username").asText()).isEqualTo(username);
        assertThat(employee.get("phoneNumber").asText()).isEqualTo(""); // 19.1 — "" for a known account without phone
        String tempPassword = json(created).at("/data/temporaryPassword").asText();
        assertThat(tempPassword).isNotBlank();
        UUID userId = UUID.fromString(employee.get("userId").asText());
        assertThat(jdbc.queryForObject("SELECT phone_number FROM public.users WHERE id = ?", String.class, userId))
                .isNull();

        // The list (bulk enrichment: users and memberships in one query each) shows the member
        MvcResult list = mockMvc.perform(get("/api/v1/employees").header("Authorization", "Bearer " + ownerToken))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        JsonNode listed = null;
        for (JsonNode e : json(list).get("data")) {
            if (e.get("userId").asText().equals(userId.toString())) listed = e;
        }
        assertThat(listed).isNotNull();
        assertThat(listed.get("username").asText()).isEqualTo(username);
        assertThat(listed.get("role").asText()).isEqualTo("EMPLOYEE");
        assertThat(listed.get("phoneNumber").asText()).isEqualTo("");

        // Same username in another case → that account, already a member here → 409 (Story 19.6)
        MvcResult taken = createMember(username.toUpperCase());
        assertThat(taken.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(taken).get("domainCode").asText()).isEqualTo("MEMBER_ALREADY_IN_COMPANY");
        assertThat(json(taken).get("error").asText()).isEqualTo("Cette personne fait déjà partie de l'entreprise");

        // Wrong password → 401
        MvcResult wrong = login(Map.of("username", username, "password", "wrong-one"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(wrong).get("domainCode").asText()).isEqualTo("INVALID_CREDENTIALS");

        // Login by username, other case, temporary password → loginToken + membership
        MvcResult step1 = login(Map.of("username", username.toLowerCase(), "password", tempPassword));
        assertThat(step1.getResponse().getStatus()).as(step1.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode memberships = json(step1).get("memberships");
        assertThat(memberships).hasSize(1);
        assertThat(memberships.get(0).get("tenantCode").asText()).isEqualTo(tenant.getCode());

        MvcResult step2 = postJson("/api/v1/auth/select-tenant", null,
                Map.of("loginToken", json(step1).get("loginToken").asText(), "tenantCode", tenant.getCode()));
        assertThat(step2.getResponse().getStatus()).as(step2.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(step2).get("passwordChangeRequired").asBoolean()).isTrue();
        String accessToken = json(step2).get("accessToken").asText();

        MvcResult profile = mockMvc.perform(get("/api/v1/auth/profile")
                .header("Authorization", "Bearer " + accessToken)).andReturn();
        assertThat(profile.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(profile).get("username").asText()).isEqualTo(username);
        assertThat(json(profile).get("phoneNumber").asText()).isEqualTo(""); // 19.1 — never null for V1

        MvcResult changed = postJson("/api/v1/auth/change-password", accessToken,
                Map.of("currentPassword", tempPassword, "newPassword", "NouveauMdp2026"));
        assertThat(changed.getResponse().getStatus()).as(changed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(changed).get("accessToken").asText()).isNotBlank();
        assertThat(json(changed).get("passwordChangeRequired").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_change_required FROM \"" + schema + "\".employees "
                + "WHERE user_id = ?", Boolean.class, userId)).isFalse();

        assertThat(login(Map.of("username", username, "password", "NouveauMdp2026")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(login(Map.of("username", username, "password", tempPassword)).getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("imported Bref member ($2b$12$ hash, 6-char password, no phone) logs in; hash unchanged")
    void importedBrefMember() throws Exception {
        String username = uniqueUsername("bref_");
        UUID userId = UUID.randomUUID();
        users.add(userId);
        jdbc.update("INSERT INTO public.users (id, username, password_hash, role, is_active, failed_attempts, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'EMPLOYEE', true, 0, now(), now())",
                userId, username, BREF_HASH);
        TestAccess.grant(jdbc, userId, schema, "EMPLOYEE");

        MvcResult step1 = login(Map.of("username", username.toUpperCase(), "password", "secret6"));

        assertThat(step1.getResponse().getStatus()).as(step1.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(step1).get("loginToken").asText()).isNotBlank();
        assertThat(json(step1).get("memberships")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM public.users WHERE id = ?", String.class, userId))
                .isEqualTo(BREF_HASH);
    }

    @Test
    @DisplayName("both / neither identifier → 422 VALIDATION_FAILED; V1 phone login unchanged (401 for an unknown phone)")
    void validationAndV1() throws Exception {
        MvcResult both = login(Map.of("phoneNumber", "+237690000001", "username", "awa", "password", "secret66"));
        assertThat(both.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(both).get("domainCode").asText()).isEqualTo("VALIDATION_FAILED");

        MvcResult neither = login(Map.of("password", "secret66"));
        assertThat(neither.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(neither).get("error").asText()).isEqualTo("Indiquez un numéro de téléphone ou un identifiant");

        MvcResult v1 = login(Map.of("phoneNumber", "+237699" + uniqueDigits(6), "password", "SecurePass1!"));
        assertThat(v1.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(v1).get("error").asText()).isEqualTo("Téléphone ou mot de passe incorrect");
    }
}

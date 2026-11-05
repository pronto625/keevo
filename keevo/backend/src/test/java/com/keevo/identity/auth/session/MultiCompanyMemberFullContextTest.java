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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 19.6 — on the real HTTP chain and PostgreSQL: the owner of company B is added to company A as EMPLOYEE by
 * phone → one account, one password → login lists A (EMPLOYEE) and B (OWNER) → both can be selected → A's owner can
 * neither reset her password nor change her phone → A's owner deactivates her → her B session still works.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("19.6 — one person in several companies (full context)")
class MultiCompanyMemberFullContextTest extends FullContextIntegrationTest {

    private static final String PASSWORD = "MonMotDePasse2026";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JwtTokenProvider jwt;
    @Autowired PasswordEncoder passwordEncoder;

    private Tenant tenantA;
    private Tenant tenantB;
    private UUID storeA;
    private UUID personId;
    private UUID ownerAId;
    private String phone;
    private String ownerAToken;

    @BeforeEach
    void setUp() {
        tenantA = tx.execute(s -> tenantFactory.create());
        tenantB = tx.execute(s -> tenantFactory.create());
        storeA = jdbc.queryForObject("SELECT id FROM \"" + tenantA.getSchemaName() + "\".stores LIMIT 1", UUID.class);
        ownerAId = UUID.randomUUID();
        ownerAToken = TestAccess.token(jdbc, jwt, ownerAId, tenantA.getSchemaName(), "OWNER");

        // The person: an account that owns company B.
        String digits = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        phone = "+237699" + digits.substring(digits.length() - 6);
        personId = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, phone_number, password_hash, role, is_active, failed_attempts, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'OWNER', true, 0, now(), now())",
                personId, phone, passwordEncoder.encode(PASSWORD));
        TestAccess.grant(jdbc, personId, tenantB.getSchemaName(), "OWNER");
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : new UUID[]{personId, ownerAId}) {
            jdbc.update("DELETE FROM public.refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", id);
            jdbc.update("DELETE FROM \"" + tenantA.getSchemaName() + "\".employees WHERE user_id = ?", id);
            jdbc.update("DELETE FROM \"" + tenantA.getSchemaName() + "\".notifications WHERE user_id = ?", id);
            jdbc.update("DELETE FROM public.users WHERE id = ?", id);
        }
        for (Tenant t : new Tenant[]{tenantA, tenantB}) {
            if (t == null) continue;
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + t.getSchemaName() + "\" CASCADE"); // test database only
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE tenant_id = ?", t.getId());
            jdbc.update("DELETE FROM public.refresh_tokens WHERE tenant_id = ?", t.getSchemaName());
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", t.getId());
            jdbc.update("DELETE FROM public.tenants WHERE id = ?", t.getId());
        }
    }

    private MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                           String token, Object body) throws Exception {
        request.contentType(MediaType.APPLICATION_JSON);
        if (body != null) request.content(objectMapper.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        // Story 19.10 — a new app (declares its version): she becomes MANAGER of A below, which a legacy V1
        // client may not open
        request.header("X-App-Version", "2.0.0");
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode selectTenant(String loginToken, Tenant tenant) throws Exception {
        MvcResult r = send(post("/api/v1/auth/select-tenant"), null,
                Map.of("loginToken", loginToken, "tenantCode", tenant.getCode()));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    @Test
    @DisplayName("owner of B added to A by phone → picker A (EMPLOYEE) + B (OWNER) → shared account protected → "
            + "deactivation in A leaves B intact")
    void ownerOfBJoinsA() throws Exception {
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM public.users WHERE id = ?", String.class,
                personId);

        // 1. Owner of A adds her phone as EMPLOYEE
        MvcResult created = send(post("/api/v1/employees"), ownerAToken, Map.of("firstName", "Awa",
                "lastName", "Mbarga", "phoneNumber", phone, "storeId", storeA.toString()));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode data = json(created).get("data");
        assertThat(data.get("existingAccount").asBoolean()).isTrue();
        assertThat(data.get("temporaryPassword").asText()).isEmpty(); // "" — V1 reads a String
        assertThat(data.at("/employee/userId").asText()).isEqualTo(personId.toString());
        String employeeId = data.at("/employee/id").asText();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM public.users WHERE id = ?", String.class, personId))
                .isEqualTo(hashBefore);
        assertThat(jdbc.queryForObject("SELECT role FROM public.users WHERE id = ?", String.class, personId))
                .isEqualTo("OWNER"); // global role untouched
        // inbox notification in A's inbox for her (after commit, best effort)
        assertThat(jdbc.queryForObject("SELECT title FROM \"" + tenantA.getSchemaName() + "\".notifications "
                + "WHERE user_id = ? AND type = 'MEMBERSHIP_ADDED'", String.class, personId))
                .startsWith("Vous avez rejoint");

        // Same phone again → already a member
        MvcResult again = send(post("/api/v1/employees"), ownerAToken, Map.of("firstName", "Awa",
                "lastName", "Mbarga", "phoneNumber", phone, "storeId", storeA.toString()));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(again).get("domainCode").asText()).isEqualTo("MEMBER_ALREADY_IN_COMPANY");

        // 2. Login with her own password → both companies with their roles
        MvcResult login = send(post("/api/v1/auth/login"), null, Map.of("phoneNumber", phone, "password", PASSWORD));
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        Map<String, JsonNode> byCode = new HashMap<>();
        for (JsonNode m : json(login).get("memberships")) byCode.put(m.get("tenantCode").asText(), m);
        assertThat(byCode).containsOnlyKeys(tenantA.getCode(), tenantB.getCode());
        assertThat(byCode.get(tenantA.getCode()).get("role").asText()).isEqualTo("EMPLOYEE");
        assertThat(byCode.get(tenantA.getCode()).get("memberRole").asText()).isEqualTo("EMPLOYEE");
        assertThat(byCode.get(tenantB.getCode()).get("role").asText()).isEqualTo("OWNER");
        assertThat(byCode.get(tenantB.getCode()).get("memberRole").asText()).isEqualTo("OWNER");
        String loginToken = json(login).get("loginToken").asText();

        // 3. Select A, then B
        JsonNode sessionA = selectTenant(loginToken, tenantA);
        assertThat(sessionA.get("role").asText()).isEqualTo("EMPLOYEE");
        assertThat(sessionA.get("passwordChangeRequired").asBoolean()).isFalse();
        JsonNode sessionB = selectTenant(loginToken, tenantB);
        assertThat(sessionB.get("role").asText()).isEqualTo("OWNER");

        // 4. Owner of A: password reset, regeneration and phone change refused; rename allowed
        MvcResult reset = send(post("/api/v1/employees/" + employeeId + "/password"), ownerAToken,
                Map.of("newPassword", "Autre2026pass"));
        assertThat(reset.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(reset).get("domainCode").asText()).isEqualTo("MEMBER_ACCOUNT_SHARED");
        assertThat(json(reset).get("error").asText()).isEqualTo(
                "Ce membre utilise aussi Bref Point ailleurs : il gère lui-même son mot de passe et ses identifiants");
        MvcResult regenerate = send(post("/api/v1/employees/" + employeeId + "/regenerate-password"), ownerAToken,
                null);
        assertThat(regenerate.getResponse().getStatus()).isEqualTo(409);
        MvcResult phoneChange = send(patch("/api/v1/employees/" + employeeId), ownerAToken,
                Map.of("phoneNumber", "+237698000111"));
        assertThat(phoneChange.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(phoneChange).get("domainCode").asText()).isEqualTo("MEMBER_ACCOUNT_SHARED");
        MvcResult rename = send(patch("/api/v1/employees/" + employeeId), ownerAToken, Map.of("firstName", "Aïcha"));
        assertThat(rename.getResponse().getStatus()).as(rename.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM public.users WHERE id = ?", String.class, personId))
                .isEqualTo(hashBefore);

        // 5. Owner of A makes her MANAGER in A → only A's sessions closed, global role untouched, B still renews
        MvcResult roleChange = send(patch("/api/v1/employees/" + employeeId + "/role"), ownerAToken,
                Map.of("role", "MANAGER"));
        assertThat(roleChange.getResponse().getStatus()).as(roleChange.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT role FROM public.users WHERE id = ?", String.class, personId))
                .isEqualTo("OWNER");
        assertThat(send(post("/api/v1/auth/refresh"), null,
                Map.of("refreshToken", sessionA.get("refreshToken").asText())).getResponse().getStatus())
                .isEqualTo(401);
        MvcResult afterRoleB = send(post("/api/v1/auth/refresh"), null,
                Map.of("refreshToken", sessionB.get("refreshToken").asText()));
        assertThat(afterRoleB.getResponse().getStatus()).as(afterRoleB.getResponse().getContentAsString())
                .isEqualTo(200);
        String refreshB = json(afterRoleB).get("refreshToken").asText();
        // she signs in to A again (the new role), to check that deactivation closes it
        String loginAgain = json(send(post("/api/v1/auth/login"), null,
                Map.of("phoneNumber", phone, "password", PASSWORD))).get("loginToken").asText();
        sessionA = selectTenant(loginAgain, tenantA);

        // 6. Owner of A deactivates her → A closed, B still valid
        MvcResult deactivated = send(patch("/api/v1/employees/" + employeeId + "/deactivate"), ownerAToken, null);
        assertThat(deactivated.getResponse().getStatus()).as(deactivated.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(send(post("/api/v1/auth/refresh"), null,
                Map.of("refreshToken", sessionA.get("refreshToken").asText())).getResponse().getStatus())
                .isEqualTo(401);
        MvcResult renewedB = send(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", refreshB));
        assertThat(renewedB.getResponse().getStatus()).as(renewedB.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(renewedB).get("role").asText()).isEqualTo("OWNER");
        MvcResult profileB = mockMvc.perform(get("/api/v1/auth/profile")
                .header("Authorization", "Bearer " + json(renewedB).get("accessToken").asText())).andReturn();
        assertThat(profileB.getResponse().getStatus()).isEqualTo(200);
    }
}

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
            jdbc.update("DELETE FROM public.membership_invitations WHERE user_id = ? OR invited_by = ?", id, id);
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

        // 1. Owner of A adds her phone as EMPLOYEE → 19.6b: an invitation, nothing attached, neutral answer
        MvcResult created = send(post("/api/v1/employees"), ownerAToken, Map.of("firstName", "Awa",
                "lastName", "Mbarga", "phoneNumber", phone, "storeId", storeA.toString()));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(202);
        JsonNode data = json(created).get("data");
        assertThat(data.get("existingAccount").asBoolean()).isTrue();
        assertThat(data.get("invitationPending").asBoolean()).isTrue();
        assertThat(data.get("temporaryPassword").asText()).isEmpty(); // "" — V1 reads a String
        assertThat(data.get("employee").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.user_tenant_memberships WHERE user_id = ? "
                + "AND tenant_id = ?", Integer.class, personId, tenantA.getId())).isZero();
        // the company sees its pending invitation (what it typed only)
        JsonNode invitations = json(send(get("/api/v1/employees/invitations"), ownerAToken, null)).get("data");
        assertThat(invitations).hasSize(1);
        assertThat(invitations.get(0).get("identifier").asText()).isEqualTo(phone);

        // Same phone again → same neutral answer, still one invitation (no second message)
        MvcResult again = send(post("/api/v1/employees"), ownerAToken, Map.of("firstName", "Awa",
                "lastName", "Mbarga", "phoneNumber", phone, "storeId", storeA.toString()));
        assertThat(again.getResponse().getStatus()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.membership_invitations WHERE user_id = ?",
                Integer.class, personId)).isEqualTo(1);

        // 2. Login with her own password → only B; her pending invitation from A; she accepts it
        MvcResult firstLogin = send(post("/api/v1/auth/login"), null,
                Map.of("phoneNumber", phone, "password", PASSWORD));
        assertThat(json(firstLogin).get("memberships")).hasSize(1);
        String firstToken = json(firstLogin).get("loginToken").asText();
        MvcResult pending = send(post("/api/v1/auth/invitations/pending"), null, Map.of("loginToken", firstToken));
        assertThat(pending.getResponse().getStatus()).as(pending.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode mine = json(pending).get("data");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("roleLabel").asText()).isEqualTo("Employé");
        MvcResult accepted = send(post("/api/v1/auth/invitations/" + mine.get(0).get("id").asText() + "/accept"),
                null, Map.of("loginToken", firstToken));
        assertThat(accepted.getResponse().getStatus()).as(accepted.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(accepted).at("/data/tenantCode").asText()).isEqualTo(tenantA.getCode());
        assertThat(json(accepted).at("/data/memberRole").asText()).isEqualTo("EMPLOYEE");
        // accepted once: a second answer is refused
        MvcResult twice = send(post("/api/v1/auth/invitations/" + mine.get(0).get("id").asText() + "/decline"),
                null, Map.of("loginToken", firstToken));
        assertThat(twice.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(twice).get("domainCode").asText()).isEqualTo("INVITATION_NOT_FOUND");
        String employeeId = jdbc.queryForObject("SELECT CAST(id AS text) FROM \"" + tenantA.getSchemaName()
                + "\".employees WHERE user_id = ?", String.class, personId);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM public.users WHERE id = ?", String.class, personId))
                .isEqualTo(hashBefore);
        assertThat(jdbc.queryForObject("SELECT role FROM public.users WHERE id = ?", String.class, personId))
                .isEqualTo("OWNER"); // global role untouched
        // the inviter's inbox (after commit, best effort)
        assertThat(jdbc.queryForObject("SELECT title FROM \"" + tenantA.getSchemaName() + "\".notifications "
                + "WHERE user_id = ? AND type = 'MEMBERSHIP_INVITATION_ACCEPTED'", String.class, ownerAId))
                .isEqualTo("Awa Mbarga a rejoint votre équipe");

        // Same phone again → already a member
        MvcResult member = send(post("/api/v1/employees"), ownerAToken, Map.of("firstName", "Awa",
                "lastName", "Mbarga", "phoneNumber", phone, "storeId", storeA.toString()));
        assertThat(member.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(member).get("domainCode").asText()).isEqualTo("MEMBER_ALREADY_IN_COMPANY");

        // Login again → both companies with their roles
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

    @Test
    @DisplayName("19.6b — invited by username, accepted from an open session in B: the "
            + "company sees the username, never the phone (list); a decline notifies nobody and blocks re-invitations")
    void invitedByUsername_phoneHidden_andDecline() throws Exception {
        jdbc.update("UPDATE public.users SET username = ? WHERE id = ?", "awa" + personId.toString().substring(0, 6),
                personId);
        String username = "awa" + personId.toString().substring(0, 6);
        String sessionB = jwt.generateAccessToken(personId, tenantB.getSchemaName(), "OWNER");

        assertThat(send(post("/api/v1/employees"), ownerAToken, Map.of("firstName", "Awa", "lastName", "K",
                "username", username, "storeId", storeA.toString())).getResponse().getStatus()).isEqualTo(202);
        MvcResult listed = send(get("/api/v1/account/invitations"), sessionB, null);
        assertThat(listed.getResponse().getStatus()).as(listed.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode mine = json(listed).get("data");
        assertThat(mine).hasSize(1);
        MvcResult accepted = send(post("/api/v1/account/invitations/" + mine.get(0).get("id").asText() + "/accept"),
                sessionB, null);
        assertThat(accepted.getResponse().getStatus()).as(accepted.getResponse().getContentAsString()).isEqualTo(200);

        JsonNode team = json(send(get("/api/v1/employees"), ownerAToken, null)).get("data");
        JsonNode awa = null;
        for (JsonNode e : team) if (personId.toString().equals(e.get("userId").asText())) awa = e;
        assertThat(awa).isNotNull();
        assertThat(awa.get("username").asText()).isEqualTo(username);
        assertThat(awa.get("phoneNumber").asText()).isEmpty(); // never the phone she did not give A

        // Owner of A removes nothing; a third company C invites her by phone, she declines
        Tenant tenantC = tx.execute(st -> tenantFactory.create());
        try {
            UUID ownerC = UUID.randomUUID();
            String ownerCToken = TestAccess.token(jdbc, jwt, ownerC, tenantC.getSchemaName(), "OWNER");
            UUID storeC = jdbc.queryForObject("SELECT id FROM \"" + tenantC.getSchemaName() + "\".stores LIMIT 1",
                    UUID.class);
            Map<String, String> body = Map.of("firstName", "Awa", "lastName", "K", "phoneNumber", phone,
                    "storeId", storeC.toString());
            assertThat(send(post("/api/v1/employees"), ownerCToken, body).getResponse().getStatus()).isEqualTo(202);
            JsonNode fromC = json(send(get("/api/v1/account/invitations"), sessionB, null)).get("data");
            assertThat(fromC).hasSize(1);
            MvcResult declined = send(post("/api/v1/account/invitations/" + fromC.get(0).get("id").asText()
                    + "/decline"), sessionB, null);
            assertThat(declined.getResponse().getStatus()).isEqualTo(204);
            // re-invited at once → same neutral answer, but nothing pending for her
            assertThat(send(post("/api/v1/employees"), ownerCToken, body).getResponse().getStatus()).isEqualTo(202);
            assertThat(json(send(get("/api/v1/account/invitations"), sessionB, null)).get("data")).isEmpty();
            assertThat(json(send(get("/api/v1/employees/invitations"), ownerCToken, null)).get("data")).isEmpty();
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", ownerC);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + tenantC.getSchemaName() + "\" CASCADE");
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE tenant_id = ?", tenantC.getId());
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantC.getId());
            jdbc.update("DELETE FROM public.tenants WHERE id = ?", tenantC.getId());
        }
    }

    @Test
    @DisplayName("19.6b — a username-only account cannot join another company (ACCOUNT_RECOVERY_REQUIRED); the "
            + "invitation stays pending; the owner can cancel it")
    void usernameOnly_recoveryRequired_thenCancelled() throws Exception {
        jdbc.update("UPDATE public.users SET phone_number = NULL, username = ? WHERE id = ?",
                "solo" + personId.toString().substring(0, 6), personId);
        String username = "solo" + personId.toString().substring(0, 6);
        String sessionB = jwt.generateAccessToken(personId, tenantB.getSchemaName(), "OWNER");

        assertThat(send(post("/api/v1/employees"), ownerAToken, Map.of("firstName", "Solo", "lastName", "K",
                "username", username, "storeId", storeA.toString())).getResponse().getStatus()).isEqualTo(202);
        String id = json(send(get("/api/v1/account/invitations"), sessionB, null)).at("/data/0/id").asText();
        MvcResult refused = send(post("/api/v1/account/invitations/" + id + "/accept"), sessionB, null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(refused).get("domainCode").asText()).isEqualTo("ACCOUNT_RECOVERY_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM public.membership_invitations WHERE id = ?::uuid",
                String.class, id)).isEqualTo("PENDING");

        MvcResult cancelled = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v1/employees/invitations/" + id), ownerAToken, null);
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(204);
        assertThat(json(send(get("/api/v1/account/invitations"), sessionB, null)).get("data")).isEmpty();
    }
}

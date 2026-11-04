package com.keevo.identity.auth.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 19.2 — sign-in code on the real HTTP chain and PostgreSQL: request (anti-enumeration, hash only), verify
 * (attempts persisted across a failed request, account without company created), create-company (tenant provisioned
 * like /auth/register, tokens usable on /auth/profile), refusals.
 *
 * <p>The code is never readable: the test overwrites the stored hash with the hash of a known code.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("19.2 — sign-in code, account without company, create-company (full context)")
class OtpSignInFullContextTest extends FullContextIntegrationTest {

    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    /** A distinct client IP per call: the per-IP limit is a singleton shared by the cached context. */
    private MvcResult postJson(String path, Object body, String bearer) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "10.19.2." + IP.getAndIncrement())
                .content(objectMapper.writeValueAsString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String email() {
        return "otp-" + UUID.randomUUID().toString().substring(0, 8) + "@ex.com";
    }

    private void setKnownCode(String identifier, String code) {
        jdbc.update("UPDATE public.otp_challenges SET code_hash = ? WHERE identifier = ? AND consumed_at IS NULL",
                passwordEncoder.encode(code), identifier);
    }

    @Test
    @DisplayName("request: same bytes for an existing and an unknown identifier; only a bcrypt hash stored")
    void requestAntiEnumeration() throws Exception {
        String unknown = email();
        String existing = email();
        jdbc.update("INSERT INTO public.users (id, email, role, is_active, failed_attempts, created_at, updated_at) "
                + "VALUES (?, ?, 'OWNER', true, 0, now(), now())", UUID.randomUUID(), existing);

        MvcResult a = postJson("/api/v1/auth/otp/request", Map.of("identifier", unknown), null);
        MvcResult b = postJson("/api/v1/auth/otp/request", Map.of("identifier", existing.toUpperCase()), null);

        assertThat(a.getResponse().getStatus()).isEqualTo(200);
        assertThat(b.getResponse().getStatus()).isEqualTo(200);
        assertThat(a.getResponse().getContentAsByteArray()).isEqualTo(b.getResponse().getContentAsByteArray());
        assertThat(a.getResponse().getContentAsString()).isEqualTo("{\"expiresIn\":300}");

        List<String> hashes = jdbc.queryForList(
                "SELECT code_hash FROM public.otp_challenges WHERE identifier IN (?, ?)", String.class, unknown, existing);
        assertThat(hashes).hasSize(2).allSatisfy(h -> assertThat(h).startsWith("$2"));
    }

    @Test
    @DisplayName("refusals: other country 422, bad channel 422, 4th request in 10 min 429")
    void requestRefusals() throws Exception {
        MvcResult country = postJson("/api/v1/auth/otp/request", Map.of("identifier", "+33612345678"), null);
        assertThat(country.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(country).get("domainCode").asText()).isEqualTo("PHONE_COUNTRY_NOT_SUPPORTED");

        MvcResult mismatch = postJson("/api/v1/auth/otp/request",
                Map.of("identifier", email(), "channel", "SMS"), null);
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(422);

        String id = email();
        for (int i = 0; i < 3; i++) {
            assertThat(postJson("/api/v1/auth/otp/request", Map.of("identifier", id), null)
                    .getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult limited = postJson("/api/v1/auth/otp/request", Map.of("identifier", id), null);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(json(limited).get("domainCode").asText()).isEqualTo("OTP_TOO_MANY_REQUESTS");
    }

    @Test
    @DisplayName("verify → new account without company → create-company → tokens usable, tenant like /register")
    void fullFlow() throws Exception {
        String id = email();
        assertThat(postJson("/api/v1/auth/otp/request", Map.of("identifier", id), null)
                .getResponse().getStatus()).isEqualTo(200);
        setKnownCode(id, "123456");

        // Wrong code: 401 and the attempt is persisted although the request failed.
        MvcResult wrong = postJson("/api/v1/auth/otp/verify", Map.of("identifier", id, "code", "000000"), null);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(wrong).get("domainCode").asText()).isEqualTo("OTP_INVALID");
        assertThat(jdbc.queryForObject("SELECT attempts FROM public.otp_challenges WHERE identifier = ?",
                Integer.class, id)).isEqualTo(1);

        MvcResult ok = postJson("/api/v1/auth/otp/verify", Map.of("identifier", id.toUpperCase(), "code", "123456"), null);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode session = json(ok);
        assertThat(session.get("memberships")).isEmpty();
        String loginToken = session.get("loginToken").asText();
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT id, password_hash, phone_number, email_verified_at FROM public.users WHERE lower(email) = ?", id);
        assertThat(user.get("password_hash")).isNull();
        assertThat(user.get("phone_number")).isNull();
        assertThat(user.get("email_verified_at")).isNotNull();

        // The code is used: replay → 401.
        assertThat(postJson("/api/v1/auth/otp/verify", Map.of("identifier", id, "code", "123456"), null)
                .getResponse().getStatus()).isEqualTo(401);

        MvcResult created = postJson("/api/v1/auth/create-company",
                Map.of("loginToken", loginToken, "deviceId", "dev-otp", "deviceLabel", "Redmi"), null);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode company = json(created);
        assertThat(company.get("userId").asText()).isEqualTo(user.get("id").toString());
        String access = company.get("token").asText();

        MvcResult profile = mockMvc.perform(get("/api/v1/auth/profile").header("Authorization", "Bearer " + access))
                .andReturn();
        assertThat(profile.getResponse().getStatus()).isEqualTo(200);

        // Same trial plan and plan history as a tenant created by /auth/register.
        MvcResult registered = postJson("/api/v1/auth/register",
                Map.of("phoneNumber", "+2376" + (10_000_000 + (int) (Math.random() * 89_999_999)),
                        "password", "SecurePass123!"), null);
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        UUID otpTenant = UUID.fromString(company.get("tenantId").asText());
        UUID regTenant = UUID.fromString(json(registered).get("tenantId").asText());
        assertThat(planOf(otpTenant)).isEqualTo(planOf(regTenant));
        assertThat(historyOf(otpTenant)).isEqualTo(historyOf(regTenant)).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT role FROM public.user_tenant_memberships WHERE tenant_id = ?",
                String.class, otpTenant)).isEqualTo("OWNER");

        // A second company with a new login → 409.
        assertThat(postJson("/api/v1/auth/otp/request", Map.of("identifier", id), null)
                .getResponse().getStatus()).isEqualTo(200);
        setKnownCode(id, "654321");
        MvcResult again = postJson("/api/v1/auth/otp/verify", Map.of("identifier", id, "code", "654321"), null);
        assertThat(json(again).get("memberships")).hasSize(1);
        MvcResult refused = postJson("/api/v1/auth/create-company",
                Map.of("loginToken", json(again).get("loginToken").asText()), null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(refused).get("domainCode").asText()).isEqualTo("COMPANY_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("5 wrong codes kill the challenge (429), the right code no longer works")
    void tooManyAttempts() throws Exception {
        String id = email();
        postJson("/api/v1/auth/otp/request", Map.of("identifier", id), null);
        setKnownCode(id, "123456");
        for (int i = 0; i < 4; i++) {
            assertThat(postJson("/api/v1/auth/otp/verify", Map.of("identifier", id, "code", "000000"), null)
                    .getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult fifth = postJson("/api/v1/auth/otp/verify", Map.of("identifier", id, "code", "000000"), null);
        assertThat(fifth.getResponse().getStatus()).isEqualTo(429);
        assertThat(json(fifth).get("domainCode").asText()).isEqualTo("OTP_TOO_MANY_ATTEMPTS");
        assertThat(postJson("/api/v1/auth/otp/verify", Map.of("identifier", id, "code", "123456"), null)
                .getResponse().getStatus()).isEqualTo(401);
    }

    private String planOf(UUID tenantId) {
        return jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId);
    }

    private List<String> historyOf(UUID tenantId) {
        return jdbc.queryForList("SELECT coalesce(from_plan, '-') || '>' || to_plan || ':' || cause "
                + "FROM public.tenant_plan_events WHERE tenant_id = ? ORDER BY occurred_at", String.class, tenantId);
    }
}

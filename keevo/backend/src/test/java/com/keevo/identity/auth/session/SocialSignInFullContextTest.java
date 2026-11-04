package com.keevo.identity.auth.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.adapter.out.social.JwksClient;
import com.keevo.identity.auth.adapter.out.social.SocialTestTokens;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 19.3 — Google / Apple sign-in on the real HTTP chain and PostgreSQL, with tokens signed by a local key whose
 * JWKS replaces the provider's (no network): new account without company → create-company → tokens usable; the same
 * verified email through Apple links the same account; refused tokens answer 401.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "keevo.auth.social.google.client-ids=web.apps.googleusercontent.com, test-google-client",
        "keevo.auth.social.apple.audiences=com.keevo.app,com.brefpoint.app"})
@DisplayName("19.3 — Google and Apple sign-in (full context)")
class SocialSignInFullContextTest extends FullContextIntegrationTest {

    private static final SocialTestTokens PROVIDER = new SocialTestTokens("test-kid");
    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwksClient jwksClient;

    @BeforeEach
    void stubJwks() {
        when(jwksClient.fetch(anyString())).thenReturn(new JwksClient.JwksDocument(PROVIDER.jwks(), null));
    }

    private MvcResult postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "10.19.3." + IP.getAndIncrement())
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String googleToken(String subject, String email) {
        return PROVIDER.token("https://accounts.google.com", "test-google-client", subject,
                Instant.now().plusSeconds(600), Map.of("email", email, "email_verified", true, "given_name", "Ana"));
    }

    private static String appleToken(String subject, String email) {
        Map<String, Object> claims = email == null ? Map.of() : Map.of("email", email, "email_verified", "true");
        return PROVIDER.token("https://appleid.apple.com", "com.brefpoint.app", subject,
                Instant.now().plusSeconds(600), claims);
    }

    @Test
    @DisplayName("Google → new account (memberships []) → create-company 201 → profile 200; Apple same email → same user")
    void googleThenCreateCompanyThenApple() throws Exception {
        String email = "soc-" + UUID.randomUUID().toString().substring(0, 8) + "@Ex.com";
        String googleSub = "g-" + UUID.randomUUID();

        MvcResult signedIn = postJson("/api/v1/auth/social/google", Map.of("idToken", googleToken(googleSub, email)));
        assertThat(signedIn.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(signedIn).get("memberships")).isEmpty();
        Map<String, Object> user = jdbc.queryForMap("SELECT id, email, first_name, password_hash, phone_number, "
                + "email_verified_at FROM public.users WHERE lower(email) = lower(?)", email);
        assertThat(user.get("email")).isEqualTo(email.toLowerCase());
        assertThat(user.get("first_name")).isEqualTo("Ana");
        assertThat(user.get("password_hash")).isNull();
        assertThat(user.get("phone_number")).isNull();
        assertThat(user.get("email_verified_at")).isNotNull();

        MvcResult created = postJson("/api/v1/auth/create-company",
                Map.of("loginToken", json(signedIn).get("loginToken").asText(), "deviceId", "dev-social"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(created).get("userId").asText()).isEqualTo(user.get("id").toString());
        MvcResult profile = mockMvc.perform(get("/api/v1/auth/profile")
                .header("Authorization", "Bearer " + json(created).get("token").asText())).andReturn();
        assertThat(profile.getResponse().getStatus()).isEqualTo(200);

        // Google again → same account, now with its company.
        MvcResult again = postJson("/api/v1/auth/social/google", Map.of("idToken", googleToken(googleSub, email)));
        assertThat(json(again).get("memberships")).hasSize(1);

        // Apple with the same verified email → linked to the same account: one user, two authenticators.
        String appleSub = "a-" + UUID.randomUUID();
        MvcResult apple = postJson("/api/v1/auth/social/apple",
                Map.of("identityToken", appleToken(appleSub, email.toLowerCase())));
        assertThat(apple.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(apple).get("memberships")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.users WHERE lower(email) = lower(?)",
                Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT provider FROM public.user_authenticators WHERE user_id = ? "
                + "ORDER BY provider", String.class, user.get("id"))).containsExactly("APPLE", "GOOGLE");

        // Apple later without email (after first consent) → found by subject.
        MvcResult noEmail = postJson("/api/v1/auth/social/apple", Map.of("identityToken", appleToken(appleSub, null)));
        assertThat(json(noEmail).get("memberships")).hasSize(1);
    }

    @Test
    @DisplayName("Apple without email, unknown subject → account identified by its technical username only")
    void appleWithoutEmail() throws Exception {
        String appleSub = "a-" + UUID.randomUUID();
        MvcResult r = postJson("/api/v1/auth/social/apple",
                Map.of("identityToken", appleToken(appleSub, null), "firstName", "Paul", "lastName", "Nkoulou"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r).get("memberships")).isEmpty();
        Map<String, Object> user = jdbc.queryForMap("SELECT u.username, u.email, u.phone_number, u.first_name "
                + "FROM public.users u JOIN public.user_authenticators a ON a.user_id = u.id "
                + "WHERE a.provider = 'APPLE' AND a.subject = ?", appleSub);
        assertThat((String) user.get("username")).matches("^apple-[0-9a-f]{12}$");
        assertThat(user.get("email")).isNull();
        assertThat(user.get("phone_number")).isNull();
        assertThat(user.get("first_name")).isEqualTo("Paul");
    }

    @Test
    @DisplayName("garbage, wrong audience or other key → 401 SOCIAL_TOKEN_INVALID; employee email → 409")
    void refusals() throws Exception {
        MvcResult garbage = postJson("/api/v1/auth/social/google", Map.of("idToken", "garbage"));
        assertThat(garbage.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(garbage).get("domainCode").asText()).isEqualTo("SOCIAL_TOKEN_INVALID");

        String wrongAud = PROVIDER.token("https://accounts.google.com", "someone-else", "g-x",
                Instant.now().plusSeconds(600), Map.of());
        assertThat(postJson("/api/v1/auth/social/google", Map.of("idToken", wrongAud))
                .getResponse().getStatus()).isEqualTo(401);

        String forged = new SocialTestTokens("test-kid").token("https://appleid.apple.com", "com.brefpoint.app",
                "a-x", Instant.now().plusSeconds(600), Map.of());
        assertThat(postJson("/api/v1/auth/social/apple", Map.of("identityToken", forged))
                .getResponse().getStatus()).isEqualTo(401);

        String employeeEmail = "emp-" + UUID.randomUUID().toString().substring(0, 8) + "@ex.com";
        jdbc.update("INSERT INTO public.users (id, email, role, is_active, failed_attempts, email_verified_at, "
                + "created_at, updated_at) VALUES (?, ?, 'EMPLOYEE', true, 0, now(), now(), now())",
                UUID.randomUUID(), employeeEmail);
        MvcResult conflict = postJson("/api/v1/auth/social/google",
                Map.of("idToken", googleToken("g-" + UUID.randomUUID(), employeeEmail)));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(conflict).get("domainCode").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
    }
}

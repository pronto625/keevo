package com.keevo.identity.auth.adapter.out.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.port.out.AppleTokenClient;
import com.keevo.shared.MutableTestClock;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Story 19.3b — Apple server API adapter against a mocked HTTP server (never the real Apple). */
@DisplayName("HttpAppleTokenClient (Story 19.3b)")
class HttpAppleTokenClientTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final String CLIENT = "com.keevo.app";

    private KeyPair keyPair;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private HttpAppleTokenClient client;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new HttpAppleTokenClient(restTemplate, new ObjectMapper(), "TEAM123", "KEY456", keyPair.getPrivate(),
                Set.of(CLIENT), new MutableTestClock(NOW));
    }

    private static Map<String, String> form(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String unsignedIdToken(String sub) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(StandardCharsets.UTF_8));
        return "eyJhbGciOiJSUzI1NiJ9." + payload + ".sig";
    }

    @Test
    @DisplayName("client secret: ES256, kid, iss = team, sub = client id, aud = appleid, 5 min")
    void clientSecret() {
        Jws<Claims> jws = Jwts.parser().verifyWith(keyPair.getPublic())
                .clock(() -> Date.from(NOW)).build().parseSignedClaims(client.clientSecret(CLIENT));
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("ES256");
        assertThat(jws.getHeader().getKeyId()).isEqualTo("KEY456");
        assertThat(jws.getPayload().getIssuer()).isEqualTo("TEAM123");
        assertThat(jws.getPayload().getSubject()).isEqualTo(CLIENT);
        assertThat(jws.getPayload().getAudience()).containsExactly("https://appleid.apple.com");
        assertThat(jws.getPayload().getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("exchange: form POST to /auth/token, refresh token + id_token subject returned")
    void exchange() {
        server.expect(requestTo(HttpAppleTokenClient.TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(request -> {
                    Map<String, String> f = form(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                    assertThat(f).containsEntry("client_id", CLIENT).containsEntry("code", "the-code")
                            .containsEntry("grant_type", "authorization_code").containsKey("client_secret");
                })
                .andRespond(withSuccess("{\"access_token\":\"at\",\"refresh_token\":\"rt-1\",\"id_token\":\""
                        + unsignedIdToken("apple-sub") + "\"}", MediaType.APPLICATION_JSON));

        assertThat(client.exchange(CLIENT, "the-code"))
                .contains(new AppleTokenClient.AppleGrant("rt-1", "apple-sub"));
        server.verify();
    }

    @Test
    @DisplayName("exchange: 400 invalid_grant or no refresh token → empty; unknown client id → empty, no call")
    void exchangeRefused() {
        server.expect(requestTo(HttpAppleTokenClient.TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid_grant\"}"));
        server.expect(requestTo(HttpAppleTokenClient.TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"at\"}", MediaType.APPLICATION_JSON));

        assertThat(client.exchange(CLIENT, "c1")).isEmpty();
        assertThat(client.exchange(CLIENT, "c2")).isEmpty();
        assertThat(client.exchange("com.other.app", "c3")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("revoke: form POST to /auth/revoke with token_type_hint; 5xx → exception (retried by the job)")
    void revoke() {
        server.expect(requestTo(HttpAppleTokenClient.REVOKE_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(form(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
                        .containsEntry("client_id", CLIENT).containsEntry("token", "rt-1")
                        .containsEntry("token_type_hint", "refresh_token"))
                .andRespond(withSuccess());
        server.expect(requestTo(HttpAppleTokenClient.REVOKE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        client.revoke(CLIENT, "rt-1");
        assertThatThrownBy(() -> client.revoke(CLIENT, "rt-2")).isInstanceOf(RestClientException.class);
        assertThatThrownBy(() -> client.revoke("com.other.app", "rt-3")).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    // ── AppleRevocationConfig ────────────────────────────────────────────────

    private final SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

    private String pem() {
        return "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----";
    }

    @Test
    @DisplayName("config: disabled by default → Null Object adapter")
    void configDisabled() {
        AppleTokenClient c = AppleRevocationConfig.create(new MockEnvironment(), cipher, new ObjectMapper(),
                new MutableTestClock(NOW));
        assertThat(c).isInstanceOf(DisabledAppleTokenClient.class);
        assertThat(c.enabled()).isFalse();
        assertThat(c.exchange(CLIENT, "code")).isEmpty();
    }

    @Test
    @DisplayName("config: enabled with key (one-line PEM) → HTTP adapter; client ids default to the Apple audiences")
    void configEnabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(AppleRevocationConfig.PREFIX + "enabled", "true")
                .withProperty(AppleRevocationConfig.PREFIX + "team-id", "TEAM123")
                .withProperty(AppleRevocationConfig.PREFIX + "key-id", "KEY456")
                .withProperty(AppleRevocationConfig.PREFIX + "private-key", pem())
                .withProperty("keevo.auth.social.apple.audiences", CLIENT);
        AppleTokenClient c = AppleRevocationConfig.create(env, cipher, new ObjectMapper(), new MutableTestClock(NOW));
        assertThat(c).isInstanceOf(HttpAppleTokenClient.class);
        assertThat(c.enabled()).isTrue();
    }

    @Test
    @DisplayName("config: enabled but missing key, bad key, no client id or no cipher key → startup fails, value never echoed")
    void configInvalid() {
        MockEnvironment base = new MockEnvironment()
                .withProperty(AppleRevocationConfig.PREFIX + "enabled", "true")
                .withProperty(AppleRevocationConfig.PREFIX + "team-id", "TEAM123")
                .withProperty(AppleRevocationConfig.PREFIX + "key-id", "KEY456")
                .withProperty("keevo.auth.social.apple.audiences", CLIENT);
        ObjectMapper om = new ObjectMapper();
        MutableTestClock clock = new MutableTestClock(NOW);

        assertThatThrownBy(() -> AppleRevocationConfig.create(base, cipher, om, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("private-key");
        base.setProperty(AppleRevocationConfig.PREFIX + "private-key", "not-a-secret-key-value");
        assertThatThrownBy(() -> AppleRevocationConfig.create(base, cipher, om, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not-a-secret-key-value");
        base.setProperty(AppleRevocationConfig.PREFIX + "private-key", pem());
        assertThatThrownBy(() -> AppleRevocationConfig.create(base, new SecretCipher(""), om, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("KEEVO_SECRETS_ENCRYPTION_KEY");
        base.setProperty("keevo.auth.social.apple.audiences", "");
        assertThatThrownBy(() -> AppleRevocationConfig.create(base, cipher, om, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("client-ids");
    }
}

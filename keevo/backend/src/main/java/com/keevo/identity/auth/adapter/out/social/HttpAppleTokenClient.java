package com.keevo.identity.auth.adapter.out.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.port.out.AppleTokenClient;
import io.jsonwebtoken.Jwts;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

/**
 * Story 19.3b — Sign in with Apple server API over HTTP: {@code POST /auth/token} (authorization code → refresh token)
 * and {@code POST /auth/revoke}. Each call carries a fresh client secret: an ES256 JWT signed with the team's Sign in
 * with Apple key ({@code kid} = key id, {@code iss} = team id, {@code sub} = client id, {@code aud} =
 * {@value #APPLE_AUDIENCE}, valid {@link #CLIENT_SECRET_TTL}).
 *
 * <p>Only client ids listed in {@code allowedClientIds} are used (the bundles / Services IDs of the configured team).
 * Neither the key, the secret, the code nor the tokens are ever logged.
 */
public class HttpAppleTokenClient implements AppleTokenClient {

    static final String APPLE_AUDIENCE = "https://appleid.apple.com";
    static final String TOKEN_URL = "https://appleid.apple.com/auth/token";
    static final String REVOKE_URL = "https://appleid.apple.com/auth/revoke";
    static final Duration CLIENT_SECRET_TTL = Duration.ofMinutes(5);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String teamId;
    private final String keyId;
    private final PrivateKey privateKey;
    private final Set<String> allowedClientIds;
    private final Clock clock;

    public HttpAppleTokenClient(RestTemplate restTemplate, ObjectMapper objectMapper, String teamId, String keyId,
                                PrivateKey privateKey, Set<String> allowedClientIds, Clock clock) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.teamId = teamId;
        this.keyId = keyId;
        this.privateKey = privateKey;
        this.allowedClientIds = Set.copyOf(allowedClientIds);
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Optional<AppleGrant> exchange(String clientId, String authorizationCode) {
        if (!allowedClientIds.contains(clientId)) {
            return Optional.empty();
        }
        MultiValueMap<String, String> form = baseForm(clientId);
        form.add("code", authorizationCode);
        form.add("grant_type", "authorization_code");
        String body;
        try {
            body = post(TOKEN_URL, form).getBody();
        } catch (HttpClientErrorException e) {
            // 400 invalid_grant / invalid_client: the code is refused (expired, used, other bundle)
            return Optional.empty();
        }
        try {
            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            String refreshToken = json.path("refresh_token").asText(null);
            String subject = subjectOf(json.path("id_token").asText(null));
            if (refreshToken == null || refreshToken.isBlank() || subject == null) {
                return Optional.empty();
            }
            return Optional.of(new AppleGrant(refreshToken, subject));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unreadable Apple token response");
        }
    }

    @Override
    public void revoke(String clientId, String refreshToken) {
        if (!allowedClientIds.contains(clientId)) {
            throw new IllegalStateException("Apple client id not configured for revocation");
        }
        MultiValueMap<String, String> form = baseForm(clientId);
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        post(REVOKE_URL, form); // non-2xx → RestClientException → retried by the job
    }

    /** Client secret JWT (ES256), see the class comment. Package-private for tests. */
    String clientSecret(String clientId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .header().keyId(keyId).and()
                .issuer(teamId)
                .subject(clientId)
                .audience().add(APPLE_AUDIENCE).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(CLIENT_SECRET_TTL)))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    private MultiValueMap<String, String> baseForm(String clientId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret(clientId));
        return form;
    }

    private ResponseEntity<String> post(String url, MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return restTemplate.postForEntity(url, new HttpEntity<>(form, headers), String.class);
    }

    /**
     * The {@code sub} of the id_token Apple returned on its own TLS response (read, not re-verified: it only guards
     * that the code belongs to the Apple user who signed in).
     */
    private String subjectOf(String idToken) throws java.io.IOException {
        if (idToken == null) {
            return null;
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        String sub = objectMapper.readTree(new String(payload, StandardCharsets.UTF_8)).path("sub").asText(null);
        return sub == null || sub.isBlank() ? null : sub;
    }
}

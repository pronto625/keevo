package com.keevo.identity.auth.adapter.out.social;

import com.keevo.identity.auth.domain.model.SocialProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Story 19.3 — the two {@link JwksSocialTokenVerifier} strategies (Google, Apple), from Bref's configuration:
 * {@code keevo.auth.social.google.client-ids} ({@code GOOGLE_CLIENT_IDS}) and {@code keevo.auth.social.apple.audiences}
 * ({@code APPLE_BUNDLE_IDS}; when empty, Bref's single {@code APPLE_BUNDLE_ID}), comma lists. An empty list disables that
 * provider (every token refused, as in Bref).
 */
@Configuration
public class SocialAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(SocialAuthConfig.class);

    static final String JWKS_REST_TEMPLATE = "socialJwksRestTemplate";
    static final int CONNECT_TIMEOUT_MS = 5_000;
    static final int READ_TIMEOUT_MS = 10_000;
    static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    static final String APPLE_JWKS = "https://appleid.apple.com/auth/keys";
    static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    static final Set<String> APPLE_ISSUERS = Set.of("https://appleid.apple.com");

    /** JWKS fetches: connect 5 s, read 10 s (same bounds as the 19.9 provider calls, identity's own client). */
    @Bean(JWKS_REST_TEMPLATE)
    public RestTemplate socialJwksRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    @Bean
    public JwksSocialTokenVerifier googleTokenVerifier(
            @Value("${keevo.auth.social.google.client-ids:}") String clientIds, JwksClient jwksClient, Clock clock) {
        return configured(new JwksSocialTokenVerifier(SocialProvider.GOOGLE, GOOGLE_ISSUERS, GOOGLE_JWKS,
                commaList(clientIds), JwksSocialTokenVerifier.EmailRule.REJECT_UNVERIFIED, jwksClient, clock));
    }

    @Bean
    public JwksSocialTokenVerifier appleTokenVerifier(
            @Value("${keevo.auth.social.apple.audiences:}") String audiences,
            @Value("${keevo.auth.social.apple.bundle-id:}") String bundleId, JwksClient jwksClient, Clock clock) {
        List<String> accepted = commaList(audiences).isEmpty() ? commaList(bundleId) : commaList(audiences);
        return configured(new JwksSocialTokenVerifier(SocialProvider.APPLE, APPLE_ISSUERS, APPLE_JWKS,
                accepted, JwksSocialTokenVerifier.EmailRule.IGNORE_UNVERIFIED, jwksClient, clock));
    }

    private static JwksSocialTokenVerifier configured(JwksSocialTokenVerifier verifier) {
        if (!verifier.isConfigured()) {
            log.warn("[social] {} sign-in disabled: no audience configured", verifier.provider());
        }
        return verifier;
    }

    static List<String> commaList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}

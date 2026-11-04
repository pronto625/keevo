package com.keevo.identity.auth.adapter.out.social;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story 19.3 — JWKS over HTTP with identity's own client {@code socialJwksRestTemplate} (connect 5 s, read 10 s, as the
 * 19.9 providers), so a slow provider never holds a sign-in request longer.
 */
@Component
public class RestTemplateJwksClient implements JwksClient {

    private static final Pattern MAX_AGE = Pattern.compile("max-age\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final RestTemplate http;

    public RestTemplateJwksClient(@Qualifier(SocialAuthConfig.JWKS_REST_TEMPLATE) RestTemplate http) {
        this.http = http;
    }

    @Override
    public JwksDocument fetch(String uri) {
        ResponseEntity<String> response = http.getForEntity(uri, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("JWKS fetch failed (HTTP " + response.getStatusCode().value() + ")");
        }
        return new JwksDocument(response.getBody(), maxAge(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)));
    }

    /** {@code max-age} of a {@code Cache-Control} header, null when absent or unreadable. */
    static Duration maxAge(String cacheControl) {
        if (cacheControl == null) {
            return null;
        }
        Matcher m = MAX_AGE.matcher(cacheControl);
        if (!m.find()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(m.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.keevo.identity.auth.adapter.out.social;

import java.time.Duration;

/**
 * Story 19.3 — fetches a provider's published signing keys (JWKS document). A seam so the verification can be tested
 * with locally generated keys.
 */
public interface JwksClient {

    /**
     * @param uri the JWKS URL
     * @return the document and how long it may be cached ({@code Cache-Control: max-age}, null when absent)
     * @throws RuntimeException when the keys cannot be fetched
     */
    JwksDocument fetch(String uri);

    /** Raw JWKS JSON and its cache lifetime (null = not stated by the provider). */
    record JwksDocument(String json, Duration maxAge) {
    }
}

package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;

/**
 * Story 19.3 — Strategy: verifies one provider's ID token locally (signature against the provider's published keys,
 * issuer, audience among the configured ones, expiry) and returns the identity it proves.
 */
public interface SocialTokenVerifier {

    /** The provider whose tokens this verifier accepts. */
    SocialProvider provider();

    /**
     * @param token the raw ID token (JWT) sent by the app
     * @return the proven identity
     * @throws com.keevo.shared.domain.exception.DomainException SOCIAL_TOKEN_INVALID on any failure (bad signature,
     *         issuer, audience, expiry, unverified Google email, provider not configured, keys unavailable)
     */
    SocialIdentity verify(String token);
}

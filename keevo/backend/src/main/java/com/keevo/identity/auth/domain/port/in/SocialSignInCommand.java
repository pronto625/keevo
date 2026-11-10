package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.SocialProvider;

/**
 * Story 19.3 — Google or Apple sign-in: the provider, its ID token, the optional names typed or sent by the app
 * (Apple sends them at first consent only) and the caller's address (per-IP limit; null = not limited).
 *
 * <p>Story 19.3b — optional clear {@code nonce} (replay protection) and Apple {@code authorizationCode} (kept to
 * revoke the link at account deletion).
 */
public record SocialSignInCommand(SocialProvider provider, String token, String firstName, String lastName,
                                  String clientIp, String nonce, String authorizationCode) {

    /** Without nonce nor authorization code (19.3 shape). */
    public SocialSignInCommand(SocialProvider provider, String token, String firstName, String lastName,
                               String clientIp) {
        this(provider, token, firstName, lastName, clientIp, null, null);
    }

    @Override
    public String toString() {
        return "SocialSignInCommand{provider=" + provider + "}";
    }
}

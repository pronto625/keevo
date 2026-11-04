package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.SocialProvider;

/**
 * Story 19.3 — Google or Apple sign-in: the provider, its ID token, the optional names typed or sent by the app
 * (Apple sends them at first consent only) and the caller's address (per-IP limit; null = not limited).
 */
public record SocialSignInCommand(SocialProvider provider, String token, String firstName, String lastName,
                                  String clientIp) {

    @Override
    public String toString() {
        return "SocialSignInCommand{provider=" + provider + "}";
    }
}

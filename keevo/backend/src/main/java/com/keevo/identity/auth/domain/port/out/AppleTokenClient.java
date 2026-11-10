package com.keevo.identity.auth.domain.port.out;

import java.util.Optional;

/**
 * Story 19.3b — Sign in with Apple server API (App Store account-deletion requirement): exchanges the app's
 * authorization code for a refresh token, and revokes that token when the account is deleted or Apple is unlinked.
 *
 * <p>The default adapter is disabled (no network call, nothing stored); the HTTP adapter is enabled by
 * {@code keevo.auth.social.apple.revocation.enabled}. {@code clientId} is the bundle / Services ID the code was
 * issued for (the identity token's audience).
 */
public interface AppleTokenClient {

    /** True when Apple's server API is configured (otherwise no code is exchanged and nothing is revoked). */
    boolean enabled();

    /**
     * Exchanges a one-time authorization code ({@code grant_type=authorization_code}).
     *
     * @return the grant, or empty when Apple refused the code or answered without a refresh token
     * @throws RuntimeException on a network / server failure (the caller logs and goes on)
     */
    Optional<AppleGrant> exchange(String clientId, String authorizationCode);

    /**
     * Revokes a refresh token. Apple answers 200 for an already revoked or unknown token.
     *
     * @throws RuntimeException when the revocation was not acknowledged (retried later)
     */
    void revoke(String clientId, String refreshToken);

    /** Refresh token and the Apple user ({@code sub}) it belongs to. {@link #toString()} prints neither. */
    record AppleGrant(String refreshToken, String subject) {
        @Override
        public String toString() {
            return "AppleGrant{}";
        }
    }
}

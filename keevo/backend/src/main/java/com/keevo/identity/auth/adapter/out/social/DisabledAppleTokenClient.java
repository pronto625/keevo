package com.keevo.identity.auth.adapter.out.social;

import com.keevo.identity.auth.domain.port.out.AppleTokenClient;

import java.util.Optional;

/**
 * Story 19.3b — Null Object adapter, the default: Apple's server API is not configured, no network call is ever made,
 * no code is exchanged (so no token is stored and nothing is queued for revocation).
 */
public class DisabledAppleTokenClient implements AppleTokenClient {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Optional<AppleGrant> exchange(String clientId, String authorizationCode) {
        return Optional.empty();
    }

    @Override
    public void revoke(String clientId, String refreshToken) {
        throw new IllegalStateException("Apple token revocation is not configured");
    }
}

package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.DeviceInfo;

import com.keevo.identity.auth.domain.model.AuthTokens;

/**
 * RefreshTokenUseCase — Driving port for token refresh.
 *
 * <p>Accepts raw opaque refresh token string, validates it, and issues new tokens.
 */
public interface RefreshTokenUseCase {

    /**
     * Issue new access + refresh token pair using a valid refresh token.
     *
     * @param rawRefreshToken raw opaque refresh token from client
     * @param legacyClient    Story 19.10 — the app declares no version (V1): a MANAGER / VIEWER session is refused
     * @return new token pair
     * @throws com.keevo.shared.domain.exception.DomainException REFRESH_TOKEN_INVALID, APP_UPDATE_REQUIRED
     */
    AuthTokens refresh(String rawRefreshToken, DeviceInfo device, boolean legacyClient);

    /** Not a legacy client. */
    default AuthTokens refresh(String rawRefreshToken, DeviceInfo device) {
        return refresh(rawRefreshToken, device, false);
    }

    /** Refresh without device data (the session keeps its known device). */
    default AuthTokens refresh(String rawRefreshToken) {
        return refresh(rawRefreshToken, DeviceInfo.NONE);
    }

    /**
     * Logout: revoke the refresh token so it can never renew a session again.
     * Unknown or already revoked tokens are ignored (idempotent).
     */
    void revoke(String rawRefreshToken);
}

package com.keevo.identity.auth.domain.port.in;

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
     * @return new token pair
     * @throws com.keevo.shared.domain.exception.DomainException REFRESH_TOKEN_INVALID
     */
    AuthTokens refresh(String rawRefreshToken);
}

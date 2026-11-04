package com.keevo.identity.auth.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

import java.util.UUID;

/**
 * Login-step-1 token check shared by {@link SelectTenantService} and {@link CreateCompanyService} (Story 19.2 — one
 * rule, one set of errors).
 */
final class LoginTokens {

    private LoginTokens() {
    }

    /**
     * Parses the loginToken, verifies it has scope=login_pending, and extracts the userId.
     *
     * @throws DomainException TOKEN_EXPIRED if the token is expired
     * @throws DomainException TOKEN_INVALID if scope != login_pending or token is malformed
     */
    static UUID parseUserId(JwtTokenProvider jwtTokenProvider, String loginToken) {
        try {
            Claims claims = jwtTokenProvider.parseToken(loginToken);
            String scope = jwtTokenProvider.extractScope(claims);
            if (!"login_pending".equals(scope)) {
                // access token used as loginToken — reject
                throw new DomainException(ErrorCode.TOKEN_INVALID);
            }
            return jwtTokenProvider.extractUserId(claims);
        } catch (ExpiredJwtException e) {
            throw new DomainException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException e) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
    }
}

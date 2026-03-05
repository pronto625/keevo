package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.AuthTokens;

/**
 * AuthenticateUserUseCase — Driving port for login.
 *
 * <p>Architecture rules:
 * - Accepts only pure Java records (no HTTP types)
 * - Returns domain model (AuthTokens)
 * - Throws DomainException on failure
 */
public interface AuthenticateUserUseCase {

    /**
     * Authenticate a user with phone number and password.
     *
     * @param command login credentials
     * @return access + refresh token pair on success
     * @throws com.keevo.shared.domain.exception.DomainException INVALID_CREDENTIALS, ACCOUNT_LOCKED
     */
    AuthTokens authenticate(AuthenticateUserCommand command);
}

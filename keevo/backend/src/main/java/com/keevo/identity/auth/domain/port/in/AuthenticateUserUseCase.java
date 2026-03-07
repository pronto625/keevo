package com.keevo.identity.auth.domain.port.in;

/**
 * AuthenticateUserUseCase — Driving port for login step 1 (Story 1.7 two-step login).
 *
 * <p>Architecture rules:
 * - Accepts only pure Java records (no HTTP types)
 * - Returns {@link LoginSessionResult} containing loginToken + memberships list
 * - Throws DomainException on failure (INVALID_CREDENTIALS, ACCOUNT_LOCKED)
 *
 * <p>Step 2 (tenant selection) is handled by {@link SelectTenantUseCase}.
 */
public interface AuthenticateUserUseCase {

    /**
     * Authenticate a user with phone number and password (step 1 of two-step login).
     *
     * @param command login credentials
     * @return loginToken (5min) + memberships list
     * @throws com.keevo.shared.domain.exception.DomainException INVALID_CREDENTIALS, ACCOUNT_LOCKED
     */
    LoginSessionResult authenticate(AuthenticateUserCommand command);
}

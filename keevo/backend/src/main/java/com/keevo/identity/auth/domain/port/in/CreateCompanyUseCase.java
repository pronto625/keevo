package com.keevo.identity.auth.domain.port.in;

/**
 * Story 19.2 — provisions a company for the user of a {@code loginToken} exactly like {@code /auth/register}, and opens
 * its session.
 */
public interface CreateCompanyUseCase {

    RegistrationResult create(CreateCompanyCommand command);
}

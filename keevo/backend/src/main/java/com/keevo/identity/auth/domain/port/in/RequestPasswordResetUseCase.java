package com.keevo.identity.auth.domain.port.in;

/**
 * RequestPasswordResetUseCase — Port-in for the forgot-password use case.
 *
 * <p>Story 14.12 — Always returns silently (never throws), per AC2/D1 anti-enumeration.
 */
@FunctionalInterface
public interface RequestPasswordResetUseCase {

    /**
     * Execute the forgot-password flow.
     *
     * <p>Never throws a domain exception — always returns (anti-enumeration).
     */
    void execute(RequestPasswordResetCommand command);
}

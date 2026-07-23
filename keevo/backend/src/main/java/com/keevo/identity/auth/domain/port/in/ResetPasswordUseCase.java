package com.keevo.identity.auth.domain.port.in;

/**
 * ResetPasswordUseCase — Port-in for the reset-password use case.
 *
 * <p>Story 14.12 — Throws DomainException on invalid/expired code, locked token,
 * or weak new password.
 */
@FunctionalInterface
public interface ResetPasswordUseCase {

    /**
     * Execute the password reset flow.
     *
     * @throws com.keevo.shared.domain.exception.DomainException with
     *         INVALID_OR_EXPIRED_CODE, CODE_LOCKED, or VALIDATION_FAILED
     */
    void execute(ResetPasswordCommand command);
}

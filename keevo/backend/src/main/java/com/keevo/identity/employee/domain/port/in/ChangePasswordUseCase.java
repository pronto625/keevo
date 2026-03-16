package com.keevo.identity.employee.domain.port.in;

import com.keevo.identity.auth.domain.model.AuthTokens;

/**
 * ChangePasswordUseCase — Driving port for employee password change.
 *
 * <p>Returns fresh AuthTokens after successful password change
 * (JWT without passwordChangeRequired flag).
 *
 * <p>Story 3.5 — AC4.
 */
public interface ChangePasswordUseCase {
    AuthTokens execute(ChangePasswordCommand command);
}

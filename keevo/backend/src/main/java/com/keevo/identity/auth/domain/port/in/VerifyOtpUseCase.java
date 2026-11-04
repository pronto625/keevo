package com.keevo.identity.auth.domain.port.in;

/**
 * Story 19.2 — checks a sign-in code and opens login step 1 exactly like {@code /auth/login}: a {@code loginToken}
 * and the user's memberships (empty for an account created by this call).
 */
public interface VerifyOtpUseCase {

    LoginSessionResult verify(VerifyOtpCommand command);
}

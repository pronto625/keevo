package com.keevo.identity.auth.domain.port.in;

/**
 * Story 19.3 — signs in with a Google or Apple ID token and opens login step 1 exactly like {@code /auth/login}: a
 * {@code loginToken} and the user's memberships (empty for an account created by this call → create-company).
 */
public interface SocialSignInUseCase {

    LoginSessionResult signIn(SocialSignInCommand command);
}

package com.keevo.identity.auth.domain.model;

/**
 * Story 19.3 — identity provider of a social sign-in. The name is stored in
 * {@code public.user_authenticators.provider} (CHECK constraint in V48).
 */
public enum SocialProvider {
    GOOGLE,
    APPLE
}

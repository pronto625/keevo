package com.keevo.identity.auth.domain.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Story 19.3 — identity proven by a verified Google or Apple ID token.
 *
 * <p>{@code subject} is the provider's stable user id ({@code sub}). {@code email} is the token's address, lowercased
 * and stripped, null when absent; it may only be used to link or create an account when {@code emailVerified}
 * ({@link #verifiedEmail()}). Names are optional (Google {@code given_name}/{@code family_name}; Apple sends none in
 * the token). {@link #toString()} prints no personal data.
 */
public record SocialIdentity(SocialProvider provider, String subject, String email, boolean emailVerified,
                             String firstName, String lastName) {

    public SocialIdentity {
        Objects.requireNonNull(provider, "provider must not be null");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        String mail = User.normalizeIdentifier(email);
        email = mail == null ? null : mail.toLowerCase(Locale.ROOT);
        emailVerified = email != null && emailVerified;
        firstName = User.normalizeIdentifier(firstName);
        lastName = User.normalizeIdentifier(lastName);
    }

    /** The email when the provider verified it, otherwise null (an unproven address is never linked nor stored). */
    public String verifiedEmail() {
        return emailVerified ? email : null;
    }

    @Override
    public String toString() {
        return "SocialIdentity{provider=" + provider + ", emailVerified=" + emailVerified + "}";
    }
}

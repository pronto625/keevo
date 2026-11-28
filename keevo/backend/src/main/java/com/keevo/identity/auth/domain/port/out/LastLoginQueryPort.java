package com.keevo.identity.auth.domain.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * LastLoginQueryPort — P02: the last sign-in of a user in the current company, read from the tenant audit trail
 * ({@code USER_AUTHENTICATED}). Driven port: keeps SQL out of {@code UserProfileService}.
 */
public interface LastLoginQueryPort {

    /** Latest {@code USER_AUTHENTICATED} of {@code userId} in the current tenant, empty when never recorded. */
    Optional<Instant> lastLoginAt(UUID userId);
}

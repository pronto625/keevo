package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.SocialProvider;

import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.3 — social identities linked to accounts ({@code public.user_authenticators}, unique
 * {@code (provider, subject)}).
 */
public interface UserAuthenticatorRepository {

    /** The account linked to this provider identity, if any (unique-index lookup). */
    Optional<UUID> findUserId(SocialProvider provider, String subject);

    /**
     * Links the identity to the account unless it is already linked ({@code ON CONFLICT (provider, subject) DO
     * NOTHING}: a concurrent first login never aborts the caller's transaction).
     *
     * @return true when the row was inserted
     */
    boolean insertIfAbsent(UUID userId, SocialProvider provider, String subject);
}

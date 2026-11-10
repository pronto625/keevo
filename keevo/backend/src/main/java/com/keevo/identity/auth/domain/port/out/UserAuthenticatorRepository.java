package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.SocialProvider;

import java.time.Instant;
import java.util.List;
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

    // ── Story 19.3b — account settings and Apple revocation ─────────────────

    /** One linked identity of an account (the API exposes the provider and date only). */
    record LinkedIdentity(UUID id, SocialProvider provider, String subject, Instant linkedAt) {
        @Override
        public String toString() {
            return "LinkedIdentity{provider=" + provider + "}";
        }
    }

    /** The account's linked identities, oldest first (index on {@code user_id}). */
    List<LinkedIdentity> findByUser(UUID userId);

    /** Row id of the identity, if linked (unique-index lookup). */
    Optional<UUID> findId(SocialProvider provider, String subject);

    /** Stores the encrypted Apple refresh token of the row and the client id it was issued for. */
    void storeRevocationToken(UUID authenticatorId, String clientId, byte[] encryptedToken);

    /**
     * Unlinks every identity of {@code provider} from the account: copies their stored revocation tokens to the
     * revocation outbox, then deletes the rows — two statements, in the caller's transaction.
     *
     * @return the number of identities unlinked
     */
    int unlink(UUID userId, SocialProvider provider);
}

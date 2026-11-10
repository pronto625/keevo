package com.keevo.identity.auth.domain.port.out;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.3b — transactional outbox of the Apple tokens to revoke ({@code public.apple_token_revocations}). Rows are
 * written by {@link UserAuthenticatorRepository} in the transaction that deletes their authenticator; this port
 * drains them.
 */
public interface AppleRevocationQueue {

    /** A queued token, encrypted with the authenticator row id as context. {@link #toString()} prints no secret. */
    record PendingRevocation(UUID id, UUID authenticatorId, String clientId, byte[] token, int attempts) {
        @Override
        public String toString() {
            return "PendingRevocation{id=" + id + ", attempts=" + attempts + "}";
        }
    }

    /** At most {@code limit} rows due now, oldest due first (index on {@code next_attempt_at}). */
    List<PendingRevocation> findDue(int limit);

    /** Deletes the rows (revoked, or never revocable). One statement. */
    int delete(Collection<UUID> ids);

    /**
     * Counts one more failed attempt on the rows and pushes them back (exponential backoff, capped at one day), then
     * deletes the rows that reached {@code maxAttempts}. Two set-based statements.
     *
     * @return the number of rows given up (deleted)
     */
    int recordFailures(Collection<UUID> ids, int maxAttempts);
}

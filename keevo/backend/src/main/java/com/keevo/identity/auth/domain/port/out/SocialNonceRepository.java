package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.SocialProvider;

import java.time.Instant;

/**
 * Story 19.3b — one-time nonces of verified social ID tokens ({@code public.social_nonces}, primary key
 * {@code (provider, nonce)}): a nonce consumed once can never sign in again before it expires.
 */
public interface SocialNonceRepository {

    /**
     * Records the nonce as used ({@code INSERT … ON CONFLICT DO NOTHING}, own statement).
     *
     * @return true when it was unused (row inserted), false on a replay
     */
    boolean consume(SocialProvider provider, String nonce, Instant expiresAt);

    /** Deletes the nonces expired before {@code cutoff} (one set-based DELETE). */
    int purgeExpiredBefore(Instant cutoff);
}

package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.OtpChallenge;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.2 — driven port for {@code public.otp_challenges}. Every method is one SQL statement on the
 * {@code (identifier, created_at DESC)} index or the primary key.
 */
public interface OtpChallengeRepository {

    /** Consumes every live (unconsumed) challenge of the identifier; returns the number of rows. */
    int invalidateActive(String identifier, Instant now);

    /** Stores a new challenge (code hash only). */
    void insert(UUID id, String identifier, String codeHash, Instant expiresAt, Instant createdAt);

    /** Latest unconsumed, unexpired challenge of the identifier, locked ({@code FOR UPDATE}) until the transaction ends. */
    Optional<OtpChallenge> lockLatestActive(String identifier, Instant now);

    /**
     * Counts one wrong code; the challenge is consumed when the count reaches {@code maxAttempts}.
     *
     * @return attempts after the increment
     */
    int recordFailure(UUID id, int maxAttempts, Instant now);

    /** Marks the challenge consumed (right code). */
    void consume(UUID id, Instant now);

    /** Sum of wrong codes on the identifier's challenges created since {@code since}. */
    int sumFailuresSince(String identifier, Instant since);

    /** Deletes challenges created before the cutoff; returns the number of rows. */
    int purgeCreatedBefore(Instant cutoff);
}

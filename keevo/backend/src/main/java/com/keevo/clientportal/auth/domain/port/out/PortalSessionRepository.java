package com.keevo.clientportal.auth.domain.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Story 21.7 — the portal's clients and sessions ({@code public.portal_clients}, {@code public.portal_sessions}). */
public interface PortalSessionRepository {

    /** The client of {@code phone}, created when absent; its last sign-in set to {@code now}. One statement. */
    UUID upsertClient(String phone, Instant now);

    /** Stores a session (hash only). */
    void insertSession(byte[] tokenHash, UUID clientId, Instant createdAt, Instant expiresAt);

    /** Deletes the client's sessions beyond the {@code keep} newest. @return sessions deleted */
    int trimSessions(UUID clientId, int keep);

    /** The phone of the live session {@code tokenHash} (not expired at {@code now}), by primary key. */
    Optional<String> phoneOfSession(byte[] tokenHash, Instant now);

    /** Deletes the session; idempotent. @return sessions deleted (0 = unknown token) */
    int deleteSession(byte[] tokenHash);

    /** Deletes the sessions expired before {@code cutoff}. @return rows deleted */
    int purgeExpiredBefore(Instant cutoff);
}

package com.keevo.clientportal.auth.domain.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Story 21.7 — the portal's clients and sessions ({@code public.portal_clients}, {@code public.portal_sessions}). */
public interface PortalSessionRepository {

    /**
     * Reprise 11/11/2026 — what a password sign-in needs of a client: its id, phone and BCrypt hash ({@code null} = the
     * client only ever signed in with a code).
     */
    record Credentials(UUID clientId, String phone, String passwordHash) {
    }

    /** The client of {@code phone} (E.164), by the unique phone index. */
    Optional<Credentials> findByPhone(String phone);

    /** The client of {@code email} (lower-cased), by the unique e-mail index. */
    Optional<Credentials> findByEmail(String email);

    /** Whether {@code email} (lower-cased) belongs to a client other than the one of {@code phone}. */
    boolean emailTakenByOther(String email, String phone);

    /**
     * Bref's registration: the client of {@code phone}, created when absent, gets its name, password hash and e-mail
     * ({@code null} keeps the current one); its last sign-in set to {@code now}. One statement.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the e-mail was taken meanwhile
     */
    UUID register(String phone, String name, String email, String passwordHash, Instant now);

    /** Sets the client's last sign-in (password sign-in). */
    void touchLogin(UUID clientId, Instant now);

    /** Deletes every session of the client (a new password ends the others). @return sessions deleted */
    int deleteSessionsOf(UUID clientId);

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

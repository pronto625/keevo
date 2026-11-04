package com.keevo.identity.auth.domain.port.in;

import java.util.UUID;

/**
 * AuthenticateUserCommand — Input record for the login use case.
 *
 * <p>Pure Java record — NO Spring, HTTP, or framework types.
 * ActorId is null for self-authentication (user logging in as themselves).
 *
 * <p>Story 19.4 — exactly one of {@code phoneNumber} / {@code username} is set (checked by the REST adapter): the
 * phone is only looked up as a phone, the username only as a username, so an all-digit username is never confused with
 * a phone typed without « + ».
 *
 * @param phoneNumber user-provided phone number (null on the username path)
 * @param password    user-provided plaintext password (hashed inside service)
 * @param actorId     null for self-authentication — NEVER sourced from SecurityContextHolder
 * @param username    user-provided username (null on the phone path) — Story 19.4
 * @param clientIp    caller IP for the per-IP brake on failed logins (null = not limited) — Story 19.4
 */
public record AuthenticateUserCommand(
    String phoneNumber,
    String password,
    UUID actorId,
    String username,
    String clientIp
) {
    /** Phone + password login (pre-19.4 shape). */
    public AuthenticateUserCommand(String phoneNumber, String password, UUID actorId) {
        this(phoneNumber, password, actorId, null, null);
    }

    /** Story 19.4 — phone + password login from an IP. */
    public static AuthenticateUserCommand byPhone(String phoneNumber, String password, String clientIp) {
        return new AuthenticateUserCommand(phoneNumber, password, null, null, clientIp);
    }

    /** Story 19.4 — username + password login. */
    public static AuthenticateUserCommand byUsername(String username, String password) {
        return byUsername(username, password, null);
    }

    /** Story 19.4 — username + password login from an IP. */
    public static AuthenticateUserCommand byUsername(String username, String password, String clientIp) {
        return new AuthenticateUserCommand(null, password, null, username, clientIp);
    }

    /** True when the command uses the username path. */
    public boolean usesUsername() {
        return username != null;
    }
}

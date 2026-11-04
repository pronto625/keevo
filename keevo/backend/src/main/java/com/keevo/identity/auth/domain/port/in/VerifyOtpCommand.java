package com.keevo.identity.auth.domain.port.in;

/**
 * Story 19.2 — sign-in code check: the identifier the code was sent to, the code typed and the caller's address
 * (per-IP limit; null = not limited by IP).
 */
public record VerifyOtpCommand(String identifier, String code, String clientIp) {

    /** Without a client address (not limited by IP). */
    public VerifyOtpCommand(String identifier, String code) {
        this(identifier, code, null);
    }
}

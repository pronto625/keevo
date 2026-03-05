package com.keevo.identity.auth.domain.port.in;

import java.util.UUID;

/**
 * AuthenticateUserCommand — Input record for the login use case.
 *
 * <p>Pure Java record — NO Spring, HTTP, or framework types.
 * ActorId is null for self-authentication (user logging in as themselves).
 *
 * @param phoneNumber user-provided phone number
 * @param password    user-provided plaintext password (hashed inside service)
 * @param actorId     null for self-authentication — NEVER sourced from SecurityContextHolder
 */
public record AuthenticateUserCommand(
    String phoneNumber,
    String password,
    UUID actorId
) {}

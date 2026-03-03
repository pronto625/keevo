package com.keevo.identity.auth.domain.port.in;

/**
 * RegisterUserCommand — Driving port input for user registration.
 *
 * <p>Pure Java record — NO HTTP/Spring/framework types.
 * MCP migration readiness: this record auto-serializes as MCP tool parameters.
 *
 * <p>actorId is null for self-registration (user creates their own account).
 */
public record RegisterUserCommand(
    String phoneNumber,
    String password,
    String actorId   // null for self-registration; admin-created accounts may set this
) {}

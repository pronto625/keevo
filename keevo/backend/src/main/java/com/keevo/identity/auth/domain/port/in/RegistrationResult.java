package com.keevo.identity.auth.domain.port.in;

/**
 * RegistrationResult — Output of a successful user registration.
 *
 * <p>Pure Java record — NO HTTP/Spring/framework types.
 */
public record RegistrationResult(
    String tenantCode,
    String token,
    String userId,
    String tenantId
) {}

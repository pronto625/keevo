package com.keevo.identity.auth.adapter.in.rest.dto;

/**
 * RegistrationResponse — REST output DTO for successful user registration.
 *
 * <p>Lives in the REST adapter layer.
 * HTTP 201 Created.
 */
public record RegistrationResponse(
    String tenantCode,
    String token,
    String userId,
    String tenantId
) {}

package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * RegistrationRequest — REST input DTO for user registration.
 *
 * <p>Lives in the REST adapter layer — NEVER passed to domain/use case directly.
 * Mapped to {@link com.keevo.identity.auth.domain.port.in.RegisterUserCommand} in controller.
 */
public record RegistrationRequest(

    @NotBlank(message = "Phone number is required")
    @Pattern(
        regexp = "^\\+?[0-9]{8,15}$",
        message = "Phone number must be 8-15 digits, optionally starting with +"
    )
    String phoneNumber,

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password

) {}

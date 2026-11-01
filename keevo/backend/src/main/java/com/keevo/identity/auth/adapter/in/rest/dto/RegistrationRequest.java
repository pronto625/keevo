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

    @NotBlank(message = "Le numéro de téléphone est obligatoire")
    @Pattern(
        regexp = "^\\+?[0-9]{8,15}$",
        message = "Numéro de téléphone invalide (8 à 15 chiffres, éventuellement précédés de +)"
    )
    String phoneNumber,

    @NotBlank(message = "Le mot de passe est obligatoire")
    @Size(min = 8, max = 72, message = "Le mot de passe doit contenir entre 8 et 72 caractères")
    String password

) {}

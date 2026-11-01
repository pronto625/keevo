package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * LoginRequest — DTO for POST /api/v1/auth/login.
 *
 * @param phoneNumber E.164 format phone number  e.g. +33612345678
 * @param password    Raw password (8–72 chars, BCrypt truncates at 72)
 */
public record LoginRequest(

    @NotBlank(message = "Le numéro de téléphone est obligatoire")
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Numéro de téléphone invalide (format international, ex. +237612345678)")
    String phoneNumber,

    @NotBlank(message = "Le mot de passe est obligatoire")
    @Size(min = 8, max = 72, message = "Le mot de passe doit contenir entre 8 et 72 caractères")
    String password
) {}

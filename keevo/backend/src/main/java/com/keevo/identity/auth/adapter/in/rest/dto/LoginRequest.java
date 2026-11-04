package com.keevo.identity.auth.adapter.in.rest.dto;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.Usernames;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * LoginRequest — DTO for POST /api/v1/auth/login.
 *
 * <p>Story 19.4 — exactly one of {@code phoneNumber} / {@code username} (a blank one counts as absent; checked by the
 * controller: both or neither → 422 {@code VALIDATION_FAILED}). The phone keeps its pattern and messages (the V1 app
 * sends only {@code phoneNumber}); its password is 8–72 characters. On the username path (Bref members, who may have
 * 6-character passwords) the password is 1–200 characters ({@link LoginPasswordLength}).
 *
 * @param phoneNumber E.164 format phone number  e.g. +33612345678
 * @param password    Raw password
 * @param username    member username (Story 19.4), compared case-insensitively
 */
@LoginPasswordLength
@Schema(description = "Send exactly one of phoneNumber or username, with the password.")
public record LoginRequest(

    @Schema(description = "E.164 phone number (phone path, V1). Exactly one of phoneNumber / username.",
            example = "+237612345678")
    @Pattern(regexp = "^\\s*$|^\\+[1-9]\\d{7,14}$",
             message = "Numéro de téléphone invalide (format international, ex. +237612345678)")
    String phoneNumber,

    @Schema(description = "Password: 8–72 characters on the phone path, 1–200 on the username path.")
    @NotBlank(message = "Le mot de passe est obligatoire")
    String password,

    @Schema(description = "Member username, compared case-insensitively (Story 19.4). Exactly one of phoneNumber / "
            + "username.", example = "awa_k", maxLength = 50)
    @Size(max = 50, message = Usernames.INVALID_MESSAGE)
    String username
) {
    /** Phone + password (the V1 shape). */
    public LoginRequest(String phoneNumber, String password) {
        this(phoneNumber, password, null);
    }

    /** True when a (non-blank) username is given. */
    public boolean hasUsername() {
        return User.normalizeIdentifier(username) != null;
    }

    /** True when a (non-blank) phone number is given. */
    public boolean hasPhoneNumber() {
        return User.normalizeIdentifier(phoneNumber) != null;
    }
}

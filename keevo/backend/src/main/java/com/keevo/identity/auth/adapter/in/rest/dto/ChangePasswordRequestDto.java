package com.keevo.identity.auth.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequestDto(
        @NotBlank @Size(max = 72) String currentPassword,
        @NotBlank @Size(min = 8, max = 72) String newPassword
) {}

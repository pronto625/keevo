package com.keevo.identity.employee.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

/** Story 19.6b — the login-step-1 token proving who answers an invitation before choosing a company. */
public record LoginTokenRequestDto(@NotBlank String loginToken) {
}

package com.keevo.identity.auth.adapter.in.rest.dto;

/** Story 19.2 — answer of /auth/otp/request: validity of the code in seconds (same whether or not an account exists). */
public record OtpRequestResponseDto(int expiresIn) {}

package com.keevo.identity.auth.adapter.in.rest.dto;

/** Story 22.19 (D17) — body of {@code PUT /api/v1/auth/profile}: each name 1–50 characters once trimmed. */
public record UpdateOwnProfileRequest(String firstName, String lastName) {
}

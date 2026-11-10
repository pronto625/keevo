package com.keevo.identity.auth.adapter.in.rest.dto;

import java.time.Instant;

/**
 * Story 19.3b — one Google / Apple link of the signed-in account: {@code provider} ({@code GOOGLE} | {@code APPLE},
 * for the app's logic), {@code label} (« Google » | « Apple », to display) and when it was linked.
 */
public record SocialAccountDto(String provider, String label, Instant linkedAt) {
}

package com.keevo.identity.auth.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 16.11 — one connected device (« Appareils connectés »).
 *
 * @param sessionId   opaque id used to close the session (never shown on screen)
 * @param deviceLabel readable label (model, system); « Appareil inconnu » when the app sent none
 * @param lastUsedAt  last sign-in or renewal
 * @param current     the device making the request
 */
public record DeviceSessionDto(UUID sessionId, String deviceLabel, Instant lastUsedAt, boolean current) {
}

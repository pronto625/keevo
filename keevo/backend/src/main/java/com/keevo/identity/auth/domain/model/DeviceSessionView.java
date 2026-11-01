package com.keevo.identity.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 16.11 — one open device session as listed in « Appareils connectés ».
 *
 * @param sessionId   opaque session id (the only id exposed; never a token hash)
 * @param deviceLabel readable label sent by the app, null when unknown
 * @param lastUsedAt  last sign-in / renewal of the session
 * @param current     true for the device making the request
 */
public record DeviceSessionView(UUID sessionId, String deviceLabel, Instant lastUsedAt, boolean current) {
}

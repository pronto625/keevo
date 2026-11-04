package com.keevo.messaging.notification.adapter.in.rest.dto;

/**
 * Story 19.9b — a browser's {@code PushSubscription}: {@code endpoint} and its {@code keys.p256dh} / {@code keys.auth}
 * (flattened, as Bref), optional {@code userAgent}. Checked by the service (422 {@code VALIDATION_FAILED}).
 */
public record WebPushSubscribeRequestDto(String endpoint, String p256dh, String auth, String userAgent) {
}

package com.keevo.messaging.notification.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.9b — subscription keys checked at subscribe. */
@DisplayName("PushKeys — p256dh on P-256, 16-byte auth (Story 19.9b)")
class WebPushKeysTest {

    // RFC 8291 Appendix A receiver keys
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    @Test
    @DisplayName("valid browser keys accepted, padded or not")
    void valid() {
        assertThat(PushKeys.isValid(UA_PUBLIC, AUTH)).isTrue();
        assertThat(PushKeys.isValid(UA_PUBLIC + "=", AUTH + "==")).isTrue();
    }

    @Test
    @DisplayName("not base64, wrong length, compressed, off-curve point, or auth not 16 bytes → refused")
    void invalid() {
        assertThat(PushKeys.isValid("not*base64", AUTH)).isFalse();
        assertThat(PushKeys.isValid("BKey", AUTH)).isFalse();
        assertThat(PushKeys.isValid("A" + UA_PUBLIC.substring(1), AUTH)).isFalse();
        String offCurve = UA_PUBLIC.substring(0, UA_PUBLIC.length() - 2) + "w8";
        assertThat(PushKeys.isValid(offCurve, AUTH)).isFalse();
        assertThat(PushKeys.isValid(UA_PUBLIC, "c2hvcnQ")).isFalse();
        assertThat(PushKeys.isValid(UA_PUBLIC, null)).isFalse();
    }
}

package com.keevo.messaging.notification.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.9b — Bref's push services allow-list (SSRF guard). */
@DisplayName("PushEndpoints — browser push services allow-list (Story 19.9b)")
class WebPushEndpointsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://fcm.googleapis.com/fcm/send/abc:APA91bE",
            "https://updates.push.services.mozilla.com/wpush/v2/gAAAA",
            "https://updates-autopush.stage.mozaws.net/wpush/v1/x",
            "https://updates-autopush.dev.mozaws.net/wpush/v1/x",
            "https://web.push.apple.com/QGvO",
            "https://wns2-par02p.notify.windows.com/w/?token=AwYAAAB",
            "https://FCM.googleapis.com:443/fcm/send/x"})
    void allowed(String endpoint) {
        assertThat(PushEndpoints.isAllowed(endpoint)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "https://evil.example/x",
            "http://fcm.googleapis.com/fcm/send/x",
            "https://user:pw@fcm.googleapis.com/fcm/send/x",
            "https://fcm.googleapis.com:8443/fcm/send/x",
            "https://fcm.googleapis.com.evil.example/x",
            "https://push.apple.com.evil.example/x",
            "not a url",
            "ftp://fcm.googleapis.com/x"})
    void refused(String endpoint) {
        assertThat(PushEndpoints.isAllowed(endpoint)).isFalse();
    }

    @Test
    @DisplayName("more than 2048 characters refused")
    void tooLong() {
        assertThat(PushEndpoints.isAllowed("https://fcm.googleapis.com/" + "a".repeat(2048))).isFalse();
    }

    @Test
    @DisplayName("origin = VAPID audience")
    void origin() {
        assertThat(PushEndpoints.origin("https://fcm.googleapis.com/fcm/send/x")).isEqualTo("https://fcm.googleapis.com");
        assertThat(PushEndpoints.origin("https://web.push.apple.com:443/x")).isEqualTo("https://web.push.apple.com");
    }
}

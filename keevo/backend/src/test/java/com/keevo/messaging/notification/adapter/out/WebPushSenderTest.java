package com.keevo.messaging.notification.adapter.out;

import com.keevo.messaging.notification.domain.model.WebPushSettings;
import com.keevo.messaging.notification.domain.model.WebPushSubscription;
import com.keevo.messaging.notification.domain.port.out.WebPushSubscriptionRepository;
import com.keevo.shared.capability.notification.NotificationPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** Story 19.9b — Web Push delivery: headers, 404/410 deletion in one statement, failures isolated. */
@DisplayName("WebPushSender — browser notifications (Story 19.9b)")
class WebPushSenderTest {

    private static final NotificationPayload PAYLOAD = NotificationPayload.of("STOCK_ALERT", "Stock bas", "Riz",
            "/stock/1", Map.of("productId", "p1"));
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);

    private RestTemplate http;
    private MockRestServiceServer server;
    private WebPushSubscriptionRepository repository;
    private WebPushSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        http = new RestTemplate();
        server = MockRestServiceServer.bindTo(http).build();
        repository = mock(WebPushSubscriptionRepository.class);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(WebPushCrypto.p256Spec());
        KeyPair pair = generator.generateKeyPair();
        byte[] s = ((ECPrivateKey) pair.getPrivate()).getS().toByteArray();
        byte[] fixed = new byte[32];
        System.arraycopy(s, Math.max(0, s.length - 32), fixed, Math.max(0, 32 - s.length), Math.min(32, s.length));
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        settings = new WebPushSettings(enc.encodeToString(WebPushCrypto.encodePoint((ECPublicKey) pair.getPublic())),
                enc.encodeToString(fixed), null);
    }

    private static WebPushSubscription sub(UUID user, String endpoint) {
        return new WebPushSubscription(UUID.randomUUID(), user, endpoint, UA_PUBLIC, AUTH, null);
    }

    @Test
    @DisplayName("owner with 2 browsers, one gone (410): one delivered, the 410 row deleted in one statement")
    void goneDeleted() {
        UUID owner = UUID.randomUUID();
        WebPushSubscription alive = sub(owner, "https://fcm.googleapis.com/fcm/send/alive");
        WebPushSubscription gone = sub(owner, "https://updates.push.services.mozilla.com/wpush/v2/gone");
        when(repository.findByUserIds(List.of(owner))).thenReturn(List.of(alive, gone));
        server.expect(requestTo(alive.endpoint())).andExpect(method(HttpMethod.POST))
                .andExpect(header("TTL", "86400"))
                .andExpect(header("Content-Encoding", "aes128gcm"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Authorization"))
                        .startsWith("vapid t=").endsWith(", k=" + settings.publicKey()))
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(requestTo(gone.endpoint())).andRespond(withStatus(HttpStatus.GONE));

        new WebPushSender(http, repository, settings, CLOCK).sendToUsers(List.of(owner), PAYLOAD);

        server.verify();
        verify(repository).deleteByIds(List.of(gone.id()));
    }

    @Test
    @DisplayName("other errors (500): WARN, no deletion, never throws")
    void otherErrorKept() {
        UUID user = UUID.randomUUID();
        WebPushSubscription s = sub(user, "https://fcm.googleapis.com/fcm/send/x");
        when(repository.findByUserIds(any())).thenReturn(List.of(s));
        server.expect(requestTo(s.endpoint())).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        new WebPushSender(http, repository, settings, CLOCK).sendToUsers(List.of(user), PAYLOAD);

        verify(repository, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("a stored endpoint outside the allow-list is never called and is deleted")
    void disallowedDeleted() {
        UUID user = UUID.randomUUID();
        WebPushSubscription evil = sub(user, "https://evil.example/x");
        when(repository.findByUserIds(any())).thenReturn(List.of(evil));

        new WebPushSender(http, repository, settings, CLOCK).sendToUsers(List.of(user), PAYLOAD);

        server.verify();
        verify(repository).deleteByIds(List.of(evil.id()));
    }

    @Test
    @DisplayName("VAPID keys missing: disabled, no query, no call")
    void disabled() {
        WebPushSender sender = new WebPushSender(http, repository, new WebPushSettings("", "", ""), CLOCK);
        assertThat(sender.isEnabled()).isFalse();
        sender.sendToUsers(List.of(UUID.randomUUID()), PAYLOAD);
        verifyNoInteractions(repository);
        assertThat(new WebPushSender(http, repository, new WebPushSettings("abc", "def", ""), CLOCK).isEnabled())
                .isFalse();
    }

    @Test
    @DisplayName("a lookup failure never throws")
    void lookupFailure() {
        when(repository.findByUserIds(any())).thenThrow(new IllegalStateException("db down"));
        new WebPushSender(http, repository, settings, CLOCK).sendToUsers(List.of(UUID.randomUUID()), PAYLOAD);
        verify(repository, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("payload as Bref: title, body, icon, badge, data")
    void payload() {
        String json = new String(WebPushSender.payload(PAYLOAD), StandardCharsets.UTF_8);
        assertThat(json).startsWith("{\"title\":\"Stock bas\",\"body\":\"Riz\",\"icon\":\"/icon.svg\","
                + "\"badge\":\"/favicon-32x32.png\",\"data\":{").contains("\"deepLink\":\"/stock/1\"")
                .contains("\"type\":\"STOCK_ALERT\"").contains("\"productId\":\"p1\"");
    }
}

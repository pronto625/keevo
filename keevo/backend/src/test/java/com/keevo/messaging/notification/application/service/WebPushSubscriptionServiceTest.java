package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.model.WebPushSettings;
import com.keevo.messaging.notification.domain.model.WebPushSubscription;
import com.keevo.messaging.notification.domain.port.out.WebPushSubscriptionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 19.9b — subscribe / unsubscribe rules. */
@DisplayName("WebPushSubscriptionService — subscribe rules (Story 19.9b)")
class WebPushSubscriptionServiceTest {

    private static final String FCM = "https://fcm.googleapis.com/fcm/send/abc";
    private static final UUID USER = UUID.randomUUID();
    private static final String KEY =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    private WebPushSubscriptionRepository repository;
    private WebPushSubscriptionService service;

    @BeforeEach
    void setUp() {
        repository = mock(WebPushSubscriptionRepository.class);
        service = new WebPushSubscriptionService(repository, new WebPushSettings("pub", "priv", null));
    }

    @Test
    @DisplayName("valid FCM endpoint: one upsert with the caller as owner")
    void subscribe() {
        when(repository.upsert(any())).thenReturn(true);
        service.subscribe(USER, FCM, " " + KEY + " ", AUTH, "Mozilla/5.0");
        ArgumentCaptor<WebPushSubscription> captor = ArgumentCaptor.forClass(WebPushSubscription.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER);
        assertThat(captor.getValue().endpoint()).isEqualTo(FCM);
        assertThat(captor.getValue().p256dh()).isEqualTo(KEY);
        assertThat(captor.getValue().userAgent()).isEqualTo("Mozilla/5.0");
    }

    @Test
    @DisplayName("endpoint of another user with other keys: 409 PUSH_ENDPOINT_OWNED")
    void owned() {
        when(repository.upsert(any())).thenReturn(false);
        assertThatThrownBy(() -> service.subscribe(USER, FCM, KEY, AUTH, null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.PUSH_ENDPOINT_OWNED);
    }

    @Test
    @DisplayName("endpoint outside the push services, http, missing or invalid keys (not a P-256 point, auth not 16 bytes): VALIDATION_FAILED, nothing written")
    void invalid() {
        for (String[] input : new String[][]{
                {"https://evil.example/x", KEY, AUTH}, {"http://fcm.googleapis.com/x", KEY, AUTH},
                {FCM, "", AUTH}, {FCM, KEY, null}, {FCM, "k".repeat(201), AUTH},
                {FCM, "BKey", AUTH}, {FCM, KEY, "c2hvcnQ"}}) {
            assertThatThrownBy(() -> service.subscribe(USER, input[0], input[1], input[2], null))
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        verify(repository, never()).upsert(any());
    }

    @Test
    @DisplayName("user agent cut to 500 characters")
    void userAgentCut() {
        when(repository.upsert(any())).thenReturn(true);
        service.subscribe(USER, FCM, KEY, AUTH, "x".repeat(600));
        ArgumentCaptor<WebPushSubscription> captor = ArgumentCaptor.forClass(WebPushSubscription.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().userAgent()).hasSize(500);
    }

    @Test
    @DisplayName("unsubscribe deletes the caller's row only; blank endpoint ignored")
    void unsubscribe() {
        service.unsubscribe(USER, FCM);
        verify(repository).deleteByUserAndEndpoint(USER, FCM);
        WebPushSubscriptionRepository other = mock(WebPushSubscriptionRepository.class);
        new WebPushSubscriptionService(other, new WebPushSettings("", "", null)).unsubscribe(USER, " ");
        verifyNoInteractions(other);
    }

    @Test
    @DisplayName("public key: empty when Web Push is disabled")
    void publicKey() {
        assertThat(service.vapidPublicKey()).isEqualTo("pub");
        assertThat(new WebPushSubscriptionService(repository, new WebPushSettings("pub", "", null)).vapidPublicKey())
                .isEmpty();
    }
}

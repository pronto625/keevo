package com.keevo.payments.appstore.application.listener;

import com.keevo.payments.appstore.domain.event.AppStoreSubscriptionNotice;
import com.keevo.payments.appstore.domain.event.AppStoreSubscriptionNotice.Kind;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("AppStorePushListener — owner push + inbox after an App Store change (Story 21.2)")
class AppStorePushListenerTest {

    private static final Instant UNTIL = Instant.parse("2026-12-09T09:00:00Z");

    @Test
    @DisplayName("type, French title and body with the date in WAT, deep link /plan; a push failure is swallowed")
    void push() {
        PushNotifier notifier = mock(PushNotifier.class);
        AppStorePushListener listener = new AppStorePushListener(notifier);
        listener.onNotice(new AppStoreSubscriptionNotice("kv_abc123", "E-1", Kind.ACTIVE, "Pro", UNTIL));
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifier).notifyOwners(eq("kv_abc123"), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo("APP_STORE_ACTIVE");
        assertThat(payload.getValue().title()).isEqualTo("Abonnement App Store actif");
        assertThat(payload.getValue().body()).isEqualTo("Votre plan Pro est actif jusqu'au 09/12/2026.");

        doThrow(new IllegalStateException("down")).when(notifier).notifyOwners(any(), any());
        assertThatCode(() -> listener.onNotice(new AppStoreSubscriptionNotice("kv_abc123", "E-2", Kind.EXPIRED, "Pro",
                null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every kind has its texts")
    void texts() {
        assertThat(AppStorePushListener.body(new AppStoreSubscriptionNotice("s", "k", Kind.CANCELLED, "Pro", UNTIL)))
                .isEqualTo("Votre plan Pro reste actif jusqu'au 09/12/2026, sans renouvellement.");
        assertThat(AppStorePushListener.body(new AppStoreSubscriptionNotice("s", "k", Kind.BILLING_ISSUE, "Pro",
                UNTIL))).endsWith("avant le 09/12/2026.");
        assertThat(AppStorePushListener.type(new AppStoreSubscriptionNotice("s", "k", Kind.REFUNDED, "Pro", null)))
                .isEqualTo("APP_STORE_EXPIRED");
        for (Kind kind : Kind.values()) {
            AppStoreSubscriptionNotice n = new AppStoreSubscriptionNotice("s", "k", kind, "Pro", null);
            assertThat(AppStorePushListener.title(n)).isNotBlank();
            assertThat(AppStorePushListener.body(n)).isNotBlank().doesNotContain("null");
        }
    }
}

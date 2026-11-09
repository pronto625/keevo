package com.keevo.payments.checkout.application.listener;

import com.keevo.payments.checkout.domain.event.PlanPaymentConfirmedEvent;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("PaymentPushListener — « Paiement reçu » to the owners (Story 21.1)")
class PaymentPushListenerTest {

    @Test
    @DisplayName("push + inbox: type, title, French body with the expiry date in WAT, deep link")
    void push() {
        PushNotifier push = mock(PushNotifier.class);
        // 2027-02-28 23:59:59 WAT = 22:59:59Z
        new PaymentPushListener(push).onConfirmed(new PlanPaymentConfirmedEvent("kv_abc123", UUID.randomUUID(),
                "Pro", Instant.parse("2027-02-28T22:59:59Z")));
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(push).notifyOwners(eq("kv_abc123"), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo("PLAN_PAYMENT_CONFIRMED");
        assertThat(payload.getValue().title()).isEqualTo("Paiement reçu");
        assertThat(payload.getValue().body()).isEqualTo("Votre plan Pro est actif jusqu'au 28/02/2027.");
        assertThat(payload.getValue().deepLink()).isEqualTo("/plan");
    }

    @Test
    @DisplayName("a delivery failure is swallowed (best effort)")
    void bestEffort() {
        PushNotifier push = mock(PushNotifier.class);
        doThrow(new IllegalStateException("down")).when(push).notifyOwners(any(), any());
        new PaymentPushListener(push).onConfirmed(new PlanPaymentConfirmedEvent("kv_abc123", UUID.randomUUID(),
                "Pro", Instant.parse("2027-02-28T22:59:59Z")));
    }
}

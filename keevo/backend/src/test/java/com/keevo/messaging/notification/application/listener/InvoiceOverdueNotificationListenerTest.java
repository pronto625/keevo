package com.keevo.messaging.notification.application.listener;

import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.event.catalog.invoicing.InvoicesOverdueEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("InvoiceOverdueNotificationListener — one owner notification per run (Story 20.4)")
class InvoiceOverdueNotificationListenerTest {

    private final PushNotifier push = mock(PushNotifier.class);
    private final InvoiceOverdueNotificationListener listener = new InvoiceOverdueNotificationListener(push);

    @Test
    @DisplayName("French title / body with the numbers, deep link to the overdue list, no id")
    void notifies() {
        listener.onOverdue(new InvoicesOverdueEvent(List.of("FAC-2026-000012", "FAC-2026-000015", "FAC-2026-000020"),
                null, "kv_aaaaaa", Instant.now()));
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(push).notifyOwners(eq("kv_aaaaaa"), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo("INVOICE_OVERDUE");
        assertThat(payload.getValue().title()).isEqualTo("Factures en retard");
        assertThat(payload.getValue().body()).isEqualTo(
                "3 facture(s) en retard de paiement : FAC-2026-000012, FAC-2026-000015, FAC-2026-000020");
        assertThat(payload.getValue().deepLink()).isEqualTo("/invoices?paymentState=OVERDUE");
        assertThat(payload.getValue().metadata()).containsOnlyKeys("count");
    }

    @Test
    @DisplayName("more than 5 numbers: the first 5 then « … »; empty → nothing; a push failure is swallowed")
    void longListAndFailures() {
        List<String> numbers = IntStream.rangeClosed(1, 7).mapToObj(i -> "FAC-2026-00000" + i).toList();
        assertThat(InvoiceOverdueNotificationListener.body(numbers)).isEqualTo("7 facture(s) en retard de paiement : "
                + "FAC-2026-000001, FAC-2026-000002, FAC-2026-000003, FAC-2026-000004, FAC-2026-000005, …");
        PushNotifier none = mock(PushNotifier.class);
        new InvoiceOverdueNotificationListener(none).onOverdue(new InvoicesOverdueEvent(List.of(), null, "kv_a",
                Instant.now()));
        verifyNoInteractions(none);
        doThrow(new IllegalStateException("down")).when(push).notifyOwners(any(), any());
        listener.onOverdue(new InvoicesOverdueEvent(List.of("FAC-2026-000001"), null, "kv_aaaaaa", Instant.now()));
    }
}

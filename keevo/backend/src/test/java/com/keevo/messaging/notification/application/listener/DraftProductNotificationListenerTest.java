package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.product.domain.event.CsvImportCompletedEvent;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TDD tests for DraftProductNotificationListener (Story 2.4 — AC9 CSV batch).
 *
 * <p>Note: the per-draft-product notification (onDraftCreated) has been removed.
 * The owner is now notified via SaleDraftValidatedNotificationListener#onSalePendingValidation
 * when the full pending sale is submitted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DraftProductNotificationListener")
class DraftProductNotificationListenerTest {

    @Mock private NotificationPort notificationPort;

    private DraftProductNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new DraftProductNotificationListener(notificationPort);
    }

    // ── CSV batch import (onCsvImportCompleted) ───────────────────────────────

    @Test
    @DisplayName("shouldSendBatchedNotificationOnCsvImportCompletion")
    void shouldSendBatchedNotificationOnCsvImportCompletion() {
        var event = new CsvImportCompletedEvent(
                42, "kv_test", UUID.randomUUID(), "Simon", "OWNER", Instant.now());

        listener.onCsvImportCompleted(event);

        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq("kv_test"), cap.capture());

        NotificationPayload payload = cap.getValue();
        assertEquals("CSV_IMPORT_DRAFTS_PENDING", payload.type());
        assertTrue(payload.body().contains("42"));
        assertEquals("42", payload.metadata().get("count"));
    }

    @Test
    @DisplayName("shouldNotRollbackOnCsvImportNotificationFailure")
    void shouldNotRollbackOnCsvImportNotificationFailure() {
        var event = new CsvImportCompletedEvent(
                5, "kv_test", UUID.randomUUID(), "Loïc", "EMPLOYEE", Instant.now());

        doThrow(new RuntimeException("Network error")).when(notificationPort).notifyOwners(any(), any());

        assertDoesNotThrow(() -> listener.onCsvImportCompleted(event));
    }
}

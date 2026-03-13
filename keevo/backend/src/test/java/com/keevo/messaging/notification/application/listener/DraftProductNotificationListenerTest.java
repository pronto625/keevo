package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.product.domain.event.CsvImportCompletedEvent;
import com.keevo.catalog.product.domain.event.ProductCreatedProgressivelyEvent;
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
 * TDD tests for DraftProductNotificationListener (Story 2.4 — AC9).
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

    // ── Single draft (onDraftCreated) ─────────────────────────────────────────

    @Test
    @DisplayName("shouldCallNotifyOwnersWhenEmployeeCreatesDraft")
    void shouldCallNotifyOwnersWhenEmployeeCreatesDraft() {
        var event = new ProductCreatedProgressivelyEvent(
                UUID.randomUUID(), "Sandales bleues", UUID.randomUUID(),
                "Loïc", "EMPLOYEE", "kv_test", "DRAFT", Instant.now());

        listener.onDraftCreated(event);

        verify(notificationPort).notifyOwners(eq("kv_test"), any(NotificationPayload.class));
    }

    @Test
    @DisplayName("shouldCallNotifyOwnersWhenOwnerCreatesDraft_selfReminderIsIntentional")
    void shouldCallNotifyOwnersWhenOwnerCreatesDraft() {
        // Owner self-notification is intentional — acts as a validation reminder
        var event = new ProductCreatedProgressivelyEvent(
                UUID.randomUUID(), "Chapeau rouge", UUID.randomUUID(),
                "Simon", "OWNER", "kv_test", "DRAFT", Instant.now());

        listener.onDraftCreated(event);

        verify(notificationPort).notifyOwners(eq("kv_test"), any(NotificationPayload.class));
    }

    @Test
    @DisplayName("shouldBuildPayloadWithExpectedTypeAndDeepLink")
    void shouldBuildPayloadWithExpectedTypeAndDeepLink() {
        UUID productId = UUID.randomUUID();
        var event = new ProductCreatedProgressivelyEvent(
                productId, "Article Test", UUID.randomUUID(),
                "Loïc", "EMPLOYEE", "kv_test", "DRAFT", Instant.now());

        listener.onDraftCreated(event);

        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq("kv_test"), cap.capture());

        NotificationPayload payload = cap.getValue();
        assertEquals("DRAFT_PRODUCT_PENDING_VALIDATION", payload.type());
        assertNotNull(payload.title());
        assertTrue(payload.body().contains("Article Test"));
        assertEquals("/products/" + productId + "/edit", payload.deepLink());
        assertEquals(productId.toString(), payload.metadata().get("productId"));
    }

    @Test
    @DisplayName("shouldNotRollbackDraftWhenNotificationPortThrows")
    void shouldNotRollbackDraftWhenNotificationPortThrows() {
        var event = new ProductCreatedProgressivelyEvent(
                UUID.randomUUID(), "Produit fragile", UUID.randomUUID(),
                "Loïc", "EMPLOYEE", "kv_test", "DRAFT", Instant.now());

        doThrow(new RuntimeException("FCM timeout")).when(notificationPort).notifyOwners(any(), any());

        // Must NOT throw — notification failure must never fail the caller
        assertDoesNotThrow(() -> listener.onDraftCreated(event));
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

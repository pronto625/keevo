package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.product.domain.event.CsvImportCompletedEvent;
import com.keevo.catalog.product.domain.event.ProductCreatedEvent;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TDD tests for ProductCreationNotificationListener (Story 14.10).
 *
 * <p>Batching is driven by a controllable {@link MutableClock} instead of real sleeps:
 * events are recorded, the clock is advanced past the window, then {@link
 * ProductCreationNotificationListener#flushExpiredWindows()} is invoked directly to
 * simulate the periodic sweep.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCreationNotificationListener")
class ProductCreationNotificationListenerTest {

    @Mock private NotificationPort notificationPort;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private UserRepository userRepository;

    private MutableClock clock;
    private ProductCreationNotificationListener listener;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test123";
    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");
    private static final long WINDOW_MIN = 5;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        listener = new ProductCreationNotificationListener(
                notificationPort, whatsAppPort, userRepository, WINDOW_MIN, clock);
    }

    private void advancePastWindow() {
        clock.advance(Duration.ofMinutes(WINDOW_MIN).plusSeconds(1));
    }

    // ── 6.1 shouldNotifyOwnerWhenEmployeeCreatesActiveProduct ──────────────

    @Test
    @DisplayName("should notify owner when employee creates ACTIVE product")
    void shouldNotifyOwnerWhenEmployeeCreatesActiveProduct() {
        ProductCreatedEvent event = new ProductCreatedEvent(
                PRODUCT_ID, "Test Product", "KEV-001", TENANT_ID, ACTOR_ID,
                "EMPLOYEE", "Loïc", "Boutique A", NOW);

        listener.onProductCreated(event);
        advancePastWindow();
        listener.flushExpiredWindows();

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT_ID), captor.capture());
        NotificationPayload payload = captor.getValue();
        assertThat(payload.type()).isEqualTo("EMPLOYEE_PRODUCT_CREATED");
        assertThat(payload.body()).contains("Loïc");
        assertThat(payload.body()).contains("Test Product");
        assertThat(payload.body()).contains("Boutique A");
    }

    // ── 6.2 shouldNotSkipCatalogEventsBeyondScope ───────────────────────────
    // NOTE: the per-draft-product notification (onProductCreatedProgressively) was
    // removed — Story 2.4's original design intentionally has no listener for
    // ProductCreatedProgressivelyEvent (see deleted DraftProductNotificationListener
    // javadoc). AC2's "preserved without change" now genuinely holds: only
    // SaleDraftValidatedNotificationListener notifies for the draft-product lifecycle.

    // ── 6.3 shouldSkipNotificationWhenOwnerCreatesProduct ──────────────────

    @Test
    @DisplayName("should skip notification when owner creates product")
    void shouldSkipNotificationWhenOwnerCreatesProduct() {
        ProductCreatedEvent event = new ProductCreatedEvent(
                PRODUCT_ID, "Owner Product", "KEV-002", TENANT_ID, ACTOR_ID,
                "OWNER", "Simon", null, NOW);

        listener.onProductCreated(event);
        advancePastWindow();
        listener.flushExpiredWindows();

        verify(notificationPort, never()).notifyOwners(anyString(), any());
    }

    // ── 6.4 shouldSendBatchedRecapWhenEmployeeCreatesMultipleProductsInWindow ──

    @Test
    @DisplayName("should send exactly one batched recap when employee creates multiple products in window")
    void shouldSendBatchedRecapWhenEmployeeCreatesMultipleProductsInWindow() {
        ProductCreatedEvent event1 = new ProductCreatedEvent(
                UUID.randomUUID(), "Product 1", "KEV-A", TENANT_ID, ACTOR_ID,
                "EMPLOYEE", "Loïc", "Boutique A", NOW);
        ProductCreatedEvent event2 = new ProductCreatedEvent(
                UUID.randomUUID(), "Product 2", "KEV-B", TENANT_ID, ACTOR_ID,
                "EMPLOYEE", "Loïc", "Boutique A", NOW.plusSeconds(30));

        listener.onProductCreated(event1);
        clock.advance(Duration.ofSeconds(30));
        listener.onProductCreated(event2);

        // Window not yet expired — no dispatch yet
        listener.flushExpiredWindows();
        verify(notificationPort, never()).notifyOwners(anyString(), any());

        advancePastWindow();
        listener.flushExpiredWindows();

        // Exactly ONE consolidated notification for both events — not one per event
        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT_ID), captor.capture());
        NotificationPayload batchPayload = captor.getValue();
        assertThat(batchPayload.type()).isEqualTo("EMPLOYEE_PRODUCT_CREATED_BATCH");
        assertThat(batchPayload.body()).contains("2 produits");
        assertThat(batchPayload.metadata()).containsEntry("count", "2");
    }

    // ── 6.5 shouldSendSingleNotificationWhenEmployeeCreatesOneProduct ──────

    @Test
    @DisplayName("should send single notification when employee creates one product")
    void shouldSendSingleNotificationWhenEmployeeCreatesOneProduct() {
        ProductCreatedEvent event = new ProductCreatedEvent(
                PRODUCT_ID, "Solo Product", "KEV-SOLO", TENANT_ID, ACTOR_ID,
                "EMPLOYEE", "Loïc", "Boutique A", NOW);

        listener.onProductCreated(event);
        advancePastWindow();
        listener.flushExpiredWindows();

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("EMPLOYEE_PRODUCT_CREATED");
    }

    // ── 6.5b shouldStartFreshWindowAfterPreviousWindowFlushed ──────────────

    @Test
    @DisplayName("should start a fresh window after the previous one was flushed (no cross-window leak)")
    void shouldStartFreshWindowAfterPreviousWindowFlushed() {
        ProductCreatedEvent event1 = new ProductCreatedEvent(
                UUID.randomUUID(), "Product 1", "KEV-A", TENANT_ID, ACTOR_ID,
                "EMPLOYEE", "Loïc", "Boutique A", NOW);
        listener.onProductCreated(event1);
        advancePastWindow();
        listener.flushExpiredWindows();

        ProductCreatedEvent event2 = new ProductCreatedEvent(
                UUID.randomUUID(), "Product 2", "KEV-B", TENANT_ID, ACTOR_ID,
                "EMPLOYEE", "Loïc", "Boutique A", NOW);
        listener.onProductCreated(event2);
        advancePastWindow();
        listener.flushExpiredWindows();

        // Two separate windows → two separate single notifications, never merged
        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort, org.mockito.Mockito.times(2))
                .notifyOwners(eq(TENANT_ID), captor.capture());
        assertThat(captor.getAllValues()).allMatch(p -> p.type().equals("EMPLOYEE_PRODUCT_CREATED"));
    }

    // ── 6.6 shouldNotRollbackProductCreationWhenNotificationFails ──────────

    @Test
    @DisplayName("should not throw when notification fails (best-effort)")
    void shouldNotRollbackProductCreationWhenNotificationFails() {
        doThrow(new RuntimeException("FCM down")).when(notificationPort)
                .notifyOwners(anyString(), any());

        ProductCreatedEvent event = new ProductCreatedEvent(
                PRODUCT_ID, "Resilient Product", "KEV-RES", TENANT_ID, ACTOR_ID,
                "EMPLOYEE", "Loïc", "Boutique A", NOW);

        listener.onProductCreated(event);
        advancePastWindow();

        // Must not throw
        assertThatCode(() -> listener.flushExpiredWindows()).doesNotThrowAnyException();

        verify(notificationPort).notifyOwners(eq(TENANT_ID), any());
    }

    // ── 6.7 shouldNotDoubleNotifyOnCsvImport ──────────────────────────────

    @Test
    @DisplayName("should not double-notify on CSV import (CSV path preserved) — content + metadata correct")
    void shouldNotDoubleNotifyOnCsvImport() {
        CsvImportCompletedEvent csvEvent = new CsvImportCompletedEvent(
                42, TENANT_ID, ACTOR_ID, "Simon", "OWNER", NOW);

        listener.onCsvImportCompleted(csvEvent);

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        // CSV import always sends 1 consolidated notification, never per-product
        verify(notificationPort).notifyOwners(eq(TENANT_ID), captor.capture());
        NotificationPayload payload = captor.getValue();
        assertThat(payload.type()).isEqualTo("CSV_IMPORT_DRAFTS_PENDING");
        assertThat(payload.body()).contains("42");
        assertThat(payload.metadata()).containsEntry("count", "42");
    }

    // ── 6.8 shouldNotRollbackOnCsvImportNotificationFailure ────────────────
    // (regression coverage carried over from the deleted DraftProductNotificationListenerTest)

    @Test
    @DisplayName("should not throw when CSV import notification fails (best-effort)")
    void shouldNotRollbackOnCsvImportNotificationFailure() {
        doThrow(new RuntimeException("Network error")).when(notificationPort).notifyOwners(any(), any());

        CsvImportCompletedEvent csvEvent = new CsvImportCompletedEvent(
                5, TENANT_ID, ACTOR_ID, "Loïc", "EMPLOYEE", NOW);

        assertThatCode(() -> listener.onCsvImportCompleted(csvEvent)).doesNotThrowAnyException();
    }

    /** Test double — a {@link Clock} whose {@code instant()} can be advanced on demand. */
    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

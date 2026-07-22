package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.event.StockAdjustedEvent;
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
 * TDD tests for StockModificationNotificationListener (Story 14.10).
 *
 * <p>Batching is driven by a controllable {@link MutableClock} instead of real sleeps:
 * events are recorded, the clock is advanced past the window, then {@link
 * StockModificationNotificationListener#flushExpiredWindows()} is invoked directly to
 * simulate the periodic sweep.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StockModificationNotificationListener")
class StockModificationNotificationListenerTest {

    @Mock private NotificationPort notificationPort;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private UserRepository userRepository;

    private MutableClock clock;
    private StockModificationNotificationListener listener;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test123";
    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");
    private static final long WINDOW_MIN = 5;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        listener = new StockModificationNotificationListener(
                notificationPort, whatsAppPort, userRepository, WINDOW_MIN, clock);
    }

    private void advancePastWindow() {
        clock.advance(Duration.ofMinutes(WINDOW_MIN).plusSeconds(1));
    }

    // ── 7.1 shouldNotifyOwnerWhenEmployeeEntersStock ─────────────────────

    @Test
    @DisplayName("should notify owner when employee enters stock (STOCK_ENTRY)")
    void shouldNotifyOwnerWhenEmployeeEntersStock() {
        StockAdjustedEvent event = new StockAdjustedEvent(
                PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY,
                0, 10, 10, ACTOR_ID, "Livraison", TENANT_ID,
                "EMPLOYEE", "Loïc", "Boutique A", "Widget", NOW);

        listener.onStockAdjusted(event);
        advancePastWindow();
        listener.flushExpiredWindows();

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT_ID), captor.capture());
        NotificationPayload payload = captor.getValue();
        assertThat(payload.type()).isEqualTo("EMPLOYEE_STOCK_MODIFIED");
        assertThat(payload.body()).contains("Loïc");
        assertThat(payload.body()).contains("10 unité(s)");
        assertThat(payload.body()).contains("Boutique A");
        assertThat(payload.body()).contains("Widget");
    }

    // ── 7.2 shouldNotifyOwnerWhenEmployeeAdjustsStock ────────────────────

    @Test
    @DisplayName("should notify owner when employee adjusts stock (ADJUSTMENT)")
    void shouldNotifyOwnerWhenEmployeeAdjustsStock() {
        StockAdjustedEvent event = new StockAdjustedEvent(
                PRODUCT_ID, null, STORE_ID, MovementType.ADJUSTMENT,
                50, -5, 45, ACTOR_ID, "Ajustement", TENANT_ID,
                "EMPLOYEE", "Loïc", "Boutique A", "Widget", NOW);

        listener.onStockAdjusted(event);
        advancePastWindow();
        listener.flushExpiredWindows();

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT_ID), captor.capture());
        NotificationPayload payload = captor.getValue();
        assertThat(payload.type()).isEqualTo("EMPLOYEE_STOCK_MODIFIED");
        assertThat(payload.body()).contains("50 → 45");
    }

    // ── 7.2b shouldFallBackToTruncatedProductIdWhenProductNameMissing ─────

    @Test
    @DisplayName("should fall back to a truncated product id when productName is absent")
    void shouldFallBackToTruncatedProductIdWhenProductNameMissing() {
        StockAdjustedEvent event = new StockAdjustedEvent(
                PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY,
                0, 10, 10, ACTOR_ID, "Livraison", TENANT_ID,
                "EMPLOYEE", "Loïc", "Boutique A", null, NOW);

        listener.onStockAdjusted(event);
        advancePastWindow();
        listener.flushExpiredWindows();

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT_ID), captor.capture());
        assertThat(captor.getValue().body()).contains(PRODUCT_ID.toString().substring(0, 8));
    }

    // ── 7.3 shouldSkipNotificationWhenOwnerModifiesStock ─────────────────

    @Test
    @DisplayName("should skip notification when owner modifies stock")
    void shouldSkipNotificationWhenOwnerModifiesStock() {
        StockAdjustedEvent event = new StockAdjustedEvent(
                PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY,
                0, 10, 10, ACTOR_ID, null, TENANT_ID,
                "OWNER", "Simon", "Boutique A", "Widget", NOW);

        listener.onStockAdjusted(event);
        advancePastWindow();
        listener.flushExpiredWindows();

        verify(notificationPort, never()).notifyOwners(anyString(), any());
    }

    // ── 7.4 shouldSkipSaleDecrementEvents ────────────────────────────────

    @Test
    @DisplayName("should skip SALE movement events (not in scope D2)")
    void shouldSkipSaleDecrementEvents() {
        StockAdjustedEvent event = new StockAdjustedEvent(
                PRODUCT_ID, null, STORE_ID, MovementType.SALE,
                10, -1, 9, ACTOR_ID, null, TENANT_ID,
                "EMPLOYEE", "Loïc", "Boutique A", "Widget", NOW);

        listener.onStockAdjusted(event);
        advancePastWindow();
        listener.flushExpiredWindows();

        verify(notificationPort, never()).notifyOwners(anyString(), any());
    }

    // ── 7.5 shouldSendBatchedRecapWhenEmployeeModifiesMultipleStocksInWindow ──

    @Test
    @DisplayName("should send exactly one batched recap when employee modifies multiple stocks in window")
    void shouldSendBatchedRecapWhenEmployeeModifiesMultipleStocksInWindow() {
        StockAdjustedEvent event1 = new StockAdjustedEvent(
                UUID.randomUUID(), null, STORE_ID, MovementType.STOCK_ENTRY,
                0, 5, 5, ACTOR_ID, null, TENANT_ID,
                "EMPLOYEE", "Loïc", "Boutique A", "Widget A", NOW);
        StockAdjustedEvent event2 = new StockAdjustedEvent(
                UUID.randomUUID(), null, STORE_ID, MovementType.ADJUSTMENT,
                10, -2, 8, ACTOR_ID, "Correction", TENANT_ID,
                "EMPLOYEE", "Loïc", "Boutique A", "Widget B", NOW.plusSeconds(30));

        listener.onStockAdjusted(event1);
        clock.advance(Duration.ofSeconds(30));
        listener.onStockAdjusted(event2);

        // Window not yet expired — no dispatch yet
        listener.flushExpiredWindows();
        verify(notificationPort, never()).notifyOwners(anyString(), any());

        advancePastWindow();
        listener.flushExpiredWindows();

        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT_ID), captor.capture());
        NotificationPayload batchPayload = captor.getValue();
        assertThat(batchPayload.type()).isEqualTo("EMPLOYEE_STOCK_MODIFIED_BATCH");
        assertThat(batchPayload.body()).contains("2 modification(s)");
        assertThat(batchPayload.metadata()).containsEntry("count", "2");
    }

    // ── 7.6 shouldNotRollbackStockModificationWhenNotificationFails ──────

    @Test
    @DisplayName("should not throw when notification fails (best-effort)")
    void shouldNotRollbackStockModificationWhenNotificationFails() {
        doThrow(new RuntimeException("FCM down")).when(notificationPort)
                .notifyOwners(anyString(), any());

        StockAdjustedEvent event = new StockAdjustedEvent(
                PRODUCT_ID, null, STORE_ID, MovementType.STOCK_ENTRY,
                0, 10, 10, ACTOR_ID, "Livraison", TENANT_ID,
                "EMPLOYEE", "Loïc", "Boutique A", "Widget", NOW);

        listener.onStockAdjusted(event);
        advancePastWindow();

        assertThatCode(() -> listener.flushExpiredWindows()).doesNotThrowAnyException();

        verify(notificationPort).notifyOwners(eq(TENANT_ID), any());
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

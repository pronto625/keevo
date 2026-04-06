package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.stock.domain.event.StockThresholdBreachedEvent;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationCooldownRepository;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for StockAlertNotificationListener (Story 8.1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StockAlertNotificationListener")
class StockAlertNotificationListenerTest {

    @Mock private NotificationPort notificationPort;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private NotificationCooldownRepository cooldownRepository;
    @Mock private TenantPreferencesRepository tenantPreferencesRepository;
    @Mock private UserRepository userRepository;
    @Mock private StoreRepository storeRepository;

    private StockAlertNotificationListener listener;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        listener = new StockAlertNotificationListener(
                notificationPort, whatsAppPort, cooldownRepository,
                tenantPreferencesRepository, userRepository, storeRepository);
    }

    private StockThresholdBreachedEvent event() {
        return event(PRODUCT_ID, STORE_ID);
    }

    private StockThresholdBreachedEvent event(UUID productId, UUID storeId) {
        return new StockThresholdBreachedEvent(
                productId, "Savon Palmolive", storeId, 3, 5,
                ACTOR_ID, TENANT_ID, Instant.now());
    }

    private TenantPreferences enabledPrefs() {
        return TenantPreferences.withDefaults(
                UUID.randomUUID(), SectorType.FOOD_GROCERY, "20:00:00", true, Instant.now());
    }

    private TenantPreferences disabledAlertPrefs() {
        var prefs = enabledPrefs();
        return new TenantPreferences(
                prefs.id(), prefs.sectorType(), prefs.eodReportTime(), false, prefs.createdAt(),
                prefs.eodReportEnabled(), prefs.eodReportChannel(),
                prefs.weeklyReportEnabled(), prefs.weeklyReportDay(),
                prefs.weeklyReportTime(), prefs.weeklyReportChannel(),
                prefs.inventoryReportEnabled(), prefs.inventoryReportChannel(),
                prefs.stockAlertChannel(), prefs.trendNotificationEnabled());
    }

    private TenantPreferences bothChannelPrefs() {
        var prefs = enabledPrefs();
        return new TenantPreferences(
                prefs.id(), prefs.sectorType(), prefs.eodReportTime(), true, prefs.createdAt(),
                prefs.eodReportEnabled(), prefs.eodReportChannel(),
                prefs.weeklyReportEnabled(), prefs.weeklyReportDay(),
                prefs.weeklyReportTime(), prefs.weeklyReportChannel(),
                prefs.inventoryReportEnabled(), prefs.inventoryReportChannel(),
                StockAlertChannel.BOTH, prefs.trendNotificationEnabled());
    }

    private Store testStore() {
        return new Store(STORE_ID, "Boutique Cosmos", StoreType.STORE, null, null, true,
                Instant.now(), Instant.now());
    }

    private User ownerUser() {
        return new User(UUID.randomUUID(), "+243990001234", "hash",
                com.keevo.identity.auth.domain.model.Role.OWNER, true, Instant.now());
    }

    // ── AC1: Individual alert — enabled, no cooldown ─────────────────────────

    @Test
    @DisplayName("alert enabled + no cooldown → sends push notification")
    void onStockThresholdBreached_alertEnabled_noActiveCooldown_sendsPush() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.existsActiveCooldown(eq("STOCK_ALERT"), any(), any(), any())).thenReturn(false);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID)).thenReturn(Optional.of(ownerUser()));

        listener.onStockThresholdBreached(event());

        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT_ID), cap.capture());
        NotificationPayload payload = cap.getValue();
        assertEquals("STOCK_ALERT", payload.type());
        assertTrue(payload.body().contains("Savon Palmolive"));
        assertTrue(payload.body().contains("3 unité(s)"));
        assertTrue(payload.body().contains("Boutique Cosmos"));
    }

    // ── AC1: Alert disabled → skip ───────────────────────────────────────────

    @Test
    @DisplayName("alert disabled → skips notification entirely")
    void onStockThresholdBreached_alertDisabled_skipsNotification() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(disabledAlertPrefs()));

        listener.onStockThresholdBreached(event());

        verifyNoInteractions(notificationPort);
        verifyNoInteractions(whatsAppPort);
    }

    // ── AC1: Cooldown active → skip ──────────────────────────────────────────

    @Test
    @DisplayName("cooldown active → skips notification")
    void onStockThresholdBreached_cooldownActive_skipsNotification() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.existsActiveCooldown(eq("STOCK_ALERT"), eq(PRODUCT_ID), eq(STORE_ID), any()))
                .thenReturn(true);

        listener.onStockThresholdBreached(event());

        verifyNoInteractions(notificationPort);
        verifyNoInteractions(whatsAppPort);
    }

    // ── AC1: Push fails → logs WARN, does not throw ──────────────────────────

    @Test
    @DisplayName("push fails → logs warn, does not throw")
    void onStockThresholdBreached_pushFails_logsWarnDoesNotThrow() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(bothChannelPrefs()));
        when(cooldownRepository.existsActiveCooldown(any(), any(), any(), any())).thenReturn(false);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID)).thenReturn(Optional.of(ownerUser()));
        doThrow(new RuntimeException("FCM timeout")).when(notificationPort).notifyOwners(any(), any());

        assertDoesNotThrow(() -> listener.onStockThresholdBreached(event()));
    }

    // ── AC1: WhatsApp fails → logs warn, does not throw ──────────────────────

    @Test
    @DisplayName("whatsApp fails → logs warn, does not throw")
    void onStockThresholdBreached_whatsAppFails_logsWarnDoesNotThrow() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(bothChannelPrefs()));
        when(cooldownRepository.existsActiveCooldown(any(), any(), any(), any())).thenReturn(false);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID)).thenReturn(Optional.of(ownerUser()));
        doThrow(new RuntimeException("WA fail")).when(whatsAppPort).sendReport(any(), any());

        assertDoesNotThrow(() -> listener.onStockThresholdBreached(event()));
    }

    // ── AC1: Upserts cooldown after dispatch ─────────────────────────────────

    @Test
    @DisplayName("upserts cooldown after successful dispatch")
    void onStockThresholdBreached_upsertsCooldownAfterSuccess() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.existsActiveCooldown(any(), any(), any(), any())).thenReturn(false);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID)).thenReturn(Optional.of(ownerUser()));

        listener.onStockThresholdBreached(event());

        verify(cooldownRepository).upsertCooldown("STOCK_ALERT", PRODUCT_ID, STORE_ID);
    }

    // ── AC2: Three or fewer events → dispatches individually ─────────────────

    @Test
    @DisplayName("three events in window → dispatches individually (no batch)")
    void batch_threeOrFewer_dispatches_individually() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.existsActiveCooldown(any(), any(), any(), any())).thenReturn(false);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID)).thenReturn(Optional.of(ownerUser()));

        for (int i = 0; i < 3; i++) {
            listener.onStockThresholdBreached(event(UUID.randomUUID(), STORE_ID));
        }

        // Each call should produce an individual STOCK_ALERT notification
        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort, times(3)).notifyOwners(eq(TENANT_ID), cap.capture());
        cap.getAllValues().forEach(p -> assertEquals("STOCK_ALERT", p.type()));
    }

    // ── AC2: Fourth event in window → dispatches batch ───────────────────────

    @Test
    @DisplayName("fourth event in window → dispatches batch notification")
    void batch_fourthEventInWindow_dispatchesBatchNotification() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.existsActiveCooldown(any(), any(), any(), any())).thenReturn(false);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(userRepository.findOwnerByTenantSchemaName(TENANT_ID)).thenReturn(Optional.of(ownerUser()));

        // Fire 4 events for the same store
        for (int i = 0; i < 4; i++) {
            listener.onStockThresholdBreached(event(UUID.randomUUID(), STORE_ID));
        }

        // 3 individual + 1 batch
        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort, atLeast(4)).notifyOwners(eq(TENANT_ID), cap.capture());

        boolean hasBatch = cap.getAllValues().stream()
                .anyMatch(p -> "STOCK_ALERT_BATCH".equals(p.type()));
        assertTrue(hasBatch, "Should have dispatched a batch notification");
    }

    // ── AC1: No preferences → skip ──────────────────────────────────────────

    @Test
    @DisplayName("no preferences found → skips notification")
    void onStockThresholdBreached_noPreferences_skipsNotification() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.empty());

        listener.onStockThresholdBreached(event());

        verifyNoInteractions(notificationPort);
        verifyNoInteractions(whatsAppPort);
    }
}

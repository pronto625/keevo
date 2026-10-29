package com.keevo.messaging.notification.application.listener;

import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
import com.keevo.shared.infrastructure.event.ModuleListenerTestSupport;
import com.keevo.identity.onboarding.adapter.in.api.IdentityOnboardingApiTestSupport;
import com.keevo.shared.event.catalog.stock.StockThresholdBreachedEvent;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationCooldownRepository;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
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

    @Mock private PushNotifier pushNotifier;
    @Mock private WhatsAppSender whatsAppSender;
    @Mock private NotificationCooldownRepository cooldownRepository;
    @Mock private TenantPreferencesRepository tenantPreferencesRepository;
    @Mock private OwnerRecipientResolver ownerRecipientResolver;
    @Mock private StoreRepository storeRepository;

    private StockAlertNotificationListener listener;

    private StockAlertNotificationListener raw;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));
        raw = new StockAlertNotificationListener(
                pushNotifier, whatsAppSender, cooldownRepository,
                IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository), ownerRecipientResolver, StoreApiTestSupport.queryPort(storeRepository), clock);
        // 12.6a: module listener — wrapped like the application context does (tenant bound from the event)
        listener = ModuleListenerTestSupport.bound(raw);
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

    private static final String OWNER_PHONE = "+243990001234";

    // ── AC1: Individual alert — enabled, no cooldown ─────────────────────────

    @Test
    @DisplayName("alert enabled + no cooldown → sends push notification")
    void onStockThresholdBreached_alertEnabled_noActiveCooldown_sendsPush() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.tryAcquireCooldown(eq("STOCK_ALERT"), any(), any(), any())).thenReturn(true);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of(OWNER_PHONE));

        listener.onStockThresholdBreached(event());

        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(pushNotifier).notifyOwners(eq(TENANT_ID), cap.capture());
        NotificationPayload payload = cap.getValue();
        assertEquals("STOCK_ALERT", payload.type());
        assertTrue(payload.body().contains("Savon Palmolive"));
        assertTrue(payload.body().contains("3 unité(s)"));
        assertTrue(payload.body().contains("Boutique Cosmos"));
        // J15 — the store name is used as is, never prefixed a second time.
        assertFalse(payload.body().contains("Boutique Boutique"));
        assertTrue(payload.body().endsWith("— Boutique Cosmos"), payload.body());
    }

    // ── AC1: Alert disabled → skip ───────────────────────────────────────────

    @Test
    @DisplayName("alert disabled → skips notification entirely")
    void onStockThresholdBreached_alertDisabled_skipsNotification() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(disabledAlertPrefs()));

        listener.onStockThresholdBreached(event());

        verifyNoInteractions(pushNotifier);
        verifyNoInteractions(whatsAppSender);
    }

    // ── AC1: Cooldown active → skip ──────────────────────────────────────────

    @Test
    @DisplayName("cooldown active → skips notification")
    void onStockThresholdBreached_cooldownActive_skipsNotification() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.tryAcquireCooldown(eq("STOCK_ALERT"), eq(PRODUCT_ID), eq(STORE_ID), any()))
                .thenReturn(false);

        listener.onStockThresholdBreached(event());

        verifyNoInteractions(pushNotifier);
        verifyNoInteractions(whatsAppSender);
        // Cooldown left unchanged: no separate upsert
        verify(cooldownRepository, never()).upsertCooldown(any(), any(), any());
    }

    // ── AC1: Push fails → WhatsApp still attempted, then the failure is rethrown (12.4 R2, 12.6a) ──────

    @Test
    @DisplayName("push fails → WhatsApp still sent, DeliveryFailedException rethrown (publication retried)")
    void onStockThresholdBreached_pushFails_rethrowsAfterBothChannels() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(bothChannelPrefs()));
        when(cooldownRepository.tryAcquireCooldown(any(), any(), any(), any())).thenReturn(true);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of(OWNER_PHONE));
        doThrow(new RuntimeException("FCM timeout")).when(pushNotifier).notifyOwners(any(), any());

        assertThrows(com.keevo.shared.event.DeliveryFailedException.class,
                () -> listener.onStockThresholdBreached(event()));
        verify(whatsAppSender).sendReport(eq(OWNER_PHONE), any());
    }

    // ── AC1: WhatsApp fails → push still sent, then the failure is rethrown (12.4 R2, 12.6a) ───────────

    @Test
    @DisplayName("whatsApp fails → push still sent, DeliveryFailedException rethrown (publication retried)")
    void onStockThresholdBreached_whatsAppFails_rethrowsAfterBothChannels() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(bothChannelPrefs()));
        when(cooldownRepository.tryAcquireCooldown(any(), any(), any(), any())).thenReturn(true);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of(OWNER_PHONE));
        doThrow(new RuntimeException("WA fail")).when(whatsAppSender).sendReport(any(), any());

        assertThrows(com.keevo.shared.event.DeliveryFailedException.class,
                () -> listener.onStockThresholdBreached(event()));
        verify(pushNotifier).notifyOwners(eq(TENANT_ID), any());
    }

    // ── AC1: Upserts cooldown after dispatch ─────────────────────────────────

    @Test
    @DisplayName("acquires the cooldown atomically (4h window), no separate upsert for the own product")
    void onStockThresholdBreached_upsertsCooldownAfterSuccess() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.tryAcquireCooldown(any(), any(), any(), any())).thenReturn(true);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of(OWNER_PHONE));

        listener.onStockThresholdBreached(event());

        verify(cooldownRepository).tryAcquireCooldown("STOCK_ALERT", PRODUCT_ID, STORE_ID, Duration.ofHours(4));
        verify(cooldownRepository, never()).upsertCooldown("STOCK_ALERT", PRODUCT_ID, STORE_ID);
    }

    // ── AC2: Three or fewer events → dispatches individually ─────────────────

    @Test
    @DisplayName("three events in window → dispatches individually (no batch)")
    void batch_threeOrFewer_dispatches_individually() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.tryAcquireCooldown(any(), any(), any(), any())).thenReturn(true);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of(OWNER_PHONE));

        for (int i = 0; i < 3; i++) {
            listener.onStockThresholdBreached(event(UUID.randomUUID(), STORE_ID));
        }

        // Each call should produce an individual STOCK_ALERT notification
        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(pushNotifier, times(3)).notifyOwners(eq(TENANT_ID), cap.capture());
        cap.getAllValues().forEach(p -> assertEquals("STOCK_ALERT", p.type()));
    }

    // ── AC2: Fourth event in window → dispatches batch ───────────────────────

    @Test
    @DisplayName("fourth event in window → dispatches batch notification")
    void batch_fourthEventInWindow_dispatchesBatchNotification() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.tryAcquireCooldown(any(), any(), any(), any())).thenReturn(true);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of(OWNER_PHONE));

        // Fire 4 events for the same store
        for (int i = 0; i < 4; i++) {
            listener.onStockThresholdBreached(event(UUID.randomUUID(), STORE_ID));
        }

        // 3 individual + 1 batch
        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(pushNotifier, atLeast(4)).notifyOwners(eq(TENANT_ID), cap.capture());

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

        verifyNoInteractions(pushNotifier);
        verifyNoInteractions(whatsAppSender);
    }

    // ── Story 13.9: cooldown TOCTOU + batch-window eviction ──────────────────

    @Test
    @DisplayName("13.9: two concurrent events, same product → only the cooldown winner notifies")
    void concurrentSameProduct_onlyCooldownWinnerNotifies() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        // The atomic upsert lets exactly one caller acquire the cooldown.
        when(cooldownRepository.tryAcquireCooldown(eq("STOCK_ALERT"), eq(PRODUCT_ID), eq(STORE_ID), any()))
                .thenReturn(true, false);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of(OWNER_PHONE));

        listener.onStockThresholdBreached(event());
        listener.onStockThresholdBreached(event());

        verify(pushNotifier, times(1)).notifyOwners(eq(TENANT_ID), any());
    }

    @Test
    @DisplayName("13.9: lookup failure before dispatch does not consume the cooldown")
    void lookupFailure_doesNotConsumeCooldown() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(storeRepository.findById(STORE_ID)).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> listener.onStockThresholdBreached(event()));

        verify(cooldownRepository, never()).tryAcquireCooldown(any(), any(), any(), any());
        verifyNoInteractions(pushNotifier);
    }

    @Test
    @DisplayName("13.9: window older than 60 s is evicted by the periodic sweep")
    void evictExpiredWindows_removesIdleWindow() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.tryAcquireCooldown(any(), any(), any(), any())).thenReturn(true);
        when(storeRepository.findById(any())).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of(OWNER_PHONE));

        listener.onStockThresholdBreached(event(UUID.randomUUID(), STORE_ID));
        listener.onStockThresholdBreached(event(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(2, raw.openWindowCount());

        clock.advance(Duration.ofSeconds(30));
        raw.evictExpiredWindows();
        assertEquals(2, raw.openWindowCount(), "windows still inside the 60 s batch window are kept");

        clock.advance(Duration.ofSeconds(31));
        raw.evictExpiredWindows();
        assertEquals(0, raw.openWindowCount());
    }

    @Test
    @DisplayName("13.9: event after eviction starts a fresh window (batch counting restarts)")
    void eventAfterEviction_startsFreshWindow() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(enabledPrefs()));
        when(cooldownRepository.tryAcquireCooldown(any(), any(), any(), any())).thenReturn(true);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of(OWNER_PHONE));

        for (int i = 0; i < 3; i++) {
            listener.onStockThresholdBreached(event(UUID.randomUUID(), STORE_ID));
        }
        clock.advance(Duration.ofSeconds(61));
        raw.evictExpiredWindows();
        assertEquals(0, raw.openWindowCount());

        listener.onStockThresholdBreached(event(UUID.randomUUID(), STORE_ID));

        assertEquals(1, raw.openWindowCount());
        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(pushNotifier, times(4)).notifyOwners(eq(TENANT_ID), cap.capture());
        cap.getAllValues().forEach(p -> assertEquals("STOCK_ALERT", p.type()));
    }

    /** Mutable clock — lets the test move time past the batch window. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }

    /** v1s-14-17 — BOTH channel: the alert reaches the resolved owner phone on WhatsApp. */
    @Test
    @DisplayName("v1s-14-17: BOTH channel → WhatsApp sent to the resolved owner phone")
    void onStockThresholdBreached_bothChannel_sendsWhatsAppToResolvedOwnerPhone() {
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(bothChannelPrefs()));
        when(cooldownRepository.tryAcquireCooldown(any(), any(), any(), any())).thenReturn(true);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(testStore()));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT_ID)).thenReturn(Optional.of("+237600000001"));

        listener.onStockThresholdBreached(event());

        verify(whatsAppSender).sendReport(eq("+237600000001"), anyString());
    }
}

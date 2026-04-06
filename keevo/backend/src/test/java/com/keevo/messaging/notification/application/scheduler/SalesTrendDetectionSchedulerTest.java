package com.keevo.messaging.notification.application.scheduler;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationCooldownRepository;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for SalesTrendDetectionScheduler (Story 8.1 — AC3/AC4).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalesTrendDetectionScheduler")
class SalesTrendDetectionSchedulerTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private NotificationPort notificationPort;
    @Mock private NotificationCooldownRepository cooldownRepository;
    @Mock private TenantPreferencesRepository tenantPreferencesRepository;
    @Mock private StoreRepository storeRepository;

    private SalesTrendDetectionScheduler scheduler;

    private static final String SCHEMA = "kv_abc123";
    private static final UUID STORE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new SalesTrendDetectionScheduler(
                jdbcTemplate, notificationPort, cooldownRepository,
                tenantPreferencesRepository, storeRepository);
    }

    private Store testStore() {
        return new Store(STORE_ID, "Cosmos", StoreType.STORE, null, null, true,
                Instant.now(), Instant.now());
    }

    private TenantPreferences trendEnabledPrefs() {
        return TenantPreferences.withDefaults(
                UUID.randomUUID(), SectorType.FOOD_GROCERY, "20:00:00", true, Instant.now());
    }

    private TenantPreferences trendDisabledPrefs() {
        var defaults = trendEnabledPrefs();
        return new TenantPreferences(
                defaults.id(), defaults.sectorType(), defaults.eodReportTime(),
                defaults.stockAlertEnabled(), defaults.createdAt(),
                defaults.eodReportEnabled(), defaults.eodReportChannel(),
                defaults.weeklyReportEnabled(), defaults.weeklyReportDay(),
                defaults.weeklyReportTime(), defaults.weeklyReportChannel(),
                defaults.inventoryReportEnabled(), defaults.inventoryReportChannel(),
                defaults.stockAlertChannel(), false);
    }

    private void stubActiveSchemas(String... schemas) {
        when(jdbcTemplate.queryForList(contains("public.tenants"), eq(String.class)))
                .thenReturn(List.of(schemas));
    }

    // ── AC3: No sales history → skip ─────────────────────────────────────────

    @Test
    @DisplayName("no sales history (avg < 1) → skips notification")
    void detectTrend_noSalesHistoryAvailable_skipsNotification() {
        stubActiveSchemas(SCHEMA);
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(trendEnabledPrefs()));
        when(storeRepository.findAllActive()).thenReturn(List.of(testStore()));
        // Current hour: 5 sales, avg: 0.3 (< 1.0 threshold)
        when(jdbcTemplate.queryForObject(contains("date_trunc('hour', NOW())"), eq(Integer.class), any(Object[].class)))
                .thenReturn(5);
        when(jdbcTemplate.queryForObject(contains("AVG(hourly_count)"), eq(Double.class), any(), any(), any()))
                .thenReturn(0.3);

        scheduler.detectTrends();

        verifyNoInteractions(notificationPort);
    }

    // ── AC3/AC4: Drop > 40% → negative trend notification ───────────────────

    @Test
    @DisplayName("drop > 40% → sends negative trend notification")
    void detectTrend_dropAbove40Percent_sendsNegativeTrendNotification() {
        stubActiveSchemas(SCHEMA);
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(trendEnabledPrefs()));
        when(storeRepository.findAllActive()).thenReturn(List.of(testStore()));
        // Current: 2, avg: 10 → ratio = 0.2 < 0.60
        when(jdbcTemplate.queryForObject(contains("date_trunc('hour', NOW())"), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);
        when(jdbcTemplate.queryForObject(contains("AVG(hourly_count)"), eq(Double.class), any(), any(), any()))
                .thenReturn(10.0);
        when(cooldownRepository.existsActiveCooldown(eq("TREND_DOWN"), isNull(), eq(STORE_ID), any()))
                .thenReturn(false);

        scheduler.detectTrends();

        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(SCHEMA), cap.capture());
        assertEquals("TREND_DOWN", cap.getValue().type());
        assertTrue(cap.getValue().body().contains("Cosmos"));
    }

    // ── AC3/AC4: Spike > 60% → positive trend notification ──────────────────

    @Test
    @DisplayName("spike > 60% → sends positive trend notification")
    void detectTrend_spikeAbove60Percent_sendsPositiveTrendNotification() {
        stubActiveSchemas(SCHEMA);
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(trendEnabledPrefs()));
        when(storeRepository.findAllActive()).thenReturn(List.of(testStore()));
        // Current: 20, avg: 5 → ratio = 4.0 > 1.60
        when(jdbcTemplate.queryForObject(contains("date_trunc('hour', NOW())"), eq(Integer.class), any(Object[].class)))
                .thenReturn(20);
        when(jdbcTemplate.queryForObject(contains("AVG(hourly_count)"), eq(Double.class), any(), any(), any()))
                .thenReturn(5.0);
        when(cooldownRepository.existsActiveCooldown(eq("TREND_UP"), isNull(), eq(STORE_ID), any()))
                .thenReturn(false);

        scheduler.detectTrends();

        ArgumentCaptor<NotificationPayload> cap = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(SCHEMA), cap.capture());
        assertEquals("TREND_UP", cap.getValue().type());
        assertTrue(cap.getValue().body().contains("20 vente(s)"));
    }

    // ── AC3: Trend disabled → skip ───────────────────────────────────────────

    @Test
    @DisplayName("trend notification disabled → skips all stores")
    void detectTrend_trendNotificationDisabled_skipsAll() {
        stubActiveSchemas(SCHEMA);
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(trendDisabledPrefs()));

        scheduler.detectTrends();

        verifyNoInteractions(notificationPort);
        verifyNoInteractions(cooldownRepository);
    }

    // ── AC3: Cooldown active → skip ──────────────────────────────────────────

    @Test
    @DisplayName("cooldown active → skips notification")
    void detectTrend_cooldownActive_skipsNotification() {
        stubActiveSchemas(SCHEMA);
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(trendEnabledPrefs()));
        when(storeRepository.findAllActive()).thenReturn(List.of(testStore()));
        // Trigger drop > 40%
        when(jdbcTemplate.queryForObject(contains("date_trunc('hour', NOW())"), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);
        when(jdbcTemplate.queryForObject(contains("AVG(hourly_count)"), eq(Double.class), any(), any(), any()))
                .thenReturn(10.0);
        when(cooldownRepository.existsActiveCooldown(eq("TREND_DOWN"), isNull(), eq(STORE_ID), any()))
                .thenReturn(true);

        scheduler.detectTrends();

        verifyNoInteractions(notificationPort);
    }

    // ── AC3: avg < 1 → skip (avoid noise on new tenants) ────────────────────

    @Test
    @DisplayName("avg less than 1 → skips to avoid noise")
    void detectTrend_avgLessThan1_skipsToAvoidNoise() {
        stubActiveSchemas(SCHEMA);
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(trendEnabledPrefs()));
        when(storeRepository.findAllActive()).thenReturn(List.of(testStore()));
        when(jdbcTemplate.queryForObject(contains("date_trunc('hour', NOW())"), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("AVG(hourly_count)"), eq(Double.class), any(), any(), any()))
                .thenReturn(0.5);

        scheduler.detectTrends();

        verifyNoInteractions(notificationPort);
    }

    // ── AC3: Exception for one tenant → continues others ─────────────────────

    @Test
    @DisplayName("exception for one tenant → continues other tenants")
    void detectTrend_exceptionForOneTenant_continuesOtherTenants() {
        String schema2 = "kv_def456";
        stubActiveSchemas(SCHEMA, schema2);
        // First tenant throws
        when(tenantPreferencesRepository.findByCurrentTenant())
                .thenThrow(new RuntimeException("DB error"))
                .thenReturn(Optional.of(trendDisabledPrefs()));

        assertDoesNotThrow(() -> scheduler.detectTrends());

        // Second tenant should still be processed (prefs loaded, even if disabled)
        verify(tenantPreferencesRepository, times(2)).findByCurrentTenant();
    }
}

package com.keevo.identity.onboarding.domain.model;

import com.keevo.identity.api.StockAlertChannel;

import com.keevo.identity.api.ReportChannel;
import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantPreferencesView;

import java.time.Instant;
import java.util.UUID;

/**
 * TenantPreferences — Tenant-level configuration defaults set during onboarding.
 *
 * <p>AC8: Seeded with eod_report_time = '20:00:00', stock_alert_enabled = true,
 * and the chosen sector_type.
 *
 * <p>Story 7.5: Extended with 9 report-preference fields.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public record TenantPreferences(
        UUID id,
        SectorType sectorType,
        String eodReportTime,               // "HH:mm:ss" format — e.g. "20:00:00"
        boolean stockAlertEnabled,
        Instant createdAt,
        // ── Story 7.5 — Report preferences ────────────────────────────────────
        boolean eodReportEnabled,           // default true
        ReportChannel eodReportChannel,     // default WHATSAPP
        boolean weeklyReportEnabled,        // default true
        int weeklyReportDay,                // 0=Sunday … 6=Saturday, default 0
        String weeklyReportTime,            // "HH:mm:ss", default "20:00:00"
        ReportChannel weeklyReportChannel,  // default WHATSAPP
        boolean inventoryReportEnabled,     // default true
        ReportChannel inventoryReportChannel, // default WHATSAPP
        StockAlertChannel stockAlertChannel,  // default PUSH
        // ── Story 8.1 — Trend notification preference ─────────────────────────
        boolean trendNotificationEnabled,   // default true
        // ── Story 17.2 — seller leaderboard shared with employees (advanced-reports) ──
        boolean leaderboardVisibleToEmployees, // default false
        // ── Story 19.12 — default sale flow (written only by its own use case) ──
        SaleFlow defaultSaleFlow              // default POS
) {

    public TenantPreferences {
        if (defaultSaleFlow == null) {
            defaultSaleFlow = SaleFlow.POS;
        }
    }

    /**
     * Pre-19.12 arity — {@code defaultSaleFlow = POS}. Safe for an update: the generic repository update never writes
     * the sale flow (it has its own use case).
     */
    public TenantPreferences(UUID id, SectorType sectorType, String eodReportTime, boolean stockAlertEnabled,
                             Instant createdAt, boolean eodReportEnabled, ReportChannel eodReportChannel,
                             boolean weeklyReportEnabled, int weeklyReportDay, String weeklyReportTime,
                             ReportChannel weeklyReportChannel, boolean inventoryReportEnabled,
                             ReportChannel inventoryReportChannel, StockAlertChannel stockAlertChannel,
                             boolean trendNotificationEnabled, boolean leaderboardVisibleToEmployees) {
        this(id, sectorType, eodReportTime, stockAlertEnabled, createdAt, eodReportEnabled, eodReportChannel,
                weeklyReportEnabled, weeklyReportDay, weeklyReportTime, weeklyReportChannel, inventoryReportEnabled,
                inventoryReportChannel, stockAlertChannel, trendNotificationEnabled, leaderboardVisibleToEmployees,
                SaleFlow.POS);
    }

    /**
     * Pre-17.2 arity — {@code leaderboardVisibleToEmployees = false} (the column default). Production code that
     * rebuilds an existing row must use the canonical constructor and carry the stored flag.
     */
    public TenantPreferences(UUID id, SectorType sectorType, String eodReportTime, boolean stockAlertEnabled,
                             Instant createdAt, boolean eodReportEnabled, ReportChannel eodReportChannel,
                             boolean weeklyReportEnabled, int weeklyReportDay, String weeklyReportTime,
                             ReportChannel weeklyReportChannel, boolean inventoryReportEnabled,
                             ReportChannel inventoryReportChannel, StockAlertChannel stockAlertChannel,
                             boolean trendNotificationEnabled) {
        this(id, sectorType, eodReportTime, stockAlertEnabled, createdAt, eodReportEnabled, eodReportChannel,
                weeklyReportEnabled, weeklyReportDay, weeklyReportTime, weeklyReportChannel, inventoryReportEnabled,
                inventoryReportChannel, stockAlertChannel, trendNotificationEnabled, false, SaleFlow.POS);
    }

    /** The same preferences with the leaderboard visibility set (Story 17.2). */
    public TenantPreferences withLeaderboardVisibleToEmployees(boolean visible) {
        return new TenantPreferences(id, sectorType, eodReportTime, stockAlertEnabled, createdAt, eodReportEnabled,
                eodReportChannel, weeklyReportEnabled, weeklyReportDay, weeklyReportTime, weeklyReportChannel,
                inventoryReportEnabled, inventoryReportChannel, stockAlertChannel, trendNotificationEnabled, visible,
                defaultSaleFlow);
    }

    /** The same preferences with the default sale flow set (Story 19.12). */
    public TenantPreferences withDefaultSaleFlow(SaleFlow flow) {
        return new TenantPreferences(id, sectorType, eodReportTime, stockAlertEnabled, createdAt, eodReportEnabled,
                eodReportChannel, weeklyReportEnabled, weeklyReportDay, weeklyReportTime, weeklyReportChannel,
                inventoryReportEnabled, inventoryReportChannel, stockAlertChannel, trendNotificationEnabled,
                leaderboardVisibleToEmployees, flow);
    }

    /** The stored values as the published view (no Story 17.8 rule applied: see the effective-preferences use case). */
    public TenantPreferencesView toView() {
        return new TenantPreferencesView(eodReportTime, stockAlertEnabled, eodReportEnabled,
                eodReportChannel, weeklyReportEnabled, weeklyReportDay, weeklyReportTime, weeklyReportChannel,
                inventoryReportEnabled, inventoryReportChannel, stockAlertChannel, trendNotificationEnabled,
                leaderboardVisibleToEmployees);
    }

    /**
     * Factory with Story 8.1 defaults — used by OnboardingService and tests.
     * Avoids breaking callers when new fields are added to the record.
     */
    public static TenantPreferences withDefaults(UUID id, SectorType sectorType,
                                                  String eodReportTime,
                                                  boolean stockAlertEnabled,
                                                  Instant createdAt) {
        return new TenantPreferences(
            id, sectorType, eodReportTime, stockAlertEnabled, createdAt,
            true, ReportChannel.WHATSAPP,
            true, 0, "20:00:00", ReportChannel.WHATSAPP,
            true, ReportChannel.WHATSAPP,
            StockAlertChannel.PUSH,
            true,
            false,
            SaleFlow.POS
        );
    }
}


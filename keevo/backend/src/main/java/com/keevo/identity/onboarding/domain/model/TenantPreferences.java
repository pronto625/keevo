package com.keevo.identity.onboarding.domain.model;

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
        boolean trendNotificationEnabled    // default true
) {

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
            true
        );
    }
}


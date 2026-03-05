package com.keevo.identity.onboarding.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * TenantPreferences — Tenant-level configuration defaults set during onboarding.
 *
 * <p>AC8: Seeded with eod_report_time = '20:00:00', stock_alert_enabled = true,
 * and the chosen sector_type.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public record TenantPreferences(
        UUID id,
        SectorType sectorType,
        String eodReportTime,       // "HH:mm:ss" format — e.g. "20:00:00"
        boolean stockAlertEnabled,
        Instant createdAt
) {}

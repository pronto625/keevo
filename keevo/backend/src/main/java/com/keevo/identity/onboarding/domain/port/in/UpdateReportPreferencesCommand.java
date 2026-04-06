package com.keevo.identity.onboarding.domain.port.in;

import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;

/**
 * UpdateReportPreferencesCommand — Command record for updating report preferences.
 * Story 7.5 — Task 2.4 — GoF Command pattern.
 *
 * <p>Immutable value object — decouples service from HTTP layer.
 * Pure Java — NO Spring/framework imports.
 */
public record UpdateReportPreferencesCommand(
        boolean eodReportEnabled,
        ReportChannel eodReportChannel,
        String eodReportTime,               // "HH:mm:ss", nullable (keeps existing if null)
        boolean weeklyReportEnabled,
        int weeklyReportDay,                // 0–6
        String weeklyReportTime,            // "HH:mm:ss"
        ReportChannel weeklyReportChannel,
        boolean inventoryReportEnabled,
        ReportChannel inventoryReportChannel,
        boolean stockAlertEnabled,          // AC3 — must be updatable
        StockAlertChannel stockAlertChannel,
        Boolean trendNotificationEnabled    // Story 8.1 — nullable means no change
) {}

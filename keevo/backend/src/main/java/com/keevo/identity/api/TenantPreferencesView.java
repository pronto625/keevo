package com.keevo.identity.api;

/**
 * A tenant's report / alert preferences (Stories 7.5, 8.1) as seen by other modules (Story 12.2d).
 * Same values as the onboarding preferences row; times are {@code "HH:mm:ss"} strings.
 *
 * @param weeklyReportDay 0 = Sunday … 6 = Saturday
 */
public record TenantPreferencesView(
        String eodReportTime,
        boolean stockAlertEnabled,
        boolean eodReportEnabled,
        ReportChannel eodReportChannel,
        boolean weeklyReportEnabled,
        int weeklyReportDay,
        String weeklyReportTime,
        ReportChannel weeklyReportChannel,
        boolean inventoryReportEnabled,
        ReportChannel inventoryReportChannel,
        StockAlertChannel stockAlertChannel,
        boolean trendNotificationEnabled
) {}

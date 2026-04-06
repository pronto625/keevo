package com.keevo.identity.onboarding.adapter.in.web;

import com.keevo.identity.onboarding.domain.model.TenantPreferences;

import java.time.Instant;

/**
 * TenantPreferencesResponseDto — Response DTO for tenant preferences.
 * Story 7.5 — added 9 reporting & stock-alert fields.
 */
public record TenantPreferencesResponseDto(
    String sectorType,
    String eodReportTime,
    boolean stockAlertEnabled,
    Instant createdAt,
    boolean eodReportEnabled,
    String eodReportChannel,
    boolean weeklyReportEnabled,
    int weeklyReportDay,
    String weeklyReportTime,
    String weeklyReportChannel,
    boolean inventoryReportEnabled,
    String inventoryReportChannel,
    String stockAlertChannel,
    boolean trendNotificationEnabled
) {

    public static TenantPreferencesResponseDto fromDomain(TenantPreferences prefs) {
        return new TenantPreferencesResponseDto(
            prefs.sectorType() != null ? prefs.sectorType().name() : null,
            prefs.eodReportTime(),
            prefs.stockAlertEnabled(),
            prefs.createdAt(),
            prefs.eodReportEnabled(),
            prefs.eodReportChannel() != null ? prefs.eodReportChannel().name() : "WHATSAPP",
            prefs.weeklyReportEnabled(),
            prefs.weeklyReportDay(),
            prefs.weeklyReportTime(),
            prefs.weeklyReportChannel() != null ? prefs.weeklyReportChannel().name() : "WHATSAPP",
            prefs.inventoryReportEnabled(),
            prefs.inventoryReportChannel() != null ? prefs.inventoryReportChannel().name() : "WHATSAPP",
            prefs.stockAlertChannel() != null ? prefs.stockAlertChannel().name() : "PUSH",
            prefs.trendNotificationEnabled()
        );
    }
}
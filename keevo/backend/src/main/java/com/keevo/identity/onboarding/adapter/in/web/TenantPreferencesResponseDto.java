package com.keevo.identity.onboarding.adapter.in.web;

import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;

import java.time.Instant;

/**
 * TenantPreferencesResponseDto — Response DTO for tenant preferences.
 * Story 7.5 — added 9 reporting & stock-alert fields.
 * Story 17.8 — {@code reportConfigEditable} (additive, last component): {@code true} iff the paid module
 * {@code report-config} is ENABLED; the GET returns the effective values (fixed defaults when it is DISABLED).
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
    boolean trendNotificationEnabled,
    boolean reportConfigEditable,
    boolean leaderboardVisibleToEmployees
) {

    /**
     * The GET view (Story 17.8): {@code sectorType} and {@code createdAt} from the stored row, report / alert
     * values from the effective preferences.
     */
    public static TenantPreferencesResponseDto effective(TenantPreferences stored, TenantPreferencesView effective,
                                                         boolean reportConfigEditable) {
        return new TenantPreferencesResponseDto(
            stored.sectorType() != null ? stored.sectorType().name() : null,
            effective.eodReportTime(),
            effective.stockAlertEnabled(),
            stored.createdAt(),
            effective.eodReportEnabled(),
            effective.eodReportChannel() != null ? effective.eodReportChannel().name() : "WHATSAPP",
            effective.weeklyReportEnabled(),
            effective.weeklyReportDay(),
            effective.weeklyReportTime(),
            effective.weeklyReportChannel() != null ? effective.weeklyReportChannel().name() : "WHATSAPP",
            effective.inventoryReportEnabled(),
            effective.inventoryReportChannel() != null ? effective.inventoryReportChannel().name() : "WHATSAPP",
            effective.stockAlertChannel() != null ? effective.stockAlertChannel().name() : "PUSH",
            effective.trendNotificationEnabled(),
            reportConfigEditable,
            stored.leaderboardVisibleToEmployees()
        );
    }

    /** The stored row as is — the PUT response (only reachable with {@code report-config} ENABLED → editable). */
    public static TenantPreferencesResponseDto fromDomain(TenantPreferences prefs) {
        return effective(prefs, prefs.toView(), true);
    }
}

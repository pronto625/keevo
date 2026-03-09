package com.keevo.identity.onboarding.adapter.in.web;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;

import java.time.Instant;

/**
 * TenantPreferencesResponseDto — Response DTO for tenant preferences.
 */
public record TenantPreferencesResponseDto(
    String sectorType,
    String eodReportTime,
    boolean stockAlertEnabled,
    Instant createdAt
) {

    public static TenantPreferencesResponseDto fromDomain(TenantPreferences prefs) {
        return new TenantPreferencesResponseDto(
            prefs.sectorType() != null ? prefs.sectorType().name() : null,
            prefs.eodReportTime(),
            prefs.stockAlertEnabled(),
            prefs.createdAt()
        );
    }
}
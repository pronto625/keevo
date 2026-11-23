package com.keevo.identity.onboarding.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * UpdateReportPreferencesRequestDto — Request body for PUT /report-preferences.
 * All fields are required; channel values are validated by enum conversion downstream. Times: {@code HH:mm} or
 * {@code HH:mm:ss} (Story 22.19).
 */
public record UpdateReportPreferencesRequestDto(

    boolean eodReportEnabled,

    @NotNull
    String eodReportChannel,

    boolean weeklyReportEnabled,

    @Min(0) @Max(6)
    int weeklyReportDay,

    @NotNull
    @Pattern(regexp = TIME, message = "Heure du rapport hebdomadaire invalide (HH:mm)")
    String weeklyReportTime,

    @NotNull
    String weeklyReportChannel,

    boolean inventoryReportEnabled,

    @NotNull
    String inventoryReportChannel,

    @NotNull
    Boolean stockAlertEnabled,

    @NotNull
    String stockAlertChannel,

    @Pattern(regexp = TIME, message = "Heure du rapport de fin de journée invalide (HH:mm)")
    String eodReportTime,

    Boolean trendNotificationEnabled       // Story 8.1 — nullable: absent means no change
) {

    /** Story 22.19 (D3) — {@code HH:mm} (what the GET returns) or {@code HH:mm:ss}. */
    static final String TIME = "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$";

    /** {@code HH:mm} → {@code HH:mm:00}; {@code HH:mm:ss} and {@code null} unchanged. */
    static String withSeconds(String time) {
        return time != null && time.length() == 5 ? time + ":00" : time;
    }
}

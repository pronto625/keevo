package com.keevo.identity.onboarding.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * UpdateReportPreferencesRequestDto — Request body for PUT /report-preferences.
 * All fields are required; channel values are validated by enum conversion downstream.
 */
public record UpdateReportPreferencesRequestDto(

    boolean eodReportEnabled,

    @NotNull
    String eodReportChannel,

    boolean weeklyReportEnabled,

    @Min(0) @Max(6)
    int weeklyReportDay,

    @NotNull
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$",
             message = "weeklyReportTime must be HH:mm:ss")
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

    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$",
             message = "eodReportTime must be HH:mm:ss")
    String eodReportTime
) {}

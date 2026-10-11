package com.keevo.commerce.sale.adapter.in.rest.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * CloseDayRequestDto — Request body for manual day closure.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
public record CloseDayRequestDto(
        @NotNull UUID storeId,
        UUID id,          // optional — the app's local closure id, kept so it is not duplicated on pull
        Instant closedAt  // optional — when the closure was made on the device
) {}

package com.keevo.commerce.sale.adapter.in.rest.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * CloseDayRequestDto — Request body for manual day closure.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
public record CloseDayRequestDto(
        @NotNull UUID storeId
) {}

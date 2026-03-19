package com.keevo.commerce.sale.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DayClosureResponseDto — Response body for day closure.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
public record DayClosureResponseDto(
        UUID id,
        UUID storeId,
        UUID actorId,
        Instant closedAt,
        boolean isAutomatic,
        DayClosureSummaryDto summary
) {}

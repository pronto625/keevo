package com.keevo.commerce.sale.adapter.in.rest.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SaleHistoryItemDto — Response DTO for a single sale in history.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
public record SaleHistoryItemDto(
        UUID id,
        UUID storeId,
        UUID employeeId,
        UUID clientId,
        String paymentMode,
        int totalAmount,
        int discountAmount,
        String status,
        Instant occurredAt,
        Instant createdAt,
        List<SaleItemHistoryDto> items
) {}

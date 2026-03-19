package com.keevo.commerce.sale.adapter.in.rest.dto;

import java.util.UUID;

/**
 * SaleItemHistoryDto — Response DTO for a single sale item in history.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
public record SaleItemHistoryDto(
        UUID id,
        UUID productId,
        UUID variantId,
        String productName,
        int catalogueUnitPrice,
        int appliedUnitPrice,
        int quantity,
        int subtotal
) {}

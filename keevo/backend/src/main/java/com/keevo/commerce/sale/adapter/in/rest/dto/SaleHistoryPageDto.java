package com.keevo.commerce.sale.adapter.in.rest.dto;

import java.util.List;

/**
 * SaleHistoryPageDto — Paginated response for sales history.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
public record SaleHistoryPageDto(
        List<SaleHistoryItemDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}

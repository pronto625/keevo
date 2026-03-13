package com.keevo.catalog.product.application.dto;

/**
 * CsvRowError — Represents a single row-level validation error during CSV import.
 */
public record CsvRowError(
        int lineNumber,  // 1-based CSV line number (header = 1, first data = 2)
        String column,   // column/field name where the error occurred
        String message   // human-readable error description
) {}

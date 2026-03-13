package com.keevo.catalog.product.adapter.in.web.dto;

import com.keevo.catalog.product.application.dto.CsvRowError;
import com.keevo.catalog.product.application.dto.ImportResult;

import java.util.List;

/**
 * ImportResultResponseDto — HTTP response DTO for the CSV import endpoint.
 *
 * <p>Serialised from {@link ImportResult}.
 * Story 2.4.
 */
public record ImportResultResponseDto(
        int                  imported,
        int                  skipped,
        boolean              limitReached,
        String               limitMessage,
        List<CsvRowErrorDto> errors
) {

    public record CsvRowErrorDto(
            int    lineNumber,
            String column,
            String message
    ) {}

    /** Factory method — converts domain ImportResult to the HTTP response DTO. */
    public static ImportResultResponseDto from(ImportResult result) {
        List<CsvRowErrorDto> errorDtos = result.getErrors().stream()
                .map(e -> new CsvRowErrorDto(e.lineNumber(), e.column(), e.message()))
                .toList();
        return new ImportResultResponseDto(
                result.getImported(),
                result.getSkipped(),
                result.isLimitReached(),
                result.getLimitMessage(),
                errorDtos
        );
    }
}

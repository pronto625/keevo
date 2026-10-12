package com.keevo.reporting.export.adapter.in.rest.dto;

import java.util.List;

/**
 * Body of {@code POST /api/v1/exports} (Story v1s-18-1). Scalars are strings, parsed by the
 * controller so that a malformed value answers 400 {@code VALIDATION_ERROR}.
 *
 * @param datasets dataset keys (non-empty, no duplicates)
 * @param format   {@code CSV} | {@code XLSX} | {@code PDF}
 * @param storeId  optional store UUID
 * @param from     optional ISO-8601 instant (inclusive)
 * @param to       optional ISO-8601 instant (inclusive)
 */
public record ExportRequestDto(List<String> datasets, String format, String storeId, String from, String to) {
}

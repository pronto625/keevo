package com.keevo.reporting.export.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keevo.reporting.export.domain.model.ExportJob;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A background export job (« Mes exports »), Story v1s-18-1. {@code downloadUrl} only when READY. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportJobDto(
        UUID id,
        String status,
        String format,
        List<String> datasets,
        UUID storeId,
        Instant from,
        Instant to,
        Long rowCount,
        String fileName,
        Long fileSize,
        String contentType,
        String errorCode,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant expiresAt,
        String downloadUrl
) {
    public static ExportJobDto from(ExportJob j, String downloadUrl) {
        return new ExportJobDto(j.getId(), j.getStatus().name(), j.getFormat().name(), j.getDatasets(),
                j.getFilter().storeId(), j.getFilter().from(), j.getFilter().to(), j.getRowCount(),
                j.getFileName(), j.getFileSize(), j.getContentType(), j.getErrorCode(), j.getCreatedAt(),
                j.getStartedAt(), j.getCompletedAt(), j.getExpiresAt(), downloadUrl);
    }
}

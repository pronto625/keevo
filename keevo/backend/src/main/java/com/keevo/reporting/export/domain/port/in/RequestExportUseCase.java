package com.keevo.reporting.export.domain.port.in;

import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** {@code POST /api/v1/exports} — direct or background export (Story v1s-18-1). */
public interface RequestExportUseCase {

    /**
     * Validate, rate-limit, gate, count, then either stream the file to {@code direct} or create a
     * background job.
     *
     * @param tenantSchema caller's tenant schema
     * @param actorId      caller (OWNER)
     * @param request      the export request
     * @param direct       target opened only for a direct export
     */
    ExportOutcome request(String tenantSchema, UUID actorId, ExportRequest request, DirectOutput direct)
            throws IOException;

    /** Lazily opened direct target: headers are set from the arguments before the first byte. */
    @FunctionalInterface
    interface DirectOutput {
        OutputStream open(String contentType, String fileName) throws IOException;
    }

    /** Either a direct export already written, or a background job. */
    sealed interface ExportOutcome permits Direct, Background {}

    record Direct(UUID exportId, String fileName, long rowCount) implements ExportOutcome {}

    record Background(ExportJob job) implements ExportOutcome {}
}

package com.keevo.reporting.export.domain.port.in;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

/** Download of a READY export file (Story v1s-18-1). */
public interface DownloadExportUseCase {

    /**
     * Open the file of a job of the current tenant.
     *
     * @throws com.keevo.shared.domain.exception.DomainException EXPORT_NOT_FOUND (404),
     *         EXPORT_EXPIRED (410), EXPORT_NOT_READY (409)
     */
    ExportFile open(UUID jobId) throws IOException;

    /**
     * Verify a signed download token.
     *
     * @throws com.keevo.shared.domain.exception.DomainException EXPORT_LINK_INVALID (403),
     *         EXPORT_EXPIRED (410)
     */
    LinkTarget verifyLink(String token);

    /** Signed download path of a READY job, or {@code null} when it is not downloadable. */
    String signedDownloadPath(String tenantSchema, UUID jobId, Instant expiresAt);

    record ExportFile(String fileName, String contentType, long size, InputStream content) {}

    record LinkTarget(String tenantSchema, UUID jobId) {}
}

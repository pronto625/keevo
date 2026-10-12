package com.keevo.reporting.export.domain.model;

import com.keevo.shared.application.port.export.ExportFilter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ExportJob — a background export of the current tenant (Story v1s-18-1).
 *
 * <p>Transitions: {@code PENDING → RUNNING → READY → EXPIRED}; {@code PENDING|RUNNING → FAILED}.
 * Any other transition throws {@link IllegalStateException}.
 */
public final class ExportJob {

    private final UUID id;
    private final UUID requestedBy;
    private ExportJobStatus status;
    private final ExportFormat format;
    private final List<String> datasets;
    private final ExportFilter filter;
    private Long rowCount;
    private final String fileName;
    private Long fileSize;
    private final String contentType;
    private String storagePath;
    private String errorCode;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant expiresAt;

    /** Full constructor (rehydration from persistence). */
    public ExportJob(UUID id, UUID requestedBy, ExportJobStatus status, ExportFormat format,
                     List<String> datasets, ExportFilter filter, Long rowCount, String fileName,
                     Long fileSize, String contentType, String storagePath, String errorCode,
                     Instant createdAt, Instant startedAt, Instant completedAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.status = Objects.requireNonNull(status, "status");
        this.format = Objects.requireNonNull(format, "format");
        this.datasets = List.copyOf(datasets);
        this.filter = filter == null ? ExportFilter.none() : filter;
        this.rowCount = rowCount;
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.fileSize = fileSize;
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.storagePath = storagePath;
        this.errorCode = errorCode;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.expiresAt = expiresAt;
    }

    /** A new PENDING job. */
    public static ExportJob pending(UUID id, UUID requestedBy, ExportFormat format, List<String> datasets,
                                    ExportFilter filter, String fileName, String contentType, Instant now) {
        return new ExportJob(id, requestedBy, ExportJobStatus.PENDING, format, datasets, filter,
                null, fileName, null, contentType, null, null, now, null, null, null);
    }

    public void start(Instant now) {
        require(status == ExportJobStatus.PENDING, "start");
        status = ExportJobStatus.RUNNING;
        startedAt = now;
    }

    public void complete(long rows, long size, String path, Instant now, Instant expiry) {
        require(status == ExportJobStatus.RUNNING, "complete");
        status = ExportJobStatus.READY;
        rowCount = rows;
        fileSize = size;
        storagePath = Objects.requireNonNull(path, "path");
        completedAt = now;
        expiresAt = Objects.requireNonNull(expiry, "expiry");
    }

    public void fail(String code, Instant now) {
        require(status.isActive(), "fail");
        status = ExportJobStatus.FAILED;
        errorCode = code;
        completedAt = now;
    }

    public void expire() {
        require(status == ExportJobStatus.READY, "expire");
        status = ExportJobStatus.EXPIRED;
    }

    /** READY whose {@code expiresAt} has passed at {@code now}. */
    public boolean isExpiredAt(Instant now) {
        return status == ExportJobStatus.EXPIRED
                || (status == ExportJobStatus.READY && expiresAt != null && !now.isBefore(expiresAt));
    }

    /** READY and not yet expired. */
    public boolean isDownloadableAt(Instant now) {
        return status == ExportJobStatus.READY && !isExpiredAt(now);
    }

    private void require(boolean allowed, String transition) {
        if (!allowed) {
            throw new IllegalStateException("Illegal export job transition '" + transition + "' from " + status);
        }
    }

    public UUID getId() { return id; }
    public UUID getRequestedBy() { return requestedBy; }
    public ExportJobStatus getStatus() { return status; }
    public ExportFormat getFormat() { return format; }
    public List<String> getDatasets() { return datasets; }
    public ExportFilter getFilter() { return filter; }
    public Long getRowCount() { return rowCount; }
    public String getFileName() { return fileName; }
    public Long getFileSize() { return fileSize; }
    public String getContentType() { return contentType; }
    public String getStoragePath() { return storagePath; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}

package com.keevo.reporting.export.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * ExportJobJpaEntity — row of the tenant table {@code export_jobs} (Story v1s-18-1).
 * {@code public.export_jobs} (V15) is the validation template.
 */
@Entity
@Table(name = "export_jobs")
public class ExportJobJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "requested_by", nullable = false, columnDefinition = "UUID")
    private UUID requestedBy;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "format", nullable = false, length = 10)
    private String format;

    /** Comma-separated dataset keys, in request order. */
    @Column(name = "datasets", nullable = false, length = 500)
    private String datasets;

    @Column(name = "store_id", columnDefinition = "UUID")
    private UUID storeId;

    @Column(name = "period_from")
    private Instant periodFrom;

    @Column(name = "period_to")
    private Instant periodTo;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "storage_path", length = 500)
    private String storagePath;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ExportJobJpaEntity() {
    }

    @SuppressWarnings("java:S107")
    ExportJobJpaEntity(UUID id, UUID requestedBy, String status, String format, String datasets,
                       UUID storeId, Instant periodFrom, Instant periodTo, Long rowCount,
                       String fileName, Long fileSize, String contentType, String storagePath,
                       String errorCode, Instant createdAt, Instant startedAt, Instant completedAt,
                       Instant expiresAt) {
        this.id = id;
        this.requestedBy = requestedBy;
        this.status = status;
        this.format = format;
        this.datasets = datasets;
        this.storeId = storeId;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.rowCount = rowCount;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.storagePath = storagePath;
        this.errorCode = errorCode;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getStatus() { return status; }
    public String getFormat() { return format; }
    public String getDatasets() { return datasets; }
    public UUID getStoreId() { return storeId; }
    public Instant getPeriodFrom() { return periodFrom; }
    public Instant getPeriodTo() { return periodTo; }
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

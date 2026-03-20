package com.keevo.sync.sync.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sync_operations_log")
public class SyncOperationsLogJpaEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "operation_type", length = 50, nullable = false)
    private String operationType;

    @Column(name = "entity_id", length = 36)
    private String entityId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(name = "client_timestamp")
    private Instant clientTimestamp;

    protected SyncOperationsLogJpaEntity() {}

    public SyncOperationsLogJpaEntity(String id, String operationType, String entityId,
                                      String status, String errorReason,
                                      Instant processedAt, Instant clientTimestamp) {
        this.id = id;
        this.operationType = operationType;
        this.entityId = entityId;
        this.status = status;
        this.errorReason = errorReason;
        this.processedAt = processedAt;
        this.clientTimestamp = clientTimestamp;
    }

    public String getId() { return id; }
    public String getOperationType() { return operationType; }
    public String getEntityId() { return entityId; }
    public String getStatus() { return status; }
    public String getErrorReason() { return errorReason; }
    public Instant getProcessedAt() { return processedAt; }
    public Instant getClientTimestamp() { return clientTimestamp; }
}

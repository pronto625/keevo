package com.keevo.sync.sync.adapter.out.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "sync_conflicts_log")
public class SyncConflictsLogJpaEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "operation_id", length = 36, nullable = false)
    private String operationId;

    @Column(name = "operation_type", length = 50, nullable = false)
    private String operationType;

    @Column(name = "entity_id", length = 36)
    private String entityId;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "conflict_type", length = 30, nullable = false)
    private String conflictType;

    @Column(name = "strategy", length = 30, nullable = false)
    private String strategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conflict_data", columnDefinition = "jsonb")
    private Map<String, Object> conflictData;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    @Column(name = "actor_id", length = 36)
    private String actorId;

    protected SyncConflictsLogJpaEntity() {}

    public SyncConflictsLogJpaEntity(String id, String operationId, String operationType,
                                     String entityId, String entityType,
                                     String conflictType, String strategy,
                                     Map<String, Object> conflictData,
                                     Instant resolvedAt, String actorId) {
        this.id = id;
        this.operationId = operationId;
        this.operationType = operationType;
        this.entityId = entityId;
        this.entityType = entityType;
        this.conflictType = conflictType;
        this.strategy = strategy;
        this.conflictData = conflictData;
        this.resolvedAt = resolvedAt;
        this.actorId = actorId;
    }

    public String getId() { return id; }
    public String getOperationId() { return operationId; }
    public String getOperationType() { return operationType; }
    public String getEntityId() { return entityId; }
    public String getEntityType() { return entityType; }
    public String getConflictType() { return conflictType; }
    public String getStrategy() { return strategy; }
    public Map<String, Object> getConflictData() { return conflictData; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getActorId() { return actorId; }
}

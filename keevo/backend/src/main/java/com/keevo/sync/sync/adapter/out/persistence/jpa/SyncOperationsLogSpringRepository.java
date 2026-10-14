package com.keevo.sync.sync.adapter.out.persistence.jpa;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncOperationsLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SyncOperationsLogSpringRepository
        extends JpaRepository<SyncOperationsLogJpaEntity, String> {

    /** Latest operation that wrote the entity: a superseded one (acknowledged, not applied) is skipped. */
    @Query("SELECT e FROM SyncOperationsLogJpaEntity e " +
           "WHERE e.entityId = :entityId AND e.id <> :excludeId AND e.status = 'APPLIED' " +
           "AND (e.errorReason IS NULL OR e.errorReason <> 'LWW_SUPERSEDED') " +
           "ORDER BY e.processedAt DESC LIMIT 1")
    Optional<SyncOperationsLogJpaEntity> findPreviousAppliedByEntityId(
            @Param("entityId") String entityId,
            @Param("excludeId") String excludeOperationId);

    @Query("SELECT e FROM SyncOperationsLogJpaEntity e " +
           "WHERE e.entityId IN :entityIds AND e.status = 'APPLIED' " +
           "AND e.errorReason LIKE 'MERGED_INTO:%'")
    List<SyncOperationsLogJpaEntity> findMergedInto(@Param("entityIds") Collection<String> entityIds);

    /**
     * Inserts a placeholder row, or re-claims a REJECTED one. Returns 0 when the row exists
     * with a final status (or was finalised by a concurrent transaction we waited on).
     */
    @Modifying
    @Query(value = "INSERT INTO sync_operations_log " +
            "(id, operation_type, entity_id, status, error_reason, processed_at, client_timestamp) " +
            "VALUES (:id, :operationType, :entityId, 'REJECTED', 'PROCESSING', :now, :clientTimestamp) " +
            "ON CONFLICT (id) DO UPDATE SET error_reason = 'PROCESSING', processed_at = :now " +
            "WHERE sync_operations_log.status = 'REJECTED'",
            nativeQuery = true)
    int claim(@Param("id") String id,
              @Param("operationType") String operationType,
              @Param("entityId") String entityId,
              @Param("now") Instant now,
              @Param("clientTimestamp") Instant clientTimestamp);
}

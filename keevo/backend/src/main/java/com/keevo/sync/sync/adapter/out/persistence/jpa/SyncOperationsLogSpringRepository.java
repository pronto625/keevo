package com.keevo.sync.sync.adapter.out.persistence.jpa;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncOperationsLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SyncOperationsLogSpringRepository
        extends JpaRepository<SyncOperationsLogJpaEntity, String> {

    @Query("SELECT e FROM SyncOperationsLogJpaEntity e " +
           "WHERE e.entityId = :entityId AND e.id <> :excludeId AND e.status = 'APPLIED' " +
           "ORDER BY e.processedAt DESC LIMIT 1")
    Optional<SyncOperationsLogJpaEntity> findPreviousAppliedByEntityId(
            @Param("entityId") String entityId,
            @Param("excludeId") String excludeOperationId);

    @Query("SELECT e FROM SyncOperationsLogJpaEntity e " +
           "WHERE e.entityId IN :entityIds AND e.status = 'APPLIED' " +
           "AND e.errorReason LIKE 'MERGED_INTO:%'")
    List<SyncOperationsLogJpaEntity> findMergedInto(@Param("entityIds") Collection<String> entityIds);
}

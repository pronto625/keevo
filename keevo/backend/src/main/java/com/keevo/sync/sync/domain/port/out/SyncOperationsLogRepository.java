package com.keevo.sync.sync.domain.port.out;

import com.keevo.sync.sync.domain.model.SyncOperationsLogEntry;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * SyncOperationsLogRepository — Port for idempotency log persistence.
 */
public interface SyncOperationsLogRepository {

    boolean existsById(String operationId);

    void save(SyncOperationsLogEntry entry);

    Optional<SyncOperationsLogEntry> findPreviousAppliedByEntityId(String entityId, String excludeOperationId);

    /**
     * For client-generated product ids whose CREATE was merged into an already-existing
     * server product (same name), maps clientId → server product id.
     * Ids that were never merged are absent from the result.
     */
    Map<String, String> findMergedProductTargets(Collection<String> clientIds);
}

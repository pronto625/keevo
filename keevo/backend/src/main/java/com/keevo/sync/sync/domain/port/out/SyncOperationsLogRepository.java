package com.keevo.sync.sync.domain.port.out;

import com.keevo.sync.sync.domain.model.SyncOperationsLogEntry;

import java.util.Optional;

/**
 * SyncOperationsLogRepository — Port for idempotency log persistence.
 */
public interface SyncOperationsLogRepository {

    boolean existsById(String operationId);

    void save(SyncOperationsLogEntry entry);

    Optional<SyncOperationsLogEntry> findPreviousAppliedByEntityId(String entityId, String excludeOperationId);
}

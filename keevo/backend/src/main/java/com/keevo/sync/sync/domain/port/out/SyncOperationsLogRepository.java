package com.keevo.sync.sync.domain.port.out;

import com.keevo.sync.sync.domain.model.SyncOperationsLogEntry;

/**
 * SyncOperationsLogRepository — Port for idempotency log persistence.
 */
public interface SyncOperationsLogRepository {

    boolean existsById(String operationId);

    void save(SyncOperationsLogEntry entry);
}

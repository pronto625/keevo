package com.keevo.sync.sync.domain.port.in;

import com.keevo.sync.sync.domain.model.ConflictResult;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;

import java.util.UUID;

/**
 * ConflictResolutionStrategy — GoF Strategy pattern for conflict resolution.
 * Evaluate conflict AFTER the operation has been applied by the handler.
 */
public interface ConflictResolutionStrategy {

    /**
     * @return ConflictResult with details, or null if no conflict detected.
     */
    ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                            UUID actorId, String tenantId);
}

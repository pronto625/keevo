package com.keevo.sync.sync.application.strategy;

import com.keevo.sync.sync.domain.model.ConflictResult;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AppendOnlyStrategy — No conflict possible for immutable/append-only operations.
 */
@Component
public class AppendOnlyStrategy implements ConflictResolutionStrategy {

    @Override
    public ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                                   UUID actorId, String tenantId) {
        return null;
    }
}

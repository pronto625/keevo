package com.keevo.sync.sync.domain.port.in;

import com.keevo.sync.sync.domain.model.ConflictResult;
import com.keevo.sync.sync.domain.model.PreApplyCheck;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;

import java.util.UUID;

/**
 * ConflictResolutionStrategy — GoF Strategy pattern for conflict resolution.
 *
 * <p>{@link #beforeApply} runs before the handler (it may veto an outdated operation);
 * {@link #evaluate} runs AFTER the operation has been applied by the handler.
 */
public interface ConflictResolutionStrategy {

    /**
     * Decides, before the handler runs, whether the operation is outdated (not applied) or
     * would overwrite a concurrent change. Default: nothing to decide up front.
     */
    default PreApplyCheck beforeApply(SyncOperation operation, String deviceId, String tenantId) {
        return PreApplyCheck.NONE;
    }

    /**
     * @return ConflictResult with details, or null if no conflict detected.
     */
    ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                            UUID actorId, String tenantId);

    /** {@link #evaluate(SyncOperation, SyncOperationResult, UUID, String)} with the pre-apply decision. */
    default ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                                    UUID actorId, String tenantId, PreApplyCheck preApply) {
        return evaluate(operation, handlerResult, actorId, tenantId);
    }
}

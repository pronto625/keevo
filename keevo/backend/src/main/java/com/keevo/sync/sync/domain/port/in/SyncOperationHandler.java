package com.keevo.sync.sync.domain.port.in;

import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;

import java.util.Set;
import java.util.UUID;

/**
 * SyncOperationHandler — Strategy interface for processing sync operations by type.
 */
public interface SyncOperationHandler {

    /** Operation types this handler supports (e.g. "CREATE_SALE"). */
    Set<String> supportedTypes();

    /** Process one operation. Returns APPLIED, CONFLICT, or REJECTED. */
    SyncOperationResult handle(SyncOperation operation, UUID actorId, String tenantId);
}

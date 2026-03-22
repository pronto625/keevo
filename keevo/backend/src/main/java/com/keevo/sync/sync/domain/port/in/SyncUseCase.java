package com.keevo.sync.sync.domain.port.in;

import com.keevo.sync.sync.domain.model.SyncBatchResult;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncPullResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SyncUseCase — Port interface for the offline-first synchronisation use case.
 *
 * <p>MCP Port Purity Rules (NON-NEGOTIABLE):
 * <ul>
 *   <li>Parameters are pure Java records only — NO HttpServletRequest, NO Principal.
 *   <li>ActorId injected explicitly in Command objects (Story 5+).
 *   <li>NO SecurityContextHolder inside this use case.
 * </ul>
 */
public interface SyncUseCase {

    record PushBatchCommand(UUID actorId, String tenantId, String deviceId,
                            List<SyncOperation> operations) {}

    SyncBatchResult pushBatch(PushBatchCommand command);

    record PullCommand(UUID actorId, String tenantId, Instant since) {}

    /**
     * Pull delta changes since the given timestamp.
     * Returns all entities modified after {@code since} across all entity types.
     * If since is null → full sync (first-time pull).
     */
    SyncPullResult pull(PullCommand command);
}

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

    /**
     * @param cursor opaque {@link SyncPullResult#nextCursor()} of the previous page, null for
     *               the first page; {@code since} stays the same on every page of a pull
     */
    record PullCommand(UUID actorId, String tenantId, Instant since, String cursor) {
        public PullCommand(UUID actorId, String tenantId, Instant since) {
            this(actorId, tenantId, since, null);
        }
    }

    /**
     * Pull delta changes since the given timestamp, one page at a time.
     * If since is null → full sync (first-time pull). While the result has
     * {@code hasMore}, the client pulls again with its {@code nextCursor} and the same since,
     * and saves {@code serverTimestamp} as its next since only after the last page.
     */
    SyncPullResult pull(PullCommand command);
}

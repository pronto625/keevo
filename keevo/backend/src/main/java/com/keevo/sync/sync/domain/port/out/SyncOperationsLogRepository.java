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

    /**
     * True when the operation was already logged with a final outcome (anything
     * but REJECTED). A rejected operation stays in the client queue and is
     * replayed with the same id; it must be processed again, not reported as
     * DUPLICATE — the client deletes DUPLICATE operations as synced.
     */
    boolean isSettled(String operationId);

    /**
     * Inserts this operation's log row before its handler runs, so the row itself serialises
     * concurrent pushes of the same operation: a second push waits on the first one's insert
     * and then sees it settled. A previously REJECTED row is claimed again (replay).
     * The row is finalised by {@link #save} in the same transaction.
     *
     * @return true when the operation is already settled — nothing was claimed, skip it
     */
    boolean isSettledElseClaim(String operationId, String operationType, String entityId,
                               java.time.Instant clientTimestamp);

    void save(SyncOperationsLogEntry entry);

    Optional<SyncOperationsLogEntry> findPreviousAppliedByEntityId(String entityId, String excludeOperationId);

    /**
     * For client-generated product ids whose CREATE was merged into an already-existing
     * server product (same name), maps clientId → server product id.
     * Ids that were never merged are absent from the result.
     */
    Map<String, String> findMergedProductTargets(Collection<String> clientIds);
}

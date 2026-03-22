package com.keevo.sync.sync.domain.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DeltaEntityProvider — Strategy interface for per-entity delta sync queries.
 *
 * Each implementation encapsulates the query and mapping logic for a single entity type.
 * SyncPullService iterates through all registered providers to assemble the full pull response.
 *
 * Adding a new entity type requires only implementing this interface — zero changes to
 * SyncPullService or SyncController (Open/Closed principle).
 */
public interface DeltaEntityProvider {

    /** Entity key for the pull response (e.g. "products", "stockLevels"). */
    String entityKey();

    /**
     * Query records modified after the given timestamp.
     * If since is null or Instant.EPOCH → return ALL records (first-time sync).
     */
    List<Map<String, Object>> queryDelta(Instant since);
}

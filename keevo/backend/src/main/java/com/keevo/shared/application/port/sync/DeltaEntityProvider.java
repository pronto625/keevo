package com.keevo.shared.application.port.sync;

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
 *
 * <p>Story 15.5b: a public SPI of {@code shared} (moved from {@code sync.sync.domain.port.in}, like
 * {@link SyncOperationHandler} in 14.3) so that a module owns the delta of its own entities (e.g. {@code invoicing}
 * for {@code invoices}) without depending on {@code sync}; the pull discovers every {@code @Component}
 * implementation.
 */
public interface DeltaEntityProvider {

    /** Rows returned per entity and per pull page. */
    int PAGE_SIZE = 1000;

    /** Keyset position in a provider's stable (cursor column, id) order. */
    record Position(Instant cursor, String id) {}

    /** One page of a delta; {@code next} is null once the delta is exhausted. */
    record Page(List<Map<String, Object>> rows, Position next) {
        public boolean hasMore() { return next != null; }
    }

    /** Entity key for the pull response (e.g. "products", "stockLevels"). */
    String entityKey();

    /**
     * Story 13.10a review — the module owning the entity ({@code module.json} id): the pull skips the provider (empty
     * list, delta done) while that module is DISABLED for the tenant, so premium data (inventory counts, transfers)
     * never reaches a device through the free sync path. READ_ONLY still pulls.
     */
    String moduleId();

    /**
     * Records whose cursor column is {@code >= since} (the overlap is intentional: the pull
     * cursor keeps a safety margin and the client upserts idempotently), ordered by
     * (cursor column, id), strictly after {@code after} when given, at most {@code limit} rows.
     * If since is Instant.EPOCH → ALL records (first-time sync).
     */
    Page queryDeltaPage(Instant since, Position after, int limit);

    /**
     * Story v1s-18-7 — true when only OWNER devices may receive this delta (FR98: e.g. the
     * audit log). For any other role the pull returns an empty list for {@link #entityKey()}
     * and never queries the provider.
     */
    default boolean ownerOnly() {
        return false;
    }

    /** First page of the delta since the given timestamp. */
    default List<Map<String, Object>> queryDelta(Instant since) {
        return queryDeltaPage(since, null, PAGE_SIZE).rows();
    }
}

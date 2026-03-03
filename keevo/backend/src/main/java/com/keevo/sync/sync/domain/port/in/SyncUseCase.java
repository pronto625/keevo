package com.keevo.sync.sync.domain.port.in;

/**
 * SyncUseCase — Port interface for the offline-first synchronisation use case.
 *
 * <p>MCP Port Purity Rules (NON-NEGOTIABLE):
 * <ul>
 *   <li>Parameters are pure Java records only — NO HttpServletRequest, NO Principal.
 *   <li>ActorId injected explicitly in Command objects (Story 5+).
 *   <li>NO SecurityContextHolder inside this use case.
 * </ul>
 *
 * <p>Full implementation in Story 5.1 (push-sync) and 5.2 (pull-sync).
 */
public interface SyncUseCase {

    /**
     * Push all queued local operations to the remote backend.
     * Returns the number of operations successfully synced.
     */
    int push();

    /**
     * Pull delta changes from the remote backend and merge into local store.
     *
     * @param since ISO-8601 timestamp of the last successful pull (nullable for full sync)
     * @return number of changes applied locally
     */
    int pull(String since);
}

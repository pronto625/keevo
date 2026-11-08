package com.keevo.shared.application.port.sync;

import java.util.Set;
import java.util.UUID;

/**
 * Story 16.13 — what a sync pull may see, resolved once by the pull endpoint from the authenticated actor and
 * carried by the pull command down to every {@link DeltaEntityProvider}; providers never read the security context.
 *
 * <ul>
 *   <li>{@code restricted = false}: OWNER (or super-admin) — every row of the tenant.</li>
 *   <li>{@code restricted = true}, {@code storeId} set: EMPLOYEE of that store — store-specific datasets are
 *       limited to it, personal ones (reports) to {@code actorId}.</li>
 *   <li>{@code restricted = true}, {@code storeId = null}: EMPLOYEE without a store (or unknown actor) — every
 *       store-specific dataset is empty (never an exception: it would freeze the pull cursor).</li>
 * </ul>
 *
 * <p>A public SPI type of {@code shared} (like {@link DeltaEntityProvider}) so module providers can honour it.
 *
 * @param restricted true for an EMPLOYEE (or when unknown — fail closed)
 * @param storeId    the employee's assigned store, null when none (or unrestricted)
 * @param actorId    the user id of the actor pulling (personal datasets), may be null
 * @param clientStoreIds Story 20.2 — for a restricted pull, the stores whose clients it sees (resolved once per pull
 *                   by the pull service); {@code null} = not resolved (the clients provider resolves it itself)
 * @param permissions Story 20.10 — for a restricted pull, the module permissions the actor holds (resolved once by the
 *                   pull endpoint from the role mapping); {@code null} = not resolved, which holds none (fail closed).
 *                   Ignored for an unrestricted pull (OWNER holds every permission).
 */
public record PullScope(boolean restricted, UUID storeId, UUID actorId, Set<UUID> clientStoreIds,
                        Set<String> permissions) {

    /** Every row of the tenant (OWNER view). */
    public static final PullScope UNRESTRICTED = new PullScope(false, null, null);

    /** Fail-closed scope: restricted, no store, no actor — store-specific and personal datasets are empty. */
    public static final PullScope NONE = new PullScope(true, null, null);

    public PullScope {
        clientStoreIds = clientStoreIds == null ? null : Set.copyOf(clientStoreIds);
        permissions = permissions == null ? null : Set.copyOf(permissions);
    }

    /** Story 20.2 arity — permissions not resolved. */
    public PullScope(boolean restricted, UUID storeId, UUID actorId, Set<UUID> clientStoreIds) {
        this(restricted, storeId, actorId, clientStoreIds, null);
    }

    /** Pre-20.2 arity — client visible stores not resolved. */
    public PullScope(boolean restricted, UUID storeId, UUID actorId) {
        this(restricted, storeId, actorId, null, null);
    }

    /** Story 20.2 — the same scope with the client visible stores resolved. */
    public PullScope withClientStoreIds(Set<UUID> ids) {
        return new PullScope(restricted, storeId, actorId, ids, permissions);
    }

    /** Story 20.10 — the same scope with the actor's module permissions resolved. */
    public PullScope withPermissions(Set<String> held) {
        return new PullScope(restricted, storeId, actorId, clientStoreIds, held);
    }

    /**
     * Story 20.10 — whether this pull may read data guarded by {@code permission}: always when unrestricted (OWNER /
     * super-admin), else only when the resolved permissions contain it (unresolved = no).
     */
    public boolean holds(String permission) {
        return !restricted || (permissions != null && permission != null && permissions.contains(permission));
    }

    public static PullScope unrestricted(UUID actorId) {
        return new PullScope(false, null, actorId);
    }

    /** EMPLOYEE scope; {@code storeId} null = employee without a store. */
    public static PullScope employee(UUID storeId, UUID actorId) {
        return new PullScope(true, storeId, actorId);
    }

    /** True when store-specific datasets must return nothing (restricted without a store). */
    public boolean excludesEveryStore() {
        return restricted && storeId == null;
    }
}

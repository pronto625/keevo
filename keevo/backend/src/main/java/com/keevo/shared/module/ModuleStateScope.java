package com.keevo.shared.module;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Unit of work for the module-state cache outside an HTTP request (Story 17.8 follow-up): a scheduler or an event
 * listener opens one around one tenant's work, so the module gate and the ports it calls (e.g. the effective report
 * preferences) share one module-state lookup instead of one each. Thread-confined ({@link ThreadLocal}), opened with
 * try-with-resources and always cleared on {@link #close()}:
 *
 * <pre>{@code try (var scope = ModuleStateScope.open()) { ... } }</pre>
 *
 * <p>Re-entrant: opening a scope while one is already open on the thread joins it (the inner {@code close} is a
 * no-op), so a listener running synchronously inside a scheduler's scope reuses the scheduler's entries. When an HTTP
 * request is bound to the thread the request cache keeps precedence (HTTP behaviour unchanged). Registry evictions
 * ({@link ModuleRegistry#evict}) also drop the entry from the open scope.
 */
public final class ModuleStateScope implements AutoCloseable {

    private static final ThreadLocal<Map<String, Object>> ENTRIES = new ThreadLocal<>();
    private static final ModuleStateScope JOINED = new ModuleStateScope(false);

    private final boolean owner;

    private ModuleStateScope(boolean owner) {
        this.owner = owner;
    }

    /** Opens a unit of work on this thread, or joins the one already open. */
    public static ModuleStateScope open() {
        if (ENTRIES.get() != null) {
            return JOINED;
        }
        ENTRIES.set(new HashMap<>());
        return new ModuleStateScope(true);
    }

    /**
     * The open unit of work's entries on this thread, keyed by tenant schema — the storage of the module-state cache
     * ({@code shared.module.infrastructure.RequestScopedActiveModulesCache}); empty when no scope is open.
     */
    public static Optional<Map<String, Object>> entries() {
        return Optional.ofNullable(ENTRIES.get());
    }

    /** Clears the unit of work when this scope opened it; no-op for a joined scope. */
    @Override
    public void close() {
        if (owner) {
            ENTRIES.remove();
        }
    }
}

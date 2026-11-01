package com.keevo.shared.module.application.port;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 16.2 — bundle definitions ({@code public.bundle_definitions} + {@code public.bundle_modules}): named lists of
 * module ids. Runs on the caller's transaction. Implemented by
 * {@code shared.module.adapter.out.persistence.JdbcBundleStore}.
 */
public interface BundleStore {

    /** Every bundle, by name. */
    List<StoredBundle> findAll();

    /** The bundle with this id; empty when there is none. */
    Optional<StoredBundle> find(String bundleId);

    /** Creates or replaces the bundle (definition and module list); {@code true} when it was created. */
    boolean save(StoredBundle bundle, UUID actorId);

    /** Removes the bundle definition; {@code false} when there was none. */
    boolean delete(String bundleId);

    /**
     * A stored bundle.
     *
     * @param modules module ids in display order
     */
    record StoredBundle(String id, String name, String description, List<String> modules) {

        public StoredBundle {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            modules = List.copyOf(modules);
        }
    }
}

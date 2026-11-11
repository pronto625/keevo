package com.keevo.migration.domain.port.out;

import com.keevo.migration.domain.model.CutoverReport.IndexProbe;

import java.util.List;
import java.util.UUID;

/**
 * Story 24.9 — the owners of a restored Bref database (source {@code DATABASE}), read-only: keyset pages, never the
 * whole list in memory.
 */
public interface BrefOwnerDirectory {

    /** Whether the restored database is configured. */
    boolean available();

    /**
     * The next owners after {@code after} ({@code null} = from the start), by id: Bref accounts of role
     * {@code owner} not deleted.
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_SOURCE_UNAVAILABLE}
     */
    List<UUID> owners(UUID after, int limit);

    /** The helper indexes of the restored copy (runbook script {@code keevo/ops/migration/bref-copy-indexes.sql}). */
    IndexProbe helperIndexes();
}

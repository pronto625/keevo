package com.keevo.migration.domain.port.out;

import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.SourceKind;

import java.util.UUID;

/**
 * Story 24.2 — reads the snapshot of one Bref owner, read-only. Strategy: one adapter per {@link SourceKind} (the
 * api-bref JSON export, a restored Bref database); both produce the same {@link BrefSnapshot} for the same data.
 */
public interface BrefSourcePort {

    SourceKind kind();

    /** Whether the source is configured (export folder / database URL). */
    boolean available();

    /**
     * @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_OWNER_NOT_FOUND} (absent, or not an
     *         owner), {@code MIGRATION_EXPORT_INVALID} (unreadable export: checksum, version, JSON),
     *         {@code MIGRATION_SOURCE_UNAVAILABLE} (not configured or unreachable)
     */
    BrefSnapshot load(UUID ownerId);
}

package com.keevo.migration.domain.port.out;

import com.keevo.migration.domain.model.BrefAnnexes;
import com.keevo.migration.domain.model.SourceKind;

import java.util.Collection;
import java.util.UUID;

/**
 * Story 24.6 — reads the annex section of one Bref owner, read-only. Strategy: one adapter per {@link SourceKind}, the
 * same as the snapshot's source (a separate port, as 24.5's, so the snapshot stays untouched); both give the same
 * {@link BrefAnnexes} for the same data.
 */
public interface BrefAnnexesSourcePort {

    SourceKind kind();

    /**
     * @param ownerId    the Bref owner
     * @param accountIds the owner and the members of the snapshot (AI conversations and notifications are any member's)
     * @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_EXPORT_INVALID} (unreadable rows or
     *         export), {@code MIGRATION_SOURCE_UNAVAILABLE} (not configured or unreachable)
     */
    BrefAnnexes load(UUID ownerId, Collection<UUID> accountIds);
}

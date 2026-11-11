package com.keevo.migration.domain.port.out;

import com.keevo.migration.domain.model.BrefMonetisation;
import com.keevo.migration.domain.model.SourceKind;

import java.util.Collection;
import java.util.UUID;

/**
 * Story 24.5 — reads the monetisation section of one Bref owner, read-only. Strategy: one adapter per
 * {@link SourceKind}, the same as the snapshot's source (a separate port so the snapshot, extended by the other steps,
 * stays untouched); both give the same {@link BrefMonetisation} for the same data.
 */
public interface BrefMonetisationSourcePort {

    SourceKind kind();

    /**
     * @param ownerId    the Bref owner
     * @param accountIds the owner and the members of the snapshot (the SMS recharges are requested by any of them)
     * @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_EXPORT_INVALID} (unreadable rows or
     *         export), {@code MIGRATION_SOURCE_UNAVAILABLE} (not configured or unreachable)
     */
    BrefMonetisation load(UUID ownerId, Collection<UUID> accountIds);
}

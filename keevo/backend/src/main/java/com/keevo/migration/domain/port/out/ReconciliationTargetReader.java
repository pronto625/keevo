package com.keevo.migration.domain.port.out;

import com.keevo.migration.domain.model.ReconciliationFigures.Actual;
import com.keevo.migration.domain.model.ReconciliationFigures.RecordsSummary;

import java.util.Collection;
import java.util.UUID;

/**
 * Story 24.8 — read-only aggregate reads of what the import stored: the owner's {@code migration_records} and the
 * imported tenant's rows. A constant number of set-based queries whatever the owner's size; they join the caller's
 * transaction (one read transaction per reconciliation).
 */
public interface ReconciliationTargetReader {

    /** Per (source table, target table): rows, distinct targets, rows with a note, first write; recorded accounts. */
    RecordsSummary records(UUID brefOwnerId);

    /**
     * The tenant's figures. The subscription carries only the credit balance (plan code and expiry {@code null}: the
     * plan is read through the subscription module's port).
     *
     * @param sampleIds the sampled documents (their ids are the Bref ids)
     * @param tokens    the Bref public tokens to look up in the legacy link registry
     */
    Actual read(String tenantSchema, UUID tenantId, Collection<UUID> sampleIds, Collection<UUID> tokens);
}

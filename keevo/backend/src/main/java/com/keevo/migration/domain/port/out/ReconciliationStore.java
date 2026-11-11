package com.keevo.migration.domain.port.out;

import com.keevo.migration.domain.model.ReconciliationReport;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Story 24.8 — persistence of the reconciliation runs ({@code public.migration_reconciliations}). */
public interface ReconciliationStore {

    /** Stores a run (history kept: one row per run). */
    void save(ReconciliationReport report, UUID startedBy);

    Optional<ReconciliationReport> latestForBatch(UUID batchId);

    Optional<ReconciliationReport> latestForOwner(UUID brefOwnerId);

    /**
     * The owners that may not be activated, with their French reason: never imported, latest import not completed,
     * not reconciled since, or reconciled with a gap — one query whatever the number of owners.
     */
    Map<UUID, String> blockingOwners(Collection<UUID> brefOwnerIds);
}

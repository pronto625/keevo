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

    /** The latest run of the owner's latest batch (any status) — the batch the gate judges. */
    Optional<ReconciliationReport> latestForOwner(UUID brefOwnerId);

    /** The owner's latest import batch (any status: a newer RUNNING one included). */
    Optional<UUID> latestBatch(UUID brefOwnerId);

    /**
     * The owners that may not be activated, with their French reason: never imported, latest import not completed,
     * not reconciled since, or reconciled with a gap — one query whatever the number of owners.
     */
    Map<UUID, String> blockingOwners(Collection<UUID> brefOwnerIds);
}

package com.keevo.migration.domain.port.in;

import com.keevo.migration.domain.model.ReconciliationReport;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Story 24.8 — the reconciliation of an imported Bref owner (FUS-MIG3): run it on a completed import batch, read the
 * latest report of a batch or of an owner, and the activation gate the cutover (24.9) calls.
 */
public interface BrefReconciliationUseCase {

    /**
     * Reconciles a COMPLETED batch and stores the report; a reconciliation that cannot run is stored
     * {@code RECONCILIATION_FAILED} with its French label (never thrown).
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_BATCH_NOT_FOUND},
     *         {@code INVALID_STATE} (the batch is not COMPLETED)
     */
    ReconciliationReport reconcile(UUID batchId, UUID actorId);

    /**
     * @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_BATCH_NOT_FOUND},
     *         {@code MIGRATION_RECONCILIATION_NOT_FOUND}
     */
    ReconciliationReport report(UUID batchId);

    /** @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_RECONCILIATION_NOT_FOUND} */
    ReconciliationReport latestForOwner(UUID brefOwnerId);

    /** The owners that block the activation, with their French reason ({@link
     * com.keevo.migration.domain.port.out.ReconciliationStore#blockingOwners}). */
    Map<UUID, String> blockingOwners(Collection<UUID> brefOwnerIds);
}

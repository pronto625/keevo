package com.keevo.migration.domain.port.in;

import com.keevo.migration.domain.model.MigrationBatch;

import java.util.UUID;

/** Story 24.2 — the super-admin use cases of the migration tool: import one Bref owner, read a batch. */
public interface BrefOwnerImportUseCase {

    /**
     * Imports (or completes the import of) a Bref owner from the given source, synchronously.
     *
     * @param source {@code EXPORT} or {@code DATABASE}
     * @return the batch (COMPLETED, BLOCKED or FAILED)
     * @throws com.keevo.shared.domain.exception.DomainException {@code VALIDATION_FAILED} (unknown source),
     *         {@code MIGRATION_SOURCE_UNAVAILABLE}, {@code MIGRATION_OWNER_NOT_FOUND}, {@code MIGRATION_EXPORT_INVALID},
     *         {@code MIGRATION_ALREADY_RUNNING}
     */
    MigrationBatch importOwner(UUID brefOwnerId, String source, UUID actorId);

    /** @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_BATCH_NOT_FOUND} */
    MigrationBatch batch(UUID batchId);
}

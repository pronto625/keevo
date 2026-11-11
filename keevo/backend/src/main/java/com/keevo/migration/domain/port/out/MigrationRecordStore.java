package com.keevo.migration.domain.port.out;

import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.SourceKind;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 24.2 — persistence of the migration batches and records ({@code public.migration_batches},
 * {@code public.migration_records}). Record writes join the caller's transaction (the step that wrote the rows), so a
 * row and its record commit together.
 */
public interface MigrationRecordStore {

    /**
     * Opens a RUNNING batch for the owner. A RUNNING batch of the same owner older than {@code staleAfter} is closed
     * FAILED (« interrompu ») first.
     *
     * @return empty when a recent run of the same owner is still RUNNING
     */
    Optional<UUID> startBatch(UUID brefOwnerId, SourceKind source, UUID startedBy, java.time.Duration staleAfter);

    /** Closes a batch (status, tenant, counts, cases, notes, error label). */
    void finishBatch(UUID batchId, BatchStatus status, UUID tenantId, Map<String, Integer> counts,
                     List<BlockedCase> cases, List<ImportNote> notes, String errorLabel);

    Optional<MigrationBatch> findBatch(UUID batchId);

    /** Story 24.9 — import batches, newest first, after the given one ({@code null} = first page), keyset. */
    List<MigrationBatch> listBatches(UUID after, int limit);

    /**
     * The owner's records in the given target tables (those the pipeline's steps read), keyed by source — one read
     * on {@code (bref_owner_id, target_table)}; a mutable map the run keeps up to date.
     */
    Map<MigrationRecord.Key, UUID> findTargets(UUID brefOwnerId, Collection<String> targetTables);

    /** Inserts the records ({@code ON CONFLICT DO NOTHING}), one JDBC batch. */
    void insert(UUID batchId, UUID brefOwnerId, Collection<MigrationRecord> records);
}

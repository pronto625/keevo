package com.keevo.migration.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 24.2 — one import run of a Bref owner ({@code public.migration_batches}).
 *
 * @param counts     what the run wrote, by kind (keys: the counter constants of {@code ImportRun}), plus {@code alreadyImported}
 * @param errorLabel French label of the failure (FAILED only)
 */
public record MigrationBatch(UUID id, UUID brefOwnerId, SourceKind source, BatchStatus status, UUID tenantId,
                             UUID startedBy, Instant startedAt, Instant finishedAt, Map<String, Integer> counts,
                             List<BlockedCase> cases, List<ImportNote> notes, String errorLabel) {

    public MigrationBatch {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
        cases = cases == null ? List.of() : List.copyOf(cases);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}

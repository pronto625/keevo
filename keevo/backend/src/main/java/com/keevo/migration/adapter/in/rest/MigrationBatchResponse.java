package com.keevo.migration.adapter.in.rest;

import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationBatch;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 24.2 — a migration batch as the super-admin dashboard shows it: the codes travel with their French labels
 * ({@code statusLabel}, {@code sourceLabel}, each case's and note's {@code label}); ids are handles (the Bref owner, the
 * tenant, the source rows of the cases), never to display as such.
 *
 * @param counts what the run wrote by kind ({@code tenantsCreated}, {@code usersCreated}, {@code membershipsCreated},
 *               {@code storesCreated}, {@code logosImported}, {@code employeesCreated}, {@code alreadyImported},
 *               {@code membersSkippedDeleted})
 */
public record MigrationBatchResponse(UUID id, UUID brefOwnerId, String source, String sourceLabel, String status,
                                     String statusLabel, UUID tenantId, Instant startedAt, Instant finishedAt,
                                     Map<String, Integer> counts, List<BlockedCase> cases, List<ImportNote> notes,
                                     String errorLabel) {

    static MigrationBatchResponse of(MigrationBatch b) {
        return new MigrationBatchResponse(b.id(), b.brefOwnerId(), b.source().name(), b.source().label(),
                b.status().name(), b.status().label(), b.tenantId(), b.startedAt(), b.finishedAt(), b.counts(),
                b.cases(), b.notes(), b.errorLabel());
    }
}

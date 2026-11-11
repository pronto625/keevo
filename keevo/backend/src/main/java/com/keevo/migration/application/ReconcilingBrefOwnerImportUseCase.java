package com.keevo.migration.application;

import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.port.in.BrefOwnerImportUseCase;
import com.keevo.migration.domain.port.in.BrefReconciliationUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Story 24.8 — Decorator of the import use case: every COMPLETED import is reconciled right after (FUS-MIG3, ADR-10:
 * « the reconciliation runs for each owner after its import »), whoever calls it (the super-admin API, the cutover
 * command of 24.9). The import pipeline ({@link BrefOwnerImportService}) is untouched; the import's own outcome is
 * returned as is — a reconciliation that fails is stored failed by the reconciliation itself, and anything else is
 * logged (the owner then has no reconciliation, which blocks it just the same).
 */
@Primary
@Service
class ReconcilingBrefOwnerImportUseCase implements BrefOwnerImportUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconcilingBrefOwnerImportUseCase.class);

    private final BrefOwnerImportService imports;
    private final BrefReconciliationUseCase reconciliation;

    ReconcilingBrefOwnerImportUseCase(BrefOwnerImportService imports, BrefReconciliationUseCase reconciliation) {
        this.imports = imports;
        this.reconciliation = reconciliation;
    }

    @Override
    public MigrationBatch importOwner(UUID brefOwnerId, String source, UUID actorId) {
        MigrationBatch batch = imports.importOwner(brefOwnerId, source, actorId);
        if (batch.status() == BatchStatus.COMPLETED) {
            try {
                reconciliation.reconcile(batch.id(), actorId);
            } catch (RuntimeException e) {
                log.warn("[ADMIN AUDIT] Bref reconciliation not stored: batch={} owner={} error={}", batch.id(),
                        brefOwnerId, e.getClass().getSimpleName());
            }
        }
        return batch;
    }

    @Override
    public MigrationBatch batch(UUID batchId) {
        return imports.batch(batchId);
    }
}

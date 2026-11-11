package com.keevo.migration.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 24.8 — one reconciliation run of an import batch ({@code public.migration_reconciliations}): aggregates and
 * French labels only (no personal data). The ids are handles (batch, Bref owner, tenant), never to display.
 *
 * @param gaps       number of lines out of tolerance
 * @param explained  number of lines whose difference is explained
 * @param errorLabel French label when the reconciliation could not run (then {@code RECONCILIATION_FAILED}, no checks)
 */
public record ReconciliationReport(UUID id, UUID batchId, UUID brefOwnerId, UUID tenantId,
                                   ReconciliationStatus status, String statusLabel, int gaps, int explained,
                                   Instant startedAt, Instant finishedAt, List<ReconciliationCheck> checks,
                                   String errorLabel) {

    public ReconciliationReport {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    /** A report from its checks: failed when any line is a gap. */
    public static ReconciliationReport of(UUID id, UUID batchId, UUID ownerId, UUID tenantId, Instant startedAt,
                                          Instant finishedAt, List<ReconciliationCheck> checks) {
        int gaps = 0;
        int explained = 0;
        for (ReconciliationCheck c : checks) {
            for (ReconciliationLine l : c.lines()) {
                if (l.status() == ReconciliationLine.Status.GAP) {
                    gaps++;
                } else if (l.status() == ReconciliationLine.Status.EXPLAINED) {
                    explained++;
                }
            }
        }
        ReconciliationStatus status = gaps == 0 ? ReconciliationStatus.RECONCILED
                : ReconciliationStatus.RECONCILIATION_FAILED;
        return new ReconciliationReport(id, batchId, ownerId, tenantId, status, status.label(), gaps, explained,
                startedAt, finishedAt, checks, null);
    }

    /** A reconciliation that could not run: the owner stays blocked. */
    public static ReconciliationReport failed(UUID id, UUID batchId, UUID ownerId, UUID tenantId, Instant startedAt,
                                              Instant finishedAt, String errorLabel) {
        ReconciliationStatus status = ReconciliationStatus.RECONCILIATION_FAILED;
        return new ReconciliationReport(id, batchId, ownerId, tenantId, status, status.label(), 0, 0, startedAt,
                finishedAt, List.of(), errorLabel);
    }
}

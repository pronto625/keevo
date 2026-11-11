package com.keevo.migration.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 24.9 — one cutover over a restored Bref database ({@code public.migration_cutovers}). The ids are handles,
 * never to display.
 *
 * @param progress       owners per status (live counts)
 * @param acceptedOwners the signed exclusion list of the « go »: owners accepted despite a gap or a block
 * @param report         the archived report (recomputed at the end of a run, of an activation and of a rollback)
 * @param recoveredOwners owners imported and activated alone after the « go » (reprise 11/11/2026)
 */
public record Cutover(UUID id, SourceKind source, CutoverStatus status, int parallelism, int runs, UUID startedBy,
                      Instant startedAt, Instant finishedAt, Instant heartbeatAt, UUID activatedBy,
                      Instant activatedAt, UUID rolledBackBy, Instant rolledBackAt, String errorLabel,
                      List<CutoverReport.StatusCount> progress, List<AcceptedOwner> acceptedOwners,
                      CutoverReport report, List<RecoveredOwner> recoveredOwners) {

    public Cutover {
        recoveredOwners = recoveredOwners == null ? List.of() : List.copyOf(recoveredOwners);
        progress = progress == null ? List.of() : List.copyOf(progress);
        acceptedOwners = acceptedOwners == null ? List.of() : List.copyOf(acceptedOwners);
        report = report == null ? CutoverReport.EMPTY : report;
    }

    /**
     * An owner the super-admin let through the « go » despite its gap or block, with the documented reason (24.12
     * rehearsal) — who and when.
     */
    public record AcceptedOwner(UUID ownerId, String reason, UUID acceptedBy, Instant acceptedAt) {
    }

    /**
     * Reprise 11/11/2026 — an owner left out at the « go », imported and activated alone afterwards: its neutral label
     * (« Compte n »), who and when.
     */
    public record RecoveredOwner(UUID ownerId, String label, UUID recoveredBy, Instant recoveredAt) {
    }
}

package com.keevo.migration.domain.port.out;

import com.keevo.migration.domain.model.Cutover;
import com.keevo.migration.domain.model.CutoverOwner;
import com.keevo.migration.domain.model.CutoverOwnerStatus;
import com.keevo.migration.domain.model.CutoverReport;
import com.keevo.migration.domain.model.CutoverStatus;
import com.keevo.migration.domain.model.OwnerFigures;
import com.keevo.migration.domain.model.SourceKind;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 24.9 — persistence of the cutovers ({@code public.migration_cutovers}, {@code public.migration_cutover_owners})
 * and the reads the cutover needs on the migration tables. Autocommit statements; every read and aggregate is
 * set-based (keyset pages, one query per aggregate).
 */
public interface CutoverStore {

    /**
     * Opens a RUNNING cutover.
     *
     * @return empty when another cutover is open
     */
    Optional<UUID> create(SourceKind source, int parallelism, UUID startedBy);

    Optional<Cutover> find(UUID cutoverId);

    /** Cutovers, newest first, after the given one (keyset). */
    List<Cutover> list(UUID after, int limit);

    /**
     * Moves the cutover to {@code to} when its status is one of {@code from} (a RUNNING or ROLLING_BACK one only when
     * its heartbeat is older than {@code staleAfter}): one conditional update.
     *
     * @return whether the cutover moved
     */
    boolean transition(UUID cutoverId, Collection<CutoverStatus> from, CutoverStatus to, Duration staleAfter,
                       boolean newRun);

    /** Keeps the heartbeat of the running coordinator fresh. */
    void heartbeat(UUID cutoverId);

    /** Closes a run or a rollback: status, error label, report. */
    void finish(UUID cutoverId, CutoverStatus status, String errorLabel, CutoverReport report);

    /** Stores the report only. */
    void saveReport(UUID cutoverId, CutoverReport report);

    /**
     * Adds owners not yet listed, after the last position, in the given order ({@code ON CONFLICT DO NOTHING}).
     *
     * @return the number of owners added
     */
    int addOwners(UUID cutoverId, List<UUID> owners);

    /** Owners to process again (PENDING, GAP, BLOCKED, FAILED), after a position (keyset). */
    List<CutoverOwner> ownersToProcess(UUID cutoverId, int afterPosition, int limit);

    /** Owners of the cutover, optionally of one status, after a position (keyset), with the company name if any. */
    List<CutoverOwner> owners(UUID cutoverId, CutoverOwnerStatus status, int afterPosition, int limit);

    /** Every owner line of the cutover except the given statuses, in listing order (the activation gate's input). */
    List<CutoverOwner> ownerLines(UUID cutoverId, Collection<CutoverOwnerStatus> except);

    /** Marks an owner as being processed (start time). */
    void ownerStarted(UUID cutoverId, UUID ownerId, Instant at);

    /** Stores the outcome of one owner. */
    void ownerDone(UUID cutoverId, UUID ownerId, CutoverOwnerStatus status, UUID batchId, UUID tenantId,
                   String reason, Integer importMs, Integer reconcileMs, OwnerFigures figures);

    /** Sets the status of owners (activation, rollback). */
    void ownersStatus(UUID cutoverId, Collection<UUID> ownerIds, CutoverOwnerStatus status, String reason);

    /** Story 24.13 — one owner line of a cutover (primary key), with its company name. */
    Optional<CutoverOwner> owner(UUID cutoverId, UUID ownerId);

    /**
     * Story 24.13 — puts one owner line back to {@code PENDING} after its company was removed: batch, tenant, reason,
     * durations, figures and times cleared, so the next run imports it again and the report no longer counts it.
     */
    void resetOwner(UUID cutoverId, UUID ownerId);

    /** Records the « go »: actor, time, accepted owners (appended), status ACTIVATED. */
    void activated(UUID cutoverId, UUID actor, List<Cutover.AcceptedOwner> accepted);

    /** Records the end of a rollback: actor, time. */
    void rolledBack(UUID cutoverId, UUID actor);

    /** The record targets of each owner in the given target tables (rollback, activation) — one query. */
    Map<UUID, Map<String, List<UUID>>> targets(Collection<UUID> ownerIds, Collection<String> targetTables);

    /** Deletes the migration records then the batches (reconciliations cascade) of the owners — set-based. */
    void purgeOwners(Collection<UUID> ownerIds);

    /** The report aggregates of the cutover (status counts, reasons, figures, anomalies, owner durations). */
    CutoverReport aggregate(UUID cutoverId, CutoverReport.Durations stages, CutoverReport.IndexProbe probe,
                            int acceptedOwners, int recoveredOwners);

    /**
     * Reprise 11/11/2026 — releases the lock of an owner's recovery: a RECOVERING cutover back to ACTIVATED with its
     * label and report, the recovered owner appended when one was activated (one fenced update).
     *
     * @param recovered  the owner activated by the recovery, {@code null} when none
     * @param errorLabel French reason the owner was not activated, {@code null} when it was (or nothing was to do)
     */
    void recovered(UUID cutoverId, Cutover.RecoveredOwner recovered, String errorLabel, CutoverReport report);

    /** Live counts per status. */
    List<CutoverReport.StatusCount> progress(UUID cutoverId);
}

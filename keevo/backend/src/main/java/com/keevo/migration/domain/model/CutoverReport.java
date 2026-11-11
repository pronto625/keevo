package com.keevo.migration.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Story 24.9 — the archived report of a cutover (FUS-MIG10): aggregates, durations and French labels only — no id, no
 * personal data (owners are counted, never named).
 *
 * @param owners        owners per status
 * @param reasons       why owners are not reconciled (French reason → number of owners)
 * @param tables        counts per table, expected (from Bref) vs Keevo, summed over the imported owners
 * @param sums          amounts in francs, expected (from Bref) vs Keevo, summed over the imported owners
 * @param anomalies     values kept as they were in Bref and noted by the import (label → number of rows)
 * @param durations     per stage and total
 * @param sourceIndexes helper indexes of the restored copy (present / expected), {@code null} when not probed
 * @param acceptedOwners  owners accepted at the « go » despite a gap or a block
 * @param recoveredOwners owners imported and activated alone after the « go » (reprise 11/11/2026; 0 in older
 *                        reports)
 */
public record CutoverReport(Instant generatedAt, int ownersTotal, List<StatusCount> owners, List<LabelCount> reasons,
                            List<FigureLine> tables, List<FigureLine> sums, List<LabelCount> anomalies,
                            Durations durations, IndexProbe sourceIndexes, int acceptedOwners,
                            int recoveredOwners) {

    public static final CutoverReport EMPTY = new CutoverReport(null, 0, List.of(), List.of(), List.of(), List.of(),
            List.of(), null, null, 0, 0);

    public CutoverReport {
        owners = owners == null ? List.of() : List.copyOf(owners);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        tables = tables == null ? List.of() : List.copyOf(tables);
        sums = sums == null ? List.of() : List.copyOf(sums);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
        durations = durations == null ? Durations.NONE : durations;
    }

    /** Owners in one status. */
    public record StatusCount(String status, String label, long count) {
    }

    /** A French label and a number (owners, rows). */
    public record LabelCount(String label, long count) {
    }

    /** One compared figure: expected from Bref, held by Keevo, difference (Keevo − expected). */
    public record FigureLine(String label, BigDecimal expected, BigDecimal actual, BigDecimal difference) {
    }

    /**
     * Durations in milliseconds ({@code null} = stage not run yet).
     *
     * @param listingMs         listing of the owners (latest run)
     * @param runWallMs         wall time of the latest run (listing + imports + reconciliations)
     * @param importsMs         Σ of the owners' import durations
     * @param reconciliationsMs Σ of the owners' reconciliation durations
     * @param ownerAvgMs        average per owner (import + reconciliation)
     * @param ownerP95Ms        95th percentile per owner
     * @param ownerMaxMs        slowest owner
     * @param activationMs      the « go »
     * @param rollbackMs        the rollback
     * @param totalMs           from the start of the cutover to its latest stage
     */
    public record Durations(Long listingMs, Long runWallMs, Long importsMs, Long reconciliationsMs, Long ownerAvgMs,
                            Long ownerP95Ms, Long ownerMaxMs, Long activationMs, Long rollbackMs, Long totalMs) {

        public static final Durations NONE = new Durations(null, null, null, null, null, null, null, null, null,
                null);
    }

    /** Helper indexes of the restored Bref copy (runbook script): {@code present} of {@code expected}. */
    public record IndexProbe(int present, int expected) {

        public boolean complete() {
            return present >= expected;
        }
    }
}

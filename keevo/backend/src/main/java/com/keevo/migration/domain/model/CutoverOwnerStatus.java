package com.keevo.migration.domain.model;

/** Story 24.9 — where one Bref owner stands in a cutover. */
public enum CutoverOwnerStatus {
    PENDING("En attente"),
    /** Imported (batch COMPLETED) and reconciled without gap. */
    RECONCILED("Importé et réconcilié"),
    /** Imported, but the reconciliation found a gap (or could not run): blocks the « go » unless accepted. */
    GAP("Écart de réconciliation"),
    /** The import found blocking cases before any write: blocks the « go » unless accepted. */
    BLOCKED("Import bloqué"),
    /** The import or the reconciliation stopped on an error: blocks the « go » unless accepted. */
    FAILED("Import en échec"),
    /** Nothing to migrate (account disabled in Bref, or without shop): reported, never blocks the « go ». */
    NOT_MIGRATED("Non repris (rien à migrer)"),
    /** The company was activated by the « go ». */
    ACTIVATED("Activé"),
    /** The company and the migration traces were removed by the rollback. */
    ROLLED_BACK("Supprimé par le retour arrière");

    private final String label;

    CutoverOwnerStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Owners a (re)run of the command processes again. */
    public boolean toProcess() {
        return this == PENDING || this == GAP || this == BLOCKED || this == FAILED;
    }
}

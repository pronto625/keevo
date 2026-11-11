package com.keevo.migration.domain.model;

/** Story 24.9 — state of a cutover (ADR-10): one run of the command over a restored Bref database, then the decision. */
public enum CutoverStatus {
    /** The coordinator lists and imports the owners. */
    RUNNING("En cours"),
    /** Every listed owner was processed: the report is ready, waiting for the « go » or the rollback. */
    READY("Prête pour la décision « go »"),
    /** The coordinator or the rollback stopped before the end: relaunch it. */
    FAILED("Interrompue : relancez-la"),
    /** The « go » was given: the imported companies are active; no automatic rollback any more. */
    ACTIVATED("Activée (« go » donné)"),
    /** The rollback removes the imported companies. */
    ROLLING_BACK("Retour arrière en cours"),
    /** The imported companies are removed: Bref can be turned back on. */
    ROLLED_BACK("Retour arrière effectué"),
    /**
     * Reprise 11/11/2026 — after the « go », one owner left out is imported again, reconciled and activated alone: the
     * lock of the ACTIVATED cutover (back to ACTIVATED at the end). A decided cutover, not an open one.
     */
    RECOVERING("Reprise d'un compte en cours");

    private final String label;

    CutoverStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** An open cutover blocks the start of another one. */
    public boolean open() {
        return this != ACTIVATED && this != ROLLED_BACK && this != RECOVERING;
    }
}

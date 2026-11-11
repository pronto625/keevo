package com.keevo.migration.domain.model;

/** Outcome of one import run of a Bref owner (Story 24.2). */
public enum BatchStatus {
    RUNNING("En cours"),
    /** Every step ran (a re-run that wrote nothing is COMPLETED too). */
    COMPLETED("Terminé"),
    /** Blocking cases found before any write: nothing was written. */
    BLOCKED("Bloqué"),
    /** A step failed: the steps already committed stay, a re-run completes them. */
    FAILED("Échec");

    private final String label;

    BatchStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
